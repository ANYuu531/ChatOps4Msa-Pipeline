package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The query DSL is the A2 design: the model picks operators and names nodes, code
 * validates and executes. These tests pin the two halves that make it safe — a plan
 * naming an unknown node or operator is dropped, and every operator's result is the
 * graph's own fact.
 */
public class GraphQueryTest {

    private static DependencyGraph boa() {
        DependencyGraph g = new DependencyGraph("bank-of-anthos");
        g.addNode("istio-ingressgateway", DependencyGraph.KIND_GATEWAY);
        for (String s : List.of("frontend", "userservice", "contacts", "ledgerwriter", "balancereader")) g.addNode(s, DependencyGraph.KIND_SERVICE);
        g.addNode("accounts-db", DependencyGraph.KIND_DB);
        g.addNode("ledger-db", DependencyGraph.KIND_DB);
        g.addNode("github.com", DependencyGraph.KIND_EXTERNAL);
        g.addNode("events", DependencyGraph.KIND_QUEUE);
        g.addNode("ts-order-service", DependencyGraph.KIND_SERVICE);

        g.addEdge("istio-ingressgateway", "frontend", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 117, "istio_requests_total");
        g.addEdge("frontend", "userservice", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 6, "istio_requests_total");
        g.addEdge("frontend", "contacts", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 6, "istio_requests_total");
        g.addEdge("frontend", "ledgerwriter", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "frontend.py:210");
        g.addEdge("ledgerwriter", "balancereader", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "LedgerWriterController.java:112");
        g.addEdge("userservice", "accounts-db", "db", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, true, 8187, "istio_tcp_connections_opened_total");
        g.addEdge("ledgerwriter", "ledger-db", "db", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "SPRING_DATASOURCE_URL");
        g.addEdge("frontend", "github.com", "external", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "config");
        g.addEdge("ledgerwriter", "events", "async", DependencyGraph.PROV_DOC, DependencyGraph.CONF_INFERRED, false, 0, "wiki");
        g.addEdge("frontend", "ts-order-service", "sync-http", DependencyGraph.PROV_DOC, DependencyGraph.CONF_INFERRED, false, 0, "wiki");
        for (DependencyGraph.Node n : g.getNodes()) {
            if (n.id.equals("ts-order-service")) n.deployed = false;
            else if (DependencyGraph.KIND_SERVICE.equals(n.kind)) n.deployed = true;
        }
        return g;
    }

    // ---------- parsing / validation ----------

    @Test
    void parsesAPlanAndDropsWhatDoesNotValidate() {
        String response = "Here you go:\n```json\n[\n"
                + "{\"op\": \"impact-of\", \"args\": [\"userservice\"]},\n"
                + "{\"op\": \"dependents-of\", \"args\": [\"paymentservice\"]},\n"   // unknown node
                + "{\"op\": \"delete-everything\", \"args\": []},\n"                 // unknown op
                + "{\"op\": \"path\", \"args\": [\"frontend\"]},\n"                  // wrong arity
                + "{\"op\": \"path\", \"args\": [\"frontend\", \"frontend\"]},\n"    // same node twice
                + "{\"op\": \"impact-of\", \"args\": [\"userservice\"]},\n"          // duplicate
                + "{\"op\": \"edges_of_type\", \"args\": [\"database\"]},\n"         // aliases normalised
                + "{\"op\": \"UNCOVERED\", \"args\": []}\n"
                + "]\n```";
        List<GraphQuery> plan = GraphQuery.parse(response, boa());
        assertEquals(List.of("impact-of(userservice)", "edges-of-type(db)", "uncovered()"),
                plan.stream().map(GraphQuery::toString).toList());
    }

    @Test
    void resolvesLooseNodeSpellingsButNotAmbiguousOnes() {
        DependencyGraph g = boa();
        assertEquals("accounts-db", GraphQuery.resolveNode("Accounts DB", g));
        assertEquals("ts-order-service", GraphQuery.resolveNode("order service", g));
        assertEquals("frontend", GraphQuery.resolveNode("FRONTEND", g));
        assertTrue(GraphQuery.resolveNode("frontend and userservice", g) == null, "two candidates is not a resolution");
        assertTrue(GraphQuery.resolveNode("nothing", g) == null);
    }

