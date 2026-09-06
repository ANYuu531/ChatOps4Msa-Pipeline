package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CoverageAnalyzer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphLayerAssigner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Executes {@link GraphQuery} operations on a {@link DependencyGraph} and writes the
 * result as Markdown for the model to phrase.
 *
 * Every operator is a lookup or a traversal; none consults the model. Where a
 * deterministic answer already exists elsewhere in the tool it is reused rather than
 * re-derived: {@code uncovered} is the {@link CoverageAnalyzer}'s own list, so the
 * thread can never disagree with the coverage message posted beside the report, and
 * {@code deploy-order} comes from the same tiers {@code GraphLayerAssigner} drew.
 */
public final class GraphQueryEngine {

    private GraphQueryEngine() {
    }

    /** All queries, each under its own heading; empty string for no queries. */
    public static String execute(DependencyGraph graph, List<GraphQuery> queries) {
        if (graph == null || queries == null || queries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (GraphQuery q : queries) {
            sb.append("### Query: ").append(q).append('\n');
            sb.append(execute(graph, q)).append('\n');
        }
        return sb.toString();
    }

    static String execute(DependencyGraph graph, GraphQuery q) {
        switch (q.op) {
            case "dependencies-of": return edges(graph, e -> e.source.equals(q.args.get(0)), q.args.get(0) + " depends on nothing (no outgoing edge)");
            case "dependents-of": return edges(graph, e -> e.target.equals(q.args.get(0)), "nothing depends on " + q.args.get(0) + " (no incoming edge)");
            case "impact-of": return closure(graph, q.args.get(0), false);
            case "startup-needs": return closure(graph, q.args.get(0), true);
            case "path": return path(graph, q.args.get(0), q.args.get(1));
            case "edges-of-type": return edges(graph, e -> q.args.get(0).equals(e.type == null ? "sync-http" : e.type), "no edge of type " + q.args.get(0));
            case "db-users": return edges(graph, e -> isKind(graph, e.target, DependencyGraph.KIND_DB), "no service -> database edge in the graph");
            case "observed-edges": return edges(graph, e -> e.runtimeObserved, "no edge was observed at runtime (a greenfield run has none by design)");
            case "unobserved-edges": return edges(graph, e -> !e.runtimeObserved, "every edge was observed at runtime");
            case "uncovered": return uncovered(graph);
            case "mentioned-only": return edges(graph, e -> DependencyGraph.CONF_INFERRED.equals(e.confidence), "no edge is mentioned-only; every edge has usage evidence");
            case "undeployed": return undeployed(graph);
            case "deploy-order": return deployOrder(graph);
            case "externals": return edges(graph, e -> isKind(graph, e.target, DependencyGraph.KIND_EXTERNAL), "no external host in the graph");
            case "async": return edges(graph, e -> isKind(graph, e.target, DependencyGraph.KIND_QUEUE) || "async".equals(e.type), "no broker relationship in the graph");
            default: return "(unknown operator)\n";
        }
    }

    private interface EdgeFilter {
        boolean test(DependencyGraph.Edge e);
    }

    private static String edges(DependencyGraph graph, EdgeFilter filter, String noneText) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (DependencyGraph.Edge e : graph.getEdges()) {
            if (!filter.test(e)) continue;
            sb.append("- ").append(GraphGrounding.edgeLine(graph, e)).append('\n');
            n++;
        }
        if (n == 0) return "- " + noneText + "\n";
        return n + " edge(s):\n" + sb;
    }

