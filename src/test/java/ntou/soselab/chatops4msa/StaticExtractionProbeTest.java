package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.DetectedStack;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ServiceRootScanner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.StackDetector;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.TreeSitterExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.MermaidEmitter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Static-only validation driver (layers A + B, no cluster, no ports) against an
 * arbitrary local checkout — used to test the dependency extractor on projects
 * other than petclinic (Bank of Anthos, train-ticket, …). Point it at a repo
 * with {@code -Dprobe.repo=/path/to/checkout}.
 *
 * It deliberately skips the LLM tier: only the java + python tree-sitter stacks
 * plus config parsing run, so the whole thing is offline and deterministic —
 * safe on a shared machine because it never opens a port or touches a cluster.
 *
 * The last section additionally probes the ledger -> graph merge on an EMPTY
 * runtime graph, to show how much of the graph a purely-static (greenfield) run
 * can recover on a repo whose layout the merger has not been tuned for.
 */
public class StaticExtractionProbeTest {

    @Test
    void staticExtractionOnLocalRepo() {
        String repoPath = System.getProperty("probe.repo");
        Assumptions.assumeTrue(repoPath != null && !repoPath.isBlank(),
                "set -Dprobe.repo=/path/to/checkout to run this driver");
        Path root = Path.of(repoPath);

        StackDetector detector = new StackDetector();
        TreeSitterExtractor treeSitter = new TreeSitterExtractor();
        ConfigExtractor config = new ConfigExtractor();
        ServiceRootScanner serviceRoots = new ServiceRootScanner();

        EdgeLedger ledger = new EdgeLedger();
        ledger.setRepo(repoPath);

        List<DetectedStack> stacks = detector.detect(root);
        System.out.println("\n================ DETECTED STACKS ================");
        for (DetectedStack s : stacks) {
            System.out.println("  - " + s.describe()
                    + (s.tier == DetectedStack.Tier.LLM ? "   <-- SKIPPED (LLM tier, needs API)" : ""));
        }

        // Only the tree-sitter grammar tiers; skip LLM so the run stays offline.
        for (DetectedStack s : stacks) {
            if (treeSitter.supports(s.language)) {
                treeSitter.extract(root, s, ledger);
            }
        }
        config.extract(root, ledger);
        serviceRoots.scan(root, ledger);

        System.out.println("\n================ FULL LEDGER ================");
        System.out.println(ledger.render());

        // ---- Focus: does DB-persistence detection fire for BOTH languages? ----
        System.out.println("================ PERSISTENCE (DB really-used) ================");
        Map<String, Integer> persistenceByService = new TreeMap<>();
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            if (!e.section.equals("jpa") && !e.section.equals("persistence")) continue;
            String svc = serviceOf(e.file);
            persistenceByService.merge(svc + "  [" + e.section + "]", 1, Integer::sum);
            System.out.println("  " + e.section + "  " + e.fields + "   @ " + e.file + ":" + e.line);
        }
        System.out.println("\n  Persistence markers per service:");
        if (persistenceByService.isEmpty()) {
            System.out.println("    (none found)");
        }
        persistenceByService.forEach((k, v) -> System.out.println("    " + k + " = " + v));

        // ---- Section histogram, for the report ----
        System.out.println("\n================ SECTION HISTOGRAM ================");
        Map<String, Integer> bySection = new LinkedHashMap<>();
        for (EdgeLedger.Edge e : ledger.getEdges()) bySection.merge(e.section, 1, Integer::sum);
        bySection.forEach((k, v) -> System.out.println("    " + k + " = " + v));
        System.out.println("    TOTAL edges = " + ledger.edgeCount()
                + " | files with syntax errors = " + ledger.getFilesWithErrors());

        // ---- Ledger -> graph merge on an EMPTY runtime graph (greenfield static-only) ----
        // The tool's CodeGraphMerger is designed to overlay code edges onto a RUNTIME
        // graph's node vocabulary. This probes what happens with no runtime layer at
        // all (pure static, no ports) on a NESTED-layout repo (src/<group>/<service>/).
        System.out.println("\n================ STATIC-ONLY GRAPH MERGE (empty runtime graph) ================");
        DependencyGraph graph = new DependencyGraph(""); // no runtime nodes -> no vocabulary
        List<CodeGraphMerger.Unresolved> unresolved =
                CodeGraphMerger.merge(graph, ledger.toJson().toString(), repoPath);
        System.out.println("  resolved nodes = " + graph.getNodes().size()
                + " | resolved edges = " + graph.getEdges().size()
                + " | UNRESOLVED code edges = " + unresolved.size());
        System.out.println("  resolved edges:");
        graph.getEdges().forEach(e ->
                System.out.println("    " + e.source + " -> " + e.target + "  (" + e.type + ")"));
        System.out.println("  sample unresolved (source_hint / target_raw):");
        unresolved.stream().limit(8).forEach(u ->
                System.out.println("    hint=" + u.rawSource + "  target=" + u.rawTarget + "  @ " + u.file + ":" + u.line));
        System.out.println("\n  ---- Mermaid emitted from the static-only graph ----");
        System.out.println(MermaidEmitter.emit(graph));
        System.out.println("=================================================\n");
    }

    /** Best-effort service name from a Bank-of-Anthos-style path src/<group>/<service>/... */
    private String serviceOf(String file) {
        String[] parts = file.replace('\\', '/').split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals("src")) {
                // src/<a>/<b>/... -> prefer the deepest dir that looks like a service
                if (i + 2 < parts.length) return parts[i + 1] + "/" + parts[i + 2];
                if (i + 1 < parts.length) return parts[i + 1];
            }
        }
        return parts.length >= 2 ? parts[parts.length - 2] : file;
    }
}
