package ntou.soselab.chatops4msa.Service.DiscordService;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.DepstateToolkit;
import ntou.soselab.chatops4msa.Service.CapabilityOrchestrator.CapabilityOrchestrator;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
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
