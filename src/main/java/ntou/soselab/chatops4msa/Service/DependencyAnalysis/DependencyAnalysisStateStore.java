package ntou.soselab.chatops4msa.Service.DependencyAnalysis;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The checkpoint for a dependency analysis: the evidence collected so far, stage
 * by stage, so a paused analysis can be resumed instead of re-run.
 *
 * Why per stage rather than one blob: when the user pauses to drive more traffic,
 * only the TRAFFIC stage is stale. DeepWiki's five questions and the repository
 * clone — by far the slowest and most expensive parts — are still valid and are
 * reused verbatim on resume.
 *
 * State is written to disk (one JSON file per Discord user) so a restart of the
 * application does not throw the checkpoint away, and expires after a TTL.
 */
@Component
public class DependencyAnalysisStateStore {

    // Stage keys. DOCS+CODE are merged into MERGED_NOTES by an LLM step; the
    // report consumes MERGED_NOTES, K8S and TRAFFIC.
    public static final String STAGE_DOCS = "docs";
    public static final String STAGE_CODE = "code";
    /**
     * The code-extracted edges as structured JSON (EdgeLedger.toJson), kept
     * alongside the rendered STAGE_CODE text so the dependency-graph merge consumes
     * code edges as data instead of re-parsing markdown.
     */
    public static final String STAGE_CODE_EDGES = "code_edges";
    public static final String STAGE_MERGED_NOTES = "merged_notes";
    public static final String STAGE_K8S = "k8s";
    public static final String STAGE_TRAFFIC = "traffic";
    /**
     * The raw Istio Prometheus JSON for the internal mesh-to-mesh query, kept
     * verbatim (unlike STAGE_TRAFFIC, which is the LLM's prose rendering of it).
     * It is the deterministic source the dependency-graph visualization parses,
     * so the graph never has to re-read LLM text.
     */
    public static final String STAGE_TRAFFIC_RAW = "traffic_raw";
    public static final String STAGE_EGRESS = "egress";
    public static final String STAGE_HEALTH = "health";
    /** The journey that was driven, and how it went — refined across resumes to close coverage gaps. */
    public static final String STAGE_TRAFFIC_SCENARIO = "traffic_scenario";
    public static final String STAGE_TRAFFIC_REPORT = "traffic_report";
    /** Kept so a resume can re-drive traffic without the user re-supplying them. */
    public static final String STAGE_ENTRY_URL = "entry_url";
    public static final String STAGE_AUTH_HINT = "auth_hint";
    /** JSON list of the external hosts found in the code; drives the ServiceEntry suggestions. */
    public static final String STAGE_EXTERNAL_HOSTS = "external_hosts";

    public static class State {
        public String repoName = "";
        public String namespace = "";
        public Instant updatedAt = Instant.now();
        public final Map<String, String> stages = new LinkedHashMap<>();

        public String stage(String key) {
            return stages.getOrDefault(key, "");
        }

        public boolean has(String key) {
            String value = stages.get(key);
            return value != null && !value.isBlank();
        }

        JSONObject toJson() {
            JSONObject stagesJson = new JSONObject();
            stages.forEach(stagesJson::put);
            return new JSONObject()
                    .put("repoName", repoName)
                    .put("namespace", namespace)
                    .put("updatedAt", updatedAt.toString())
                    .put("stages", stagesJson);
        }

        static State fromJson(JSONObject json) {
            State state = new State();
            state.repoName = json.optString("repoName", "");
            state.namespace = json.optString("namespace", "");
            try {
                state.updatedAt = Instant.parse(json.optString("updatedAt"));
            } catch (Exception e) {
                state.updatedAt = Instant.now();
            }
            JSONObject stagesJson = json.optJSONObject("stages");
            if (stagesJson != null) {
                for (String key : stagesJson.keySet()) {
                    state.stages.put(key, stagesJson.optString(key, ""));
                }
            }
            return state;
        }
    }

    private final ConcurrentMap<String, State> cache = new ConcurrentHashMap<>();
    private final Path directory;
    private final Duration ttl;

    public DependencyAnalysisStateStore(
            @Value("${dependency.state.dir:./dep-state}") String directory,
            @Value("${dependency.state.ttl-hours:24}") long ttlHours) {
        this.directory = Path.of(directory);
        this.ttl = Duration.ofHours(ttlHours);
    }

    /** Starts a fresh run, discarding any previous checkpoint for this user. */
    public State start(String userId, String repoName, String namespace) {
        State state = new State();
        state.repoName = repoName == null ? "" : repoName;
        state.namespace = namespace == null ? "" : namespace;
        save(userId, state);
        return state;
    }

    /** Null when there is no checkpoint, or it has expired. */
    public State get(String userId) {
        if (userId == null || userId.isBlank()) return null;

        State state = cache.get(userId);
        if (state == null) state = readFromDisk(userId);
        if (state == null) return null;

        if (Duration.between(state.updatedAt, Instant.now()).compareTo(ttl) > 0) {
            remove(userId);
            return null;
        }
        return state;
    }

    public void putStage(String userId, String stage, String value) {
        if (userId == null || stage == null) return;
        State state = get(userId);
        if (state == null) state = new State();
        state.stages.put(stage, value == null ? "" : value);
        save(userId, state);
    }

    public String getStage(String userId, String stage) {
        State state = get(userId);
        return state == null ? "" : state.stage(stage);
    }

    public void save(String userId, State state) {
        if (userId == null || userId.isBlank() || state == null) return;
        state.updatedAt = Instant.now();
        cache.put(userId, state);
        writeToDisk(userId, state);
    }

    public void remove(String userId) {
        if (userId == null) return;
        cache.remove(userId);
        try {
            Files.deleteIfExists(fileOf(userId));
        } catch (Exception ignored) {
            // a stale file will simply expire via the TTL
        }
    }

    // ---------- persistence ----------

    private Path fileOf(String userId) {
        // Discord ids are numeric, but sanitise anyway: this becomes a filename.
        String safe = userId.replaceAll("[^A-Za-z0-9_-]", "_");
        return directory.resolve(safe + ".json");
    }

    private void writeToDisk(String userId, State state) {
        try {
            Files.createDirectories(directory);
            Files.writeString(fileOf(userId), state.toJson().toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Persistence is an optimisation: an unwritable directory must not break
            // the analysis, it only means a restart loses the checkpoint.
            System.out.println("[WARNING] cannot persist dependency-analysis checkpoint: " + e.getMessage());
        }
    }

    private State readFromDisk(String userId) {
        try {
            Path file = fileOf(userId);
            if (!Files.exists(file)) return null;
            State state = State.fromJson(new JSONObject(Files.readString(file, StandardCharsets.UTF_8)));
            cache.put(userId, state);
            return state;
        } catch (Exception e) {
            return null;
        }
    }
}
