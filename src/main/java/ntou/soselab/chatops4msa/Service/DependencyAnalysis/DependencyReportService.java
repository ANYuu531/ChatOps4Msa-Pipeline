package ntou.soselab.chatops4msa.Service.DependencyAnalysis;

import ntou.soselab.chatops4msa.Entity.ToolkitFunction.DiscordToolkit;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.LlmToolkit;
import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Produces the final dependency-analysis report from the stored checkpoint, so
 * clicking "Generate report" never re-runs collection.
 *
 * Mirrors the final LLM step of the low-code flow: same inputs, same prompt
 * template (dependency_analysis).
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
     * Generates and posts the report for the given user from the stored evidence.
     * UserContextHolder must already be set to this user (LlmToolkit needs it).
     */
    public void generateAndPost(String userId) {
        DependencyAnalysisStateStore.State state = stateStore.get(userId);
        if (state == null) {
            jdaService.sendChatOpsChannelWarningMessage(
                    "[WARNING] No dependency-analysis checkpoint found (it may have expired). "
                            + "Please re-run get-dependency-analysis.");
            return;
        }

        String prompt = "## Documentation + code dependency notes\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_MERGED_NOTES) + "\n\n"
                + "## Kubernetes / Istio runtime notes\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_K8S) + "\n\n"
                + "## Istio runtime-observed edge ledger (internal, mesh-to-mesh traffic)\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_TRAFFIC) + "\n\n"
                + "## Istio egress edge ledger (external dependencies leaving the mesh)\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_EGRESS);

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
