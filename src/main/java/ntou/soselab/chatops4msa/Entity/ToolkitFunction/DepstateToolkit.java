package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import net.dv8tion.jda.api.interactions.components.buttons.Button;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import ntou.soselab.chatops4msa.Service.DiscordService.UserContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Checkpoint step for get-dependency-analysis: persists the collected evidence
 * for the current user and asks (via buttons) whether to generate the report now
 * or pause to supplement more evidence and re-run.
 */
@Component
public class DepstateToolkit extends ToolkitFunction {

    public static final String CONTINUE_BUTTON_ID = "DepContinue";
    public static final String PAUSE_BUTTON_ID = "DepPause";

    private final DependencyAnalysisStateStore stateStore;
    private final JDAService jdaService;

    @Autowired
    public DepstateToolkit(DependencyAnalysisStateStore stateStore,
                           @Lazy JDAService jdaService) {
        this.stateStore = stateStore;
        this.jdaService = jdaService;
    }

    /**
     * Saves the evidence keyed by the current Discord user and posts the
     * continue/pause decision buttons.
     */
    public String toolkitDepstateCheckpoint(String repo_name,
                                            String namespace,
                                            String deepwiki_notes,
                                            String k8s_notes,
                                            String runtime_notes) {
        String userId = UserContextHolder.getUserId();
        if (userId == null || userId.isBlank()) {
            return "[ERROR] no user context; cannot save checkpoint.";
        }

        stateStore.save(userId, new DependencyAnalysisStateStore.State(
                repo_name, namespace, deepwiki_notes, k8s_notes, runtime_notes));

        String message = "**Dependency Analysis - collection done, completeness checked**\n"
                + "Repository: `" + repo_name + "` | Namespace: `" + namespace + "`\n\n"
                + "Review the completeness check and gap list above, then choose:\n"
                + "• **Generate report** — produce the final report from the current evidence\n"
                + "• **Pause & supplement** — go collect more traffic/code, then re-run get-dependency-analysis";

        List<Button> buttons = List.of(
                Button.success(CONTINUE_BUTTON_ID, "Generate report"),
                Button.secondary(PAUSE_BUTTON_ID, "Pause & supplement"));

        jdaService.sendChatOpsChannelMessageWithButtons(message, buttons);
        return "checkpoint saved";
    }
}
