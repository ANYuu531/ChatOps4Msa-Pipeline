package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyReportService;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.AliasResolution;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DocGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Which service is this name?" — the alias counterpart of the Tier 3 ask, end to end
 * through the pure-Java layer: a documented or coded name no rule can align is
 * recorded as a question instead of being dropped or fuzzy-matched; the operator's
 * typed answer is read deterministically; the answer is applied on the next build
 * and remembered for the repository.
 *
 * No Spring context, no Discord, no LLM.
 */
public class AliasAskFlowTest {

    private static final Set<String> SERVICES = Set.of(
            "customers-service", "vets-service", "visits-service", "api-gateway");

    private static DependencyGraph shop() {
        DependencyGraph g = new DependencyGraph("shop");
        g.addNode("web", DependencyGraph.KIND_SERVICE);
        g.addNode("orders", DependencyGraph.KIND_SERVICE);
        g.addNode("payment", DependencyGraph.KIND_SERVICE);
        g.addNode("shipping", DependencyGraph.KIND_SERVICE);
        g.addNode("orders-db", DependencyGraph.KIND_DB);
        g.addEdge("web", "orders", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 95, "istio");
        return g;
    }

    // ---------- ranking and reading ----------

    @Test
    void theClosestKnownServicesAreOfferedFirstAndUnrelatedOnesAreNot() {
        List<String> candidates = AliasResolution.rankCandidates("Customer API", SERVICES, 4);

        assertEquals("customers-service", candidates.get(0),
                "the service sharing the word 'customer' must come first: " + candidates);
        assertFalse(candidates.contains("vets-service"), "nothing in common: not a candidate");
        // A database is never offered for a service alias; only service ids are passed in.
        assertTrue(AliasResolution.rankCandidates("zzz", SERVICES, 4).isEmpty());
    }

    @Test
    void aTypedAnswerIsReadDeterministicallyAndAnUnreadableOneStaysPending() {
        AliasResolution.Questions questions = new AliasResolution.Questions();
        questions.add("Customer API", "doc", "synchronous: web -> Customer API", SERVICES);
        AliasResolution.Question q = questions.list().get(0);

        assertEquals("customers-service", AliasResolution.parseAnswer("1", q, SERVICES));
        assertEquals("customers-service", AliasResolution.parseAnswer("CustomersService", q, SERVICES));
        assertEquals("vets-service", AliasResolution.parseAnswer("vets-service", q, SERVICES),
                "a full service id is accepted even when it was not a candidate");
        assertEquals(AliasResolution.IGNORE, AliasResolution.parseAnswer(" ignore ", q, SERVICES));
        assertEquals(AliasResolution.IGNORE, AliasResolution.parseAnswer("略過", q, SERVICES));
        assertEquals(AliasResolution.NEW, AliasResolution.parseAnswer("new", q, SERVICES));
        assertNull(AliasResolution.parseAnswer("", q, SERVICES), "blank: still pending");
        assertNull(AliasResolution.parseAnswer("9", q, SERVICES), "no such candidate: not a merge");
        assertNull(AliasResolution.parseAnswer("something else", q, SERVICES), "not understood: not a merge");
    }

    @Test
    void theSameNameSeenTwiceIsOneQuestionWithTwoMentions() {
        AliasResolution.Questions questions = new AliasResolution.Questions();
        questions.add("Customer API", "doc", "first", SERVICES);
        questions.add("customer-api", "doc", "second", SERVICES);
        questions.add("CustomerAPI", "code", "third", SERVICES);

        assertEquals(1, questions.size());
        assertEquals(3, questions.list().get(0).mentions);
        assertEquals("first", questions.list().get(0).seenIn, "the first sighting is the example");
    }

    // ---------- the documentation layer ----------

    private static final String NOTES = "{ \"synchronous_candidates\": ["
            + "{ \"source\": \"orders\", \"target\": \"Shipping Hub\", \"dependency_type\": \"rest\","
            + "  \"configured\": \"no\", \"provenance_reference\": \"architecture.md §3\" },"
            + "{ \"source\": \"orders\", \"target\": \"payment\", \"dependency_type\": \"rest\", \"configured\": \"yes\" }"
            + "] }";

