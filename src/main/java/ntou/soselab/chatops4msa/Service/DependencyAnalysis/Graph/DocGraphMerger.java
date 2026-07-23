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
     * Resolves a documented name onto the graph vocabulary. Matches an existing node
     * (case-insensitive, after stripping jdbc/scheme/port/path and k8s DNS suffixes);
     * otherwise introduces a new node when the name is plausible, so a documented
     * dependency the runtime and code never surfaced still appears. Returns null for
     * a blank, placeholder, or implausible name (never invents a dependency).
     */
    private String resolveNode(String raw, String forcedKind) {
        if (raw == null || raw.isBlank()) return null;
        boolean external = DependencyGraph.KIND_EXTERNAL.equals(forcedKind);
        String cleaned = external ? host(raw) : label(raw);
        if (cleaned == null || cleaned.isEmpty()) return null;

        for (String node : knownNodes) {
            if (node.equalsIgnoreCase(cleaned)) return node;
        }
        if (!isPlausible(cleaned, external)) return null;

        String kind = forcedKind != null ? forcedKind : DependencyGraph.classifyKind(cleaned);
        graph.addNode(cleaned, kind);
        knownNodes.add(cleaned);
        return cleaned;
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

    // ---------- name normalisation (mirrors CodeGraphMerger's rules, doc inputs are cleaner) ----------

    /** Single lower-cased DNS label, scheme/jdbc/port/path and cluster suffixes stripped. */
    private static String label(String raw) {
        String s = strip(raw);
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot); // <svc>.<ns>.svc.cluster.local -> <svc>
        return s;
    }

    /** Full host with dots preserved (for an external domain). */
    private static String host(String raw) {
        return strip(raw);
    }

    private static String strip(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("jdbc:")) s = s.substring(5);
        s = s.replaceFirst("^[a-z][a-z0-9+.-]*://", ""); // scheme
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);       // path
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);            // userinfo
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);       // port
        s = s.replace("\"", "").replace("'", "").trim();
        if (s.contains("${") || s.contains("{{")) return "";
        return s;
    }

    /** A k8s-service-ish label (or a dotted domain when external), not a bare number. */
    private static boolean isPlausible(String s, boolean external) {
        if (s == null) return false;
        if (external) {
            return s.contains(".") && s.length() >= 4 && s.length() <= 253
                    && s.matches("[a-z0-9.-]+") && !s.matches("[0-9.]+");
        }
        if (s.length() < 2 || s.length() > 63) return false;
        if (!s.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?")) return false;
        return !s.matches("[0-9]+");
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
