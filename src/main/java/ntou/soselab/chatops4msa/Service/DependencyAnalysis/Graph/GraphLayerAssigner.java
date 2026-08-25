package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigns every node a <b>layer</b>, so the emitters can draw the graph in tiers
 * (ingress on top, then services by call depth, then the data stores) instead of
 * letting the layout engine scatter it.
 *
 * Why: a free layout is unreadable past a couple of dozen edges — train-ticket's 52
 * edges come out as a hairball, and you cannot see the shape of the system. Tiering
 * turns the same data into "what calls what, and how deep does it go".
 *
 * Deterministic, no LLM. The rules, in order:
 * <ol>
 *   <li><b>Gateways</b> (the ingress) are layer 0 — traffic enters there by definition.</li>
 *   <li><b>Services</b> are laid out by <b>longest path</b> from the entry points, over
 *       the service-to-service edges only. Longest rather than shortest on purpose: a
 *       service that is reachable both directly and through a chain belongs BELOW the
 *       chain, otherwise the arrows point back upwards and the tiering reads as wrong.</li>
 *   <li><b>Data stores and brokers</b> go in the layer under the deepest service, and
 *       <b>external hosts</b> under that: they are where a dependency chain ends, never
 *       where it continues.</li>
 * </ol>
 *
 * <b>Cycles are expected</b>, not an error — microservices call each other back all the
 * time (train-ticket does). Strongly-connected components are collapsed to a single
 * vertex (Tarjan), the layering runs on the resulting DAG, and every member of a cycle
 * lands on the same layer, which is the honest depiction: within a cycle there is no
 * "before".
 *
 * <b>Entry points.</b> A gateway is the natural entry, but a greenfield/static graph
 * usually has none (no cluster, so no ingress node). Then any node with no incoming
 * service edge is an entry — for Bank of Anthos that is the frontend. If everything has
 * an incoming edge (one big cycle), the SCC DAG's own sources are used, so the pass
 * always terminates with every node assigned.
 */
public class GraphLayerAssigner {

    private GraphLayerAssigner() {
    }

    /**
     * Sets {@link DependencyGraph.Node#layer} on every node, in place.
     *
     * <p>Run this AFTER {@code GraphNormalizer} — a phantom node or an un-collapsed
     * alias would otherwise occupy a layer of its own and distort the depth of
     * everything below it.
     */
    public static void assign(DependencyGraph graph) {
        if (graph == null || graph.isEmpty()) return;

        Map<String, DependencyGraph.Node> nodes = new LinkedHashMap<>();
        for (DependencyGraph.Node node : graph.getNodes()) nodes.put(node.id, node);

        // The layering runs over the service/gateway subgraph only: a db or an external
        // host is a terminal, and letting it carry depth would push whatever else talks
        // to it downwards for no reason.
        Set<String> tiered = new LinkedHashSet<>();
        for (DependencyGraph.Node node : nodes.values()) {
            if (isTierable(node)) tiered.add(node.id);
        }
        if (tiered.isEmpty()) {
            // Nothing but data stores/externals: one flat layer is the honest answer.
            for (DependencyGraph.Node node : nodes.values()) node.layer = 0;
            return;
        }

        Map<String, Set<String>> out = new HashMap<>();
        for (String id : tiered) out.put(id, new LinkedHashSet<>());
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            if (tiered.contains(edge.source) && tiered.contains(edge.target)
                    && !edge.source.equals(edge.target)) {
                out.get(edge.source).add(edge.target);
            }
        }

        // Collapse cycles, then layer the DAG they form.
        Map<String, Integer> component = stronglyConnectedComponents(tiered, out);
        int componentCount = component.values().stream().mapToInt(c -> c).max().orElse(0) + 1;

        List<Set<Integer>> componentOut = new ArrayList<>();
        int[] indegree = new int[componentCount];
        for (int i = 0; i < componentCount; i++) componentOut.add(new LinkedHashSet<>());
        for (String source : tiered) {
            for (String target : out.get(source)) {
                int from = component.get(source);
                int to = component.get(target);
                if (from != to && componentOut.get(from).add(to)) indegree[to]++;
            }
        }

        int[] depth = new int[componentCount];
        Deque<Integer> queue = new ArrayDeque<>();
        for (int c : entryComponents(tiered, component, indegree, nodes, componentCount)) {
            queue.add(c);
        }