    @Test
    void anUnalignedDocumentedServiceIsAskedAboutNotDroppedAndNotGuessed() {
        DependencyGraph g = shop();
        AliasResolution.Questions questions = new AliasResolution.Questions();

        DocGraphMerger.merge(g, NOTES, null, questions);

        // Unchanged behaviour: the name is NOT on the graph (no phantom node, no fuzzy edge).
        assertNull(g.findNode("shipping-hub"));
        assertNull(g.findNode("Shipping Hub"));
        // The aligned edge still merges as before.
        assertNotNull(g.getEdges().stream().filter(e -> e.target.equals("payment")).findFirst().orElse(null));
        // New behaviour: the name is a question, with the closest service offered.
        assertEquals(1, questions.size());
        AliasResolution.Question q = questions.list().get(0);
        assertEquals("Shipping Hub", q.name);
        assertEquals("doc", q.origin);
        assertTrue(q.seenIn.contains("orders -> Shipping Hub"), q.seenIn);
        assertEquals("shipping", q.candidates.get(0), "candidates: " + q.candidates);
    }

    @Test
    void theOperatorsAnswerOutranksEveryRuleOnTheNextBuild() {
        // Mapped onto an existing service: the doc edge lands there, with doc provenance.
        AliasResolution.Answers mapped = new AliasResolution.Answers();
        mapped.put("Shipping Hub", "shipping");
        DependencyGraph g1 = shop();
        AliasResolution.Questions none = new AliasResolution.Questions();
        DocGraphMerger.merge(g1, NOTES, mapped, none);
        DependencyGraph.Edge e = g1.getEdges().stream()
                .filter(x -> x.source.equals("orders") && x.target.equals("shipping")).findFirst().orElse(null);
        assertNotNull(e, "the answered alias becomes the edge the docs asserted");
        assertTrue(e.provenance.contains(DependencyGraph.PROV_DOC));
        assertEquals(DependencyGraph.CONF_INFERRED, e.confidence, "doc-only, not configured: still mentioned-only");
        assertTrue(none.isEmpty(), "an answered name is not asked again");

        // Declared a real service: a new node appears and carries the edge.
        AliasResolution.Answers fresh = new AliasResolution.Answers();
        fresh.put("shipping hub", AliasResolution.NEW);   // any spelling of the name finds the answer
        DependencyGraph g2 = shop();
        DocGraphMerger.merge(g2, NOTES, fresh, null);
        assertNotNull(g2.findNode("shipping-hub"));
        assertTrue(g2.getEdges().stream().anyMatch(x -> x.target.equals("shipping-hub")));

        // Dismissed: nothing on the graph, and no question either.
        AliasResolution.Answers ignored = new AliasResolution.Answers();
        ignored.put("Shipping Hub", AliasResolution.IGNORE);
        DependencyGraph g3 = shop();
        AliasResolution.Questions q3 = new AliasResolution.Questions();
        DocGraphMerger.merge(g3, NOTES, ignored, q3);
        assertNull(g3.findNode("shipping-hub"));
        assertTrue(g3.getEdges().stream().noneMatch(x -> x.target.contains("shipping")));
        assertTrue(q3.isEmpty());

        // An answer naming a node this graph does not have conjures nothing.
        AliasResolution.Answers stale = new AliasResolution.Answers();
        stale.put("Shipping Hub", "fulfilment");
        DependencyGraph g4 = shop();
        DocGraphMerger.merge(g4, NOTES, stale, null);
        assertNull(g4.findNode("fulfilment"));
        assertTrue(g4.getEdges().stream().noneMatch(x -> x.target.equals("fulfilment")));
    }

    // ---------- the code residue ----------

    @Test
    void aResidualCodeEdgeTheOperatorResolvedIsAddedAndTheRestBecomeQuestions() {
        DependencyGraph g = shop();
        List<CodeGraphMerger.Unresolved> residue = List.of(
                new CodeGraphMerger.Unresolved("feign", "orders", "ShippingHub", "OrderClient.java", 12),
                new CodeGraphMerger.Unresolved("http-client", "orders", "pricing-engine", "Pricing.java", 40),
                new CodeGraphMerger.Unresolved("url", "web", "10.0.0.7", "Config.java", 3));

        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("ShippingHub", "shipping");
        List<CodeGraphMerger.Unresolved> rest = DependencyReportService.applyAliasAnswers(g, residue, answers);

        DependencyGraph.Edge e = g.getEdges().stream()
                .filter(x -> x.source.equals("orders") && x.target.equals("shipping")).findFirst().orElse(null);
        assertNotNull(e, "the operator's word places the edge without the LLM");
        assertTrue(e.provenance.contains(DependencyGraph.PROV_CODE));
        assertEquals(DependencyGraph.CONF_DOCUMENTED, e.confidence, "a declared call the operator confirmed");
        assertTrue(e.provenanceRefs.get(0).contains("OrderClient.java:12"));
        assertEquals(2, rest.size(), "the unanswered rows go on to the LLM as before");

        // What the LLM then cannot place is a question — except an address, which is not a name.
        AliasResolution.Questions questions = new AliasResolution.Questions();
        DependencyReportService.recordCodeQuestions(g, rest, questions);
        assertEquals(1, questions.size());
        AliasResolution.Question q = questions.list().get(0);
        assertEquals("pricing-engine", q.name);
        assertEquals("code", q.origin);
        assertTrue(q.seenIn.contains("Pricing.java:40"), q.seenIn);
    }

