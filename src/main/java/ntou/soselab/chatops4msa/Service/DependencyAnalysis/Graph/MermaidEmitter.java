package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Serialises a {@link DependencyGraph} into Mermaid {@code flowchart} syntax —
 * the lowest-cost renderer (a pure string transform, zero infrastructure, zero
 * new dependencies).
 *
 * Discord does not render Mermaid itself, but the block pastes straight into
 * mermaid.live, GitHub, HackMD or a paper's document and draws there. It is the
 * Phase 1 emitter: enough to see the dependency graph at a glance without
 * standing up any image or web tooling.
 *
 * Visual encoding used here (the subset Mermaid can express):
 * <ul>
 *   <li>node shape/colour by {@code kind} (service / db / queue / gateway / external)</li>
 *   <li>edge label = runtime request count</li>
 *   <li>solid arrow = runtime-observed; dashed arrow = code/doc-only (Phase 2+)</li>
 * </ul>
 */
public class MermaidEmitter {

    private MermaidEmitter() {
    }

    public static String emit(DependencyGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart LR\n");
        sb.append("%% Microservice dependency graph — runtime-observed edges (Istio istio_requests_total)\n");
        if (graph.getNamespace() != null && !graph.getNamespace().isBlank()) {
            sb.append("%% namespace: ").append(graph.getNamespace()).append('\n');
        }

        if (graph.isEmpty()) {
            // A valid, renderable diagram that states the absence rather than an empty file.
            sb.append("  none[\"No runtime-observed edges\"]\n");
            return sb.toString();
        }

        // Stable, collision-free Mermaid ids; the human name stays in the label.
        Map<String, String> ids = assignIds(graph);

        for (DependencyGraph.Node node : graph.getNodes()) {
            sb.append("  ").append(nodeDeclaration(ids.get(node.id), node)).append('\n');
        }

        for (DependencyGraph.Edge edge : graph.getEdges()) {
            String arrow = edge.runtimeObserved ? "-->" : "-.->";
            String label = edge.count > 0 ? "|" + edge.count + "|" : "";
            sb.append("  ")
                    .append(ids.get(edge.source)).append(' ')
                    .append(arrow).append(label).append(' ')
                    .append(ids.get(edge.target)).append('\n');
        }

        appendClassDefs(sb, graph);
        return sb.toString();
    }

    private static String nodeDeclaration(String id, DependencyGraph.Node node) {
        String label = quote(node.id);
        String shaped = switch (node.kind == null ? DependencyGraph.KIND_SERVICE : node.kind) {
            case DependencyGraph.KIND_DB -> id + "[(" + label + ")]";      // cylinder
            case DependencyGraph.KIND_QUEUE -> id + "{{" + label + "}}";   // hexagon
            case DependencyGraph.KIND_GATEWAY -> id + "([" + label + "])"; // stadium
            case DependencyGraph.KIND_EXTERNAL -> id + "[/" + label + "/]";// parallelogram
            default -> id + "[" + label + "]";                            // rectangle
        };
        if (node.kind != null && !DependencyGraph.KIND_SERVICE.equals(node.kind)) {
            shaped += ":::" + node.kind;
        }
        return shaped;
    }

    /** Only emit a classDef for the kinds actually present, to keep the block tidy. */
    private static void appendClassDefs(StringBuilder sb, DependencyGraph graph) {
        Set<String> kinds = new HashSet<>();
        for (DependencyGraph.Node node : graph.getNodes()) {
            if (node.kind != null) kinds.add(node.kind);
        }
        if (kinds.contains(DependencyGraph.KIND_DB)) {
            sb.append("classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;\n");
        }
        if (kinds.contains(DependencyGraph.KIND_QUEUE)) {
            sb.append("classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;\n");
        }
        if (kinds.contains(DependencyGraph.KIND_GATEWAY)) {
            sb.append("classDef gateway fill:#e9f9ec,stroke:#2e8b57,color:#14351f;\n");
        }
        if (kinds.contains(DependencyGraph.KIND_EXTERNAL)) {
            sb.append("classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;\n");
        }
    }

    private static Map<String, String> assignIds(DependencyGraph graph) {
        Map<String, String> ids = new HashMap<>();
        Set<String> used = new HashSet<>();
        int counter = 0;
        for (DependencyGraph.Node node : graph.getNodes()) {
            String base = node.id.replaceAll("[^A-Za-z0-9]", "_");
            if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = "n_" + base;
            String candidate = base;
            while (used.contains(candidate)) candidate = base + "_" + (counter++);
            used.add(candidate);
            ids.put(node.id, candidate);
        }
        return ids;
    }

    /** Wrap a label in quotes so hyphens/dots in the name do not break Mermaid parsing. */
    private static String quote(String text) {
        return "\"" + text.replace("\"", "'") + "\"";
    }
}
