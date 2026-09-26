package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.DetectedStack;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ServiceRootScanner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.StackDetector;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.TreeSitterExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphLayerAssigner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphNormalizer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.MermaidEmitter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The whole static (greenfield) graph path on an arbitrary local checkout, offline:
 * stack detection → tree-sitter (java/python) + config + service roots → ledger →
 * merge onto an EMPTY runtime graph → db promotion → normalize → layers → Mermaid.
 *
 * <p>This is what the tool does when it is handed a repo and no namespace, minus the
 * two steps that need a network (the DeepWiki doc layer and the LLM residue pass).
 * It exists to answer one question honestly: does the extraction and merge tuned on
 * petclinic / Bank of Anthos / train-ticket still produce a truthful graph on a
 * project it has never seen? Run with
 * {@code mvn -o test -Dtest=GreenfieldProbeTest -Dprobe.repo=/path/to/checkout
 * [-Dprobe.out=/path/graph.mmd]}. Without {@code probe.repo} it is skipped.
 */
public class GreenfieldProbeTest {

    @Test
    void greenfieldGraphOnLocalRepo() throws IOException {
        String repoPath = System.getProperty("probe.repo");
        Assumptions.assumeTrue(repoPath != null && !repoPath.isBlank(),
                "set -Dprobe.repo=/path/to/checkout to run this driver");
        Path root = Path.of(repoPath);
        String repoName = root.getFileName().toString();

        StackDetector detector = new StackDetector();
        TreeSitterExtractor treeSitter = new TreeSitterExtractor();
        ConfigExtractor config = new ConfigExtractor();
        ServiceRootScanner serviceRoots = new ServiceRootScanner();

        EdgeLedger ledger = new EdgeLedger();
        ledger.setRepo(repoPath);

        StringBuilder out = new StringBuilder();
        List<DetectedStack> stacks = detector.detect(root);
        out.append("## stacks\n");
        for (DetectedStack s : stacks) {
            out.append("- ").append(s.describe())
                    .append(s.tier == DetectedStack.Tier.LLM ? "   <-- skipped (LLM tier)" : "")
                    .append('\n');
        }
        for (DetectedStack s : stacks) {
            if (treeSitter.supports(s.language)) treeSitter.extract(root, s, ledger);
        }
        config.extract(root, ledger);
        serviceRoots.scan(root, ledger);

        out.append("\n## ledger sections\n");
        Map<String, Integer> bySection = new LinkedHashMap<>();
        for (EdgeLedger.Edge e : ledger.getEdges()) bySection.merge(e.section, 1, Integer::sum);
        bySection.forEach((k, v) -> out.append("- ").append(k).append(" = ").append(v).append('\n'));
        out.append("- TOTAL = ").append(ledger.edgeCount())
                .append(" | files with syntax errors = ").append(ledger.getFilesWithErrors()).append('\n');

        String codeEdges = ledger.toJson().toString();
        DependencyGraph graph = new DependencyGraph("");
        List<CodeGraphMerger.Unresolved> unresolved = CodeGraphMerger.merge(graph, codeEdges, repoName);
        int rawNodes = graph.getNodes().size();
        int rawEdges = graph.getEdges().size();
        Set<String> persistence = CodeGraphMerger.persistenceServices(graph, codeEdges, repoName);
        CodeGraphMerger.promoteReallyUsedDbs(graph, persistence);
        GraphNormalizer.normalize(graph);
        GraphLayerAssigner.assign(graph);

        out.append("\n## graph\n");
        out.append("- after merge: ").append(rawNodes).append(" nodes / ").append(rawEdges).append(" edges")
                .append(" | after normalize: ").append(graph.getNodes().size()).append(" nodes / ")
                .append(graph.getEdges().size()).append(" edges | unresolved code edges = ")
                .append(unresolved.size()).append('\n');
        out.append("- persistence services: ").append(persistence).append('\n');

        out.append("\n## nodes (kind, layer)\n");
        Map<String, String> nodeLines = new TreeMap<>();
        for (DependencyGraph.Node n : graph.getNodes()) {
            nodeLines.put(n.id, "- " + n.id + "  [" + n.kind + ", L" + n.layer + "]");
        }
        nodeLines.values().forEach(l -> out.append(l).append('\n'));

        out.append("\n## edges (type, confidence, evidence)\n");
        Map<String, String> edgeLines = new TreeMap<>();
        for (DependencyGraph.Edge e : graph.getEdges()) {
            String ev = e.evidence.isEmpty() ? "" : "  " + e.evidence.get(0);
            edgeLines.put(e.source + "->" + e.target,
                    "- " + e.source + " -> " + e.target + "  (" + e.type + ", " + e.confidence + ")" + ev);
        }
        edgeLines.values().forEach(l -> out.append(l).append('\n'));

        out.append("\n## unresolved (source hint / raw target / file:line), first 40\n");
        unresolved.stream().limit(40).forEach(u -> out.append("- ").append(u.rawSource)
                .append("  =>  ").append(u.rawTarget).append("   @ ").append(u.file).append(':').append(u.line).append('\n'));

        String mermaid = MermaidEmitter.emit(graph);
        out.append("\n## mermaid\n```mermaid\n").append(mermaid).append("\n```\n");

        System.out.println("\n================ GREENFIELD PROBE: " + repoName + " ================\n" + out);

        String outPath = System.getProperty("probe.out");
        if (outPath != null && !outPath.isBlank()) {
            Path target = Path.of(outPath);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, mermaid);
            Files.writeString(Path.of(outPath.replaceAll("\\.mmd$", "") + ".summary.md"), out.toString());
        }
    }
}