    private static String closure(DependencyGraph graph, String node, boolean forward) {
        Map<String, Integer> depth = GraphGrounding.closure(graph, node, forward);
        if (depth.isEmpty()) {
            return forward
                    ? "- " + node + " needs nothing else to start: it has no outgoing edge.\n"
                    : "- nothing transitively depends on " + node + ": it has no incoming edge.\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(depth.size()).append(" node(s), by distance from ").append(node)
                .append(forward ? " (what it needs, nearest first):\n" : " (what it impacts, nearest first):\n");
        TreeMap<Integer, List<String>> byDepth = new TreeMap<>();
        for (Map.Entry<String, Integer> e : depth.entrySet()) byDepth.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        for (Map.Entry<Integer, List<String>> e : byDepth.entrySet()) {
            sb.append("- depth ").append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append('\n');
        }
        return sb.toString();
    }

    private static String path(DependencyGraph graph, String a, String b) {
        List<String> ab = GraphGrounding.shortestPath(graph, a, b);
        List<String> ba = GraphGrounding.shortestPath(graph, b, a);
        if (ab == null && ba == null) return "- no directed path between " + a + " and " + b + " in either direction\n";
        StringBuilder sb = new StringBuilder();
        if (ab != null) sb.append("- ").append(String.join(" -> ", ab)).append(" (").append(ab.size() - 1).append(" hop(s))\n");
        else sb.append("- no directed path ").append(a).append(" -> ").append(b).append('\n');
        if (ba != null) sb.append("- ").append(String.join(" -> ", ba)).append(" (").append(ba.size() - 1).append(" hop(s))\n");
        else sb.append("- no directed path ").append(b).append(" -> ").append(a).append('\n');
        return sb.toString();
    }

    private static String uncovered(DependencyGraph graph) {
        CoverageAnalyzer.Report r = CoverageAnalyzer.analyze(graph);
        if (!r.hasEdges()) return "- coverage is not measurable: no scoreable service -> service edge (greenfield, or nothing extracted)\n";
        StringBuilder sb = new StringBuilder();
        sb.append("Business edges observed ").append(r.observed).append(" / ").append(r.total).append(" (").append(r.percent()).append("%).\n");
        if (r.uncovered.isEmpty()) sb.append("- every scoreable business edge was exercised\n");
        else for (String e : r.uncovered) sb.append("- uncovered: ").append(e).append('\n');
        if (r.hasDbEdges()) {
            sb.append("Data layer observed ").append(r.dbObserved).append(" / ").append(r.dbTotal).append(" (").append(r.dbPercent()).append("%, TCP connections).\n");
            for (String e : r.dbUncovered) sb.append("- no connection seen: ").append(e).append('\n');
        }
        if (r.mentionedOnly > 0) sb.append("- not scored: ").append(r.mentionedOnly).append(" mentioned-only edge(s)\n");
        return sb.toString();
    }

    private static String undeployed(DependencyGraph graph) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (DependencyGraph.Node node : graph.getNodes()) {
            if (!Boolean.FALSE.equals(node.deployed)) continue;
            sb.append("- ").append(node.id).append(" (").append(node.kind).append("): referenced in code/docs, not running in the cluster\n");
            n++;
        }
        if (n > 0) return n + " node(s):\n" + sb;
        boolean anyKnown = false;
        for (DependencyGraph.Node node : graph.getNodes()) if (node.deployed != null) anyKnown = true;
        return anyKnown
                ? "- every referenced workload is deployed\n"
                : "- deployment state was not determined for any node (greenfield run: no cluster was queried)\n";
    }

    private static String deployOrder(DependencyGraph graph) {
        boolean layered = false;
        for (DependencyGraph.Node n : graph.getNodes()) if (n.layer != null) layered = true;
        if (!layered) GraphLayerAssigner.assign(graph);

        Map<String, Boolean> touched = new HashMap<>();
        for (DependencyGraph.Edge e : graph.getEdges()) {
            touched.put(e.source, true);
            touched.put(e.target, true);
        }
        TreeMap<Integer, List<String>> tiers = new TreeMap<>();
        for (DependencyGraph.Node n : graph.getNodes()) {
            if (n.layer == null || !touched.containsKey(n.id)) continue;
            tiers.computeIfAbsent(n.layer, k -> new ArrayList<>()).add(n.id);
        }
        if (tiers.isEmpty()) return "- no order can be derived: the graph has no edges\n";
        StringBuilder sb = new StringBuilder();
        sb.append("Start deepest tier first, so every service finds what it calls already running:\n");
        int step = 1;
        for (Map.Entry<Integer, List<String>> t : tiers.descendingMap().entrySet()) {
            sb.append("- step ").append(step++).append(" (tier ").append(t.getKey()).append("): ")
                    .append(String.join(", ", t.getValue())).append('\n');
        }
        sb.append("- derived from call depth in the graph; it is an ordering of hard dependencies, not a measured start-up time\n");
        return sb.toString();
    }

    private static boolean isKind(DependencyGraph graph, String id, String kind) {
        for (DependencyGraph.Node n : graph.getNodes()) if (n.id.equals(id)) return kind.equals(n.kind);
        return false;
    }
}
