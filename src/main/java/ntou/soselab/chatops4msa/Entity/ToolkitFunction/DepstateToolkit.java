package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import net.dv8tion.jda.api.interactions.components.buttons.Button;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ExternalHost;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import ntou.soselab.chatops4msa.Service.DiscordService.UserContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Checkpointing for the dependency analysis.
 *
 * Each collection stage stores its result as it completes, so that pausing to
 * supplement evidence (typically: drive traffic through the mesh) can be resumed
 * from the breakpoint. Resuming re-runs only the stale stage and its downstream
 * steps; DeepWiki and the repository clone are not repeated.
 */
@Component
public class DepstateToolkit extends ToolkitFunction {

    public static final String CONTINUE_BUTTON_ID = "DepContinue";
    public static final String PAUSE_BUTTON_ID = "DepPause";
    public static final String RESUME_BUTTON_ID = "DepResume";
    public static final String APPLY_SERVICE_ENTRIES_BUTTON_ID = "DepApplyServiceEntries";

    private final DependencyAnalysisStateStore stateStore;
    private final JDAService jdaService;

    @Autowired
    public DepstateToolkit(DependencyAnalysisStateStore stateStore,
                           @Lazy JDAService jdaService) {
        this.stateStore = stateStore;
        this.jdaService = jdaService;
    }

    /**
     * Begins a fresh run, discarding any earlier checkpoint for this user.
     *
     * entry_url and auth_hint are stored so that resuming can re-drive traffic
     * without asking the user for them again.
     */
    public String toolkitDepstateStart(String repo_name, String namespace,
                                       String entry_url, String auth_hint) {
        String userId = requireUser();
        if (userId == null) return "[ERROR] no user context; cannot start a checkpoint.";

        stateStore.start(userId, repo_name, namespace);
        stateStore.putStage(userId, DependencyAnalysisStateStore.STAGE_ENTRY_URL, entry_url);
        stateStore.putStage(userId, DependencyAnalysisStateStore.STAGE_AUTH_HINT, auth_hint);
        return "started";
    }

    /** Stores one collection stage. */
    public String toolkitDepstatePut(String stage, String value) {
        String userId = requireUser();
        if (userId == null) return "[ERROR] no user context; cannot save stage.";
        stateStore.putStage(userId, stage, value);
        return "saved " + stage;
    }

    /**
     * @return the stored stage, or an empty string when it was never collected.
     *         The low-code flow tests the result to decide whether to skip the
     *         (expensive) stage or recompute it.
     */
    public String toolkitDepstateGet(String stage) {
        String userId = requireUser();
        if (userId == null) return "";
        return stateStore.getStage(userId, stage);
    }

    /**
     * Posts the continue / pause decision. The report itself is produced only when
     * the user clicks "Generate report", from the evidence already stored.
     */
    public String toolkitDepstateCheckpoint() {
        String userId = requireUser();
        if (userId == null) return "[ERROR] no user context; cannot post the checkpoint.";

        DependencyAnalysisStateStore.State state = stateStore.get(userId);
        if (state == null) return "[ERROR] no checkpoint to post; the run was never started.";

        String message = "**Dependency Analysis - collection done, completeness checked**\n"
                + "Repository: `" + state.repoName + "` | Namespace: `" + state.namespace + "`\n\n"
                + "Review the completeness check and gap list above, then choose:\n"
                + "• **Generate report** — produce the final report from the current evidence\n"
                + "• **Pause & supplement** — go drive traffic or add evidence, then resume "
                + "(the analysis continues from here; DeepWiki and code extraction are not re-run)";

        List<Button> buttons = List.of(
                Button.success(CONTINUE_BUTTON_ID, "Generate report"),
                Button.secondary(PAUSE_BUTTON_ID, "Pause & supplement"));

        jdaService.sendChatOpsChannelMessageWithButtons(message, buttons);
        return "checkpoint posted";
    }

    /**
     * Posts the "Apply ServiceEntries" decision button, right after the manifest
     * has been shown. The apply itself is a change to the cluster, so it is never
     * done silently: it happens only when the user clicks (handled in
     * ButtonListener, which runs kubectl apply through the k8s MCP server).
     *
     * When the code extraction found no external hosts there is nothing to apply,
     * so no button is posted.
     */
    public String toolkitDepstateApplyButton() {
        String userId = requireUser();
        if (userId == null) return "[ERROR] no user context; cannot post the apply button.";

        List<ExternalHost> hosts = ExternalHost.fromJson(
                stateStore.getStage(userId, DependencyAnalysisStateStore.STAGE_EXTERNAL_HOSTS));
        if (hosts.isEmpty()) {
            return "no external hosts; nothing to apply.";
        }

        String message = "**Apply the ServiceEntry manifests above?**\n"
                + "They declare the " + hosts.size() + " external host(s) to Istio so the mesh can "
                + "observe and attribute those edges — without them the traffic goes through "
                + "PassthroughCluster and the hostname is lost.\n"
                + "Clicking **Apply** runs `kubectl apply` on the cluster through the k8s MCP server. "
                + "Nothing changes until you click.";

        List<Button> buttons = List.of(
                Button.primary(APPLY_SERVICE_ENTRIES_BUTTON_ID, "Apply ServiceEntries"));

        jdaService.sendChatOpsChannelMessageWithButtons(message, buttons);
        return "apply button posted";
    }

    private String requireUser() {
        String userId = UserContextHolder.getUserId();
        return (userId == null || userId.isBlank()) ? null : userId;
    }
}
