package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the model is shown for a question — the context block — is a pure function of
 * the archive and the question, so it can be pinned without an LLM: authority order,
 * grounding, coverage, and the retrieved passages, in that order.
 */
public class ReportQaContextTest {

    private static ReportArchive archive() {
        ReportArchive a = new ReportArchive();
        a.repoName = "bank-of-anthos";
        a.namespace = "bank-of-anthos";
        a.mode = "runtime";
        a.report = "# 4. Synchronous Dependency Candidates\n"
                + "### Candidate: frontend -> userservice\n- Runtime observed: Yes\n\n"
                + "### Candidate: ledgerwriter -> balancereader\n- Runtime observed: Yes\n- Limitations: only on a debit\n\n"
                + "# 9. Unresolved Candidates and Unknowns\n- none\n";
        a.coverage = "Istio observed **7 / 7** business edges";
        a.evidence.put("docs+code notes", "## ledgerwriter\nChecks the balance via balancereader before a debit (LedgerWriterController.java:112).\n");
        a.chunks.addAll(ReportQaService.chunk(a));
        return a;
    }

    private static DependencyGraph graph() {
        DependencyGraph g = new DependencyGraph("bank-of-anthos");
        for (String s : List.of("frontend", "userservice", "ledgerwriter", "balancereader", "contacts")) {
            g.addNode(s, DependencyGraph.KIND_SERVICE);
        }
        g.addEdge("frontend", "userservice", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 6, "istio_requests_total");
        g.addEdge("ledgerwriter", "balancereader", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 2, "istio_requests_total");
        return g;
    }

    @Test
    void corpusIsReportThenCoverageThenEvidence() {
        ReportArchive a = archive();
        List<String> sources = a.chunks.stream().map(c -> c.source).distinct().toList();
        assertEquals(List.of("report", "runtime coverage", "docs+code notes"), sources);
    }

    @Test
    void contextIsOrderedByAuthorityAndGroundedInTheQuestion() {
        String ctx = ReportQaService.buildContext(archive(), graph(), "when does ledgerwriter call balancereader?", null, 4);

        int meta = ctx.indexOf("## Report");
        int facts = ctx.indexOf("# 1. GRAPH FACTS");
        int coverage = ctx.indexOf("# 2. RUNTIME COVERAGE");
        int passages = ctx.indexOf("# 3. RETRIEVED PASSAGES");
        assertTrue(meta >= 0 && meta < facts && facts < coverage && coverage < passages);

        assertTrue(ctx.contains("- Repository: bank-of-anthos"));
        assertTrue(ctx.contains("## Node: ledgerwriter"));
        assertTrue(ctx.contains("## Relation: ledgerwriter and balancereader"));
        assertTrue(ctx.contains("Istio observed **7 / 7** business edges"));
        // The passage about this very edge is retrieved, from both the report and the notes.
        assertTrue(ctx.contains("[report › 4. Synchronous Dependency Candidates › Candidate: ledgerwriter -> balancereader]"));
        assertTrue(ctx.contains("[docs+code notes › ledgerwriter]"));
        assertTrue(ctx.contains("LedgerWriterController.java:112"));
    }

    @Test
    void greenfieldArchiveSaysSoInsteadOfShowingCoverage() {
        ReportArchive a = archive();
        a.namespace = "";
        a.mode = "greenfield";
        a.coverage = "";
        String ctx = ReportQaService.buildContext(a, new DependencyGraph(""), "what is here?", null, 4);
        assertTrue(ctx.contains("- Namespace: (none — greenfield, static analysis)"));
        assertTrue(ctx.contains("Not measured (greenfield run, or no service-to-service edges to score)."));
    }

    @Test
    void discordSplittingRespectsTheLimitAndLineBoundaries() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("- line ").append(i).append(' ').append("x".repeat(40)).append('\n');
        List<String> pieces = ReportQaService.splitForDiscord(sb.toString());
        assertTrue(pieces.size() >= 3);
        assertTrue(pieces.stream().allMatch(p -> p.length() <= 1900));
        assertTrue(pieces.stream().allMatch(p -> p.startsWith("- line ")), "pieces begin at a line boundary");
        assertEquals(List.of("(empty answer)"), ReportQaService.splitForDiscord("  "));
    }

    @Test
    void starterQuestionsNameTheBusiestServiceOfTheGraph() {
        ReportArchive a = archive();
        DependencyGraph g = graph();
        g.addEdge("frontend", "contacts", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 1, null);
        String starters = ReportQaService.starterQuestions(g, a);
        assertTrue(starters.contains("`frontend`"));
        assertTrue(starters.contains("never observed at runtime"), "a runtime archive offers the coverage question");

        a.mode = "greenfield";
        assertTrue(!ReportQaService.starterQuestions(g, a).contains("never observed at runtime"));
    }
}
