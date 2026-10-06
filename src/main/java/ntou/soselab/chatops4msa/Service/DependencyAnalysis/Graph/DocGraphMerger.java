package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Merges the DeepWiki documentation provenance ledger (the {@code merged_notes} JSON,
 * a docs+code fusion produced by the {@code deepwiki_dependency_notes} prompt) onto
 * a {@link DependencyGraph} as {@code doc}-provenance edges.
 *
 * DeepWiki is the one source that names dependencies the mesh never observes and the
 * code extractor cannot see (an externalised datasource, a documented association).
 * Those belong in the graph — but only ever as declared provenance, never as runtime fact: the
 * ledger's own contract fixes {@code runtime_observed = "unknown"}, so every edge
 * here is added with {@code runtimeObserved = false} (dashed / dotted, never solid).
 *
 * The ledger's three-axis provenance model is what drives the DB "really used vs merely
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
    /** The service ids a documented name may be an alias of — offered as candidates when asking. */
    private final Set<String> knownServices = new LinkedHashSet<>();
    /** What the operator has already decided for a documented name; never null. */
    private final AliasResolution.Answers answers;
    /** Where an unresolved documented service name is recorded instead of being dropped; may be null. */
    private final AliasResolution.Questions questions;
    /**
     * The analysed repository's own names ("zpng/spring-cloud-microservice-examples" and
     * "spring-cloud-microservice-examples"): the wiki sometimes makes the whole repository
     * the source of an infrastructure dependency, and that is not a service to ask about.
     */
    private final Set<String> repoNames = new LinkedHashSet<>();

    private DocGraphMerger(DependencyGraph graph, AliasResolution.Answers answers,
                           AliasResolution.Questions questions, String repoName) {
        this.graph = graph;
        if (repoName != null && !repoName.isBlank()) {
            String r = repoName.trim().toLowerCase(Locale.ROOT);
            repoNames.add(AliasResolution.key(r));
            int slash = r.lastIndexOf('/');
            if (slash >= 0 && slash < r.length() - 1) repoNames.add(AliasResolution.key(r.substring(slash + 1)));
        }
        this.answers = answers == null ? new AliasResolution.Answers() : answers;
        this.questions = questions;
        for (DependencyGraph.Node node : graph.getNodes()) {
            knownNodes.add(node.id);
            if (node.kind == null || DependencyGraph.KIND_SERVICE.equals(node.kind)
                    || DependencyGraph.KIND_GATEWAY.equals(node.kind)) {
                // A node the operator has already folded onto another is offered under
                // the name it will have, not the one the normaliser is about to retire.
                String decided = this.answers.decisionFor(node.id);
                boolean renamed = decided != null && !AliasResolution.IGNORE.equals(decided)
                        && !AliasResolution.NEW.equals(decided) && graph.findNode(decided) != null;
                knownServices.add(renamed ? decided : node.id);
            }
        }
    }

    /**
     * @param graph           the graph to enrich (mutated in place)
     * @param mergedNotesJson the {@code merged_notes} DeepWiki provenance-ledger JSON
     */
    public static void merge(DependencyGraph graph, String mergedNotesJson) {
        merge(graph, mergedNotesJson, null, null);
    }

    /**
     * As {@link #merge(DependencyGraph, String)}, consulting the operator's earlier
     * alias answers and recording the documented service names that still do not
     * align — the tool asks about those instead of guessing or dropping them.
     *
     * @param answers   what the operator decided for names asked before; null = nothing yet
     * @param questions receives each unresolved service name; null = do not collect
     */
    public static void merge(DependencyGraph graph, String mergedNotesJson,
                             AliasResolution.Answers answers, AliasResolution.Questions questions) {
        merge(graph, mergedNotesJson, answers, questions, null);
    }

    /**
     * @param repoName the analysed repository ("owner/repo"); a documented component by
     *                 that name is the repository itself, never a service, and is dropped
     *                 without a question
     */
    public static void merge(DependencyGraph graph, String mergedNotesJson,
                             AliasResolution.Answers answers, AliasResolution.Questions questions,
                             String repoName) {
        if (graph == null || mergedNotesJson == null || mergedNotesJson.isBlank()) return;
        JSONObject root = parseObject(mergedNotesJson);
        if (root == null) return;

        // Which nodes the runtime, k8s and code layers already knew: anything that
        // exists only after this merge was the wiki's word alone, and GraphNormalizer
        // drops it again if no edge ends up holding it in place.
        Set<String> known = new java.util.HashSet<>();
        for (DependencyGraph.Node n : graph.getNodes()) known.add(n.id);

        DocGraphMerger merger = new DocGraphMerger(graph, answers, questions, repoName);
        try {
            merger.mergeSynchronous(root.optJSONArray("synchronous_candidates"));
            merger.mergeInfrastructure(root.optJSONArray("infrastructure_dependencies"));
            merger.mergeAsynchronous(root.optJSONArray("asynchronous_workflows"));
        } catch (Exception e) {
            // Doc edges are additive colour on top of the deterministic graph; never
            // let a malformed ledger break the graph that is already built.
            System.out.println("[WARNING] doc-edge merge skipped: " + e.getMessage());
        }
        for (DependencyGraph.Node n : graph.getNodes()) {
            if (!known.contains(n.id)) n.docIntroduced = true;
        }
    }

    /** synchronous_candidates[]: source -> target, typed by dependency_type. */
    private void mergeSynchronous(JSONArray items) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String depType = it.optString("dependency_type", "");
            String rawSource = it.optString("source", "");
            String rawTarget = it.optString("target", "");
            String seenIn = "synchronous: " + rawSource + " -> " + rawTarget;
            String source = resolveNode(rawSource, null, seenIn);
            String targetKind = kindForDependencyType(depType);
            String target = resolveNode(rawTarget, targetKind, seenIn);
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
            String rawSource = it.optString("source_component", "");
            String rawTarget = it.optString("target", "");
            String seenIn = "infrastructure: " + rawSource + " -> " + rawTarget;
            String source = resolveNode(rawSource, null, seenIn);
            String targetKind = kindForDependencyType(depType);
            String target = resolveNode(rawTarget, targetKind, seenIn);
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
            String rawBroker = it.optString("broker", "");
            String seenIn = "asynchronous: " + it.optString("producer", "") + " -> "
                    + rawBroker + " -> " + it.optString("consumer", "");
            String broker = resolveNode(rawBroker, DependencyGraph.KIND_QUEUE, seenIn);
            if (broker == null) continue;
            // The wiki lists several producers or consumers in one field —
            // "cloud-simple-service, cloud-simple-serviceB" — which, taken as one name,
            // matched nothing and was asked about as a whole (2026-10-06). Each name
            // is resolved on its own.
            for (String raw : splitNames(it.optString("producer", ""))) {
                String producer = resolveNode(raw, null, seenIn);
                if (producer != null && !producer.equals(broker)) addDocEdge(producer, broker, "async", it);
            }
            for (String raw : splitNames(it.optString("consumer", ""))) {
                String consumer = resolveNode(raw, null, seenIn);
                if (consumer != null && !consumer.equals(broker)) addDocEdge(broker, consumer, "async", it);
            }
        }
    }

    /** "a, b and c" / "a; b" / "a / b" → [a, b, c]; a single name comes back as itself. */
    public static List<String> splitNames(String field) {
        List<String> out = new ArrayList<>();
        if (field == null) return out;
        for (String part : field.split("\\s*(,|;|/|\\band\\b|&)\\s*")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /**
     * Adds one doc edge. Confidence comes from the {@code configured} flag: a
     * configuration/connection-string-backed dependency is documented; a
     * doc-only mention is inferred (the weakest, dotted tier).
     */
    private void addDocEdge(String source, String target, String type, JSONObject item) {
        boolean configured = "yes".equalsIgnoreCase(item.optString("configured", ""));
        String confidence = configured ? DependencyGraph.CONF_DOCUMENTED : DependencyGraph.CONF_INFERRED;
        String ref = item.optString("provenance_reference", "");
        // Legacy key: notes produced by the prompt before the rename.
        if (ref.isBlank()) ref = item.optString("evidence_reference", "");
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
    private String resolveNode(String raw, String forcedKind, String seenIn) {
        if (raw == null || raw.isBlank()) return null;
        boolean external = DependencyGraph.KIND_EXTERNAL.equals(forcedKind);

        // 0) The operator has already said what this name is. Their word outranks
        //    every rule below: a name they mapped lands on that node, a name they
        //    declared a real service becomes one, a name they dismissed is dropped.
        String decided = answers.decisionFor(raw);
        if (decided != null) return applyDecision(raw, decided);

        // The repository itself, named as a component: not a service, and not a question.
        if (repoNames.contains(AliasResolution.key(raw))) return null;

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

        // 3) A datastore / broker is a legitimate new node (mysql, hsqldb, redis, …):
        //    a real dependency the runtime and code often miss. Keep the simple
        //    lower-cased name — never camel-split "MySQL" into "my-sql". An in-process
        //    cache LIBRARY (caffeine, ehcache, guava) is not a datastore node, so it is
        //    dropped rather than drawn as a db the service "uses".
        String kind = forcedKind != null ? forcedKind : DependencyGraph.classifyKind(kebab(raw));
        if (DependencyGraph.KIND_DB.equals(kind) || DependencyGraph.KIND_QUEUE.equals(kind)) {
            String name = simplify(raw);
            if (!isPlausibleLabel(name) || IN_PROCESS_CACHES.contains(name)) return null;
            String k = DependencyGraph.classifyKind(name);
            String finalKind = DependencyGraph.KIND_SERVICE.equals(k) ? kind : k;
            graph.addNode(name, finalKind);
            knownNodes.add(name);
            return name;
        }

        // 4) A documented service/gateway that does NOT align to a known workload is
        //    usually an alias, a technology label ("Netflix Eureka"), or a grouping
        //    ("All Services") — but sometimes a real service the other layers missed.
        //    The tool cannot tell which, so it does not guess: the name is recorded as
        //    a question for the operator (with the closest known services as
        //    candidates) and left off the graph until they answer. Introducing it
        //    blindly would litter the graph with phantom "(not deployed)" nodes;
        //    dropping it silently would hide a dependency the docs asserted.
        if (questions != null && isPlausibleLabel(kebab(raw))) {
            questions.add(raw, "doc", seenIn, knownServices);
        }
        return null;
    }

    /** Resolves a name the operator has decided on: a node id, a new node, or nothing. */
    private String applyDecision(String raw, String decided) {
        if (AliasResolution.IGNORE.equals(decided)) return null;
        if (AliasResolution.NEW.equals(decided)) {
            String id = AliasResolution.nodeId(raw);
            if (id.isEmpty()) return null;
            graph.addNode(id, DependencyGraph.classifyKind(id));
            knownNodes.add(id);
            knownServices.add(id);
            return id;
        }
        // A node id. It must still exist on this graph: an answer given against an
        // older run whose vocabulary has since changed must not conjure a node.
        for (String node : knownNodes) if (node.equalsIgnoreCase(decided)) return node;
        return null;
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

    /** In-process cache libraries — a datastore edge to one of these would overstate a real db. */
    private static final Set<String> IN_PROCESS_CACHES = Set.of(
            "caffeine", "ehcache", "guava", "guava-cache", "concurrentmapcache", "simplecache");

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
