package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One change the operator made to the graph in the report's thread: an edge or a node
 * the analysis did not produce, or an edge it produced that the operator says is wrong.
 *
 * <p>The change is recorded, not merged into a rule: it carries the operator's name,
 * the time, and the references they accepted, under its own provenance
 * ({@link DependencyGraph#PROV_HUMAN}), so the graph never presents a human's word as
 * something a layer found. Its confidence follows the same grading as every other edge —
 * {@code documented} when a line in the repository backs it (the tool found the line or
 * checked the one the operator pointed at), {@code inferred} when it rests on the
 * operator's word alone, which draws it dotted. A later runtime run that observes the
 * call upgrades it to solid like any other declared edge.
 *
 * <p>A removal ({@link #REMOVE_EDGE}) is the operator's judgement over the tool's: the
 * edge is dropped whatever its provenance, and dropped again after every later merge,
 * even one where the runtime layer observes the call — the thread says so before it is
 * confirmed, and "undo" in the thread takes the removal back.
 *
 * <p>Kept per repository like the alias answers ({@code dep-state/edits/<repo>.json}), and
 * re-applied in order after every merge of that repository, so the next report carries it.
 */
public final class GraphEdit {

    public static final String ADD_EDGE = "add-edge";
    public static final String ADD_NODE = "add-node";
    public static final String REMOVE_EDGE = "remove-edge";

    public String action = ADD_EDGE;
    public String source = "";
    public String target = "";
    /** For an edge: sync-http, grpc, async, db, external or startup. */
    public String type = "sync-http";
    /** For a node, and for an endpoint the operator introduced: service, db, queue, external, gateway. */
    public String kind = "";
    public String sourceKind = "";
    public String targetKind = "";
    public String confidence = DependencyGraph.CONF_INFERRED;
    /** file:line references (repository lines), or what the operator wrote as their reference. */
    public final List<String> refs = new ArrayList<>();
    public String by = "";
    public String at = "";

    /** Applies this change to the graph. An added edge that is already there gains the human provenance and references. */
    public void apply(DependencyGraph graph) {
        if (graph == null) return;
        if (ADD_NODE.equals(action)) {
            if (!target.isBlank()) graph.addNode(target, kind.isBlank() ? DependencyGraph.classifyKind(target) : kind);
            return;
        }
        if (REMOVE_EDGE.equals(action)) {
            graph.removeEdge(source, target);
            return;
        }
        if (source.isBlank() || target.isBlank() || source.equals(target)) return;
        if (graph.findNode(source) == null) graph.addNode(source, sourceKind.isBlank() ? DependencyGraph.classifyKind(source) : sourceKind);
        if (graph.findNode(target) == null) graph.addNode(target, targetKind.isBlank() ? DependencyGraph.classifyKind(target) : targetKind);
        String stamp = "human: added by " + (by.isBlank() ? "operator" : by) + (at.isBlank() ? "" : " " + at);
        graph.addEdge(source, target, type, DependencyGraph.PROV_HUMAN, confidence, false, 0, stamp);
        for (String ref : refs) graph.addEdge(source, target, type, DependencyGraph.PROV_HUMAN, confidence, false, 0, ref);
    }

    public String describe() {
        if (ADD_NODE.equals(action)) return "node `" + target + "` (" + (kind.isBlank() ? DependencyGraph.classifyKind(target) : kind) + ")";
        if (REMOVE_EDGE.equals(action)) return "removed `" + source + "` → `" + target + "`";
        return "`" + source + "` → `" + target + "` (" + type + ", " + confidence + ")";
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("action", action).put("source", source).put("target", target).put("type", type)
                .put("kind", kind).put("sourceKind", sourceKind).put("targetKind", targetKind)
                .put("confidence", confidence).put("refs", new JSONArray(refs)).put("by", by).put("at", at);
    }

    public static GraphEdit fromJson(JSONObject j) {
        GraphEdit e = new GraphEdit();
        e.action = j.optString("action", ADD_EDGE);
        e.source = j.optString("source", "");
        e.target = j.optString("target", "");
        e.type = j.optString("type", "sync-http");
        e.kind = j.optString("kind", "");
        e.sourceKind = j.optString("sourceKind", "");
        e.targetKind = j.optString("targetKind", "");
        e.confidence = j.optString("confidence", DependencyGraph.CONF_INFERRED);
        JSONArray refs = j.optJSONArray("refs");
        if (refs != null) for (int i = 0; i < refs.length(); i++) if (!refs.optString(i, "").isBlank()) e.refs.add(refs.optString(i));
        e.by = j.optString("by", "");
        e.at = j.optString("at", "");
        return e;
    }

    /** Tolerant of an absent or corrupt file: unreadable means "no edits". */
    public static List<GraphEdit> listFromJson(String json) {
        List<GraphEdit> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject j = array.optJSONObject(i);
                if (j != null) out.add(fromJson(j));
            }
        } catch (Exception ignored) {
            // nothing applied
        }
        return out;
    }

    public static String listToJson(List<GraphEdit> edits) {
        JSONArray array = new JSONArray();
        for (GraphEdit e : edits) array.put(e.toJson());
        return array.toString();
    }

    /** Applies every remembered edit, in the order they were made. */
    public static int applyAll(DependencyGraph graph, String json) {
        List<GraphEdit> edits = listFromJson(json);
        for (GraphEdit e : edits) e.apply(graph);
        return edits.size();
    }
}
