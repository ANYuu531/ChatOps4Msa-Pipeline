package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared output contract of every code-extraction tier.
 *
 * Tree-sitter (deterministic) and the LLM fallback both produce an EdgeLedger,
 * so the downstream prompts (deepwiki_dependency_notes / dependency_analysis)
 * see exactly one format and do not care which tier ran.
 *
 * An edge is a section (what kind of dependency signal) plus an ordered map of
 * key/value fields plus file:line evidence. Sections are free-form strings so a
 * new .scm pattern can introduce one without touching Java.
 */
public class EdgeLedger {

    /** Human titles for the known sections; unknown sections fall back to the raw key. */
    private static final Map<String, String> SECTION_TITLES = new LinkedHashMap<>();

    static {
        SECTION_TITLES.put("feign", "Synchronous - Feign clients");
        SECTION_TITLES.put("http-client", "Synchronous - HTTP client calls (RestTemplate / WebClient / requests / httpx)");
        SECTION_TITLES.put("http-server", "Inbound - HTTP endpoints exposed by this service");
        SECTION_TITLES.put("url", "Synchronous - absolute URL literals in code");
        SECTION_TITLES.put("kafka-produce", "Asynchronous - Kafka producers (service -> topic)");
        SECTION_TITLES.put("kafka-consume", "Asynchronous - Kafka consumers (topic -> service)");
        SECTION_TITLES.put("rabbit-produce", "Asynchronous - RabbitMQ producers (service -> exchange/routingKey)");
        SECTION_TITLES.put("rabbit-consume", "Asynchronous - RabbitMQ consumers (queue -> service)");
        SECTION_TITLES.put("grpc", "Synchronous - gRPC stubs / clients");
        SECTION_TITLES.put("config", "Infrastructure / service-URL config keys");
        SECTION_TITLES.put("jpa", "Persistence - JPA/ORM markers (entities / repositories: proves DB is really used)");
    }

    public static class Edge {
        public final String section;
        public final Map<String, String> fields;
        public final String file;
        public final int line;
        public final String confidence;

        public Edge(String section, Map<String, String> fields, String file, int line, String confidence) {
            this.section = section;
            this.fields = fields;
            this.file = file;
            this.line = line;
            this.confidence = confidence;
        }

        /** Rendered as one bullet in the ledger. */
        String render() {
            StringBuilder sb = new StringBuilder();
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                parts.add(e.getKey() + "=" + e.getValue());
            }
            sb.append(String.join(", ", parts));
            sb.append(" [Evidence: ").append(file);
            if (line > 0) sb.append(':').append(line);
            sb.append(", ").append(confidence).append(']');
            return sb.toString();
        }

        /** Dedup key: same section + same fields + same location is the same edge. */
        String dedupKey() {
            return section + "|" + fields + "|" + file + "|" + line;
        }

