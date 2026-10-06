package ntou.soselab.chatops4msa.Service.DiscordService;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.DepstateToolkit;
import ntou.soselab.chatops4msa.Service.CapabilityOrchestrator.CapabilityOrchestrator;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.AliasResolution;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic.AskItem;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Receives the values the operator typed into the Tier 3 form, and immediately
 * re-drives traffic with them.
 *
 * This closes the only loop in the pipeline that runs the other way round: everywhere
 * else the human asks the tool for something, here the TOOL asks the human. It exists
 * because the last few percent of coverage are blocked by values no analysis can
 * derive — a real account number, a tenant id — and guessing them 4xx's the request
 * before the deep edge is ever crossed.
 *
 * The answers go into the checkpoint rather than into a field here: the analysis that
 * asked has already finished, and the run that uses them is a fresh background one.
 */
@Service
public class ModalListener extends ListenerAdapter {

    private final DependencyAnalysisStateStore stateStore;
    private final CapabilityOrchestrator orchestrator;
    private final DependencyAnalysisRunner analysisRunner;

    @Lazy
    @Autowired
    public ModalListener(DependencyAnalysisStateStore stateStore,
                         CapabilityOrchestrator orchestrator,
                         DependencyAnalysisRunner analysisRunner) {
        this.stateStore = stateStore;
        this.orchestrator = orchestrator;
        this.analysisRunner = analysisRunner;
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (DepstateToolkit.RESOLVE_ALIASES_MODAL_ID.equals(event.getModalId())) {
            onAliasAnswers(event);
            return;
        }
        if (!DepstateToolkit.ASK_VALUES_MODAL_ID.equals(event.getModalId())) return;

        System.out.println(">>> trigger modal interaction event");
        String testerId = event.getUser().getId();

        DependencyAnalysisStateStore.State state = stateStore.get(testerId);
        if (state == null) {
            event.reply("The checkpoint has expired, so these values have nowhere to go. "
                    + "Please re-run get-dependency-analysis.").setEphemeral(true).queue();
            return;
        }

        List<AskItem> pending = AskItem.fromJson(
                stateStore.getStage(testerId, DependencyAnalysisStateStore.STAGE_PENDING_ASKS));

        // Merge into whatever was supplied earlier in this run: a value is asked for
        // once and then reused by every later supplement round.
        Map<String, String> values = AskItem.valuesFromJson(
                stateStore.getStage(testerId, DependencyAnalysisStateStore.STAGE_USER_VALUES));

        List<String> answered = new ArrayList<>();
        for (ModalMapping mapping : event.getValues()) {
            String value = AskItem.sanitize(mapping.getAsString());
            if (value.isEmpty()) continue;              // left blank: still pending
            values.put(mapping.getId(), value);
            answered.add(mapping.getId());
        }

        if (answered.isEmpty()) {
            event.reply("Nothing was filled in, so no traffic was re-driven. "
                    + "Click **Provide values** again when you have them.").setEphemeral(true).queue();
            return;
        }

        stateStore.putStage(testerId, DependencyAnalysisStateStore.STAGE_USER_VALUES,
                AskItem.valuesToJson(values));
        // Whatever is still blank stays pending, so the next round can ask again.
        List<AskItem> stillPending = new ArrayList<>();
        for (AskItem ask : pending) {
            if (!values.containsKey(ask.key)) stillPending.add(ask);
        }
        stateStore.putStage(testerId, DependencyAnalysisStateStore.STAGE_PENDING_ASKS,
                AskItem.toJson(stillPending));

        event.reply(summary(pending, values, answered, stillPending)).queue();

        // Re-drive traffic with the new values. Off the event thread: the resume runs
        // for minutes, and Discord's ack window is three seconds.
        List<String> roleNameList = new ArrayList<>();
        Member member = event.getMember();
        if (member != null) {
            for (Role role : member.getRoles()) roleNameList.add(role.getName());
        }
        String namespace = state.namespace;
        analysisRunner.run(testerId, "resume-dependency-analysis", () ->
                orchestrator.performTheCapability(
                        "resume-dependency-analysis", Map.of("namespace", namespace), roleNameList));

        System.out.println("<<< end of current modal interaction event");
    }

