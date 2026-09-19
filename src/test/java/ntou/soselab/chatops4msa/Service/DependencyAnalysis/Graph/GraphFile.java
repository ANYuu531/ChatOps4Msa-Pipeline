package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads back a graph DepWeaver itself produced — the Mermaid and Graphviz files under
 * {@code docs/} — so an experiment can run over real graphs instead of hand-built ones.
 *
 * Only what the renderers write is understood: node shape carries the kind (cylinder or
 * {@code [( )]} = data store, ellipse or {@code ([ ])} = gateway, {@code [/ /]} =
 * external) and a dashed edge means it was never observed at runtime. Confidence is set
 * so a re-rendered slice looks like the file it came from; provenance is not in the
 * picture, so it is left empty and no experiment here depends on it.
 *
 * Not a test (the name matches no surefire pattern).
 */
final class GraphFile {

    private static final Pattern MERMAID_NODE = Pattern.compile(
            "^\\s*([A-Za-z0-9_]+)\\s*(\\[\\(|\\(\\[|\\[/|\\[)\"?([^\"\\]/)]+)\"?.*$");
    private static final Pattern MERMAID_EDGE = Pattern.compile(
            "^\\s*([A-Za-z0-9_]+)\\s*(-[.-]->|-\\.\\s*([a-z]+)\\s*\\.->|--\\s*\"?([^\"]*)\"?\\s*-->)\\s*([A-Za-z0-9_]+)\\s*$");
    private static final Pattern DOT_NODE = Pattern.compile("^\\s*\"([^\"]+)\"\\s*\\[(.*)\\];\\s*$");
    private static final Pattern DOT_EDGE = Pattern.compile("^\\s*\"([^\"]+)\"\\s*->\\s*\"([^\"]+)\"\\s*(?:\\[(.*)\\])?;\\s*$");
    private static final Pattern GRAPHML_NODE = Pattern.compile("<node\\s+id=\"([^\"]+)\"");
    private static final Pattern GRAPHML_EDGE = Pattern.compile("<edge\\s+[^>]*source=\"([^\"]+)\"\\s+target=\"([^\"]+)\"");

    private GraphFile() {
    }

    static DependencyGraph read(Path file, String namespace) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(".graphml")) return fromGraphml(Files.readString(file), namespace);
        List<String> lines = Files.readAllLines(file);
        return name.endsWith(".dot") ? fromDot(lines, namespace) : fromMermaid(lines, namespace);
    }

    /**
     * A GraphML dependency graph from outside this project (the MicroDepGraph dataset —
     * see {@code src/test/resources/graphs/microdepgraph/SOURCE.md}). Those graphs carry
     * no kind and no evidence level: every node is read as a service and every edge as a
     * declared dependency, which is what the dataset actually asserts.
     */
    static DependencyGraph fromGraphml(String xml, String namespace) {
        DependencyGraph graph = new DependencyGraph(namespace);
        Matcher node = GRAPHML_NODE.matcher(xml);
        while (node.find()) graph.addNode(unescape(node.group(1)), DependencyGraph.KIND_SERVICE);
        Matcher edge = GRAPHML_EDGE.matcher(xml);
        while (edge.find()) {
            String source = unescape(edge.group(1));
            String target = unescape(edge.group(2));
            graph.addEdge(source, target, "sync-http", null, DependencyGraph.CONF_DOCUMENTED, false, 0, null);
        }
        return graph;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
    }

    private static DependencyGraph fromMermaid(List<String> lines, String namespace) {
        DependencyGraph graph = new DependencyGraph(namespace);
        Map<String, String> idByAlias = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.startsWith("%%") || line.contains("classDef") || line.contains("flowchart")) continue;
            Matcher edge = MERMAID_EDGE.matcher(line);
            if (edge.matches()) continue;                       // edges need every node read first
            Matcher node = MERMAID_NODE.matcher(line);
            if (!node.matches()) continue;
            String alias = node.group(1);
            String id = node.group(3).trim();
            String kind = line.contains(":::db") || "[(".equals(node.group(2)) ? DependencyGraph.KIND_DB
                    : line.contains(":::external") || "[/".equals(node.group(2)) ? DependencyGraph.KIND_EXTERNAL
                    : line.contains(":::gateway") || "([".equals(node.group(2)) ? DependencyGraph.KIND_GATEWAY
                    : DependencyGraph.KIND_SERVICE;
            idByAlias.put(alias, id);
            graph.addNode(id, kind);
        }
        for (String line : lines) {
            Matcher edge = MERMAID_EDGE.matcher(line);
            if (!edge.matches()) continue;
            String source = idByAlias.get(edge.group(1));
            String target = idByAlias.get(edge.group(5));
            if (source == null || target == null) continue;
            String label = edge.group(3) != null ? edge.group(3) : edge.group(4);
            boolean observed = line.contains("-->") && !line.contains("-.");
            graph.addEdge(source, target, type(label, graph, target), null,
                    observed ? DependencyGraph.CONF_OBSERVED : DependencyGraph.CONF_DOCUMENTED, observed, 0, null);
        }
        return graph;
    }

    private static DependencyGraph fromDot(List<String> lines, String namespace) {
        DependencyGraph graph = new DependencyGraph(namespace);
        for (String line : lines) {
            if (line.contains("rank=same")) continue;
            Matcher node = DOT_NODE.matcher(line);
            if (!node.matches()) continue;
            String attrs = node.group(2);
            String kind = attrs.contains("shape=cylinder") ? DependencyGraph.KIND_DB
                    : attrs.contains("shape=ellipse") ? DependencyGraph.KIND_GATEWAY
                    : attrs.contains("shape=note") || attrs.contains("shape=parallelogram") ? DependencyGraph.KIND_EXTERNAL
                    : DependencyGraph.KIND_SERVICE;
            graph.addNode(node.group(1), kind);
        }
        for (String line : lines) {
            Matcher edge = DOT_EDGE.matcher(line);
            if (!edge.matches()) continue;
            String attrs = edge.group(3) == null ? "" : edge.group(3);
            Matcher label = Pattern.compile("label=\"([^\"]*)\"").matcher(attrs);
            String type = label.find() ? label.group(1) : null;
            boolean observed = !attrs.contains("style=dashed") && !attrs.contains("style=dotted");
            graph.addEdge(edge.group(1), edge.group(2), type(type, graph, edge.group(2)), null,
                    observed ? DependencyGraph.CONF_OBSERVED : DependencyGraph.CONF_DOCUMENTED, observed, 0, null);
        }
        return graph;
    }

    /** The label when the renderer wrote one ("db", "ext"), else what the target's kind implies. */
    private static String type(String label, DependencyGraph graph, String target) {
        if (label != null && !label.isBlank()) return label.trim();
        for (DependencyGraph.Node n : graph.getNodes()) {
            if (!n.id.equals(target)) continue;
            if (DependencyGraph.KIND_DB.equals(n.kind)) return "db";
            if (DependencyGraph.KIND_QUEUE.equals(n.kind)) return "async";
            if (DependencyGraph.KIND_EXTERNAL.equals(n.kind)) return "ext";
        }
        return "sync-http";
    }
}