    @Test
    void junkYieldsAnEmptyPlan() {
        assertTrue(GraphQuery.parse(null, boa()).isEmpty());
        assertTrue(GraphQuery.parse("no json here", boa()).isEmpty());
        assertTrue(GraphQuery.parse("[not, valid", boa()).isEmpty());
        assertTrue(GraphQuery.parse("[]", boa()).isEmpty());
        assertTrue(GraphQuery.parse("[{\"op\":\"uncovered\",\"args\":[]}]", null).isEmpty());
    }

    // ---------- execution ----------

    private static String run(String op, String... args) {
        return GraphQueryEngine.execute(boa(), new GraphQuery(op, List.of(args)));
    }

    @Test
    void directEdgesEitherWay() {
        String out = run("dependencies-of", "frontend");
        assertTrue(out.startsWith("5 edge(s):"), out);
        assertTrue(out.contains("frontend -> userservice [sync-http]"));
        assertTrue(out.contains("frontend -> github.com [external]"));

        String in = run("dependents-of", "balancereader");
        assertTrue(in.contains("ledgerwriter -> balancereader"));
        assertEquals("- nothing depends on istio-ingressgateway (no incoming edge)\n", run("dependents-of", "istio-ingressgateway"));
    }

    @Test
    void transitiveQueriesGroupByDepth() {
        String impact = run("impact-of", "accounts-db");
        assertTrue(impact.contains("3 node(s), by distance from accounts-db (what it impacts, nearest first):"));
        assertTrue(impact.contains("- depth 1: userservice"));
        assertTrue(impact.contains("- depth 2: frontend"));
        assertTrue(impact.contains("- depth 3: istio-ingressgateway"));

        String needs = run("startup-needs", "ledgerwriter");
        assertTrue(needs.contains("- depth 1: balancereader, ledger-db, events"));
        assertEquals("- accounts-db needs nothing else to start: it has no outgoing edge.\n", run("startup-needs", "accounts-db"));
    }

    @Test
    void pathReportsBothDirections() {
        String out = run("path", "istio-ingressgateway", "accounts-db");
        assertTrue(out.contains("- istio-ingressgateway -> frontend -> userservice -> accounts-db (3 hop(s))"));
        assertTrue(out.contains("- no directed path accounts-db -> istio-ingressgateway"));
        assertTrue(run("path", "contacts", "ledger-db").contains("no directed path between contacts and ledger-db in either direction"));
    }

    @Test
    void typeAndEvidenceFilters() {
        assertTrue(run("edges-of-type", "db").startsWith("2 edge(s):"));
        assertTrue(run("db-users").contains("userservice -> accounts-db [db]; confidence=documented; provenance=code; runtime observed: YES (8187 TCP connections"));
        assertTrue(run("observed-edges").startsWith("4 edge(s):"));
        String unobserved = run("unobserved-edges");
        assertTrue(unobserved.startsWith("6 edge(s):"));
        assertTrue(unobserved.contains("ledgerwriter -> balancereader"));
        String mentioned = run("mentioned-only");
        assertTrue(mentioned.startsWith("2 edge(s):"));
        assertTrue(mentioned.contains("ledgerwriter -> events"));
        assertTrue(run("externals").contains("frontend -> github.com"));
        assertTrue(run("async").contains("ledgerwriter -> events [async]"));
    }

    @Test
    void uncoveredReusesTheCoverageAnalyser() {
        String out = run("uncovered");
        // The analyser scores the gateway edge and the four service->service sync edges
        // with usage evidence (5); the two mentioned-only edges are not scored. Three of
        // the five were observed. The point is that this is the analyser's own figure.
        assertTrue(out.contains("Business edges observed 3 / 5 (60%)."), out);
        assertTrue(out.contains("- uncovered: frontend -> ledgerwriter"));
        assertTrue(out.contains("- uncovered: ledgerwriter -> balancereader"));
        assertTrue(out.contains("Data layer observed 1 / 2"));
        assertTrue(out.contains("- no connection seen: ledgerwriter -> ledger-db"));
        assertTrue(out.contains("- not scored: 2 mentioned-only edge(s)"));
    }

