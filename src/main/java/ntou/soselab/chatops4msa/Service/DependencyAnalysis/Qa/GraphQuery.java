package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One operation of the graph-query DSL: an operator from a fixed catalogue and its
 * validated arguments.
 *
 * This is the A2 design from the 8/14 report — NL → structured query → deterministic
 * execution → the model phrases the result. The model's only job on this side is to
 * pick operators and name nodes; everything it names is validated here against the
 * graph before anything runs, so a hallucinated service cannot produce a query, let
 * alone an answer.
 */
public final class GraphQuery {

    /** The catalogue: operator → number of arguments (node ids, except {@code edges-of-type}). */
    public static final Map<String, Integer> OPS = new java.util.LinkedHashMap<>();

    static {
        OPS.put("dependencies-of", 1);   // direct outgoing edges of X
        OPS.put("dependents-of", 1);     // direct incoming edges of X
        OPS.put("impact-of", 1);         // transitive dependents of X ("what breaks if X fails")
        OPS.put("startup-needs", 1);     // transitive dependencies of X ("what must run before X")
        OPS.put("path", 2);              // shortest directed path X -> Y (and Y -> X)
        OPS.put("edges-of-type", 1);     // sync-http | db | async | external
        OPS.put("db-users", 0);          // every service -> database edge, with the evidence level
        OPS.put("observed-edges", 0);    // edges Istio saw at runtime
        OPS.put("unobserved-edges", 0);  // edges declared (code/doc) but never observed
        OPS.put("uncovered", 0);         // the coverage analyser's uncovered business edges
        OPS.put("mentioned-only", 0);    // edges with no usage evidence (dotted)
        OPS.put("undeployed", 0);        // nodes referenced but not running in the cluster
        OPS.put("deploy-order", 0);      // start-up order implied by the tiers
        OPS.put("externals", 0);         // external hosts and who calls them
        OPS.put("async", 0);             // broker relationships
    }

    static final List<String> EDGE_TYPES = List.of("sync-http", "db", "async", "external");

    public final String op;
    public final List<String> args;

    GraphQuery(String op, List<String> args) {
        this.op = op;
        this.args = List.copyOf(args);
    }

    @Override
    public String toString() {
        return op + "(" + String.join(", ", args) + ")";
    }

    /**
     * Parses the model's plan — a JSON array of {@code {"op": ..., "args": [...]}} —
     * keeping only queries whose operator is in the catalogue and whose arguments
     * resolve to nodes of {@code graph} (or to an edge type, for {@code edges-of-type}).
     * Markdown fences and prose around the array are tolerated; anything unparseable
     * yields an empty list, never an exception.
     */
    public static List<GraphQuery> parse(String response, DependencyGraph graph) {
        List<GraphQuery> out = new ArrayList<>();
        if (response == null || graph == null) return out;
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) return out;
        JSONArray array;
        try {
            array = new JSONArray(response.substring(start, end + 1));
        } catch (Exception e) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            GraphQuery query = validate(item.optString("op", ""), item.optJSONArray("args"), graph);
            if (query != null && !out.contains(query)) out.add(query);
        }
        return out;
    }

    private static GraphQuery validate(String rawOp, JSONArray rawArgs, DependencyGraph graph) {
        String op = rawOp == null ? "" : rawOp.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        Integer arity = OPS.get(op);
        if (arity == null) return null;
        List<String> args = new ArrayList<>();
        if (rawArgs != null) for (int i = 0; i < rawArgs.length(); i++) args.add(rawArgs.optString(i, "").trim());
        args.removeIf(String::isBlank);
        if (args.size() != arity) return null;

        List<String> resolved = new ArrayList<>();
        for (String arg : args) {
            String value = "edges-of-type".equals(op) ? edgeType(arg) : resolveNode(arg, graph);
            if (value == null) return null;
            resolved.add(value);
        }
        if ("path".equals(op) && resolved.get(0).equals(resolved.get(1))) return null;
        return new GraphQuery(op, resolved);
    }

    private static String edgeType(String arg) {
        String t = arg.toLowerCase(Locale.ROOT).replace('_', '-');
        if (t.equals("http") || t.equals("sync")) t = "sync-http";
        if (t.equals("database")) t = "db";
        if (t.equals("queue") || t.equals("broker")) t = "async";
        return EDGE_TYPES.contains(t) ? t : null;
    }

    /** The node id the argument names: exact, then case-insensitive, then a loose spelling. Null if none. */
    static String resolveNode(String arg, DependencyGraph graph) {
        for (DependencyGraph.Node n : graph.getNodes()) if (n.id.equals(arg)) return n.id;
        String lower = arg.toLowerCase(Locale.ROOT);
        for (DependencyGraph.Node n : graph.getNodes()) if (n.id.toLowerCase(Locale.ROOT).equals(lower)) return n.id;
        List<DependencyGraph.Node> named = GraphGrounding.mentionedNodes(arg, graph);
        return named.size() == 1 ? named.get(0).id : null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GraphQuery q && q.op.equals(op) && q.args.equals(args);
    }

    @Override
    public int hashCode() {
        return op.hashCode() * 31 + args.hashCode();
    }
}
