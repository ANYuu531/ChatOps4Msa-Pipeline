package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

/**
 * Builds a {@link DependencyGraph} from the raw Istio Prometheus JSON, with NO
 * LLM in the loop — this is the deterministic, most-reliable layer of the graph.
 *
 * The input is the untouched body returned by {@code toolkit-prometheus-query}
 * for the internal mesh-to-mesh query:
 * <pre>
 *   sum by(source_workload,destination_workload)(
 *     istio_requests_total{reporter="destination",destination_workload_namespace="&lt;ns&gt;"})
 * </pre>
 * Each series in {@code data.result[]} is one directed, runtime-observed edge:
 * {@code source_workload -> destination_workload} with {@code value[1]} the
 * cumulative request count. This mirrors exactly what the {@code istio_runtime_edges}
 * prompt tells the LLM to read — only here it is parsed structurally instead.
 *
 * The same filtering rules the prompt applies are applied here: series whose
 * endpoints are empty, {@code "unknown"}, or a telemetry/infra component are not
 * business edges and are dropped. {@code istio-ingressgateway} as a source is a
 * legitimate entry edge and is kept.
 */
public class RuntimeGraphBuilder {

    /** Telemetry / control-plane workloads that are never a business dependency. */
    private static final Set<String> INFRA = Set.of(
            "prometheus", "grafana", "jaeger", "kiali", "istiod",
            "zipkin", "loki", "tempo", "alertmanager",
            "node-exporter", "kube-state-metrics", "otel-collector");

    private RuntimeGraphBuilder() {
    }

    /**
     * @param prometheusJson the raw body from the internal istio_requests_total query
     * @param namespace      the namespace the query was scoped to (for node metadata)
     * @return a graph of the runtime-observed business edges; empty (no edges) when
     *         Prometheus was unavailable, the JSON is malformed, or nothing was observed
     */
    public static DependencyGraph fromIstioRequests(String prometheusJson, String namespace) {
        DependencyGraph graph = new DependencyGraph(namespace);
        if (prometheusJson == null || prometheusJson.isBlank()) return graph;

        // An UNAVAILABLE block is not data: we could not ask, so there is nothing to draw.
        if (prometheusJson.stripLeading().startsWith("PROMETHEUS_UNAVAILABLE")) return graph;

        JSONArray result;
        try {
            JSONObject root = new JSONObject(prometheusJson);
            if (!"success".equals(root.optString("status"))) return graph;
            JSONObject data = root.optJSONObject("data");
            if (data == null) return graph;
            result = data.optJSONArray("result");
            if (result == null) return graph;
        } catch (Exception e) {
            // Not Prometheus JSON (a proxy error page, say): draw nothing rather than guess.
            return graph;
        }

        for (int i = 0; i < result.length(); i++) {
            JSONObject series = result.optJSONObject(i);
            if (series == null) continue;
            JSONObject metric = series.optJSONObject("metric");
            if (metric == null) continue;

            String source = metric.optString("source_workload", "").trim();
            String target = metric.optString("destination_workload", "").trim();
            if (isNotBusiness(source) || isNotBusiness(target)) continue;
            if (source.equals(target)) continue; // self-calls are noise, not a dependency

            long count = parseCount(series.optJSONArray("value"));

            graph.addNode(source, DependencyGraph.classifyKind(source));
            graph.addNode(target, DependencyGraph.classifyKind(target));
            graph.addEdge(source, target,
                    "sync-http",
                    DependencyGraph.PROV_RUNTIME,
                    DependencyGraph.CONF_OBSERVED,
                    true,
                    count,
                    "istio_requests_total " + source + "->" + target + " = " + count);
        }
        return graph;
    }