    @Test
    void greenfieldNeverPresentsCoverageOrDeploymentAsMeasured() {
        DependencyGraph g = new DependencyGraph("");
        for (String s : List.of("frontend", "userservice", "accounts-db")) g.addNode(s, DependencyGraph.classifyKind(s));
        g.addEdge("frontend", "userservice", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code");
        g.addEdge("userservice", "accounts-db", "db", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code");

        String uncovered = GraphQueryEngine.execute(g, new GraphQuery("uncovered", List.of()));
        assertTrue(uncovered.startsWith("Coverage was NOT measured"), uncovered);
        assertFalse(uncovered.contains("%"), "no percentage for a run that measured nothing");
        assertTrue(uncovered.contains("- declared, unverified: frontend -> userservice"));
        assertTrue(uncovered.contains("- declared datastore edge, unverified: userservice -> accounts-db"));

        String undeployed = GraphQueryEngine.execute(g, new GraphQuery("undeployed", List.of()));
        assertTrue(undeployed.contains("greenfield (static) run, no cluster was queried"));

        String sheet = GraphGrounding.factSheet(g, g.getNodes().iterator().next());
        assertTrue(sheet.contains("- Deployed: unknown — greenfield (static) run, no cluster was queried"));
        assertFalse(sheet.contains("StatefulSet"), "no guessing at reasons a static run cannot know");
    }

    @Test
    void undeployedAndDeployOrder() {
        assertTrue(run("undeployed").contains("- ts-order-service (service): referenced in code/docs, not running"));

        String order = run("deploy-order");
        assertTrue(order.startsWith("Start deepest tier first"));
        // Layers are assigned on the fly when the archive carried none.
        assertTrue(order.contains("- step 1 (tier"));
        int dbs = order.indexOf("accounts-db");
        int entry = order.indexOf("istio-ingressgateway");
        assertTrue(dbs >= 0 && entry >= 0 && dbs < entry, "data stores start before the entry point:\n" + order);
        assertFalse(order.contains("(unknown operator)"));
        // github.com is called by frontend, but nobody deploys github.com.
        assertFalse(order.contains("step") && order.matches("(?s).*step \\d+ \\(tier \\d+\\): [^\\n]*github\\.com.*"), order);
        assertTrue(order.contains("external hosts are not deployed here and are assumed reachable: github.com"), order);
    }

    @Test
    void executingAPlanRendersOneHeadingPerQueryAndNothingForNone() {
        DependencyGraph g = boa();
        String out = GraphQueryEngine.execute(g, List.of(new GraphQuery("uncovered", List.of()), new GraphQuery("impact-of", List.of("frontend"))));
        assertTrue(out.contains("### Query: uncovered()"));
        assertTrue(out.contains("### Query: impact-of(frontend)"));
        assertEquals("", GraphQueryEngine.execute(g, List.of()));
        assertEquals("", GraphQueryEngine.execute(null, List.of(new GraphQuery("uncovered", List.of()))));
    }

    @Test
    void queryResultsLeadTheGraphFactsOfTheContext() {
        ReportArchive a = new ReportArchive();
        a.repoName = "bank-of-anthos";
        a.mode = "runtime";
        String results = GraphQueryEngine.execute(boa(), List.of(new GraphQuery("uncovered", List.of())));
        String ctx = ReportQaService.buildContext(a, boa(), "what was never exercised?", null, 4, results);
        int facts = ctx.indexOf("# 1. GRAPH FACTS");
        int results1 = ctx.indexOf("## Query results");
        int summary = ctx.indexOf("## Graph summary");
        assertTrue(facts < results1 && results1 < summary);
        assertTrue(ReportQaService.buildContext(a, boa(), "x", null, 4, "").indexOf("## Query results") < 0);
    }
}
