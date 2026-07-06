package ntou.soselab.chatops4msa.Service.DependencyAnalysis;

import ntou.soselab.chatops4msa.Entity.ToolkitFunction.DiscordToolkit;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.LlmToolkit;
import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Produces the final dependency-analysis report from evidence that was already
 * collected and stored, so clicking "continue" does not re-run collection.
 *
 * Mirrors the final toolkit-llm-call in dependency_analysis.yml: same inputs,
 * same prompt template (dependency_analysis).
 */
@Service
public class DependencyReportService {

    private final DependencyAnalysisStateStore stateStore;
    private final LlmToolkit llmToolkit;
    private final DiscordToolkit discordToolkit;
    private final JDAService jdaService;

    @Autowired
    public DependencyReportService(DependencyAnalysisStateStore stateStore,
                                   LlmToolkit llmToolkit,
                                   DiscordToolkit discordToolkit,
                                   @Lazy JDAService jdaService) {
        this.stateStore = stateStore;
        this.llmToolkit = llmToolkit;
        this.discordToolkit = discordToolkit;
        this.jdaService = jdaService;
    }

    /**
     * Generates and posts the report for the given user from stored evidence.
     * Assumes UserContextHolder is already set to this user (LlmToolkit needs it).
     */
    public void generateAndPost(String userId) {
        DependencyAnalysisStateStore.State state = stateStore.get(userId);
        if (state == null) {
            jdaService.sendChatOpsChannelWarningMessage(
                    "[WARNING] No collected dependency-analysis evidence found (it may have expired). Please re-run get-dependency-analysis.");
            return;
        }

        String prompt = "## DeepWiki dependency notes\n" + state.deepwikiNotes + "\n\n"
                + "## Kubernetes / Istio runtime notes\n" + state.k8sNotes + "\n\n"
                + "## Istio runtime-observed edge ledger (direct traffic evidence)\n" + state.runtimeNotes;

        String response = llmToolkit.toolkitLlmCall(prompt, "dependency_analysis");

        String report = "## Microservice Dependency Analysis Report\n"
                + "**Repository:** `" + state.repoName + "` | **Namespace:** `" + state.namespace + "`\n\n"
                + response;
        try {
            // toolkitDiscordText auto-sends as a file when the text is long,
            // matching how the report is delivered from the low-code flow.
            discordToolkit.toolkitDiscordText(report);
        } catch (IOException e) {
            jdaService.sendChatOpsChannelErrorMessage("[ERROR] failed to send the report: " + e.getMessage());
        }

        stateStore.remove(userId);
    }
}
