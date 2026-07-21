package ntou.soselab.chatops4msa.Service.DiscordService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the long dependency-analysis capabilities off the Discord (JDA) event
 * thread.
 *
 * Why this exists: the collection capabilities (get-/resume-dependency-analysis)
 * run synchronously for minutes. If that runs on the JDA event thread, the bot
 * cannot acknowledge a button click within Discord's 3-second window, so the
 * click fails with "the application did not respond". Moving the work here frees
 * the event thread to flush the interaction ack immediately.
 *
 * {@link UserContextHolder} is a ThreadLocal, so it does NOT cross onto the
 * worker thread by itself — this runner sets it inside the task and clears it in
 * a finally, which is exactly what every {@code toolkit-depstate-*} /
 * {@code toolkit-llm-call} deep in the capability body reads.
 *
 * A single worker thread serialises runs, which is what we want: two overlapping
 * analyses for the same user would corrupt the one per-user checkpoint.
 */
@Service
public class DependencyAnalysisRunner {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dependency-analysis");
        thread.setDaemon(true);
        return thread;
    });

    private final JDAService jdaService;

    @Autowired
    public DependencyAnalysisRunner(@Lazy JDAService jdaService) {
        this.jdaService = jdaService;
    }

    /** A unit of work that may throw the orchestrator's checked exceptions. */
    @FunctionalInterface
    public interface AnalysisTask {
        void run() throws Exception;
    }

    /** The capabilities long enough that they must not run on the JDA event thread. */
    public static boolean isLongRunning(String capabilityName) {
        return "get-dependency-analysis".equals(capabilityName)
                || "resume-dependency-analysis".equals(capabilityName);
    }

    /**
     * Runs {@code task} on the background thread with the user's context set for
     * its whole duration. A failure is reported to the channel rather than lost on
     * the worker thread.
     *
     * @param userId the Discord user whose checkpoint this run belongs to
     * @param label  a short name for the run, used only in an error message
     */
    public void run(String userId, String label, AnalysisTask task) {
        executor.execute(() -> {
            try {
                UserContextHolder.setUserId(userId);
                task.run();
            } catch (Exception e) {
                e.printStackTrace();
                jdaService.sendChatOpsChannelErrorMessage(
                        "[ERROR] " + label + " failed: " + e.getLocalizedMessage());
            } finally {
                UserContextHolder.clear();
            }
        });
    }
}
