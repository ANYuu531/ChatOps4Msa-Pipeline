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

    /** Substrings that identify a datastore workload by convention (visual hint only). */
    private static final String[] DB_HINTS = {
            "-db", "database", "mongo", "mysql", "postgres", "postgresql",
            "redis", "cassandra", "mariadb", "couch", "elasticsearch"};

    /** Substrings that identify a message broker (visual hint only). */
    private static final String[] QUEUE_HINTS = {"rabbitmq", "kafka", "nats", "activemq", "rabbit"};

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

            graph.addNode(source, kindOf(source));
            graph.addNode(target, kindOf(target));
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

    private static String kindOf(String workload) {
        String name = workload.toLowerCase();
        if (name.contains("ingressgateway")) return DependencyGraph.KIND_GATEWAY;
        for (String hint : QUEUE_HINTS) if (name.contains(hint)) return DependencyGraph.KIND_QUEUE;
        for (String hint : DB_HINTS) if (name.contains(hint)) return DependencyGraph.KIND_DB;
        return DependencyGraph.KIND_SERVICE;
    }
}
