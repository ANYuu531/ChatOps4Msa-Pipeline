package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grounding is the deterministic half of the Q&amp;A: whatever the question names,
 * the graph's facts about it are written out by code. These tests pin that the facts
 * are the graph's facts — edges, levels, counts, closures, order — and that a service
 * is recognised however the user spells it.
 */
public class GraphGroundingTest {

    /** Bank of Anthos, as the tool actually drew it: ingress → frontend → 5 backends, ledgerwriter → balancereader, 2 dbs. */
    private static DependencyGraph boa() {
        DependencyGraph g = new DependencyGraph("bank-of-anthos");
        g.addNode("istio-ingressgateway", DependencyGraph.KIND_GATEWAY);
        for (String s : List.of("frontend", "userservice", "contacts", "ledgerwriter", "balancereader", "transactionhistory")) {
            g.addNode(s, DependencyGraph.KIND_SERVICE);
        }
        g.addNode("accounts-db", DependencyGraph.KIND_DB);
        g.addNode("ledger-db", DependencyGraph.KIND_DB);
        g.addNode("postgresql", DependencyGraph.KIND_DB); // extraction saw the name, nothing connects to it
        g.addNode("ts-order-service", DependencyGraph.KIND_SERVICE); // a train-ticket style name, undeployed

        g.addEdge("istio-ingressgateway", "frontend", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 117, "istio_requests_total");
        for (String s : List.of("userservice", "contacts", "ledgerwriter", "balancereader", "transactionhistory")) {
            g.addEdge("frontend", s, "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 6, "istio_requests_total");
        }
        g.addEdge("ledgerwriter", "balancereader", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0,
                "src/ledger/ledgerwriter/.../LedgerWriterController.java:112");
        g.addEdge("userservice", "accounts-db", "db", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, true, 8187, "istio_tcp_connections_opened_total");
        g.addEdge("ledgerwriter", "ledger-db", "db", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "SPRING_DATASOURCE_URL");
        g.addEdge("frontend", "ts-order-service", "sync-http", DependencyGraph.PROV_DOC, DependencyGraph.CONF_INFERRED, false, 0, "wiki");

        for (DependencyGraph.Node n : g.getNodes()) {
            switch (n.id) {
                case "istio-ingressgateway" -> n.layer = 0;
                case "frontend" -> n.layer = 1;
                case "ledgerwriter", "userservice", "contacts", "transactionhistory", "ts-order-service" -> n.layer = 2;
                case "balancereader" -> n.layer = 3;
                case "accounts-db", "ledger-db" -> n.layer = 4;
                default -> n.layer = 5;
            }
            if (n.id.equals("ts-order-service")) n.deployed = false;
            if (n.id.equals("frontend")) {
                n.deployed = true;
                n.replicas = "1/1";
                n.image = "frontend:v0.6.10";
            }
        }
        return g;
    }

    @Test
    void recognisesServicesHoweverTheyAreSpelt() {
        DependencyGraph g = boa();
        assertEquals(List.of("frontend"), ids(GraphGrounding.mentionedNodes("Who depends on frontend?", g)));
        assertEquals(List.of("frontend"), ids(GraphGrounding.mentionedNodes("請問frontend依賴誰", g)), "CJK is not a word boundary");
        assertEquals(List.of("accounts-db"), ids(GraphGrounding.mentionedNodes("what talks to the accounts db", g)), "separator as space");
        assertEquals(List.of("accounts-db"), ids(GraphGrounding.mentionedNodes("accountsdb?", g)), "separator dropped");
        assertEquals(List.of("ts-order-service"), ids(GraphGrounding.mentionedNodes("is the order service deployed?", g)), "ts- prefix and -service suffix stripped");
        assertTrue(GraphGrounding.mentionedNodes("frontends are fun", g).isEmpty(), "a longer word is not a mention");
        assertTrue(GraphGrounding.mentionedNodes("nothing here", g).isEmpty());
    }

    @Test
    void roleWordsNameTheNodeTheyDenote() {
        DependencyGraph g = boa();
        assertEquals(List.of("frontend"), ids(GraphGrounding.mentionedNodes("前端依賴誰？", g)));
        assertEquals(List.of("frontend"), ids(GraphGrounding.mentionedNodes("what does the front-end call?", g)));
        assertEquals(List.of("istio-ingressgateway"), ids(GraphGrounding.mentionedNodes("入口是哪個服務？", g)));
        assertEquals(List.of("istio-ingressgateway"), ids(GraphGrounding.mentionedNodes("who sits behind the gateway?", g)));
        // A literal id wins over a role word for the same node, and the order follows the question.
        assertEquals(List.of("userservice", "frontend"), ids(GraphGrounding.mentionedNodes("userservice 和前端", g)));
    }

    @Test
    void groundAddsFactSheetsForNodesThePlanNamed() {
        DependencyGraph g = boa();
        String ctx = GraphGrounding.ground(g, "what does the login service depend on?", List.of("userservice"));
        assertTrue(ctx.contains("## Node: userservice"));
        assertFalse(ctx.contains("The question names no node"));
        assertTrue(GraphGrounding.ground(g, "x", List.of("not-a-node")).contains("The question names no node"));
    }

    @Test
    void mentionsComeBackInQuestionOrderMostSpecificFirstOnTies() {
        DependencyGraph g = boa();
        List<String> named = ids(GraphGrounding.mentionedNodes("does ledgerwriter reach balancereader, and frontend?", g));
        assertEquals(List.of("ledgerwriter", "balancereader", "frontend"), named);
    }

    @Test
    void factSheetStatesEdgesLevelsCountsAndClosures() {
        DependencyGraph g = boa();
        String sheet = GraphGrounding.factSheet(g, node(g, "userservice"));

        assertTrue(sheet.contains("## Node: userservice"));
        assertTrue(sheet.contains("userservice -> accounts-db [db]"));
        assertTrue(sheet.contains("runtime observed: YES (8187 TCP connections — a connection count, not requests)"),
                "a db count is named as connections, never requests");
        assertTrue(sheet.contains("frontend -> userservice [sync-http]"));
        assertTrue(sheet.contains("runtime observed: YES (6 requests)"));
        assertTrue(sheet.contains("transitively depends on it (impacted if it fails or changes): 2 — frontend (depth 1), istio-ingressgateway (depth 2)"));
        assertTrue(sheet.contains("transitively depends on (must be reachable for it to work fully): 1 — accounts-db (depth 1)"));
    }

    @Test
    void factSheetSaysWhenAnEdgeIsDeclaredButNotObserved() {
        DependencyGraph g = boa();
        String sheet = GraphGrounding.factSheet(g, node(g, "ledgerwriter"));
        assertTrue(sheet.contains("ledgerwriter -> balancereader [sync-http]; confidence=documented; provenance=code; runtime observed: no; evidence: src/ledger/ledgerwriter/.../LedgerWriterController.java:112"));
        assertTrue(sheet.contains("ledgerwriter -> ledger-db [db]; confidence=documented; provenance=code; runtime observed: no"));
    }

    @Test
    void factSheetCarriesDeploymentStateAndTier() {
        DependencyGraph g = boa();
        String frontend = GraphGrounding.factSheet(g, node(g, "frontend"));
        assertTrue(frontend.contains("- Deployed: yes"));
        assertTrue(frontend.contains("- Image: frontend:v0.6.10"));
        assertTrue(frontend.contains("- Replicas (ready/desired): 1/1"));
        assertTrue(frontend.contains("- Tier: 1"));

        String order = GraphGrounding.factSheet(g, node(g, "ts-order-service"));
        assertTrue(order.contains("- Deployed: NO — referenced in code/docs but not running"));
    }

    @Test
    void relationReportsADirectEdgeOrTheShortestPath() {
        DependencyGraph g = boa();
        String direct = GraphGrounding.relation(g, node(g, "ledgerwriter"), node(g, "balancereader"));
        assertTrue(direct.contains("- Direct edge: ledgerwriter -> balancereader"));

        String path = GraphGrounding.relation(g, node(g, "istio-ingressgateway"), node(g, "accounts-db"));
        assertTrue(path.contains("- No direct edge between them."));
        assertTrue(path.contains("Shortest path istio-ingressgateway -> frontend -> userservice -> accounts-db"));

        String none = GraphGrounding.relation(g, node(g, "contacts"), node(g, "ledger-db"));
        assertTrue(none.contains("no directed path between them in either direction"));
    }

    @Test
    void summaryCountsLevelsListsIsolatedAndUndeployedAndImpliesAnOrder() {
        String summary = GraphGrounding.summary(boa());
        assertTrue(summary.contains("## Graph summary — namespace bank-of-anthos"));
        assertTrue(summary.contains("- Nodes: 11 (3 database, 1 gateway, 7 service)"));
        assertTrue(summary.contains("- Edges: 10 (runtime-observed: 7; declared in code/docs with usage evidence but not observed: 2; mentioned only, no usage evidence: 1)"));
        assertTrue(summary.contains("- Referenced but NOT deployed in the cluster: ts-order-service"));
        assertTrue(summary.contains("- Nodes with no edges at all (the extraction found nothing for them): postgresql"));
        assertTrue(summary.contains("  - tier 0: istio-ingressgateway"));
        assertTrue(summary.contains("  - tier 4: accounts-db, ledger-db"));
        assertFalse(summary.contains("tier 5"), "an isolated node does not get a tier line of its own");
        assertTrue(summary.contains("Implied start-up / deployment order (deepest tier first"));
        assertTrue(summary.indexOf("[accounts-db, ledger-db]") < summary.indexOf("[istio-ingressgateway]"),
                "data stores come before the entry point in the start-up order");
    }

    @Test
    void groundCombinesSummaryFactSheetsAndRelationOrListsIdsWhenNothingIsNamed() {
        DependencyGraph g = boa();
        String named = GraphGrounding.ground(g, "does frontend call userservice?");
        assertTrue(named.contains("## Graph summary"));
        assertTrue(named.contains("## Node: frontend"));
        assertTrue(named.contains("## Node: userservice"));
        assertTrue(named.contains("## Relation: frontend and userservice"));

        String unnamed = GraphGrounding.ground(g, "what is the overall shape?");
        assertTrue(unnamed.contains("The question names no node of this graph. Node ids, for reference: istio-ingressgateway, frontend"));
        assertFalse(unnamed.contains("## Node:"));

        assertTrue(GraphGrounding.ground(null, "x").contains("No dependency graph"));
    }

    @Test
    void greenfieldGraphIsLabelledAsStatic() {
        DependencyGraph g = new DependencyGraph("");
        g.addNode("a", DependencyGraph.KIND_SERVICE);
        g.addNode("b", DependencyGraph.KIND_SERVICE);
        g.addEdge("a", "b", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "x");
        assertTrue(GraphGrounding.summary(g).contains("## Graph summary (greenfield: static, no cluster)"));
        assertTrue(GraphGrounding.factSheet(g, node(g, "a")).contains("unknown — greenfield (static) run, no cluster was queried"));
    }

    @Test
    void closureAndPathHelpersHandleCyclesWithoutLooping() {
        DependencyGraph g = new DependencyGraph("ns");
        g.addEdge("a", "b", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);
        g.addEdge("b", "c", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);
        g.addEdge("c", "a", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);
        Map<String, Integer> needs = GraphGrounding.closure(g, "a", true);
        assertEquals(Map.of("b", 1, "c", 2), needs);
        assertEquals(List.of("c", "a", "b"), GraphGrounding.shortestPath(g, "c", "b"));
        assertNull(GraphGrounding.shortestPath(g, "a", "a"));
    }

    private static DependencyGraph.Node node(DependencyGraph g, String id) {
        for (DependencyGraph.Node n : g.getNodes()) if (n.id.equals(id)) return n;
        throw new AssertionError("no node " + id);
    }

    private static List<String> ids(List<DependencyGraph.Node> nodes) {
        return nodes.stream().map(n -> n.id).toList();
    }
}