    /**
     * Receives the operator's answers to "which service is this name?".
     *
     * Parsing is deterministic ({@link AliasResolution#parseAnswer}): a candidate's
     * number, a service id in any spelling, {@code new} or {@code ignore}. An answer
     * that is not understood leaves the name pending and says so — a misread answer
     * must never silently become a merged edge. Nothing is re-run here: the answers
     * are applied when the report is generated (the graph is built then), and they are
     * saved per repository so the next run applies them without asking.
     */
    private void onAliasAnswers(ModalInteractionEvent event) {
        System.out.println(">>> trigger alias modal interaction event");
        String testerId = event.getUser().getId();

        DependencyAnalysisStateStore.State state = stateStore.get(testerId);
        if (state == null) {
            event.reply("The checkpoint has expired, so these answers have nowhere to go. "
                    + "Please re-run get-dependency-analysis.").setEphemeral(true).queue();
            return;
        }

        List<AliasResolution.Question> pending = AliasResolution.Questions.fromJson(
                stateStore.getStage(testerId, DependencyAnalysisStateStore.STAGE_PENDING_ALIASES)).list();
        AliasResolution.Answers answers = AliasResolution.Answers.fromJson(
                stateStore.getStage(testerId, DependencyAnalysisStateStore.STAGE_ALIAS_ANSWERS));

        // The service ids an answer may name: every service on the graph that raised the
        // questions, plus every candidate. The operator often knows a name the ranking
        // could not put forward ("discovery" for cloud-eureka-server).
        java.util.Set<String> knownIds = AliasResolution.Questions.vocabularyFromJson(
                stateStore.getStage(testerId, DependencyAnalysisStateStore.STAGE_ALIAS_VOCABULARY));
        // A checkpoint whose questions were posted before the vocabulary was kept has
        // none: then a typed id is taken as written and checked when the graph is built
        // (an answer naming a service the graph does not have conjures nothing).
        boolean vocabularyKnown = !knownIds.isEmpty();
        for (AliasResolution.Question q : pending) knownIds.addAll(q.candidates);

        List<String> understood = new ArrayList<>();
        List<String> notUnderstood = new ArrayList<>();
        for (ModalMapping mapping : event.getValues()) {
            String id = mapping.getId();
            if (!id.startsWith(DepstateToolkit.ALIAS_INPUT_PREFIX)) continue;
            int index;
            try {
                index = Integer.parseInt(id.substring(DepstateToolkit.ALIAS_INPUT_PREFIX.length()));
            } catch (NumberFormatException e) {
                continue;
            }
            if (index < 0 || index >= pending.size()) continue;
            AliasResolution.Question q = pending.get(index);
            String typed = mapping.getAsString();
            if (typed == null || typed.isBlank()) continue;          // left blank: still pending
            String decision = AliasResolution.parseAnswer(typed, q, knownIds);
            boolean unchecked = false;
            if (decision == null && !vocabularyKnown && AliasResolution.looksLikeServiceId(typed)) {
                decision = typed.trim().toLowerCase(java.util.Locale.ROOT);
                unchecked = true;
            }
            if (decision == null) {
                notUnderstood.add("`" + q.name + "` ← \"" + typed.trim() + "\"");
                continue;
            }
            answers.put(q.name, decision);
            understood.add("`" + q.name + "` " + AliasResolution.describe(decision)
                    + (unchecked ? " _(not checked yet: applied only if that service is on the graph)_" : ""));
        }

        if (understood.isEmpty()) {
            String why = notUnderstood.isEmpty()
                    ? "Nothing was filled in, so nothing changed."
                    : "I could not read these answers, so nothing changed:\n• "
                        + String.join("\n• ", notUnderstood);
            event.reply(why + "\nAnswer with a candidate's number, the service id, `new` or `ignore`. "
                    + "Click **Resolve names** again when ready.").setEphemeral(true).queue();
            return;
        }

        stateStore.putStage(testerId, DependencyAnalysisStateStore.STAGE_ALIAS_ANSWERS, answers.toJson());
        stateStore.saveProjectAliases(state.repoName, answers.toJson());

        // Whatever was not answered (blank or unreadable) stays pending for the next round.
        AliasResolution.Questions stillPending = new AliasResolution.Questions();
        for (AliasResolution.Question q : pending) {
            if (!answers.has(q.name)) stillPending.addWithCandidates(q.name, q.origin, q.seenIn, q.candidates);
        }
        stateStore.putStage(testerId, DependencyAnalysisStateStore.STAGE_PENDING_ALIASES,
                stillPending.isEmpty() ? "" : stillPending.toJson());

        StringBuilder sb = new StringBuilder("**Got it — names resolved:**\n");
        for (String line : understood) sb.append("• ").append(line).append('\n');
        if (!notUnderstood.isEmpty()) {
            sb.append("\nNot understood (still pending): ").append(String.join(", ", notUnderstood)).append('\n');
        }
        if (!stillPending.isEmpty()) {
            sb.append("\nStill open: ").append(stillPending.size())
                    .append(" name(s) — click **Resolve names** below for the next form, or leave them off the graph.\n");
        }
        sb.append("\nThese are applied when you click **Generate report**, and remembered for `")
                .append(state.repoName).append("` so the next analysis does not ask again.");
        // A fresh button right here when names remain, so the next five are one click
        // away instead of a scroll back up to the original question.
        if (stillPending.isEmpty()) {
            event.reply(sb.toString()).queue();
        } else {
            event.reply(sb.toString())
                    .addActionRow(Button.primary(DepstateToolkit.RESOLVE_ALIASES_BUTTON_ID, "Resolve names"))
                    .queue();
        }

        System.out.println("<<< end of current alias modal interaction event");
    }

    /**
     * What was received, echoed back so the operator can see a typo — except for a
     * credential-shaped key, which is masked (it is still substituted into the
     * request; it is simply never displayed or put in a prompt).
     */
    private String summary(List<AskItem> pending, Map<String, String> values,
                           List<String> answered, List<AskItem> stillPending) {
        Map<String, AskItem> byKey = new LinkedHashMap<>();
        for (AskItem ask : pending) byKey.put(ask.key, ask);

        StringBuilder sb = new StringBuilder("**Got it — re-driving traffic with:**\n");
        for (String key : answered) {
            AskItem ask = byKey.get(key);
            String shown = ask == null ? values.get(key) : ask.maskIfSecret(values.get(key));
            sb.append("• `").append(key).append("` = ").append(shown).append('\n');
        }
        if (!stillPending.isEmpty()) {
            sb.append("\nStill unanswered (will be asked again): ");
            for (int i = 0; i < stillPending.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('`').append(stillPending.get(i).key).append('`');
            }
            sb.append('\n');
        }
        sb.append("\nResuming from the checkpoint — the requests that were held back "
                + "(`[WAIT]`) are retried with these values, then coverage is re-measured. "
                + "DeepWiki and code extraction are not re-run.");
        return sb.toString();
    }
}