    /**
     * Folds Istio <b>egress</b> telemetry onto the graph as runtime-observed external
     * edges — the one runtime signal {@link #fromIstioRequests} cannot carry, because an
     * external call has no destination sidecar and shows up only on the caller's side.
     *
     * The input is the raw body of the egress query, which groups by the callee's
     * <em>service name</em> rather than a workload (an external host has none):
     * <pre>
     *   sum by(source_workload,destination_service_name)(
     *     istio_tcp_connections_opened_total{reporter="source",
     *       source_workload_namespace="&lt;ns&gt;", destination_workload="unknown"})
     * </pre>
     * A series only becomes an edge when {@code destination_service_name} is a real host
     * Istio could attribute — i.e. a ServiceEntry exists for it. Without one the callee's
     * sidecar routes through PassthroughCluster/BlackHoleCluster and the name is lost
     * ({@code unknown}), so those opaque series are skipped rather than drawn as a
     * meaningless "-> unknown" edge.
     *
     * Each attributed series is added as an {@code external} edge with
     * {@code runtimeObserved=true}; because {@link DependencyGraph#addEdge} merges by
     * (source,target), this lands on the code-declared external edge for the same host
     * (e.g. the {@code config-server -> github.com} the code extractor found in
     * application.yml), upgrading it from a code-only dashed edge to a solid
     * code+runtime one — the mesh confirming what the code declared.
     *
     * @param graph      the graph to enrich in place
     * @param egressJson raw Prometheus body from the egress (TCP or HTTP) query; a no-op
     *                   when blank, an UNAVAILABLE marker, or not parseable
     */
    public static void mergeIstioEgress(DependencyGraph graph, String egressJson) {
        if (graph == null || egressJson == null || egressJson.isBlank()) return;
        if (egressJson.stripLeading().startsWith("PROMETHEUS_UNAVAILABLE")) return;

        JSONArray result;
        try {
            JSONObject root = new JSONObject(egressJson);
            if (!"success".equals(root.optString("status"))) return;
            JSONObject data = root.optJSONObject("data");
            if (data == null) return;
            result = data.optJSONArray("result");
            if (result == null) return;
        } catch (Exception e) {
            return;
        }

        for (int i = 0; i < result.length(); i++) {
            JSONObject series = result.optJSONObject(i);
            if (series == null) continue;
            JSONObject metric = series.optJSONObject("metric");
            if (metric == null) continue;

            String source = metric.optString("source_workload", "").trim();
            String target = metric.optString("destination_service_name", "").trim();
            if (isNotBusiness(source)) continue;
            if (!isAttributedExternalHost(target)) continue; // opaque passthrough -> skip
            if (source.equalsIgnoreCase(target)) continue;

            long count = parseCount(series.optJSONArray("value"));

            graph.addNode(source, DependencyGraph.classifyKind(source));
            graph.addNode(target, DependencyGraph.KIND_EXTERNAL);
            graph.addEdge(source, target,
                    "external",
                    DependencyGraph.PROV_RUNTIME,
                    DependencyGraph.CONF_OBSERVED,
                    true,
                    count,
                    "istio_egress " + source + "->" + target + " = " + count);
        }
    }

