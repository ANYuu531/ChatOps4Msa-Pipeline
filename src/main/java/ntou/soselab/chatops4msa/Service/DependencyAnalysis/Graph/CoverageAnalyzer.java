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
 * are counted in that ratio. Edges into a broker (async) or an external host are
 * excluded on purpose: those are not HTTP mesh edges Istio observes the same way, so
 * counting them as "uncovered traffic" would be misleading (the same reasoning that
 * keeps a declared-but-unobserved DB from being called a runtime failure).
 *
 * <p><b>The data layer is measured separately</b> ({@code dbTotal}/{@code dbObserved}),
 * not folded into that percentage — the number would stop meaning "how much of the
 * business surface did the traffic drive?", and would not be comparable with any
 * earlier run. It became worth measuring at all once databases are deployed inside
 * the mesh: a db connection is opaque TCP, so it is observable via
 * {@code istio_tcp_*} (see {@link RuntimeGraphBuilder#mergeIstioTcp}) even though it
 * never appears in {@code istio_requests_total}.
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
 *   <li><b>Merely-mentioned edges are excluded.</b> An {@code inferred} edge — the
 *       graph's dotted tier: declared somewhere with no usage evidence at all — does not
 *       enter the denominator. The documentation layer is LLM-read prose and varies
 *       between runs; one Bank of Anthos run invented three "userservice → ledger
 *       service" edges and four edges to a generic {@code postgresql} node duplicating
 *       the real ones, and coverage fell from 7/7 to 7/10 with nothing about the system
 *       or the traffic having changed. A score a paragraph of prose can dilute is not
 *       measuring what it claims to. Such edges are still DRAWN (dotted, honestly
 *       marked as unconfirmed) — they are just not scored.</li>
 * </ul>
 * The net effect: the denominator is the set of service→service sync edges that traffic
 * <em>could</em> exercise and that something actually evidences, so the percentage
 * answers "of the reachable business surface, how much did we drive?" rather than being
 * diluted by infra, phantom and merely-mentioned edges.
 */
public class CoverageAnalyzer {

    /** The coverage outcome: how many business sync edges were observed, and which were not. */
    public static final class Report {
        public final int total;
        public final int observed;
        /** The uncovered edges as "source -> target", in graph order. */
        public final List<String> uncovered;
        /**
         * The data layer, reported SEPARATELY from the business ratio above.
         *
         * A db edge is not an HTTP mesh edge and is not driven by a journey, so folding
         * it into the same percentage would change what that number means (and would
         * have made every previous run's coverage incomparable). It is still worth
         * measuring on its own: with a database actually deployed inside the mesh, its
         * edges CAN be runtime-observed (via TCP), and "which declared datastores has
         * the mesh really seen a connection to" is exactly the question the code-only
         * db edge could never answer.
         */
        public final int dbTotal;
        public final int dbObserved;
        /** The declared-but-never-connected datastore edges, as "source -> target". */
        public final List<String> dbUncovered;
        /**
         * How many edges were left OUT of both denominators for having no usage
         * evidence (the dotted tier).
         *
         * Reported, never hidden. Excluding them is right when they are documentation
         * noise, but the count is what tells a reader whether the score rests on a real
         * surface. The failure mode to watch is a THIN extraction: if the code tier
         * found almost nothing — a language whose sources the LLM reader skimmed
         * poorly, a repo whose call sites are all dynamic — while the documentation
         * tier talked at length, the denominator shrinks to a handful of edges and a
         * high percentage covers almost none of the system. (Note the code tier itself
         * is not the risk: an LLM-read edge lands in the same EdgeLedger as a
         * tree-sitter one and is scored identically — see CodeGraphMerger, which marks
         * code edges documented.)
         */
        public final int mentionedOnly;

        Report(int total, int observed, List<String> uncovered,
               int dbTotal, int dbObserved, List<String> dbUncovered, int mentionedOnly) {
            this.total = total;
            this.observed = observed;
            this.uncovered = uncovered;
            this.dbTotal = dbTotal;
            this.dbObserved = dbObserved;
            this.dbUncovered = dbUncovered;
            this.mentionedOnly = mentionedOnly;
        }

        /**
         * Whether the unscored edges outnumber the scored ones — the shape of a run
         * whose extraction produced mostly guesses, where the percentage means little.
         */
        public boolean isThinlyEvidenced() {
            return mentionedOnly > total + dbTotal;
        }

        /** Whether there is any business sync edge to measure at all. */
        public boolean hasEdges() {
            return total > 0;
        }

        /** Coverage as a whole-number percentage (0 when there is nothing to cover). */
        public int percent() {
            return total == 0 ? 0 : (int) Math.round(100.0 * observed / total);
        }

        /** Whether the graph declares any datastore dependency at all. */
        public boolean hasDbEdges() {
            return dbTotal > 0;
        }

        public int dbPercent() {
            return dbTotal == 0 ? 0 : (int) Math.round(100.0 * dbObserved / dbTotal);
        }
    }

    private CoverageAnalyzer() {
    }

    public static Report analyze(DependencyGraph graph) {
        List<String> uncovered = new ArrayList<>();
        List<String> dbUncovered = new ArrayList<>();
        int total = 0;
        int observed = 0;
        int dbTotal = 0;
        int dbObserved = 0;
        int mentionedOnly = 0;
        if (graph == null) return new Report(0, 0, uncovered, 0, 0, dbUncovered, 0);

        Map<String, DependencyGraph.Node> byId = new HashMap<>();
        for (DependencyGraph.Node node : graph.getNodes()) byId.put(node.id, node);

        for (DependencyGraph.Edge edge : graph.getEdges()) {
            if (!isEvidenced(edge)) mentionedOnly++;
            if (isDataStore(edge, byId)) {
                dbTotal++;
                if (edge.runtimeObserved) dbObserved++;
                else dbUncovered.add(edge.source + " -> " + edge.target);
                continue;
            }
            if (!isBusinessSync(edge, byId)) continue;
            total++;
            if (edge.runtimeObserved) observed++;
            else uncovered.add(edge.source + " -> " + edge.target);
        }
        return new Report(total, observed, uncovered, dbTotal, dbObserved, dbUncovered, mentionedOnly);
    }

    /**
     * An edge into a deployed datastore. Counted only when the datastore is actually
     * running: a db the cluster does not deploy (petclinic's in-memory HSQLDB, a
     * datasource declared in config but never provisioned) can have no connection to
     * observe, so counting it would permanently cap the ratio for a reason that is not
     * a gap in the analysis.
     */
    private static boolean isDataStore(DependencyGraph.Edge edge, Map<String, DependencyGraph.Node> byId) {
        DependencyGraph.Node target = byId.get(edge.target);
        if (target == null || !DependencyGraph.KIND_DB.equals(target.kind)) return false;
        if (Boolean.FALSE.equals(target.deployed)) return false;
        if (isProcessLocal(edge.target)) return false;
        // Same rule as the business ratio: a datastore the docs merely name (a generic
        // "postgresql" beside the real ledger-db) is not a measurable dependency.
        if (!isEvidenced(edge)) return false;
        DependencyGraph.Node source = byId.get(edge.source);
        return source != null && isServiceOrGateway(source.kind)
                && !Boolean.FALSE.equals(source.deployed);
    }

    /** A synchronous edge between two drivable business service/gateway workloads. */
    private static boolean isBusinessSync(DependencyGraph.Edge edge, Map<String, DependencyGraph.Node> byId) {
        String type = edge.type == null ? "" : edge.type;
        if (!type.equals("sync-http") && !type.equals("grpc")) return false;
        if (!isEvidenced(edge)) return false;
        return isCountableWorkload(edge.source, byId.get(edge.source))
                && isCountableWorkload(edge.target, byId.get(edge.target));
    }

    /**
     * Whether the edge has evidence that it is a REAL call, as opposed to something a
     * document mentioned. This is the difference between the graph's dashed and dotted
     * tiers: {@code inferred} means declared with no usage evidence at all.
     *
     * Such an edge must not enter the denominator. The documentation layer is written
     * by an LLM reading prose, and it varies between runs: one Bank of Anthos run
     * produced three extra "userservice -> ledger service" edges that do not exist in
     * the code, plus four edges to a generic "postgresql" node duplicating the real
     * ledger-db/accounts-db ones. Coverage fell from 7/7 to 7/10 with nothing about the
     * system or the traffic having changed. A number that a paragraph of prose can
     * dilute is not measuring what it claims to measure.
     *
     * Note what this does NOT exclude: an edge the code extraction found but traffic
     * never crossed is {@code documented}, counts in full, and is exactly the gap the
     * ratio exists to show. That is why the score is not trivially 100% — Bank of
     * Anthos sat at 5/7 until the missing journey was driven.
     *
     * An observed edge always counts, whatever its confidence field says — it happened.
     */
    private static boolean isEvidenced(DependencyGraph.Edge edge) {
        return edge.runtimeObserved || !DependencyGraph.CONF_INFERRED.equals(edge.confidence);
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

    /**
     * A "datastore" that lives INSIDE the process — an in-memory cache, an embedded or
     * local store. It has no network endpoint, so no connection to it can ever be
     * observed, and counting it caps the ratio at something unreachable. Worse, it then
     * appears under "declared but no connection seen — the pods may have started before
     * the database was reachable", which prescribes a fix (restart, drive traffic) that
     * cannot possibly work.
     *
     * This is the same rule the undeployed-endpoint exclusion already applies: an edge
     * that traffic could never exercise does not belong in a traffic-coverage
     * denominator. Bank of Anthos surfaced it as {@code balancereader -> in-memory-cache}
     * (a Guava cache inside the service).
     *
     * Matched on the name saying so itself, which is weaker than it should be: the
     * robust test is whether the node has a Kubernetes Service — a real datastore has a
     * network identity, a process-local one does not. That needs the Service inventory
     * kept as a structured stage, which it currently is not.
     */
    private static final String[] PROCESS_LOCAL = {
            "in-memory", "inmemory", "in_memory", "embedded", "local-cache", "localcache"};

    private static boolean isProcessLocal(String id) {
        if (id == null || id.isBlank()) return false;
        String n = id.toLowerCase(Locale.ROOT);
        for (String hint : PROCESS_LOCAL) {
            if (n.contains(hint)) return true;
        }
        return false;
    }

    private static boolean isPlatformInfra(String id) {
        if (id == null || id.isBlank()) return false;
        String n = id.toLowerCase(Locale.ROOT);
        for (String hint : PLATFORM_INFRA) {
            if (n.equals(hint) || n.contains(hint)) return true;
        }
        return false;
    }
}
