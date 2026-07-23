package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes runtime <b>traffic coverage</b> from a {@link DependencyGraph},
 * deterministically — no LLM. This is the number the pipeline never had: how much
 * of the service-to-service dependency surface was actually exercised by the driven
 * traffic, versus only known from code/docs.
 *
 * Istio can only observe an edge after a real request crosses it, so a
 * service→service edge that the code/docs declare but the mesh never saw (a dashed
 * edge in the graph) is precisely an uncovered edge — traffic did not reach it. The
 * coverage ratio is therefore just: of the business sync edges in the graph, how
 * many are {@code runtimeObserved}.
 *
 * Only <b>service/gateway → service/gateway</b> synchronous (sync-http / grpc) edges
 * are counted. Edges into a database, a broker (async) or an external host are
 * excluded on purpose: those are not HTTP mesh edges Istio observes the same way, so
 * counting them as "uncovered traffic" would be misleading (the same reasoning that
 * keeps a declared-but-unobserved DB from being called a runtime failure).
 */
public class CoverageAnalyzer {

    /** The coverage outcome: how many business sync edges were observed, and which were not. */
    public static final class Report {
        public final int total;
        public final int observed;
        /** The uncovered edges as "source -> target", in graph order. */
        public final List<String> uncovered;

        Report(int total, int observed, List<String> uncovered) {
            this.total = total;
            this.observed = observed;
            this.uncovered = uncovered;
        }

        /** Whether there is any business sync edge to measure at all. */
        public boolean hasEdges() {
            return total > 0;
        }

        /** Coverage as a whole-number percentage (0 when there is nothing to cover). */
        public int percent() {
            return total == 0 ? 0 : (int) Math.round(100.0 * observed / total);
        }
    }

    private CoverageAnalyzer() {
    }

    public static Report analyze(DependencyGraph graph) {
        List<String> uncovered = new ArrayList<>();
        int total = 0;
        int observed = 0;
        if (graph == null) return new Report(0, 0, uncovered);

        Map<String, String> kindById = new HashMap<>();
        for (DependencyGraph.Node node : graph.getNodes()) kindById.put(node.id, node.kind);

        for (DependencyGraph.Edge edge : graph.getEdges()) {
            if (!isBusinessSync(edge, kindById)) continue;
            total++;
            if (edge.runtimeObserved) observed++;
            else uncovered.add(edge.source + " -> " + edge.target);
        }
        return new Report(total, observed, uncovered);
    }

    /** A synchronous edge between two in-mesh service/gateway workloads — the kind Istio can observe. */
    private static boolean isBusinessSync(DependencyGraph.Edge edge, Map<String, String> kindById) {
        String type = edge.type == null ? "" : edge.type;
        if (!type.equals("sync-http") && !type.equals("grpc")) return false;
        return isServiceOrGateway(kindById.get(edge.source))
                && isServiceOrGateway(kindById.get(edge.target));
    }

    private static boolean isServiceOrGateway(String kind) {
        return DependencyGraph.KIND_SERVICE.equals(kind) || DependencyGraph.KIND_GATEWAY.equals(kind);
    }
}
