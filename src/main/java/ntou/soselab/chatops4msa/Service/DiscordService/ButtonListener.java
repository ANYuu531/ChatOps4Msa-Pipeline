package ntou.soselab.chatops4msa.Service.DiscordService;

import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import ntou.soselab.chatops4msa.Entity.NLP.IntentAndEntity;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.DepstateToolkit;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.McpToolkit;
import ntou.soselab.chatops4msa.Exception.CapabilityRoleException;
import ntou.soselab.chatops4msa.Exception.ToolkitFunctionException;
import ntou.soselab.chatops4msa.Service.CapabilityOrchestrator.CapabilityOrchestrator;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ExternalHost;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ExternalHostDetector;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyReportService;
import org.json.JSONObject;
import ntou.soselab.chatops4msa.Service.NLPService.DialogueTracker;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class ButtonListener extends ListenerAdapter {
    private final DialogueTracker dialogueTracker;
    private final CapabilityOrchestrator orchestrator;
    private final JDAService jdaService;
    private final DependencyReportService dependencyReportService;
    private final DependencyAnalysisStateStore stateStore;
    private final McpToolkit mcpToolkit;
    private final ExternalHostDetector externalHostDetector;
    private final DependencyAnalysisRunner analysisRunner;

    @Lazy
    @Autowired
    public ButtonListener(DialogueTracker dialogueTracker,
                          CapabilityOrchestrator orchestrator,
                          JDAService jdaService,
                          DependencyReportService dependencyReportService,
                          DependencyAnalysisStateStore stateStore,
                          McpToolkit mcpToolkit,
                          ExternalHostDetector externalHostDetector,
                          DependencyAnalysisRunner analysisRunner) {

        this.dialogueTracker = dialogueTracker;
        this.orchestrator = orchestrator;
        this.jdaService = jdaService;
        this.dependencyReportService = dependencyReportService;
        this.stateStore = stateStore;
        this.mcpToolkit = mcpToolkit;
        this.externalHostDetector = externalHostDetector;
        this.analysisRunner = analysisRunner;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {

        System.out.println(">>> trigger button interaction event");

        System.out.println("[TIME] " + new Date());
        event.deferReply().queue();
        User tester = event.getUser();
        String testerId = tester.getId();
        String testerName = tester.getName();
        String buttonId = event.getButton().getId();
        System.out.println("[DEBUG] " + testerName + " click " + buttonId);

        // disable the button
        List<MessageEmbed> originalEmbedList = event.getMessage().getEmbeds();
        Button disabledButton = event.getComponent().asDisabled();
        event.getMessage().editMessageEmbeds(originalEmbedList).setActionRow(disabledButton).queue();

        // get the user roles
        List<String> roleNameList = new ArrayList<>();
        Member member = event.getMember();
        if (member != null) {
            for (Role role : member.getRoles()) {
                roleNameList.add(role.getName());
            }
        }
        System.out.println("[User Role] " + roleNameList);

        // dependency-analysis decision buttons (handled before the NLP intent flow)
        if (DepstateToolkit.CONTINUE_BUTTON_ID.equals(buttonId)) {
            event.getHook().editOriginal("Generating report...").queue();
            // The report is an LLM call plus graph rendering — well over Discord's 3s
            // window, so run it off the event thread (the ack above flushes first).
            analysisRunner.run(testerId, "generate-report", () ->
                    dependencyReportService.generateAndPost(testerId));
            return;
        }

        if (DepstateToolkit.PAUSE_BUTTON_ID.equals(buttonId)) {
            // The checkpoint is kept, so resuming re-runs only the traffic query and
            // the health check. DeepWiki and the code extraction are not repeated.
            DependencyAnalysisStateStore.State state = stateStore.get(testerId);
            if (state == null) {
                event.getHook().editOriginal(
                        "Paused, but the checkpoint has expired. Please re-run get-dependency-analysis.").queue();
                return;
            }
            event.getHook().editOriginal(
                    "**Paused — checkpoint kept.**\n"
                            + "Drive traffic now (click through the UI, run your load test, or curl the endpoints) "
                            + "so Istio can observe the dependencies, then click **Resume** below.\n"
                            + "Resuming re-queries runtime traffic and re-runs the completeness check only — "
                            + "DeepWiki and code extraction are reused from the checkpoint.").queue();
            jdaService.sendChatOpsChannelMessageWithButtons(
                    "Namespace: `" + state.namespace + "` — resume when the traffic has been driven.",
                    List.of(Button.primary(DepstateToolkit.RESUME_BUTTON_ID, "Resume (re-check traffic)")));
            return;
        }

        if (DepstateToolkit.RESUME_BUTTON_ID.equals(buttonId)) {
            DependencyAnalysisStateStore.State state = stateStore.get(testerId);
            if (state == null) {
                event.getHook().editOriginal(
                        "The checkpoint has expired. Please re-run get-dependency-analysis.").queue();
                return;
            }
            event.getHook().editOriginal("Resuming from the checkpoint...").queue();
            // Runs the low-code resume capability in the background (it owns the
            // Prometheus property and re-posts the checkpoint buttons when it
            // finishes). Off the event thread so the ack above flushes immediately.
            String resumeNamespace = state.namespace;
            analysisRunner.run(testerId, "resume-dependency-analysis", () ->
                    orchestrator.performTheCapability(
                            "resume-dependency-analysis",
                            Map.of("namespace", resumeNamespace),
                            roleNameList));
            return;
        }

        // Apply the ServiceEntry manifests the code extraction found. This is the
        // one dependency-analysis button that changes the cluster, so it only runs
        // on an explicit click: it re-renders the manifest from the checkpoint and
        // runs kubectl apply through the k8s MCP server.
        if (DepstateToolkit.APPLY_SERVICE_ENTRIES_BUTTON_ID.equals(buttonId)) {
            DependencyAnalysisStateStore.State state = stateStore.get(testerId);
            if (state == null) {
                event.getHook().editOriginal(
                        "The checkpoint has expired. Please re-run get-dependency-analysis.").queue();
                return;
            }

            List<ExternalHost> hosts = ExternalHost.fromJson(
                    stateStore.getStage(testerId, DependencyAnalysisStateStore.STAGE_EXTERNAL_HOSTS));
            if (hosts.isEmpty()) {
                event.getHook().editOriginal("No external hosts were found; nothing to apply.").queue();
                return;
            }

            String manifest = externalHostDetector.renderServiceEntries(hosts, state.namespace);
            String namespace = state.namespace;
            event.getHook().editOriginal("Applying " + hosts.size()
                    + " ServiceEntry manifest(s) to `" + namespace
                    + "`, then re-measuring automatically...").queue();

            // Apply + re-measure run in the background: the ack above flushes, and the
            // minutes-long re-measurement never blocks the bot. Applying alone changes
            // nothing observable — Istio only sees connections made AFTER the
            // ServiceEntry exists — so we immediately re-drive traffic and re-query
            // egress (resume). That is what makes the external edge actually appear,
            // with no separate manual Resume click.
            analysisRunner.run(testerId, "apply-service-entries", () -> {
                // Reconnect is idempotent; covers a drop between collection and click.
                mcpToolkit.toolkitMcpConnect("k8s", "http://k8s-mcp-server:8000", "/mcp");
                String toolArguments = new JSONObject()
                        .put("manifest", manifest)
                        .put("namespace", namespace)
                        .put("timeout", 30)
                        .toString();
                String applyResult = mcpToolkit.toolkitMcpCallTool("k8s", "apply_manifest", toolArguments);
                jdaService.sendChatOpsChannelMessage(
                        "**ServiceEntry apply result**\n"
                                + "Re-measuring now (driving traffic + re-querying Istio)...\n\n"
                                + applyResult);
                orchestrator.performTheCapability(
                        "resume-dependency-analysis", Map.of("namespace", namespace), roleNameList);
            });
            return;
        }

        // clear the temporary data
        List<IntentAndEntity> performedCapabilityList = dialogueTracker.removeAllPerformableIntentAndEntity(testerId);

        // perform the capability
        List<String> intentNameList = new ArrayList<>();
        try {
            for (IntentAndEntity intentAndEntity : performedCapabilityList) {
                String intentName = intentAndEntity.getIntentName();
                Map<String, String> entityMap = intentAndEntity.getEntities();
                if ("Perform".equals(buttonId)) {
                    if (DependencyAnalysisRunner.isLongRunning(intentName)) {
                        // The collection capabilities run for minutes; run them off the
                        // event thread so the bot stays responsive and the ack flushes.
                        event.getHook().editOriginal("Started `" + intentName
                                + "` in the background — I'll post progress here.").queue();
                        analysisRunner.run(testerId, intentName, () ->
                                orchestrator.performTheCapability(intentName, entityMap, roleNameList));
                    } else {
                        orchestrator.performTheCapability(intentName, entityMap, roleNameList);
                    }
                }
                intentNameList.add(intentName);
            }

        } catch (CapabilityRoleException e) {
            e.printStackTrace();
            String warningMessage = "[WARNING] " + e.getLocalizedMessage();
            System.out.println(warningMessage);
            jdaService.sendChatOpsChannelWarningMessage(warningMessage);

        } catch (ToolkitFunctionException e) {
            e.printStackTrace();
            String errorMessage = "[ERROR] " + e.getLocalizedMessage();
            System.out.println(errorMessage);
            jdaService.sendChatOpsChannelErrorMessage(errorMessage);
        }

        // generate the question for next capability (top of the stack)
        if (dialogueTracker.isWaitingTester(testerId)) {
            dialogueTracker.removeWaitingTesterList(testerId);
            String question = dialogueTracker.generateQuestionString(testerId);
            //event.reply("got it\n" + buttonId + " `" + intentNameList + "`\n\n" + question).queue();
            event.getHook().editOriginal("got it\n" + buttonId + " `" + intentNameList + "`\n\n" + question).queue();

            System.out.println("[DEBUG] generate question to " + testerName);
        }

        System.out.println("<<< end of current button interaction event");
        System.out.println();
    }
}
