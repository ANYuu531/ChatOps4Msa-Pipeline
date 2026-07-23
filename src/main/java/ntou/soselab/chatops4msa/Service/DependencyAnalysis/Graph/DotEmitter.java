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
 *   <li>a <b>greyed, dashed</b> node = a service referenced by the graph but not
 *       deployed in the cluster; a live node's label carries its deployment
 *       metadata (image · replicas · created date)</li>
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

        StringBuilder attrs = new StringBuilder("shape=").append(shape);
        boolean notDeployed = Boolean.FALSE.equals(node.deployed);
        if (notDeployed) {
            // Referenced by the graph but not running in the cluster: greyed, dashed,
            // red-tinted border so it stands out as a gap.
            attrs.append(", fillcolor=\"#ececec\", color=\"#c0392b\", fontcolor=\"#7f8c8d\"")
                    .append(", style=\"rounded,filled,dashed\"");
        } else {
            attrs.append(", fillcolor=\"").append(fill).append("\"");
        }
        attrs.append(", label=\"").append(nodeLabel(node, notDeployed)).append("\"");
        return "\"" + escape(node.id) + "\" [" + attrs + "];";
    }

    /** The node label: the name, plus a second line of deployment status/metadata when known. */
    private static String nodeLabel(DependencyGraph.Node node, boolean notDeployed) {
        String name = escape(node.id);
        if (notDeployed) return name + "\\n(not deployed)";
        String meta = deployMeta(node);          // image tag · ready/desired · created-date
        return meta.isEmpty() ? name : name + "\\n" + meta;
    }

    /** A compact one-line deployment summary for a live workload, or "" when unknown. */
    private static String deployMeta(DependencyGraph.Node node) {
        if (!Boolean.TRUE.equals(node.deployed)) return "";
        java.util.List<String> parts = new java.util.ArrayList<>();
        String tag = imageTag(node.image);
        if (tag != null) parts.add(tag);
        if (node.replicas != null && !node.replicas.isBlank()) parts.add(escape(node.replicas));
        String day = dateOnly(node.deployedAt);
        if (day != null) parts.add(day);
        return String.join("  ·  ", parts);
    }

    /** The image without its registry/namespace prefix (e.g. ".../app:3.0" -> "app:3.0"). */
    private static String imageTag(String image) {
        if (image == null || image.isBlank() || "<none>".equals(image)) return null;
        String s = image.trim();
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        return escape(s);
    }

    /** The date part of an ISO-8601 instant (e.g. "2026-07-20T10:16:30Z" -> "2026-07-20"). */
    private static String dateOnly(String iso) {
        if (iso == null || iso.isBlank()) return null;
        int t = iso.indexOf('T');
        return t > 0 ? iso.substring(0, t) : iso;
    }

    private static String edgeLine(DependencyGraph.Edge edge) {
        StringBuilder attrs = new StringBuilder();
        boolean declaredOnly = isDeclaredOnly(edge);

        // The type is the label (db / async / ext / grpc); a plain sync call gets none.
        // A declared-only edge is flagged with "?" so its uncertainty is legible.
        String tag = typeTag(edge);
        if (declaredOnly) tag = tag.isEmpty() ? "declared?" : tag + "?";
        if (!tag.isEmpty()) append(attrs, "label=\"" + tag + "\"");

        // Colour by dependency type, so type is legible independent of provenance;
        // a declared-only edge is greyed to read as the weakest evidence.
        append(attrs, "color=\"" + (declaredOnly ? "#b3bcc6" : typeColor(edge)) + "\"");

        if (edge.runtimeObserved) {
            append(attrs, "style=solid");
            // The request count is demoted to line weight — present, but not the headline.
            append(attrs, "penwidth=" + weight(edge.count));
        } else if (declaredOnly) {
            // Weakest tier: declared in config/doc, with NO usage evidence and no
            // runtime — dotted, so a merely-declared db never looks really used.
            append(attrs, "style=dotted");
            append(attrs, "penwidth=1.0");
        } else {
            // Declared in code/doc (with usage evidence) but never seen on the wire — dashed.
            append(attrs, "style=dashed");
            append(attrs, "penwidth=1.0");
        }
        return "\"" + escape(edge.source) + "\" -> \"" + escape(edge.target) + "\" ["
                + attrs + "];";
    }

    /** The weakest evidence tier: declared (config/doc) only, no usage signal, not observed. */
    private static boolean isDeclaredOnly(DependencyGraph.Edge edge) {
        return !edge.runtimeObserved && DependencyGraph.CONF_INFERRED.equals(edge.confidence);
    }

    /** The edge-type label; empty for a plain synchronous call. */
    private static String typeTag(DependencyGraph.Edge edge) {
        if (edge.type == null) return "";
        return switch (edge.type) {
            case "db" -> "db";
            case "async" -> "async";
            case "external" -> "ext";
            case "grpc" -> "grpc";
            default -> "";
        };
    }

    private static String typeColor(DependencyGraph.Edge edge) {
        String type = edge.type == null ? "" : edge.type;
        switch (type) {
            case "db": return "#3a6ea5";
            case "async": return "#b9791a";
            case "external": return "#7a3fb0";
            default: return edge.runtimeObserved ? "#333333" : "#9aa6b2";
        }
    }

    /** Maps a request count to a line width in [1.2, 4.0] (log-scaled), so heavier edges read heavier. */
    private static String weight(long count) {
        if (count <= 0) return "1.2";
        double w = 1.2 + Math.min(2.8, Math.log10(count + 1));
        return String.format(java.util.Locale.ROOT, "%.1f", w);
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
        boolean hasDeclaredOnly = false;
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            if (isDeclaredOnly(edge)) { hasDeclaredOnly = true; break; }
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
        sb.append("    \"legend_c\" -> \"legend_d\" [label=\"code/doc, used\", ")
                .append("style=dashed, color=\"#999999\"];\n");
        if (hasDeclaredOnly) {
            sb.append("    \"legend_e\" [label=\"\", shape=point, width=0.01]; ")
                    .append("\"legend_f\" [label=\"\", shape=point, width=0.01];\n");
            sb.append("    \"legend_e\" -> \"legend_f\" [label=\"declared only (unconfirmed)\", ")
                    .append("style=dotted, color=\"#b3bcc6\"];\n");
        }
        sb.append("  }\n");
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
