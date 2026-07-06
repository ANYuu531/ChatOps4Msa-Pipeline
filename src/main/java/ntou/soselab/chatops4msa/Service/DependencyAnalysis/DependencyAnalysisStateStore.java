package ntou.soselab.chatops4msa.Service.DependencyAnalysis;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds the evidence collected by get-dependency-analysis for each user, so the
 * final report can be produced later when the user clicks the "continue" button
 * WITHOUT re-running the (expensive) collection.
 *
 * Keyed by Discord user id. Single latest checkpoint per user is kept.
 */
@Component
public class DependencyAnalysisStateStore {

    public static class State {
        public final String repoName;
        public final String namespace;
        public final String deepwikiNotes;
        public final String k8sNotes;
        public final String runtimeNotes;

        public State(String repoName, String namespace,
                     String deepwikiNotes, String k8sNotes, String runtimeNotes) {
            this.repoName = repoName;
            this.namespace = namespace;
            this.deepwikiNotes = deepwikiNotes;
            this.k8sNotes = k8sNotes;
            this.runtimeNotes = runtimeNotes;
        }
    }

    private final ConcurrentMap<String, State> stateMap = new ConcurrentHashMap<>();

    public void save(String userId, State state) {
        if (userId != null) stateMap.put(userId, state);
    }

    public State get(String userId) {
        return userId == null ? null : stateMap.get(userId);
    }

    public void remove(String userId) {
        if (userId != null) stateMap.remove(userId);
    }
}