    /**
     * Folds <b>in-mesh TCP</b> telemetry onto the graph — the runtime evidence for a
     * dependency that is not HTTP, which in practice means <b>the database</b>.
     *
     * Why this is separate from {@link #fromIstioRequests}: Istio emits
     * {@code istio_requests_total} only for protocols it parses (HTTP/gRPC). A MySQL
     * or PostgreSQL connection is opaque TCP, so it appears exclusively in
     * {@code istio_tcp_*} — which the pipeline previously queried only for egress
     * ({@code destination_app="unknown"}, i.e. outside the mesh). An in-cluster
     * database therefore had NO runtime evidence at all, and its edge could never be
     * more than code-declared, however real it was.
     *
     * The query groups by workload and service name:
     * <pre>
     *   sum by(source_workload,destination_workload,destination_service_name)(
     *     istio_tcp_connections_opened_total{reporter="source",
     *       source_workload_namespace="&lt;ns&gt;"})
     * </pre>
     * {@code reporter="source"} on purpose: the caller's sidecar always reports, so
     * this works whether or not the database pod itself is in the mesh — a database
     * usually is NOT sidecar-injected, in which case {@code destination_workload} is
     * {@code unknown} and the Service name is the only identity available.
     *
     * <p><b>What the count means.</b> It is connections opened, not queries — evidence
     * that this service really talks to that database, never a measure of how much.
     * How it grows depends entirely on the client: a pooling client (Java/HikariCP)
     * establishes its pool at startup and the number then barely moves, whereas a
     * client that connects per request keeps incrementing it (Bank of Anthos measured
     * in the thousands within days). Do not read the magnitude as traffic.
     *
     * <p>The pooling case is the one with an ordering requirement: the database must be
     * running <em>before</em> the application pods start, or the pool is established
     * before the sidecar can attribute it and the edge never appears. See
     * {@code kube/petclinic/README.md}.
     *
     * External hosts are skipped here: they have no destination workload and are
     * already handled by {@link #mergeIstioEgress}, which attributes them by
     * ServiceEntry. Double-counting them would give one edge two provenances.
     *
     * @param graph   the graph to enrich in place
     * @param tcpJson raw Prometheus body of the in-mesh TCP query; a no-op when blank,
     *                an UNAVAILABLE marker, or not parseable
     */
    public static void mergeIstioTcp(DependencyGraph graph, String tcpJson) {
        if (graph == null || tcpJson == null || tcpJson.isBlank()) return;
        if (tcpJson.stripLeading().startsWith("PROMETHEUS_UNAVAILABLE")) return;

        JSONArray result;
        try {
            JSONObject root = new JSONObject(tcpJson);
            if (!"success".equals(root.optString("status"))) return;
            JSONObject data = root.optJSONObject("data");
            if (data == null) return;
            result = data.optJSONArray("result");
            if (result == null) return;
        } catch (Exception e) {
            return;
        }

        for (int i = 0; i < result.length(); i++) {
            JSONObject series = result.optJSONObject(i);
            if (series == null) continue;
            JSONObject metric = series.optJSONObject("metric");
            if (metric == null) continue;

            String source = metric.optString("source_workload", "").trim();
            if (isNotBusiness(source)) continue;

            // Prefer the workload (a meshed database); fall back to the Service name,
            // which is all there is when the database has no sidecar.
            String target = metric.optString("destination_workload", "").trim();
            if (isNotBusiness(target)) {
                target = shortServiceName(metric.optString("destination_service_name", "").trim());
            }
            if (isNotBusiness(target)) continue;
            if (isAttributedExternalHost(target)) continue;   // mergeIstioEgress owns these
            if (source.equalsIgnoreCase(target)) continue;

            long count = parseCount(series.optJSONArray("value"));

            String kind = DependencyGraph.classifyKind(target);
            graph.addNode(source, DependencyGraph.classifyKind(source));
            graph.addNode(target, kind);
            // A TCP edge into something named like a datastore is the db edge the code
            // extraction already declared, now confirmed on the wire; anything else is
            // a plain non-HTTP call, kept as its own type so it is not mislabelled.
            graph.addEdge(source, target,
                    DependencyGraph.KIND_DB.equals(kind) ? "db" : "sync-tcp",
                    DependencyGraph.PROV_RUNTIME,
                    DependencyGraph.CONF_OBSERVED,
                    true,
                    count,
                    "istio_tcp_connections_opened_total " + source + "->" + target
                            + " = " + count + " connection(s)");
        }
    }

    /** "accounts-db.boa.svc.cluster.local:5432" -> "accounts-db". */
    private static String shortServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return "";
        String name = serviceName.trim();
        int colon = name.indexOf(':');
        if (colon > 0) name = name.substring(0, colon);
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Service-name labels that mean "Istio could not attribute the external target". */
    private static final Set<String> EGRESS_UNATTRIBUTED = Set.of(
            "unknown", "passthroughcluster", "blackholecluster", "-");

    /**
     * A named external host Istio attributed (a ServiceEntry exists), as opposed to an
     * opaque passthrough: a real dotted domain that is not an in-cluster k8s name.
     */
    private static boolean isAttributedExternalHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase().trim();
        if (h.isEmpty() || EGRESS_UNATTRIBUTED.contains(h)) return false;
        if (!h.contains(".")) return false; // an external dependency is a domain
        return !(h.endsWith(".svc.cluster.local") || h.endsWith(".cluster.local")
                || h.endsWith(".svc") || h.endsWith(".local"));
    }

    /** A series endpoint that is empty, unknown, or a telemetry/infra component. */
    private static boolean isNotBusiness(String workload) {
        if (workload == null || workload.isBlank()) return true;
        String name = workload.toLowerCase();
        return name.equals("unknown") || INFRA.contains(name);
    }

    private static long parseCount(JSONArray value) {
        // Prometheus instant value is [ &lt;timestamp&gt;, "&lt;sampleValue&gt;" ].
        if (value == null || value.length() < 2) return 0;
        try {
            // Values can be scientific-notation doubles ("1.23e+02"); round to a count.
            return Math.round(Double.parseDouble(value.optString(1, "0")));
        } catch (Exception e) {
            return 0;
        }
    }

}
