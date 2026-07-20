package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic.TrafficRunner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic.TrafficScenario;
import ntou.soselab.chatops4msa.Service.DiscordService.UserContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Drives traffic through the system so Istio can observe its dependency edges.
 *
 * Istio only records an edge once a real request has crossed it, so the quality
 * of the dependency graph is bounded by how much of the system the traffic
 * actually exercises. This runs a generated (or user-supplied) journey rather
 * than a single smoke request against the front page.
 */
@Component
public class TrafficToolkit extends ToolkitFunction {

    private final TrafficRunner trafficRunner;
    private final DependencyAnalysisStateStore stateStore;

    @Autowired
    public TrafficToolkit(TrafficRunner trafficRunner,
                          DependencyAnalysisStateStore stateStore) {
        this.trafficRunner = trafficRunner;
        this.stateStore = stateStore;
    }

    /**
     * @param scenario_json a Postman Collection v2.1 (LLM-generated or user-supplied);
     *                      prose and ```json fences around it are tolerated
     * @param entry_url     ingress entry point, or 'none' to skip
     * @param repeats       how many passes over the collection
     * @return an execution report, which is fed back into the next round of
     *         collection generation when coverage is still incomplete
     */
    public String toolkitTrafficRun(String scenario_json, String entry_url, String repeats) {
        if (entry_url == null || entry_url.isBlank() || "none".equalsIgnoreCase(entry_url.trim())
                || !(entry_url.startsWith("http://") || entry_url.startsWith("https://"))) {
            return "# Traffic Execution Report\n\n"
                    + "Traffic was NOT driven: no usable entry_url was provided (got: " + entry_url + ").\n"
                    + "Istio can only observe a dependency after a real request crosses it, so any edge "
                    + "not already exercised will be missing from the runtime evidence.\n"
                    + "Drive traffic manually, then resume.";
        }

        TrafficScenario scenario;
        try {
            scenario = TrafficScenario.parse(scenario_json);
        } catch (Exception e) {
            return "# Traffic Execution Report\n\n"
                    + "Traffic was NOT driven: the Postman collection could not be parsed ("
                    + e.getMessage() + ").\n"
                    + "No requests were sent, so no new runtime evidence was produced.";
        }

        int passes;
        try {
            passes = Math.max(1, Math.min(5, Integer.parseInt(repeats.trim())));
        } catch (Exception e) {
            passes = 2;
        }

        TrafficRunner.RunReport report = trafficRunner.run(scenario, entry_url.trim(), passes);
        String rendered = report.render(entry_url.trim(), passes);

        String userId = UserContextHolder.getUserId();
        if (userId != null && !userId.isBlank()) {
            // Kept so a resume can show what was already tried and refine it,
            // instead of regenerating the same ineffective journey.
            stateStore.putStage(userId, DependencyAnalysisStateStore.STAGE_TRAFFIC_SCENARIO, scenario_json);
            stateStore.putStage(userId, DependencyAnalysisStateStore.STAGE_TRAFFIC_REPORT, rendered);
        }
        return rendered;
    }

    /** The scenario used on the previous run, so it can be refined rather than reinvented. */
    public String toolkitTrafficLastScenario() {
        String userId = UserContextHolder.getUserId();
        if (userId == null || userId.isBlank()) return "";
        return stateStore.getStage(userId, DependencyAnalysisStateStore.STAGE_TRAFFIC_SCENARIO);
    }

    /** The result of the previous run, so the next scenario can react to what failed. */
    public String toolkitTrafficLastReport() {
        String userId = UserContextHolder.getUserId();
        if (userId == null || userId.isBlank()) return "";
        return stateStore.getStage(userId, DependencyAnalysisStateStore.STAGE_TRAFFIC_REPORT);
    }
}
