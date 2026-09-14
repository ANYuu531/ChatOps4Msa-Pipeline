package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cuts the part of a dependency graph a question is about — "the nodes involved in
 * checkout" — out of the whole graph, so that part can be drawn on its own.
 *
 * Which nodes a business flow involves is not in the graph: nodes carry no flow label.
 * The seeds therefore come from outside (the query planner names them from the node ids
 * and the report's passages, and validation keeps only real ids). Everything after the
 * seeds is decided here, by code:
 * <ol>
 *   <li><b>Seeds</b> — kept as given; an id that is not in the graph is ignored.</li>
 *   <li><b>Connectors</b> — the nodes on the shortest directed path between every
 *       ordered pair of seeds, when that path is at most {@link #MAX_PATH_HOPS} hops.
 *       Seeds {@code orders} and {@code shipping} bring in the service between them;
 *       two seeds at opposite ends of a large graph do not drag the graph in.</li>
 *   <li><b>Neighbours</b> — one hop around each seed, in priority order: the data
 *       stores, queues and external hosts a seed uses (a flow's state lives there), then
 *       the callers of a seed (how the flow is entered), then the other services a seed
 *       calls. Added while the slice stays within {@link #MAX_NODES}; what does not fit
 *       is counted and reported, never silently dropped.</li>
 * </ol>
 * The edges are induced: every edge of the original graph whose two ends are both kept,
 * with its type, provenance, confidence, observed flag, count and evidence unchanged. A
 * slice therefore never shows an edge the full graph does not have, and never draws one
 * with a stronger line than the full graph does. The tiers are re-assigned on the slice,
 * so it lays out compactly instead of inheriting the gaps of the full layering.
 */
public final class SubgraphExtractor {

    /** A slice that still reads as a picture in a chat message; train-ticket's 53 nodes do not. */
    public static final int MAX_NODES = 20;
    /** Seeds further apart than this are not "one flow" in any sense a picture helps with. */
    public static final int MAX_PATH_HOPS = 4;

    public static final class Result {
        public final DependencyGraph graph;
        /** The seeds that exist in the graph, in the order given. */
        public final List<String> seeds;
        /** Nodes added because they lie on a path between two seeds. */
        public final List<String> connectors;
        /** Nodes added as one-hop context around a seed. */
        public final List<String> neighbours;
        /** One-hop or connector nodes left out to respect {@link #MAX_NODES}. */
        public final List<String> omitted;

        Result(DependencyGraph graph, List<String> seeds, List<String> connectors,
               List<String> neighbours, List<String> omitted) {
            this.graph = graph;
            this.seeds = seeds;
            this.connectors = connectors;
            this.neighbours = neighbours;
            this.omitted = omitted;
        }

        public boolean isEmpty() {
            return seeds.isEmpty();
        }
    }

    private SubgraphExtractor() {
    }

    public static Result extract(DependencyGraph graph, Collection<String> seedIds) {
        Map<String, DependencyGraph.Node> byId = new HashMap<>();
        for (DependencyGraph.Node n : graph.getNodes()) byId.put(n.id, n);

        List<String> seeds = new ArrayList<>();
        if (seedIds != null) {
            for (String id : seedIds) if (byId.containsKey(id) && !seeds.contains(id)) seeds.add(id);
        }
        Set<String> keep = new LinkedHashSet<>(seeds);
        Set<String> omitted = new LinkedHashSet<>();

        // Connectors: a path is taken whole or not at all — half a path is a picture
        // of two unconnected islands that looks like a finding.
        List<String> connectors = new ArrayList<>();
        for (String a : seeds) {
            for (String b : seeds) {
                if (a.equals(b)) continue;
                List<String> path = shortestPath(graph, a, b, MAX_PATH_HOPS);
                if (path == null) continue;
                List<String> fresh = new ArrayList<>();
                for (String id : path) if (!keep.contains(id)) fresh.add(id);
                if (keep.size() + fresh.size() > MAX_NODES) {
                    omitted.addAll(fresh);
                    continue;
                }
                keep.addAll(fresh);
                connectors.addAll(fresh);
            }
        }

        // Neighbours, highest value first, so the cap cuts the least informative ones.
        List<String> neighbours = new ArrayList<>();
        for (int pass = 0; pass < 3; pass++) {
            for (String seed : seeds) {
                for (DependencyGraph.Edge e : graph.getEdges()) {
                    String other;
                    if (pass == 0) other = e.source.equals(seed) && isTerminal(byId.get(e.target)) ? e.target : null;
                    else if (pass == 1) other = e.target.equals(seed) ? e.source : null;
                    else other = e.source.equals(seed) && !isTerminal(byId.get(e.target)) ? e.target : null;
                    if (other == null || keep.contains(other)) continue;
                    if (keep.size() >= MAX_NODES) {
                        omitted.add(other);
                        continue;
                    }
                    keep.add(other);
                    neighbours.add(other);
                }
            }
        }
        omitted.removeAll(keep);

        DependencyGraph slice = new DependencyGraph(graph.getNamespace());
        for (DependencyGraph.Node n : graph.getNodes()) {
            if (!keep.contains(n.id)) continue;
            DependencyGraph.Node copy = slice.addNode(n.id, n.kind);
            copy.kind = n.kind;
            copy.deployed = n.deployed;
            copy.deployedAt = n.deployedAt;
            copy.image = n.image;
            copy.replicas = n.replicas;
        }
        for (DependencyGraph.Edge e : graph.getEdges()) {
            if (!keep.contains(e.source) || !keep.contains(e.target)) continue;
            DependencyGraph.Edge copy = slice.addEdge(e.source, e.target, e.type, null,
                    e.confidence, e.runtimeObserved, e.count, null);
            copy.provenance.addAll(e.provenance);
            copy.evidence.addAll(e.evidence);
        }
        if (!slice.getNodes().isEmpty()) GraphLayerAssigner.assign(slice);
        return new Result(slice, seeds, connectors, neighbours, new ArrayList<>(omitted));
    }

    /** A data store, queue or external host: where a flow ends rather than continues. */
    private static boolean isTerminal(DependencyGraph.Node node) {
        if (node == null || node.kind == null) return false;
        return DependencyGraph.KIND_DB.equals(node.kind)
                || DependencyGraph.KIND_QUEUE.equals(node.kind)
                || DependencyGraph.KIND_EXTERNAL.equals(node.kind);
    }

    /** Shortest directed path from → to within {@code maxHops}, both ends included; null if none. */
    static List<String> shortestPath(DependencyGraph graph, String from, String to, int maxHops) {
        Map<String, String> parent = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        depth.put(from, 0);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(to)) break;
            if (depth.get(current) >= maxHops) continue;
            for (DependencyGraph.Edge e : graph.getEdges()) {
                if (!e.source.equals(current) || depth.containsKey(e.target)) continue;
                depth.put(e.target, depth.get(current) + 1);
                parent.put(e.target, current);
                queue.add(e.target);
            }
        }
        if (!depth.containsKey(to)) return null;
        List<String> path = new ArrayList<>();
        for (String at = to; at != null; at = parent.get(at)) path.add(0, at);
        return path;
    }
}
