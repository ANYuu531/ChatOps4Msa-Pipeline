package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Merges the structured code-extracted edges (EdgeLedger.toJson) onto a runtime
 * {@link DependencyGraph}, <b>deterministically</b> — no LLM.
 *
 * The runtime graph is the authoritative vocabulary: its node ids are the real
 * k8s workload names. This merger tries to resolve each code edge's source and
 * target onto that vocabulary using section-specific rules (a Feign client name,
 * an absolute URL's host, a Kafka topic, …) plus name normalisation. What it can
 * resolve is added to the graph; what it cannot is returned as {@link Unresolved}
 * so the caller may hand only that residue to an LLM ("prefer not to use the LLM,
 * only where necessary").
 *
 * Provenance: a resolved code edge is added with provenance=code and
 * runtimeObserved=false, so an edge the code declares but the mesh never observed
 * renders dashed. When it lands on an existing runtime edge, {@link DependencyGraph}
 * merges them — the arrow stays solid and simply gains "code" provenance
 * ("confirmed by both code and runtime").
 */
public class CodeGraphMerger {

    /** A code edge whose source and/or target could not be resolved deterministically. */
    public static class Unresolved {
        public final String section;
        public final String rawSource; // best hint for the source service (file/repo derived), may be null
        public final String rawTarget; // the raw target token from the ledger fields, may be null
        public final String file;
        public final int line;

        Unresolved(String section, String rawSource, String rawTarget, String file, int line) {
            this.section = section;
            this.rawSource = rawSource;
            this.rawTarget = rawTarget;
            this.file = file;
            this.line = line;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("section", section)
                    .put("source_hint", rawSource == null ? "" : rawSource)
                    .put("target_raw", rawTarget == null ? "" : rawTarget)
                    .put("evidence", file + (line > 0 ? ":" + line : ""));
        }
    }

    private final DependencyGraph graph;
    private final Set<String> knownNodes = new LinkedHashSet<>();
    private final String defaultSource; // resolved from the repo name, used when the file path does not identify a service

    private CodeGraphMerger(DependencyGraph graph, String repoName) {
        this.graph = graph;
        for (DependencyGraph.Node node : graph.getNodes()) knownNodes.add(node.id);
        this.defaultSource = matchNode(repoShortName(repoName));
    }

    /**
     * @param graph        the runtime graph to enrich (mutated in place)
     * @param codeEdgesJson STAGE_CODE_EDGES, i.e. {@code {repo, failed, edges:[...]}}
     * @param repoName     the analysed repo ("owner/repo"), a fallback source hint
     * @return the code edges that could not be resolved deterministically
     */
    public static List<Unresolved> merge(DependencyGraph graph, String codeEdgesJson, String repoName) {
        List<Unresolved> unresolved = new ArrayList<>();
        if (graph == null || codeEdgesJson == null || codeEdgesJson.isBlank()) return unresolved;

        JSONArray edges;
        try {
            JSONObject root = new JSONObject(codeEdgesJson);
            if (root.optBoolean("failed", false)) return unresolved;
            edges = root.optJSONArray("edges");
            if (edges == null) return unresolved;
        } catch (Exception e) {
            return unresolved;
        }

        CodeGraphMerger merger = new CodeGraphMerger(graph, repoName);
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge != null) merger.mergeOne(edge, unresolved);
        }
        return unresolved;
    }

    private void mergeOne(JSONObject edge, List<Unresolved> unresolved) {
        String section = edge.optString("section", "");
        JSONObject fields = edge.optJSONObject("fields");
        if (fields == null) fields = new JSONObject();
        String file = edge.optString("file", "");
        int line = edge.optInt("line", 0);

        // Inbound endpoints this service exposes are not an outgoing dependency.
        if ("http-server".equals(section)) return;

        String source = serviceFromFile(file);
        if (source == null) source = defaultSource;

        // --- asynchronous: the other endpoint is a broker destination (its own node) ---
        if (section.startsWith("kafka") || section.startsWith("rabbit")) {
            String broker = brokerTarget(section, fields);
            if (broker == null || source == null) {
                unresolved.add(new Unresolved(section, source, broker, file, line));
                return;
            }
            graph.addNode(broker, DependencyGraph.KIND_QUEUE);
            boolean consume = section.endsWith("consume");
            // produce: service -> destination; consume: destination -> service.
            String from = consume ? broker : source;
            String to = consume ? source : broker;
            addCodeEdge(from, to, "async", file, line);
            return;
        }

        // --- synchronous: resolve the callee host/name onto a known workload ---
        String rawTarget = syncTarget(section, fields);
        String type = "grpc".equals(section) ? "grpc" : "sync-http";
        if (rawTarget == null || source == null) {
            unresolved.add(new Unresolved(section, source, rawTarget, file, line));
            return;
        }

        String fullHost = stripToHost(rawTarget);
        if (fullHost.isEmpty()) {
            unresolved.add(new Unresolved(section, source, rawTarget, file, line));
            return;
        }
        if (isExternal(fullHost, graph.getNamespace())) {
            graph.addNode(fullHost, DependencyGraph.KIND_EXTERNAL);
            addCodeEdge(source, fullHost, "external", file, line);
            return;
        }
        String node = matchNode(rawTarget);
        if (node != null) {
            addCodeEdge(source, node, type, file, line);
        } else {
            unresolved.add(new Unresolved(section, source, rawTarget, file, line));
        }
    }

    private void addCodeEdge(String from, String to, String type, String file, int line) {
        if (from.equals(to)) return;
        graph.addNode(from, DependencyGraph.KIND_SERVICE);
        graph.addEdge(from, to, type,
                DependencyGraph.PROV_CODE,
                DependencyGraph.CONF_DOCUMENTED,
                false, 0,
                "code: " + file + (line > 0 ? ":" + line : ""));
    }

    // ---------- target extraction ----------

    /** The callee token for a synchronous section, or null if none is usable. */
    private static String syncTarget(String section, JSONObject fields) {
        switch (section) {
            case "feign" -> {
                // @FeignClient(name="catalogue", url="...") — a bare name or a URL.
                String v = firstNonBlank(fields, "value", "name", "url", "attr");
                return v;
            }
            case "http-client" -> {
                // Only an absolute URL carries a host; a bare path targets self-ish.
                return firstNonBlank(fields, "url");
            }
            case "url" -> {
                return firstNonBlank(fields, "value");
            }
            case "grpc" -> {
                return firstNonBlank(fields, "value", "target", "host", "name");
            }
            case "config" -> {
                // Only a config value that looks like a URL is a dependency target.
                String v = firstNonBlank(fields, "value");
                return (v != null && looksLikeUrl(v)) ? v : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** The broker destination (topic / exchange / queue) for an async section. */
    private static String brokerTarget(String section, JSONObject fields) {
        return switch (section) {
            case "kafka-produce", "kafka-consume" -> firstNonBlank(fields, "topic");
            case "rabbit-produce" -> firstNonBlank(fields, "exchange", "routing", "value");
            case "rabbit-consume" -> firstNonBlank(fields, "queue");
            default -> null;
        };
    }

    // ---------- name normalisation ----------

    /** Resolve a host/name to a known workload id, or null. Deterministic only. */
    private String matchNode(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        String c = clean(candidate);
        if (c.isEmpty()) return null;

        for (String node : knownNodes) {
            if (node.equalsIgnoreCase(c)) return node;
        }
        // Feign/gRPC client identifiers: "CatalogueClient" / "catalogue-client" -> "catalogue".
        String stripped = c.replaceAll("(?i)[-_]?client$", "");
        if (!stripped.equals(c)) {
            for (String node : knownNodes) {
                if (node.equalsIgnoreCase(stripped)) return node;
            }
        }
        return null;
    }

    /** Lower-cased first DNS label with scheme/port/path and cluster suffixes stripped. */
    private static String clean(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^[a-z][a-z0-9+.-]*://", ""); // scheme
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);       // path
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);            // userinfo
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);       // port
        // ${...} placeholders and quotes are not a usable host.
        s = s.replace("\"", "").replace("'", "").trim();
        if (s.contains("${") || s.contains("{{")) return "";
        // Kubernetes DNS: <svc>.<ns>.svc.cluster.local -> <svc>.
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s;
    }

    /**
     * The full host of a target, dots preserved (scheme/port/path/userinfo/quotes
     * removed): {@code http://catalogue/x} -> {@code catalogue},
     * {@code https://api.github.com/v3} -> {@code api.github.com}. A ${...}/{{...}}
     * placeholder is not a usable host and yields "".
     */
    private static String stripToHost(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
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

    /** A real external host: a dotted domain that is not an in-cluster k8s name or a raw IP. */
    private static boolean isExternal(String host, String namespace) {
        if (host == null || !host.contains(".")) return false;
        if (host.endsWith(".svc.cluster.local") || host.endsWith(".cluster.local")
                || host.endsWith(".svc") || host.endsWith(".local")) return false;
        if (namespace != null && !namespace.isBlank()
                && host.endsWith("." + namespace.toLowerCase(Locale.ROOT))) return false;
        if (host.matches("[0-9.]+")) return false; // a bare IP, not a business name
        return true;
    }

    private static boolean looksLikeUrl(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://") || s.contains("://");
    }

    // ---------- source attribution ----------

    /** First path segment of a file, if it names a known service (monorepo layout). */
    private String serviceFromFile(String file) {
        if (file == null || file.isBlank()) return null;
        String path = file.replace('\\', '/');
        int slash = path.indexOf('/');
        if (slash <= 0) return null;
        return matchNode(path.substring(0, slash));
    }

    private static String repoShortName(String repoName) {
        if (repoName == null || repoName.isBlank()) return null;
        String s = repoName.trim();
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        return s.replaceAll("\\.git$", "");
    }

    private static String firstNonBlank(JSONObject fields, String... keys) {
        for (String key : keys) {
            String v = fields.optString(key, "");
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