    // ---------- a library name that is also a deployed service ----------

    /** spring-cloud-microservice's Compose file: "hystrix" is the Hystrix DASHBOARD service. */
    private static DependencyGraph composeWithHystrix() {
        DependencyGraph g = new DependencyGraph("");
        for (String s : new String[]{"hystrix", "turbine", "gateway", "discovery"}) g.addNode(s, DependencyGraph.KIND_SERVICE);
        g.addEdge("hystrix", "gateway", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: docker/docker-compose.yaml");
        g.addEdge("hystrix", "discovery", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: docker/docker-compose.yaml");
        g.addEdge("turbine", "discovery", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: docker/docker-compose.yaml");
        // Bank of Anthos: a Redis client library surfaced as a node with no edge at all.
        g.addNode("lettuce", DependencyGraph.KIND_SERVICE);
        return g;
    }

    @Test
    void aLibraryNameTheDeploymentDrewEdgesOnIsAskedAboutNotSilentlyDropped() {
        DependencyGraph g = composeWithHystrix();
        AliasResolution.Questions questions = new AliasResolution.Questions();

        GraphNormalizer.normalize(g, null, questions);

        // Unchanged default: both are dropped (the 2026-10-02 agreement run saw 93/95 because of this).
        assertNull(g.findNode("hystrix"));
        assertNull(g.findNode("lettuce"));
        assertEquals(1, g.getEdges().size());
        // New: hystrix is a question (it carried edges); lettuce is not (it carried none).
        assertEquals(1, questions.size());
        AliasResolution.Question q = questions.list().get(0);
        assertEquals("hystrix", q.name);
        assertTrue(q.seenIn.contains("hystrix -> gateway"), q.seenIn);
        assertTrue(q.seenIn.contains("library name"), q.seenIn);
    }

    @Test
    void theOperatorDecidesWhatTheLibraryNamedNodeIs() {
        // "new": it is a real service under this very name — keep it with its edges.
        AliasResolution.Answers keep = new AliasResolution.Answers();
        keep.put("hystrix", AliasResolution.NEW);
        DependencyGraph g1 = composeWithHystrix();
        AliasResolution.Questions none = new AliasResolution.Questions();
        GraphNormalizer.normalize(g1, keep, none);
        assertNotNull(g1.findNode("hystrix"));
        assertEquals(3, g1.getEdges().size());
        assertTrue(none.isEmpty(), "answered: not asked again");
        assertNull(g1.findNode("lettuce"), "unrelated library names are still dropped");

        // A service id: fold the edges onto it.
        AliasResolution.Answers fold = new AliasResolution.Answers();
        fold.put("hystrix", "turbine");
        DependencyGraph g2 = composeWithHystrix();
        GraphNormalizer.normalize(g2, fold, null);
        assertNull(g2.findNode("hystrix"));
        assertTrue(g2.getEdges().stream().anyMatch(e -> e.source.equals("turbine") && e.target.equals("gateway")));

        // "ignore": dropped, and no question.
        AliasResolution.Answers drop = new AliasResolution.Answers();
        drop.put("hystrix", AliasResolution.IGNORE);
        DependencyGraph g3 = composeWithHystrix();
        AliasResolution.Questions q3 = new AliasResolution.Questions();
        GraphNormalizer.normalize(g3, drop, q3);
        assertNull(g3.findNode("hystrix"));
        assertTrue(q3.isEmpty());
    }

    @Test
    void aModuleDirectoryNoServiceIsNamedAfterIsAskedAboutAndItsAnswerRenamesTheCaller() {
        DependencyGraph g = composeWithHystrix();
        // The dashboard module's own config: its directory is "cloud-hystrix-dashboard", the
        // deployment calls the service "hystrix", and the eureka URL is left for the LLM.
        List<CodeGraphMerger.Unresolved> residue = List.of(new CodeGraphMerger.Unresolved(
                "config", "cloud-hystrix-dashboard", "http://localhost:8761/eureka/",
                "cloud-hystrix-dashboard/src/main/resources/bootstrap.yaml", 0));

        AliasResolution.Questions questions = new AliasResolution.Questions();
        DependencyReportService.recordCodeQuestions(g, residue, questions);
        assertEquals(1, questions.size(), "the URL is not a name; the directory is");
        AliasResolution.Question q = questions.list().get(0);
        assertEquals("cloud-hystrix-dashboard", q.name);
        assertEquals("hystrix", q.candidates.get(0), "candidates: " + q.candidates);

        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("cloud-hystrix-dashboard", "hystrix");
        List<CodeGraphMerger.Unresolved> rest = DependencyReportService.applyAliasAnswers(g, residue, answers);
        assertEquals(1, rest.size(), "the callee is still a URL: it goes on to the LLM");
        assertEquals("hystrix", rest.get(0).rawSource, "…but under the caller's real name");
        assertEquals("http://localhost:8761/eureka/", rest.get(0).rawTarget);
    }

    // ---------- persistence ----------

    @Test
    void questionsAndAnswersSurviveTheCheckpointRoundTripAndACorruptStageMeansNothingPending() {
        AliasResolution.Questions questions = new AliasResolution.Questions();
        questions.add("Customer API", "doc", "synchronous: web -> Customer API", SERVICES);
        AliasResolution.Questions restored = AliasResolution.Questions.fromJson(questions.toJson());
        assertEquals(1, restored.size());
        assertEquals("Customer API", restored.list().get(0).name);
        assertEquals("customers-service", restored.list().get(0).candidates.get(0));

        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("Customer API", "customers-service");
        answers.put("Netflix Eureka", AliasResolution.IGNORE);
        AliasResolution.Answers back = AliasResolution.Answers.fromJson(answers.toJson());
        assertEquals("customers-service", back.decisionFor("customer-api"));
        assertEquals(AliasResolution.IGNORE, back.decisionFor("netflix eureka"));
        assertEquals("Customer API", back.asMap().keySet().iterator().next(), "the spelling as written is kept");

        assertTrue(AliasResolution.Questions.fromJson("not json").isEmpty());
        assertTrue(AliasResolution.Answers.fromJson("").isEmpty());
        assertTrue(AliasResolution.Answers.fromJson("[1,2]").isEmpty());
        assertTrue(AliasResolution.Answers.fromJson("{\"a\": 1}").isEmpty());
        assertTrue(AliasResolution.Answers.fromJson("garbage").isEmpty());
    }

    @Test
    void anAnswerIsRememberedForTheRepositoryAndSeedsTheNextRun(@TempDir Path dir) {
        DependencyAnalysisStateStore store = new DependencyAnalysisStateStore(dir.toString(), 24);

        DependencyAnalysisStateStore.State first = store.start("u1", "acme/shop", "shop");
        assertEquals("", first.stage(DependencyAnalysisStateStore.STAGE_ALIAS_ANSWERS), "nothing known yet");

        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("Shipping Hub", "shipping");
        store.saveProjectAliases("acme/shop", answers.toJson());

        // A later run of the same repository — even by another user, after the checkpoint expired.
        DependencyAnalysisStateStore.State next = store.start("u2", "acme/shop", "shop");
        assertEquals("shipping", AliasResolution.Answers.fromJson(
                next.stage(DependencyAnalysisStateStore.STAGE_ALIAS_ANSWERS)).decisionFor("shipping hub"));

        // A different repository knows nothing of it.
        DependencyAnalysisStateStore.State other = store.start("u1", "acme/bank", "bank");
        assertEquals("", other.stage(DependencyAnalysisStateStore.STAGE_ALIAS_ANSWERS));
    }

    // ---------- the report ----------

    @Test
    void theReportListsTheOperatorsResolutionsByCodeAndSaysNothingWhenThereAreNone() {
        assertEquals("", DependencyReportService.nameResolutionSection(new AliasResolution.Answers()));

        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("Shipping Hub", "shipping");
        answers.put("Netflix Eureka", AliasResolution.IGNORE);
        answers.put("Pricing Engine", AliasResolution.NEW);
        String text = DependencyReportService.nameResolutionSection(answers);

        assertTrue(text.contains("# Names resolved by the operator"));
        assertTrue(text.contains("`Shipping Hub` → `shipping`"));
        assertTrue(text.contains("`Netflix Eureka` ignored"));
        assertTrue(text.contains("`Pricing Engine` added as a new service node"));
        assertTrue(text.contains("not written by the language model"));
    }
}
