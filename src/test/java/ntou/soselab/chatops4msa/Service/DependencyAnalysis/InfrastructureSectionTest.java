package ntou.soselab.chatops4msa.Service.DependencyAnalysis;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report's infrastructure section is generated from the graph, not written by the
 * model, so that the two cannot disagree — which they did, differently, on two
 * consecutive runs of the same system: once calling observed db edges "unknown", once
 * replacing them with build-time libraries and cloud alternatives.
 *
 * These tests pin the properties that made it worth taking away from the model.
 */
public class InfrastructureSectionTest {

    /** userservice really uses accounts-db (observed); ledgerwriter declares a queue it never used. */
    private static DependencyGraph graph() {
        DependencyGraph g = new DependencyGraph("bank-of-anthos");
        g.addNode("userservice", DependencyGraph.KIND_SERVICE);
        g.addNode("frontend", DependencyGraph.KIND_SERVICE);
        g.addNode("accounts-db", DependencyGraph.KIND_DB);
        g.addNode("events", DependencyGraph.KIND_QUEUE);
        g.addNode("github.com", DependencyGraph.KIND_EXTERNAL);

        g.addEdge("frontend", "userservice", "sync-http", DependencyGraph.PROV_RUNTIME,
                DependencyGraph.CONF_OBSERVED, true, 42, "istio_requests_total");
        g.addEdge("userservice", "accounts-db", "db", DependencyGraph.PROV_CODE,
                DependencyGraph.CONF_DOCUMENTED, true, 8187,
                "istio_tcp_connections_opened_total userservice->accounts-db = 8187 connection(s)");
        g.addEdge("userservice", "events", "async", DependencyGraph.PROV_DOC,
                DependencyGraph.CONF_INFERRED, false, 0, "documented only");
        g.addEdge("frontend", "github.com", "external", DependencyGraph.PROV_CODE,
                DependencyGraph.CONF_DOCUMENTED, false, 0, "application.yml");
        return g;
    }

    @Test
    void listsEveryInfrastructureEdgeAndOnlyThose() {
        String section = DependencyReportService.infrastructureSection(graph());

        assertTrue(section.contains("userservice -> accounts-db"));
        assertTrue(section.contains("userservice -> events"));
        assertTrue(section.contains("frontend -> github.com"));
        // A service-to-service call belongs in the synchronous section, not here.
        assertFalse(section.contains("frontend -> userservice"));
    }

    @Test
    void anObservedEdgeIsReportedAsObservedWithItsConnectionCount() {
        String section = DependencyReportService.infrastructureSection(graph());
        int db = section.indexOf("userservice -> accounts-db");
        String entry = section.substring(db, section.indexOf("###", db + 1));

        assertTrue(entry.contains("Runtime observed: Yes"),
                "the graph draws this solid; the report must not call it unknown");
        assertTrue(entry.contains("High — confirmed at runtime"));
        // The number is connections, and saying so is the point — it is not traffic.
        assertTrue(entry.contains("8187 TCP connections observed"));
        assertTrue(entry.contains("not a request count"));
    }

    @Test
    void aDeclaredButUnobservedEdgeIsNotUpgraded() {
        String section = DependencyReportService.infrastructureSection(graph());
        int queue = section.indexOf("userservice -> events");
        String entry = section.substring(queue);

        assertTrue(entry.contains("Runtime observed: No"));
        assertTrue(entry.contains("Low — declared only"));
    }

    @Test
    void observedEdgesComeFirst() {
        String section = DependencyReportService.infrastructureSection(graph());
        assertTrue(section.indexOf("userservice -> accounts-db") < section.indexOf("userservice -> events"),
                "the measurements are what a reader cross-checks against the graph");
    }

    @Test
    void theModelsOwnSectionFiveIsReplacedNotAppended() {
        String llm = """
                # 4. Synchronous Dependency Candidates
                ...

                # 5. Infrastructure Dependencies

                ### Infrastructure dependency: Ledger Services -> Micrometer
                - Runtime observed: Unknown

                # 6. Asynchronous Communication
                None.
                """;

        String out = DependencyReportService.spliceInfrastructureSection(llm, graph());

        // The model drifted and wrote the section anyway: its version must not survive,
        // or the report would answer the same question twice, differently.
        assertFalse(out.contains("Micrometer"));
        assertTrue(out.contains("userservice -> accounts-db"));
        // Order is preserved: 4, then the generated 5, then 6.
        assertTrue(out.indexOf("# 4.") < out.indexOf("# 5."));
        assertTrue(out.indexOf("# 5.") < out.indexOf("# 6."));
        assertEquals(1, out.split("# 5\\. Infrastructure Dependencies", -1).length - 1,
                "exactly one section 5");
    }

    @Test
    void aReportWithoutSectionSixStillGetsTheFacts() {
        // Formatting surprises must never lose the section entirely.
        String llm = "# 4. Synchronous Dependency Candidates\n\nsome prose\n";

        String out = DependencyReportService.spliceInfrastructureSection(llm, graph());

        assertTrue(out.contains("some prose"));
        assertTrue(out.contains("userservice -> accounts-db"));
    }

    @Test
    void anEmptyGraphSaysSoRatherThanVanishing() {
        String section = DependencyReportService.infrastructureSection(new DependencyGraph("ns"));
        assertTrue(section.contains("# 5. Infrastructure Dependencies"));
        assertTrue(section.contains("None resolved"));

        assertTrue(DependencyReportService.spliceInfrastructureSection(null, null)
                .contains("# 5. Infrastructure Dependencies"));
    }
}
