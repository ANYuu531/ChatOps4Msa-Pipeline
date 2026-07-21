package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

/**
 * Serialises a {@link DependencyGraph} into Graphviz DOT — the source for the
 * Phase 2 static image. Graphviz lays this kind of directed service graph out
 * well, and rendering it to PNG ({@link GraphvizRenderer}) lets the graph be seen
 * inline in Discord as an attachment rather than pasted elsewhere.
 *
 * Unlike Mermaid, DOT accepts quoted ids with hyphens/dots directly, so the
 * workload names are used verbatim as node ids — no id remapping needed.
 *
 * Visual encoding:
 * <ul>
 *   <li>node shape/fill by {@code kind}</li>
 *   <li><b>solid</b> edge = runtime-observed; <b>dashed grey</b> edge = code/doc-only
 *       (not seen on the wire) — this is the provenance line-style the plan calls for</li>
 *   <li>edge label = runtime request count (when observed)</li>
 * </ul>
 */
public class DotEmitter {

    private DotEmitter() {
    }

    public static String emit(DependencyGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph dependencies {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  graph [fontname=\"Helvetica\", labelloc=\"t\"");
        if (graph.getNamespace() != null && !graph.getNamespace().isBlank()) {
            sb.append(", label=\"namespace: ").append(escape(graph.getNamespace())).append("\"");
        }
        sb.append("];\n");
        sb.append("  node [fontname=\"Helvetica\", style=\"rounded,filled\", fillcolor=\"#f5f5f5\", color=\"#888888\"];\n");
        sb.append("  edge [fontname=\"Helvetica\", fontsize=10, color=\"#555555\"];\n");

        if (graph.isEmpty()) {
            sb.append("  \"none\" [label=\"No dependency edges\", shape=box];\n");
            sb.append("}\n");
            return sb.toString();
        }

        for (DependencyGraph.Node node : graph.getNodes()) {
            sb.append("  ").append(nodeLine(node)).append('\n');
        }
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            sb.append("  ").append(edgeLine(edge)).append('\n');
        }

        appendLegend(sb, graph);
        sb.append("}\n");
        return sb.toString();
    }

    private static String nodeLine(DependencyGraph.Node node) {
        String kind = node.kind == null ? DependencyGraph.KIND_SERVICE : node.kind;
        String shape;
        String fill;
        switch (kind) {
            case DependencyGraph.KIND_DB -> { shape = "cylinder"; fill = "#e8f0ff"; }
            case DependencyGraph.KIND_QUEUE -> { shape = "box3d"; fill = "#fff5e0"; }
            case DependencyGraph.KIND_GATEWAY -> { shape = "ellipse"; fill = "#e9f9ec"; }
            case DependencyGraph.KIND_EXTERNAL -> { shape = "parallelogram"; fill = "#f3e8ff"; }
            default -> { shape = "box"; fill = "#f5f5f5"; }
        }
        return "\"" + escape(node.id) + "\" [shape=" + shape + ", fillcolor=\"" + fill + "\"];";
    }

    private static String edgeLine(DependencyGraph.Edge edge) {
        StringBuilder attrs = new StringBuilder();
        if (edge.count > 0) attrs.append("label=\"").append(edge.count).append("\"");
        if (edge.runtimeObserved) {
            // Observed edges are the ground truth: solid and darker.
            append(attrs, "style=solid");
            append(attrs, "color=\"#333333\"");
            append(attrs, "penwidth=1.4");
        } else {
            // Code/doc-only: real in the source but never seen on the wire — dashed.
            append(attrs, "style=dashed");
            append(attrs, "color=\"#999999\"");
        }
        return "\"" + escape(edge.source) + "\" -> \"" + escape(edge.target) + "\" ["
                + attrs + "];";
    }

    private static void append(StringBuilder attrs, String attr) {
        if (attrs.length() > 0) attrs.append(", ");
        attrs.append(attr);
    }

    /**
     * A small legend so a reader knows what solid vs dashed means without external
     * context. Only shown when both provenance styles are actually present.
     */
    private static void appendLegend(StringBuilder sb, DependencyGraph graph) {
        boolean hasObserved = false;
        boolean hasNonObserved = false;
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            if (edge.runtimeObserved) hasObserved = true;
            else hasNonObserved = true;
        }
        if (!(hasObserved && hasNonObserved)) return;

        sb.append("  subgraph cluster_legend {\n");
        sb.append("    label=\"legend\"; fontsize=10; color=\"#cccccc\"; style=dashed;\n");
        sb.append("    \"legend_a\" [label=\"\", shape=point, width=0.01]; ")
                .append("\"legend_b\" [label=\"\", shape=point, width=0.01];\n");
        sb.append("    \"legend_c\" [label=\"\", shape=point, width=0.01]; ")
                .append("\"legend_d\" [label=\"\", shape=point, width=0.01];\n");
        sb.append("    \"legend_a\" -> \"legend_b\" [label=\"runtime observed\", ")
                .append("style=solid, color=\"#333333\", penwidth=1.4];\n");
        sb.append("    \"legend_c\" -> \"legend_d\" [label=\"code/doc only\", ")
                .append("style=dashed, color=\"#999999\"];\n");
        sb.append("  }\n");
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
