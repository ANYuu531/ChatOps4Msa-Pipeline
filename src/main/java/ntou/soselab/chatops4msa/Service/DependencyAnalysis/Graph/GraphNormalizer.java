package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A final, deterministic clean-up pass over a fully-merged {@link DependencyGraph} —
 * no LLM. It removes the pseudo-service nodes that the code/doc extraction sometimes
 * introduces but which are not real mesh workloads, so the rendered graph (and the
 * coverage denominator) reflect actual services rather than build-time noise.
 *
 * Three shapes are cleaned, and only ever against nodes the cluster does NOT run
 * ({@code deployed != TRUE}) so a real workload is never touched:
 * <ul>
 *   <li><b>Framework / library labels</b> ({@code resilience4j}, {@code jolokia},
 *       {@code spring-cloud-gateway}, {@code netflix-eureka}, …) — a service is built
 *       <em>with</em> these, it does not have a runtime dependency <em>edge</em> to them.
 *       Removed with their edges.</li>
 *   <li><b>Grouping pseudo-nodes</b> ({@code all-services}, {@code all-microservices}) —
 *       a documentation shorthand for "every service", never a node. Removed.</li>
 *   <li><b>Aliases of a real workload</b> ({@code api-gateway-controller},
 *       {@code customers-service-client}) — the same service under a controller/client
 *       display name. Merged back onto the real workload so its edge evidence is kept on
 *       the real node instead of splitting into a duplicate.</li>
 * </ul>
 *
 * Note the deliberate difference from {@link CoverageAnalyzer}: the coverage metric also
 * excludes real control-plane services (discovery-server, config-server) from the
 * <em>percentage</em>, but those are genuine deployed workloads and must still appear in
 * the graph, so this normaliser leaves them alone.
 */
public final class GraphNormalizer {

    /** Framework / library / tech-label names that are never a deployable workload. */
    private static final Set<String> LIBRARY_PHANTOMS = Set.of(
            "spring-cloud-gateway", "spring-cloud-config", "spring-cloud-loadbalancer",
            "netflix-eureka", "eureka-client", "resilience4j", "resilience4j-circuitbreaker",
            "hystrix", "ribbon", "feign", "openfeign", "jolokia", "spring-boot-actuator",
            "actuator", "micrometer", "lombok", "mapstruct", "spring-boot", "spring-framework");

    /** Suffixes a display name adds over the real workload id (stripped to find the alias target). */
    private static final String[] ALIAS_SUFFIXES = {
            "-controller", "-application", "-app", "-client", "-impl", "-service-client"};

    private GraphNormalizer() {
    }

    /** Cleans phantom/library/grouping nodes and collapses aliases, in place. */
    public static void normalize(DependencyGraph graph) {
        if (graph == null) return;

        // Snapshot ids + deployment status up front: the pass mutates the graph.
        Map<String, Boolean> deployed = new LinkedHashMap<>();
        for (DependencyGraph.Node n : graph.getNodes()) deployed.put(n.id, n.deployed);

        for (Map.Entry<String, Boolean> e : new ArrayList<>(deployed.entrySet())) {
            String id = e.getKey();
            if (Boolean.TRUE.equals(e.getValue())) continue; // never touch a real workload

            if (isGrouping(id) || LIBRARY_PHANTOMS.contains(id.toLowerCase(Locale.ROOT))) {
                graph.removeNode(id);
                continue;
            }
            String target = aliasTarget(id, deployed);
            if (target != null) graph.renameNode(id, target);
        }
    }

    /** A documentation shorthand for "all services", not a node. */
    private static boolean isGrouping(String id) {
        String n = id.toLowerCase(Locale.ROOT);
        return n.equals("all-services") || n.equals("all-microservices")
                || n.equals("all-microservice") || n.equals("microservices")
                || n.equals("everything") || n.startsWith("all-service");
    }

    /**
     * If {@code id} is a known workload under an alias suffix — e.g.
     * {@code api-gateway-controller} over the deployed {@code api-gateway} — returns that
     * real workload id; otherwise null. Only a <em>deployed</em> target qualifies, so an
     * undeployed real service (a legitimate dashed node) is never merged away.
     */
    private static String aliasTarget(String id, Map<String, Boolean> deployed) {
        String n = id.toLowerCase(Locale.ROOT);
        for (String suffix : ALIAS_SUFFIXES) {
            if (!n.endsWith(suffix) || n.length() <= suffix.length()) continue;
            String base = n.substring(0, n.length() - suffix.length());
            if (Boolean.TRUE.equals(deployed.get(base))) return base;
        }
        return null;
    }
}