        // Longest-path layering over the DAG: a component settles one level below the
        // deepest thing that calls it. Kahn's ordering guarantees each is finalised only
        // after every predecessor, so no second pass is needed.
        int[] remaining = indegree.clone();
        Set<Integer> seen = new HashSet<>(queue);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            for (int next : componentOut.get(current)) {
                depth[next] = Math.max(depth[next], depth[current] + 1);
                if (--remaining[next] <= 0 && seen.add(next)) queue.add(next);
            }
        }

        // A node with no edge at all is not an entry point, it is unconnected — usually
        // something the extraction picked up that is not really a service (a build
        // directory, a tool). Leaving them in layer 0 buries the real entry among
        // dozens of them: train-ticket's top tier was 31 nodes wide, of which only a
        // handful actually receive traffic. They get their own tier at the bottom.
        Set<String> connected = new HashSet<>();
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            connected.add(edge.source);
            connected.add(edge.target);
        }

        int deepestService = 0;
        for (String id : tiered) {
            if (!connected.contains(id)) continue;
            int layer = depth[component.get(id)];
            nodes.get(id).layer = layer;
            deepestService = Math.max(deepestService, layer);
        }

        // Terminals sit below every service: data stores first, then the world outside.
        int dataLayer = deepestService + 1;
        int externalLayer = dataLayer + 1;
        for (DependencyGraph.Node node : nodes.values()) {
            if (isTierable(node) || !connected.contains(node.id)) continue;
            node.layer = DependencyGraph.KIND_EXTERNAL.equals(node.kind) ? externalLayer : dataLayer;
        }

        compact(nodes.values());

        int unconnectedLayer = 0;
        for (DependencyGraph.Node node : nodes.values()) {
            if (node.layer != null) unconnectedLayer = Math.max(unconnectedLayer, node.layer + 1);
        }
        for (DependencyGraph.Node node : nodes.values()) {
            if (node.layer == null) node.layer = unconnectedLayer;
        }
    }

    /**
     * Renumbers the assigned layers to be consecutive. Without this the tiers can skip
     * (a graph with no data stores but an external host jumped from 5 to 7), which
     * shows up as an empty band in the rendered picture.
     */
    private static void compact(Iterable<DependencyGraph.Node> nodes) {
        java.util.TreeSet<Integer> used = new java.util.TreeSet<>();
        for (DependencyGraph.Node node : nodes) {
            if (node.layer != null) used.add(node.layer);
        }
        Map<Integer, Integer> renumbered = new HashMap<>();
        int next = 0;
        for (int layer : used) renumbered.put(layer, next++);
        for (DependencyGraph.Node node : nodes) {
            if (node.layer != null) node.layer = renumbered.get(node.layer);
        }
    }

    /**
     * Where the layering starts. A gateway is the entry by definition; without one
     * (a greenfield graph has no ingress node) anything nothing calls is an entry.
     * If even that is empty the graph is one big cycle, so the SCC DAG's sources are
     * used — there is always at least one, which is what makes this terminate.
     */
    private static List<Integer> entryComponents(Set<String> tiered, Map<String, Integer> component,
                                                 int[] indegree, Map<String, DependencyGraph.Node> nodes,
                                                 int componentCount) {
        List<Integer> gateways = new ArrayList<>();
        for (String id : tiered) {
            if (DependencyGraph.KIND_GATEWAY.equals(nodes.get(id).kind)
                    && !gateways.contains(component.get(id))) {
                gateways.add(component.get(id));
            }
        }

        List<Integer> sources = new ArrayList<>();
        for (int c = 0; c < componentCount; c++) {
            if (indegree[c] == 0) sources.add(c);
        }

        if (!gateways.isEmpty()) {
            // Keep any other source too: a service nothing calls is still an entry, and
            // dropping it would leave it (and its subtree) unlayered at depth 0 anyway.
            for (int source : sources) {
                if (!gateways.contains(source)) gateways.add(source);
            }
            return gateways;
        }
        return sources.isEmpty() ? allComponents(componentCount) : sources;
    }

    private static List<Integer> allComponents(int count) {
        List<Integer> all = new ArrayList<>();
        for (int c = 0; c < count; c++) all.add(c);
        return all;
    }

    /** A node that participates in the call-depth layering (not a terminal). */
    private static boolean isTierable(DependencyGraph.Node node) {
        String kind = node.kind == null ? DependencyGraph.KIND_SERVICE : node.kind;
        return DependencyGraph.KIND_SERVICE.equals(kind) || DependencyGraph.KIND_GATEWAY.equals(kind);
    }

    /**
     * Tarjan's strongly-connected components, iteratively — the graph is small, but a
     * recursive version would still be one deep call chain away from a stack overflow
     * on a pathological input, and this pass must never be the thing that fails.
     *
     * @return node id -> component index
     */
    private static Map<String, Integer> stronglyConnectedComponents(
            Set<String> nodes, Map<String, Set<String>> out) {

        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowlink = new HashMap<>();
        Map<String, Integer> component = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        int[] counter = {0};
        int[] components = {0};

        for (String root : nodes) {
            if (index.containsKey(root)) continue;

            // Explicit DFS stack of (node, iterator over its successors).
            Deque<String> work = new ArrayDeque<>();
            Map<String, java.util.Iterator<String>> pending = new HashMap<>();

            work.push(root);
            index.put(root, counter[0]);
            lowlink.put(root, counter[0]);
            counter[0]++;
            stack.push(root);
            onStack.add(root);
            pending.put(root, out.getOrDefault(root, Set.of()).iterator());

            while (!work.isEmpty()) {
                String current = work.peek();
                java.util.Iterator<String> successors = pending.get(current);

                if (successors.hasNext()) {
                    String next = successors.next();
                    if (!index.containsKey(next)) {
                        index.put(next, counter[0]);
                        lowlink.put(next, counter[0]);
                        counter[0]++;
                        stack.push(next);
                        onStack.add(next);
                        pending.put(next, out.getOrDefault(next, Set.of()).iterator());
                        work.push(next);
                    } else if (onStack.contains(next)) {
                        lowlink.put(current, Math.min(lowlink.get(current), index.get(next)));
                    }
                    continue;
                }

                work.pop();
                if (!work.isEmpty()) {
                    String parent = work.peek();
                    lowlink.put(parent, Math.min(lowlink.get(parent), lowlink.get(current)));
                }
                // A root of an SCC: pop everything above it into one component.
                if (lowlink.get(current).equals(index.get(current))) {
                    String member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        component.put(member, components[0]);
                    } while (!member.equals(current));
                    components[0]++;
                }
            }
        }
        return component;
    }
}
