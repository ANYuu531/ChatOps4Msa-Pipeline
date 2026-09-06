package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a finished dependency-analysis report leaves behind for questions.
 *
 * The analysis checkpoint is deleted the moment the report is posted (it holds raw
 * Prometheus JSON and user-supplied secrets, and its job is done). What the reader
 * wants to ask about afterwards is different: the report text, the graph the report
 * was derived from, the coverage figure, and the human-readable evidence notes. This
 * object is that residue, kept as one JSON file per report, so a question asked hours
 * later — in the Discord thread opened under the report — can still be answered from
 * the same evidence the report was written from rather than from memory.
 */
public final class ReportArchive {

    public String id = "";
    public String userId = "";
    public String repoName = "";
    public String namespace = "";
    /** "greenfield" or "runtime", as the report prompt was told. */
    public String mode = "";
    public Instant createdAt = Instant.now();
    /** The Discord thread this report is discussed in; empty until the thread exists. */
    public String threadId = "";

    /** The posted report, verbatim. */
    public String report = "";
    /** {@code DependencyGraph.toJson()} of the graph the report was derived from. */
    public JSONObject graphJson = new JSONObject();
    /** The deterministic coverage message, or empty when nothing was measurable. */
    public String coverage = "";
    /** Human-readable evidence stages, label → text (merged notes, k8s notes, traffic report…). */
    public final Map<String, String> evidence = new LinkedHashMap<>();

    /** The retrieval corpus built from report + evidence, with embeddings when available. */
    public final List<TextChunk> chunks = new ArrayList<>();
    /** The Q&amp;A so far, as {role, content} messages, oldest first. */
    public final List<JSONObject> history = new ArrayList<>();

    public boolean hasEmbeddings() {
        for (TextChunk c : chunks) if (c.embedding != null) return true;
        return false;
    }

    public JSONObject toJson() {
        JSONObject ev = new JSONObject();
        evidence.forEach(ev::put);
        JSONArray chunkArray = new JSONArray();
        for (TextChunk c : chunks) chunkArray.put(c.toJson());
        return new JSONObject()
                .put("id", id)
                .put("userId", userId)
                .put("repoName", repoName)
                .put("namespace", namespace)
                .put("mode", mode)
                .put("createdAt", createdAt.toString())
                .put("threadId", threadId)
                .put("report", report)
                .put("graph", graphJson)
                .put("coverage", coverage)
                .put("evidence", ev)
                .put("chunks", chunkArray)
                .put("history", new JSONArray(history));
    }

    public static ReportArchive fromJson(JSONObject json) {
        ReportArchive a = new ReportArchive();
        a.id = json.optString("id", "");
        a.userId = json.optString("userId", "");
        a.repoName = json.optString("repoName", "");
        a.namespace = json.optString("namespace", "");
        a.mode = json.optString("mode", "");
        try {
            a.createdAt = Instant.parse(json.optString("createdAt"));
        } catch (Exception e) {
            a.createdAt = Instant.now();
        }
        a.threadId = json.optString("threadId", "");
        a.report = json.optString("report", "");
        JSONObject graph = json.optJSONObject("graph");
        a.graphJson = graph == null ? new JSONObject() : graph;
        a.coverage = json.optString("coverage", "");
        JSONObject ev = json.optJSONObject("evidence");
        if (ev != null) for (String key : ev.keySet()) a.evidence.put(key, ev.optString(key, ""));
        JSONArray chunkArray = json.optJSONArray("chunks");
        if (chunkArray != null) {
            for (int i = 0; i < chunkArray.length(); i++) {
                JSONObject c = chunkArray.optJSONObject(i);
                if (c != null) a.chunks.add(TextChunk.fromJson(c));
            }
        }
        JSONArray hist = json.optJSONArray("history");
        if (hist != null) {
            for (int i = 0; i < hist.length(); i++) {
                JSONObject m = hist.optJSONObject(i);
                if (m != null) a.history.add(m);
            }
        }
        return a;
    }
}
