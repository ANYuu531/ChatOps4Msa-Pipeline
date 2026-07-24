package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Merges the DeepWiki documentation evidence ledger (the {@code merged_notes} JSON,
 * a docs+code fusion produced by the {@code deepwiki_dependency_notes} prompt) onto
 * a {@link DependencyGraph} as {@code doc}-provenance edges.
 *
 * DeepWiki is the one source that names dependencies the mesh never observes and the
 * code extractor cannot see (an externalised datasource, a documented association).
 * Those belong in the graph — but only ever as evidence, never as runtime fact: the
 * ledger's own contract fixes {@code runtime_observed = "unknown"}, so every edge
 * here is added with {@code runtimeObserved = false} (dashed / dotted, never solid).
 *
 * The ledger's three-axis evidence model is what drives the DB "really used vs merely
 * declared" distinction the visualization needs:
 * <ul>
 *   <li>{@code configured = "yes"} (a connection string / client init / config key
 *       backs it) → {@link DependencyGraph#CONF_DOCUMENTED} (used)</li>
 *   <li>documented only ({@code configured} not "yes") → {@link DependencyGraph#CONF_INFERRED}
 *       (declared, unconfirmed — the weakest tier, rendered dotted)</li>
 * </ul>
 * Because {@link DependencyGraph#addEdge} keeps the strongest confidence, a db that
 * code proves (persistence markers) or the mesh observes is automatically promoted
 * above a doc-only declaration — the layers compose without special-casing.
 *
 * Everything is fully guarded: the input is LLM output, so any parse failure, or a
 * source/target that does not resolve to a plausible node, leaves the graph
 * untouched rather than inventing a dependency.
 */
public class DocGraphMerger {

    private final DependencyGraph graph;
    private final Set<String> knownNodes = new LinkedHashSet<>();

    private DocGraphMerger(DependencyGraph graph) {
        this.graph = graph;
        for (DependencyGraph.Node node : graph.getNodes()) knownNodes.add(node.id);
    }

    /**
     * @param graph           the graph to enrich (mutated in place)
     * @param mergedNotesJson the {@code merged_notes} DeepWiki evidence-ledger JSON
     */
    public static void merge(DependencyGraph graph, String mergedNotesJson) {
        if (graph == null || mergedNotesJson == null || mergedNotesJson.isBlank()) return;
        JSONObject root = parseObject(mergedNotesJson);
        if (root == null) return;

        DocGraphMerger merger = new DocGraphMerger(graph);
        try {
            merger.mergeSynchronous(root.optJSONArray("synchronous_candidates"));
            merger.mergeInfrastructure(root.optJSONArray("infrastructure_dependencies"));
            merger.mergeAsynchronous(root.optJSONArray("asynchronous_workflows"));
        } catch (Exception e) {
            // Doc edges are additive colour on top of the deterministic graph; never
            // let a malformed ledger break the graph that is already built.
            System.out.println("[WARNING] doc-edge merge skipped: " + e.getMessage());
        }
    }

    /** synchronous_candidates[]: source -> target, typed by dependency_type. */
    private void mergeSynchronous(JSONArray items) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String depType = it.optString("dependency_type", "");
            String source = resolveNode(it.optString("source", ""), null);
            String targetKind = kindForDependencyType(depType);
            String target = resolveNode(it.optString("target", ""), targetKind);
            if (source == null || target == null || source.equals(target)) continue;

            addDocEdge(source, target, edgeType(depType, target), it);
        }
    }

    /** infrastructure_dependencies[]: source_component -> target (db / cache / external / …). */
    private void mergeInfrastructure(JSONArray items) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String depType = it.optString("dependency_type", "");
            String source = resolveNode(it.optString("source_component", ""), null);
            String targetKind = kindForDependencyType(depType);
            String target = resolveNode(it.optString("target", ""), targetKind);
            if (source == null || target == null || source.equals(target)) continue;

            addDocEdge(source, target, edgeType(depType, target), it);
        }
    }

    /** asynchronous_workflows[]: producer -> broker -> consumer (broker is a queue node). */
    private void mergeAsynchronous(JSONArray items) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String broker = resolveNode(it.optString("broker", ""), DependencyGraph.KIND_QUEUE);
            if (broker == null) continue;
            String producer = resolveNode(it.optString("producer", ""), null);
            String consumer = resolveNode(it.optString("consumer", ""), null);
            if (producer != null && !producer.equals(broker)) addDocEdge(producer, broker, "async", it);
            if (consumer != null && !consumer.equals(broker)) addDocEdge(broker, consumer, "async", it);
        }
    }

    /**
     * Adds one doc edge. Confidence comes from the {@code configured} flag: a
     * configuration/connection-string-backed dependency is documented; a
     * doc-only mention is inferred (the weakest, dotted tier).
     */
    private void addDocEdge(String source, String target, String type, JSONObject evidence) {
        boolean configured = "yes".equalsIgnoreCase(evidence.optString("configured", ""));
        String confidence = configured ? DependencyGraph.CONF_DOCUMENTED : DependencyGraph.CONF_INFERRED;
        String ref = evidence.optString("evidence_reference", "");
        graph.addEdge(source, target, type,
                DependencyGraph.PROV_DOC, confidence, false, 0,
                "doc" + (ref.isBlank() ? "" : ": " + ref));
    }

    /**
     * Resolves a documented name onto the graph vocabulary. DeepWiki writes
     * display-name aliases — "Customers Service", "CustomersServiceClient",
     * "spring-petclinic-customers-service" — so an existing workload must be matched
     * through the same normalisation the code merger uses (kebab-casing, a {@code
     * -client} suffix, a module-directory {@code -suffix}), or the graph sprouts
     * duplicate phantom nodes. Only when nothing aligns AND the name is not a
     * telemetry/infra component is a new node introduced, so a documented dependency
     * the runtime and code never surfaced (a db, an external host, a genuinely new
     * service) still appears; a blank, placeholder, generic, or infra name yields null.
     */
    private String resolveNode(String raw, String forcedKind) {
        if (raw == null || raw.isBlank()) return null;
        boolean external = DependencyGraph.KIND_EXTERNAL.equals(forcedKind);

        // 1) Align to an existing workload before ever creating a node.
        String aligned = alignToKnown(raw);
        if (aligned != null) return aligned;

        // 2) An external domain keeps its full host.
        if (external) {
            String h = host(raw);
            if (isExternalHost(h)) {
                graph.addNode(h, DependencyGraph.KIND_EXTERNAL);
                knownNodes.add(h);
                return h;
            }
            return null;
        }

        // 3) A datastore / broker is a legitimate new node (mysql, hsqldb, redis, …).
        //    Keep the simple lower-cased name — never camel-split "MySQL" into "my-sql".
        String kind = forcedKind != null ? forcedKind : DependencyGraph.classifyKind(kebab(raw));
        if (DependencyGraph.KIND_DB.equals(kind) || DependencyGraph.KIND_QUEUE.equals(kind)) {
            String name = simplify(raw);
            if (!isPlausibleLabel(name)) return null;
            String k = DependencyGraph.classifyKind(name);
            String finalKind = DependencyGraph.KIND_SERVICE.equals(k) ? kind : k;
            graph.addNode(name, finalKind);
            knownNodes.add(name);
            return name;
        }

        // 4) A genuinely new documented service/gateway (e.g. admin-server): introduce
        //    it kebab-cased. Infra/telemetry and bare generic tokens are dropped.
        String name = kebab(raw);
        if (isInfra(name) || isGeneric(name) || !isPlausibleLabel(name)) return null;
        graph.addNode(name, DependencyGraph.classifyKind(name));
        knownNodes.add(name);
        return name;
    }

    /**
     * Matches a documented name to an existing node through kebab-casing, then a
     * {@code -client} suffix strip, then a module-directory {@code -suffix} (so
     * "spring-petclinic-customers-service" and "CustomersServiceClient" both reach
     * "customers-service"). Returns null when nothing aligns.
     */
    private String alignToKnown(String raw) {
        String c = kebab(raw);
        if (c.isEmpty()) return null;
        String stripped = c.replaceFirst("-?client$", "");
        for (String candidate : stripped.equals(c) ? new String[]{c} : new String[]{c, stripped}) {
            for (String node : knownNodes) {
                if (node.equalsIgnoreCase(candidate)) return node;
            }
        }
        for (String node : knownNodes) {
            if (node.length() >= 3 && (c.endsWith("-" + node) || stripped.equals(node))) return node;
        }
        return null;
    }

    private static String edgeType(String depType, String target) {
        String kind = DependencyGraph.classifyKind(target);
        if (DependencyGraph.KIND_QUEUE.equals(kind)) return "async";
        if (DependencyGraph.KIND_EXTERNAL.equals(kind)) return "external";
        String d = depType == null ? "" : depType.toLowerCase(Locale.ROOT);
        if (d.equals("database") || d.equals("cache") || DependencyGraph.KIND_DB.equals(kind)) return "db";
        if (d.equals("external")) return "external";
        return "sync-http";
    }

    private static String kindForDependencyType(String depType) {
        String d = depType == null ? "" : depType.toLowerCase(Locale.ROOT);
        return switch (d) {
            case "database", "cache" -> DependencyGraph.KIND_DB;
            case "external" -> DependencyGraph.KIND_EXTERNAL;
            default -> null; // let name classification decide (service/db/queue/gateway)
        };
    }

    // ---------- name normalisation ----------

    /** Telemetry / platform components that are never a business dependency (mirrors RuntimeGraphBuilder). */
    private static final Set<String> INFRA = Set.of(
            "prometheus", "grafana", "jaeger", "kiali", "istiod", "zipkin",
            "loki", "tempo", "alertmanager", "node-exporter", "kube-state-metrics", "otel-collector");
    /** Bare generic tokens that name no specific workload. */
    private static final Set<String> GENERIC = Set.of(
            "client", "service", "api", "gateway", "server", "app", "web", "backend", "frontend");

    /**
     * A display name reduced to a k8s-service-style id: camelCase and spaces become
     * hyphens ("Customers Service"/"CustomersService" -> "customers-service"), then
     * lower-cased, with anything but {@code [a-z0-9-]} dropped. Used for matching and
     * for introducing new service nodes.
     */
    private static String kebab(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        s = s.replaceAll("[\\s_./]+", "-").replaceAll("[^a-z0-9-]", "");
        s = s.replaceAll("-{2,}", "-").replaceAll("(^-|-$)", "");
        return s;
    }

    /** Lower-cased single label with jdbc/scheme/port/path stripped — never camel-split (so "MySQL" -> "mysql"). */
    private static String simplify(String raw) {
        String s = host(raw);
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot); // <svc>.<ns>.svc.cluster.local -> <svc>
        return s.replaceAll("[\\s]+", "-");
    }

    /** Full host with dots preserved (for an external domain), scheme/port/path stripped. */
    private static String host(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("jdbc:")) s = s.substring(5);
        s = s.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        s = s.replace("\"", "").replace("'", "").trim();
        if (s.contains("${") || s.contains("{{")) return "";
        return s;
    }

    private static boolean isInfra(String name) {
        return name != null && INFRA.contains(name);
    }

    private static boolean isGeneric(String name) {
        return name != null && GENERIC.contains(name);
    }

    /** A k8s-service-ish label, not a bare number. */
    private static boolean isPlausibleLabel(String s) {
        if (s == null || s.length() < 2 || s.length() > 63) return false;
        if (!s.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?")) return false;
        return !s.matches("[0-9]+");
    }

    /** A real external host: a dotted domain that is not an in-cluster name or a raw IP. */
    private static boolean isExternalHost(String host) {
        if (host == null || !host.contains(".") || host.length() < 4 || host.length() > 253) return false;
        if (!host.matches("[a-z0-9.-]+") || host.matches("[0-9.]+")) return false;
        return !host.endsWith(".svc.cluster.local") && !host.endsWith(".cluster.local")
                && !host.endsWith(".svc") && !host.endsWith(".local");
    }

    private static JSONObject parseObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONObject(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
