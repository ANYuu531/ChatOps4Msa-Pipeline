package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 *
 * <p>Beyond that, an edge only counts when it is a <em>drivable business</em> edge —
 * one where both endpoints could actually carry observable traffic:
 * <ul>
 *   <li><b>Undeployed / phantom endpoints are excluded.</b> A node the cluster does
 *       not run ({@code deployed == FALSE}) cannot receive traffic, so an edge touching
 *       it is impossible to cover — counting it as "uncovered" would penalise coverage
 *       for traffic that can never be sent. This drops the doc/code phantom nodes
 *       (a framework or library name like {@code resilience4j}/{@code jolokia}, a doc
 *       alias like {@code api-gateway-controller}, a grouping like {@code all-services})
 *       and genuinely-undeployed services (e.g. {@code genai-service}) alike, since
 *       {@link K8sGraphBuilder} marks every service-kind node with no Deployment as
 *       {@code deployed = FALSE}.</li>
 *   <li><b>Platform / control-plane endpoints are excluded.</b> Service discovery
 *       (eureka/discovery-server), config (config-server) and tracing are infrastructure,
 *       not a business service→service dependency; and the eureka/config control plane is
 *       deliberately kept out of the mesh (see {@code kube/petclinic}), so those edges can
 *       never be runtime-observed. Excluding them keeps the ratio honest instead of
 *       permanently capping it below 100%. Name-based, so it still holds on a checkpoint
 *       built without k8s deployment status.</li>
 * </ul>
 * The net effect: the denominator is the set of service→service sync edges that traffic
 * <em>could</em> exercise, so the percentage answers "of the reachable business surface,
 * how much did we drive?" rather than being diluted by infra and phantom edges.
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

        Map<String, DependencyGraph.Node> byId = new HashMap<>();
        for (DependencyGraph.Node node : graph.getNodes()) byId.put(node.id, node);

        for (DependencyGraph.Edge edge : graph.getEdges()) {
            if (!isBusinessSync(edge, byId)) continue;
            total++;
            if (edge.runtimeObserved) observed++;
            else uncovered.add(edge.source + " -> " + edge.target);
        }
        return new Report(total, observed, uncovered);
    }

    /** A synchronous edge between two drivable business service/gateway workloads. */
    private static boolean isBusinessSync(DependencyGraph.Edge edge, Map<String, DependencyGraph.Node> byId) {
        String type = edge.type == null ? "" : edge.type;
        if (!type.equals("sync-http") && !type.equals("grpc")) return false;
        return isCountableWorkload(edge.source, byId.get(edge.source))
                && isCountableWorkload(edge.target, byId.get(edge.target));
    }

    /**
     * A service/gateway endpoint whose traffic Istio could actually observe: it must be
     * a service/gateway kind, not a workload the cluster does not run (phantom or
     * undeployed), and not platform/control-plane infrastructure. See the class doc.
     */
    private static boolean isCountableWorkload(String id, DependencyGraph.Node node) {
        if (node == null || !isServiceOrGateway(node.kind)) return false;
        if (Boolean.FALSE.equals(node.deployed)) return false;
        return !isPlatformInfra(id);
    }

    private static boolean isServiceOrGateway(String kind) {
        return DependencyGraph.KIND_SERVICE.equals(kind) || DependencyGraph.KIND_GATEWAY.equals(kind);
    }

    /**
     * Platform components that are infrastructure, not a business service→service
     * dependency: service discovery, config, tracing/observability, and the framework
     * library names the doc/code extraction sometimes surfaces as pseudo-services.
     * Kept name-based so it still filters on checkpoints without k8s deployment status.
     */
    private static final String[] PLATFORM_INFRA = {
            "discovery-server", "config-server", "spring-cloud-config", "spring-cloud-gateway",
            "netflix-eureka", "eureka", "tracing-server", "zipkin", "jaeger", "admin-server",
            "prometheus", "grafana", "jolokia", "resilience4j", "hystrix"};

    private static boolean isPlatformInfra(String id) {
        if (id == null || id.isBlank()) return false;
        String n = id.toLowerCase(Locale.ROOT);
        for (String hint : PLATFORM_INFRA) {
            if (n.equals(hint) || n.contains(hint)) return true;
        }
        return false;
    }
}