        /** Structured form, so the dependency-graph builder can consume edges without re-parsing the rendered text. */
        JSONObject toJson() {
            JSONObject fieldsJson = new JSONObject();
            fields.forEach(fieldsJson::put);
            return new JSONObject()
                    .put("section", section)
                    .put("fields", fieldsJson)
                    .put("file", file)
                    .put("line", line)
                    .put("confidence", confidence);
        }
    }

    private final List<Edge> edges = new ArrayList<>();
    private final Set<String> seen = new LinkedHashSet<>();
    private final List<String> warnings = new ArrayList<>();

    private String repo = "";
    private String method = "";
    private String stack = "";
    private boolean failed = false;
    private String failureDetail = "";
    private int filesParsed = 0;
    private int filesWithErrors = 0;

    public void setRepo(String repo) { this.repo = repo; }
    public void setMethod(String method) { this.method = method; }
    public void setStack(String stack) { this.stack = stack; }
    /** Additive: a polyglot repo contributes files from several stacks. */
    public void addFilesParsed(int n) { this.filesParsed += n; }
    public void incFilesWithErrors() { this.filesWithErrors++; }
    public int getFilesWithErrors() { return filesWithErrors; }
    public int edgeCount() { return edges.size(); }
    public List<Edge> getEdges() { return edges; }

    public void addWarning(String warning) { warnings.add(warning); }

    public void fail(String detail) {
        this.failed = true;
        this.failureDetail = detail;
    }

    public void add(Edge edge) {
        if (seen.add(edge.dedupKey())) edges.add(edge);
    }

    public void add(String section, Map<String, String> fields, String file, int line, String confidence) {
        add(new Edge(section, fields, file, line, confidence));
    }

    /**
     * The edges as structured JSON: {@code {repo, failed, edges:[{section, fields,
     * file, line, confidence}]}}. This is what the dependency-graph merge consumes,
     * so code edges reach the graph as data — not by re-parsing the rendered
     * markdown. A failed extraction yields an empty edge list (never throws).
     */
    public JSONObject toJson() {
        JSONArray edgesJson = new JSONArray();
        if (!failed) {
            for (Edge edge : edges) edgesJson.put(edge.toJson());
        }
        return new JSONObject()
                .put("repo", repo)
                .put("failed", failed)
                .put("edges", edgesJson);
    }

    /**
     * Renders the ledger. Kept close to the previous JavaParser output so the
     * downstream prompt templates keep working unchanged.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Code-Extracted Edge Ledger\n\n");

        if (failed) {
            sb.append("Collection status: FAILED\n");
            sb.append("Repository: ").append(repo).append('\n');
            sb.append("Detail: ").append(failureDetail).append('\n');
            sb.append("Note: source code could not be analysed; ")
                    .append("fall back to documentation-based analysis only.\n");
            return sb.toString();
        }

        sb.append("Collection status: OK\n");
        sb.append("Repository: ").append(repo).append('\n');
        sb.append("Detected stack: ").append(stack).append('\n');
        sb.append("Extraction method: ").append(method).append('\n');
        sb.append("Source files analysed: ").append(filesParsed);
        if (filesWithErrors > 0) {
            sb.append(" (").append(filesWithErrors)
                    .append(" contained syntax errors and were parsed partially)");
        }
        sb.append('\n');
        sb.append("Edges found: ").append(edges.size()).append("\n\n");

        // Group by section, preserving the canonical section order then any extras.
        Map<String, List<Edge>> bySection = new LinkedHashMap<>();
        for (String known : SECTION_TITLES.keySet()) bySection.put(known, new ArrayList<>());
        for (Edge e : edges) {
            bySection.computeIfAbsent(e.section, k -> new ArrayList<>()).add(e);
        }

        int index = 1;
        for (Map.Entry<String, List<Edge>> entry : bySection.entrySet()) {
            List<Edge> list = entry.getValue();
            if (list.isEmpty()) continue;
            String title = SECTION_TITLES.getOrDefault(entry.getKey(), entry.getKey());
            sb.append("## ").append(index++).append(". ").append(title).append('\n');
            for (Edge e : list) sb.append("- ").append(e.render()).append('\n');
            sb.append('\n');
        }

        if (edges.isEmpty()) {
            sb.append("No dependency edges were found in source code.\n\n");
        }

        if (!warnings.isEmpty()) {
            sb.append("## Extraction warnings\n");
            for (String w : warnings) sb.append("- ").append(w).append('\n');
            sb.append('\n');
        }

        sb.append("## Notes\n");
        sb.append("- Every edge above is a literal found in source or config; ")
                .append("absence means it was not found in code, not that it cannot happen at runtime.\n");
        sb.append("- Property-indirected targets (${...} placeholders, env vars) are marked Medium; ")
                .append("resolve them against application config for the concrete host.\n");
        sb.append("- Syntax-only extraction: a call is matched on the receiver/identifier name, ")
                .append("not on a resolved type, so a target whose client is declared in a parent class may be missed.\n");
        return sb.toString();
    }
}
