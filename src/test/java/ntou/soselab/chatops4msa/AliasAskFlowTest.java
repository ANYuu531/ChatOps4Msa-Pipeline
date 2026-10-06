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
import java.util.Map;
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

    @Test
    void theAnswerMayNameAnyServiceOnTheGraphNotOnlyACandidate() {
        // First Discord run: "discovery" was the right answer for cloud-eureka-server and
        // was in no candidate list, so the form rejected it as not understood.
        AliasResolution.Questions questions = new AliasResolution.Questions();
        questions.add("cloud-eureka-server", "code", "config", List.of("cloud-config-server", "cloud-simple-ui"));
        questions.addVocabulary(List.of("discovery", "gateway", "simple-serviceb"));
        AliasResolution.Question q = questions.list().get(0);
        assertFalse(q.candidates.contains("discovery"));

        java.util.Set<String> vocabulary = AliasResolution.Questions.vocabularyFromJson(
                AliasResolution.Questions.vocabularyToJson(questions.vocabulary()));
        assertTrue(vocabulary.contains("discovery"));
        assertTrue(vocabulary.contains("cloud-config-server"), "candidates are part of it");

        assertEquals("discovery", AliasResolution.parseAnswer("discovery", q, vocabulary));
        assertEquals("simple-serviceb", AliasResolution.parseAnswer("simple-serviceB", q, vocabulary));
        assertNull(AliasResolution.parseAnswer("eureka", q, vocabulary), "not a service on the graph");

        assertTrue(AliasResolution.looksLikeServiceId("discovery"));
        assertFalse(AliasResolution.looksLikeServiceId("not a service"));
        assertTrue(AliasResolution.Questions.vocabularyFromJson("garbage").isEmpty());
    }

    @Test
    void aStaticRunSaysCoverageWasNotMeasuredInsteadOfZeroPercent() {
        // spring-cloud-microservice, greenfield, 2026-10-06: the runtime message said
        // "Istio observed 0 / 23 … 0%" and "the datastore is deployed" with no cluster at all.
        DependencyGraph g = new DependencyGraph("");
        g.addNode("gateway", DependencyGraph.KIND_SERVICE);
        g.addNode("simple-service", DependencyGraph.KIND_SERVICE);
        g.addNode("mysql", DependencyGraph.KIND_DB);
        g.addEdge("gateway", "simple-service", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: application.yaml");
        g.addEdge("simple-service", "mysql", "db", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: application.yaml");

        String text = DependencyReportService.staticCoverageMessage(g, "zpng/spring-cloud-microservice-examples");

        assertNotNull(text);
        assertTrue(text.contains("Not measured"));
        assertTrue(text.contains("unknown, not 0%"));
        assertTrue(text.contains("gateway -> simple-service"));
        assertFalse(text.contains("0%  runtime"), text);
        assertFalse(text.contains("0 / 1"), "no ratio for something never measured");
        assertFalse(text.contains("Istio observed"), "no telemetry was collected");
        assertFalse(text.contains("the datastore is deployed"), "nothing was deployed or queried");
    }

    // ---------- the first Discord run's graph (2026-10-06) ----------

    /** What the code layer drew for spring-cloud-microservice: module names beside Compose names. */
    private static DependencyGraph moduleAndComposeNames() {
        DependencyGraph g = new DependencyGraph("");
        for (String s : new String[]{"gateway", "simple-service", "simple-service2", "simple-serviceb",
                "simple-ui", "configserver", "discovery", "zipkin",
                "cloud-simple-service", "cloud-simple-serviceb", "cloud-simple-ui", "cloud-config-server"}) {
            g.addNode(s, DependencyGraph.KIND_SERVICE);
        }
        g.addNode("mysql", DependencyGraph.KIND_DB);
        g.addEdge("gateway", "simple-service", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "compose");
        g.addEdge("gateway", "cloud-simple-service", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "route");
        g.addEdge("gateway", "cloud-simple-serviceb", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "route");
        g.addEdge("gateway", "cloud-simple-ui", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "route");
        g.addEdge("cloud-simple-service", "mysql", "db", DependencyGraph.PROV_DOC, DependencyGraph.CONF_DOCUMENTED, false, 0, "pom");
        g.addEdge("cloud-config-server", "discovery", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "bootstrap");
        g.addEdge("simple-service2", "discovery", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "compose");
        return g;
    }

    @Test
    void anAnswerAlsoRenamesANodeTheCodeLayerHadAlreadyDrawn() {
        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("cloud-simple-serviceB", "simple-serviceb");
        DependencyGraph g = moduleAndComposeNames();

        GraphNormalizer.normalize(g, answers, null);

        assertNull(g.findNode("cloud-simple-serviceb"), "the operator said it is simple-serviceb");
        assertTrue(g.getEdges().stream().anyMatch(e -> e.source.equals("gateway") && e.target.equals("simple-serviceb")));
    }

    @Test
    void twoNamesThatDifferOnlyByAPrefixAreAskedAboutAndBothStayUntilAnswered() {
        DependencyGraph g = moduleAndComposeNames();
        AliasResolution.Questions questions = new AliasResolution.Questions();

        GraphNormalizer.normalize(g, null, questions);

        Map<String, AliasResolution.Question> byName = new java.util.LinkedHashMap<>();
        for (AliasResolution.Question q : questions.list()) byName.put(q.name, q);
        assertEquals(Set.of("cloud-simple-service", "cloud-simple-serviceb", "cloud-simple-ui", "cloud-config-server"),
                byName.keySet(), "and not simple-service2 / simple-serviceb against simple-service");
        assertEquals("simple-service", byName.get("cloud-simple-service").candidates.get(0));
        assertEquals("configserver", byName.get("cloud-config-server").candidates.get(0));
        assertEquals("graph", byName.get("cloud-simple-ui").origin);
        // Nothing merged by rule.
        assertNotNull(g.findNode("cloud-simple-service"));
        assertNotNull(g.findNode("simple-service"));

        // Answered "1": folded onto the deployment name, edges and all.
        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("cloud-simple-service", "simple-service");
        answers.put("cloud-config-server", "configserver");
        answers.put("cloud-simple-ui", AliasResolution.NEW);   // "they are different": keep both, stop asking
        DependencyGraph g2 = moduleAndComposeNames();
        AliasResolution.Questions again = new AliasResolution.Questions();
        GraphNormalizer.normalize(g2, answers, again);
        assertNull(g2.findNode("cloud-simple-service"));
        assertTrue(g2.getEdges().stream().anyMatch(e -> e.source.equals("simple-service") && e.target.equals("mysql")));
        assertTrue(g2.getEdges().stream().anyMatch(e -> e.source.equals("configserver") && e.target.equals("discovery")));
        assertNotNull(g2.findNode("cloud-simple-ui"));
        assertEquals(List.of("cloud-simple-serviceb"), again.list().stream().map(q -> q.name).toList(),
                "only the unanswered one is still asked");
    }

    @Test
    void aBrowserCallToARelativeUrlIsNotACallToAnotherService() {
        String js = "cloud-simple-ui/src/main/resources/static/js/app.js";
        assertTrue(CodeGraphMerger.isRelativeBrowserCall("http-client", "users", js));
        assertTrue(CodeGraphMerger.isRelativeBrowserCall("http-client", "/api/orders", js));
        assertTrue(CodeGraphMerger.isRelativeBrowserCall("http-client", "./data.json", "web/index.html"));
        assertFalse(CodeGraphMerger.isRelativeBrowserCall("http-client", "http://cloud-simple-service/user", js));
        assertFalse(CodeGraphMerger.isRelativeBrowserCall("http-client", "${API_URL}/users", js), "a placeholder may resolve");
        assertFalse(CodeGraphMerger.isRelativeBrowserCall("http-client", "users", "src/UserClient.java"), "server code is not a browser");
        assertFalse(CodeGraphMerger.isRelativeBrowserCall("url", "users", js));

        // End to end through the merge: no "users" node.
        String ledger = "{\"edges\":[{\"section\":\"http-client\",\"fields\":{\"url\":\"users\",\"method\":\"GET\"},"
                + "\"file\":\"" + js + "\",\"line\":7}]}";
        DependencyGraph g = new DependencyGraph("");
        g.addNode("simple-ui", DependencyGraph.KIND_SERVICE);
        List<CodeGraphMerger.Unresolved> rest = CodeGraphMerger.merge(g, ledger, "zpng/spring-cloud-microservice-examples");
        assertNull(g.findNode("users"));
        assertTrue(rest.isEmpty(), "not a residue either: there is nothing to resolve");
    }

    @Test
    void aStaticReportGivesTheRealReasonDeploymentIsUnknown() {
        DependencyGraph g = new DependencyGraph("");
        g.addNode("simple-service", DependencyGraph.KIND_SERVICE);
        g.addNode("mysql", DependencyGraph.KIND_DB);
        g.addEdge("simple-service", "mysql", "db", DependencyGraph.PROV_DOC, DependencyGraph.CONF_DOCUMENTED, false, 0, "pom");

        String section = DependencyReportService.infrastructureSection(g, true);
        assertTrue(section.contains("Not determined (static run: no cluster was queried)"));
        assertTrue(section.contains("Runtime observed: Unknown (static run: not measured)"),
                "nothing was measured, so 'No' would be a measurement that never happened");
        assertFalse(section.contains("Runtime observed: No"));
        assertFalse(section.contains("StatefulSet"));

        // Section 4 does not call a statically declared edge HTTP: the graph never recorded that.
        g.addNode("thrift-client", DependencyGraph.KIND_SERVICE);
        g.addEdge("thrift-client", "simple-service", "sync-http", DependencyGraph.PROV_DOC, DependencyGraph.CONF_DOCUMENTED, false, 0, "doc: UserController.java");
        g.addEdge("gateway-x", "simple-service", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 12, "istio");
        g.addNode("gateway-x", DependencyGraph.KIND_SERVICE);
        String four = DependencyReportService.synchronousSection(g, false);
        assertTrue(four.contains("Protocol: not determined"), four);
        assertTrue(four.contains("Protocol: HTTP (observed by the mesh)"), four);
        assertFalse(four.contains("Protocol: HTTP\n"));
        assertTrue(DependencyReportService.infrastructureSection(g, false).contains("StatefulSet"),
                "the runtime wording is unchanged");
    }

    @Test
    void theReportPromptCarriesTheGraphNamesAndTheOperatorsAnswers() {
        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("cloud-simple-serviceB", "simple-serviceb");
        answers.put("MyAppThriftClient", AliasResolution.IGNORE);
        answers.put("cloud-dummy-service", AliasResolution.NEW);

        String text = DependencyReportService.serviceNamesForPrompt(moduleAndComposeNames(), answers);

        assertTrue(text.contains("## Service names on the graph"));
        assertTrue(text.contains("simple-serviceb"));
        assertFalse(text.contains("mysql"), "only services");
        assertTrue(text.contains("- cloud-simple-serviceB = simple-serviceb"));
        assertTrue(text.contains("- MyAppThriftClient = ignored (not a service)"));
        assertTrue(text.contains("- cloud-dummy-service = cloud-dummy-service (a service of its own)"));
        assertEquals("", DependencyReportService.serviceNamesForPrompt(null, null));
    }

    @Test
    void aWrongAnswerCanBeTakenBackByForgettingTheRepositorysAnswers(@TempDir Path dir) {
        DependencyAnalysisStateStore store = new DependencyAnalysisStateStore(dir.toString(), 24);
        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("Zipkin Server", AliasResolution.NEW);   // should have been "zipkin"
        store.saveProjectAliases("zpng/spring-cloud-microservice-examples", answers.toJson());

        assertTrue(store.removeProjectAliases("zpng/spring-cloud-microservice-examples"));
        assertFalse(store.removeProjectAliases("zpng/spring-cloud-microservice-examples"), "nothing left");
        assertEquals("", store.start("u1", "zpng/spring-cloud-microservice-examples", "none")
                .stage(DependencyAnalysisStateStore.STAGE_ALIAS_ANSWERS), "the next run asks again");
    }

    // ---------- the second Discord run (2026-10-06) ----------

    @Test
    void answeringAQuestionWithItsOwnNameMeansAServiceOfItsOwn() {
        AliasResolution.Questions questions = new AliasResolution.Questions();
        questions.add("hystrix", "code", "library name with edges", Set.of("gateway"));
        AliasResolution.Question q = questions.list().get(0);
        assertEquals(AliasResolution.NEW, AliasResolution.parseAnswer("hystrix", q, Set.of("hystrix", "gateway")));
        assertEquals(AliasResolution.NEW, AliasResolution.parseAnswer("Hystrix", q, Set.of()));

        // An answers file written before this ("hystrix" -> "hystrix") is read as NEW.
        AliasResolution.Answers old = AliasResolution.Answers.fromJson("[{\"name\":\"hystrix\",\"decision\":\"hystrix\"}]");
        assertEquals(AliasResolution.NEW, old.decisionFor("hystrix"));
        assertTrue(DependencyReportService.nameResolutionSection(old).contains("`hystrix` kept as its own service node"));
    }

    @Test
    void aHostThatIsAStringConstantOfTheSameClassIsResolved() {
        // cloud-simple-ui's UserService: "http://" + SERVICE_NAME + "/user".
        String java = """
                public class UserService {
                    final String SERVICE_NAME = "cloud-simple-service";
                    public List<User> readUserInfo() {
                        return restTemplate.getForObject("http://" + SERVICE_NAME + "/user", List.class);
                    }
                }
                """;
        ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger ledger =
                new ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger();
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("method", "getForObject");
        fields.put("scheme", "http://");
        fields.put("hostref", "SERVICE_NAME");
        ledger.add("http-client", fields, "cloud-simple-ui/src/main/java/UserService.java", 4, "High");
        Map<String, String> unknown = new java.util.LinkedHashMap<>();
        unknown.put("method", "getForObject");
        unknown.put("scheme", "http://");
        unknown.put("hostref", "OTHER_HOST");   // not declared here: left alone
        ledger.add("http-client", unknown, "cloud-simple-ui/src/main/java/UserService.java", 5, "High");

        ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.TreeSitterExtractor.resolveConstantHosts(
                ledger, Map.of("cloud-simple-ui/src/main/java/UserService.java", java));

        assertEquals("http://cloud-simple-service", ledger.getEdges().get(0).fields.get("url"));
        assertFalse(ledger.getEdges().get(1).fields.containsKey("url"));
    }

    @Test
    void sectionFourIsWrittenFromTheGraphAndTheModelsVersionIsDropped() {
        DependencyGraph g = new DependencyGraph("");
        for (String s : new String[]{"gateway", "simple-service", "simple-ui"}) g.addNode(s, DependencyGraph.KIND_SERVICE);
        g.addNode("mysql", DependencyGraph.KIND_DB);
        g.addEdge("gateway", "simple-service", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: application.yaml");
        g.addEdge("simple-ui", "simple-service", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: UserService.java:32");
        g.addEdge("simple-service", "mysql", "db", DependencyGraph.PROV_DOC, DependencyGraph.CONF_DOCUMENTED, false, 0, "doc: pom.xml");

        String llm = """
                # 3. Components Observed in the Kubernetes Namespace
                N/A

                # 4. Synchronous Dependency Candidates
                ### Candidate: MyAppThriftClient -> FooService
                - Runtime observed: Unknown

                # 6. Asynchronous Communication
                None.
                """;
        String out = DependencyReportService.spliceFactSections(llm, g, true);

        assertFalse(out.contains("MyAppThriftClient"), "the model's section 4 is gone");
        assertTrue(out.contains("### Candidate: simple-ui -> simple-service"));
        assertTrue(out.contains("### Candidate: gateway -> simple-service"));
        assertFalse(out.contains("Candidate: simple-service -> mysql"), "a database edge is section 5's");
        assertTrue(out.contains("Runtime observation: not measured"));
        assertTrue(out.indexOf("# 3.") < out.indexOf("# 4.") && out.indexOf("# 4.") < out.indexOf("# 5.")
                && out.indexOf("# 5.") < out.indexOf("# 6."));
        assertEquals(1, out.split("# 4\\. Synchronous", -1).length - 1, "exactly one section 4");
    }

    @Test
    void theModelsProseUsesTheGraphsNamesButPathsAndFilesAreLeftAlone() {
        DependencyGraph g = new DependencyGraph("");
        for (String s : new String[]{"gateway", "turbine", "hystrix", "discovery", "simple-service", "simple-serviceb"}) {
            g.addNode(s, DependencyGraph.KIND_SERVICE);
        }
        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("Eureka Server", "discovery");
        answers.put("MyAppThriftClient", AliasResolution.IGNORE);

        String prose = "Broker relationship: hystrix-turbine -> RabbitMQ\n"
                + "Workflow: cloud-api-gateway routes to cloud-simple-serviceB via Eureka Server.\n"
                + "Endpoint: /cloud-simple-service/**\n"
                + "Provenance reference: cloud-api-gateway/src/main/resources/application.yaml lines 5-6\n"
                + "See pom.xml dependencies in cloud-simple-serviceB/pom.xml\n"
                + "MyAppThriftClient calls FooService.";

        String out = DependencyReportService.normalizeServiceNames(prose, g, answers);

        assertTrue(out.contains("Broker relationship: turbine -> RabbitMQ"), out);
        assertTrue(out.contains("Workflow: gateway routes to simple-serviceb via discovery."), out);
        assertTrue(out.contains("Endpoint: /cloud-simple-service/**"), "a route path is what it is called");
        assertTrue(out.contains("cloud-api-gateway/src/main/resources/application.yaml"), "a file path is left alone");
        assertTrue(out.contains("cloud-simple-serviceB/pom.xml"));
        assertTrue(out.contains("MyAppThriftClient calls FooService."), "an ignored name is not rewritten");
    }

    @Test
    void severalNamesInOneWikiFieldAreResolvedOneByOne() {
        assertEquals(List.of("cloud-simple-service", "cloud-simple-serviceB"),
                DocGraphMerger.splitNames("cloud-simple-service, cloud-simple-serviceB"));
        assertEquals(List.of("a", "b", "c"), DocGraphMerger.splitNames("a; b and c"));
        assertEquals(List.of("simple-service"), DocGraphMerger.splitNames("simple-service"));
        assertTrue(DocGraphMerger.splitNames("").isEmpty());

        // The fourth Discord run: the consumers "cloud-simple-service, cloud-simple-serviceB"
        // were asked about as one name although both had been answered already.
        DependencyGraph g = new DependencyGraph("");
        for (String s : new String[]{"configserver", "simple-service", "simple-serviceb", "cloud-simple-service"}) {
            g.addNode(s, DependencyGraph.KIND_SERVICE);
        }
        AliasResolution.Answers answers = new AliasResolution.Answers();
        answers.put("cloud-config-server", "configserver");
        answers.put("cloud-simple-service", "simple-service");
        answers.put("cloud-simple-serviceB", "simple-serviceb");
        AliasResolution.Questions questions = new AliasResolution.Questions();
        String notes = "{ \"asynchronous_workflows\": [ { \"producer\": \"cloud-config-server\", \"broker\": \"RabbitMQ\","
                + " \"consumer\": \"cloud-simple-service, cloud-simple-serviceB\" } ] }";

        DocGraphMerger.merge(g, notes, answers, questions);

        assertTrue(questions.isEmpty(), "every name in the list was answered: " + questions.list().stream().map(q -> q.name).toList());
        assertTrue(g.getEdges().stream().anyMatch(e -> e.source.equals("configserver") && e.target.equals("rabbitmq")));
        assertTrue(g.getEdges().stream().anyMatch(e -> e.source.equals("rabbitmq") && e.target.equals("simple-service")));
        assertTrue(g.getEdges().stream().anyMatch(e -> e.source.equals("rabbitmq") && e.target.equals("simple-serviceb")));

        // A candidate is offered under the name it will have, not one about to be retired.
        AliasResolution.Questions again = new AliasResolution.Questions();
        DocGraphMerger.merge(g, "{ \"synchronous_candidates\": [ { \"source\": \"configserver\", \"target\": \"Simple Svc\", \"dependency_type\": \"rest\" } ] }",
                answers, again);
        assertEquals(1, again.size());
        assertFalse(again.list().get(0).candidates.contains("cloud-simple-service"), again.list().get(0).candidates.toString());
    }

    @Test
    void theRepositoryItselfNamedAsAComponentIsNeitherAServiceNorAQuestion() {
        // Fifth Discord run: the wiki made "zpng/spring-cloud-microservice-examples" the
        // source of five infrastructure dependencies, and the tool asked which service it is.
        DependencyGraph g = new DependencyGraph("");
        g.addNode("gateway", DependencyGraph.KIND_SERVICE);
        AliasResolution.Questions questions = new AliasResolution.Questions();
        String notes = "{ \"infrastructure_dependencies\": ["
                + "{ \"source_component\": \"zpng/spring-cloud-microservice-examples\", \"target\": \"MySQL\", \"dependency_type\": \"database\" },"
                + "{ \"source_component\": \"spring-cloud-microservice-examples\", \"target\": \"RabbitMQ\", \"dependency_type\": \"queue\" },"
                + "{ \"source_component\": \"gateway\", \"target\": \"RabbitMQ\", \"dependency_type\": \"queue\", \"configured\": \"yes\" } ] }";

        DocGraphMerger.merge(g, notes, null, questions, "zpng/spring-cloud-microservice-examples");

        assertTrue(questions.isEmpty(), questions.list().stream().map(q -> q.name).toList().toString());
        assertNull(g.findNode("zpng/spring-cloud-microservice-examples"));
        assertTrue(g.getEdges().stream().noneMatch(e -> e.source.contains("spring-cloud-microservice-examples")),
                "a repository is not the source of an edge");
        assertTrue(g.getEdges().stream().anyMatch(e -> e.source.equals("gateway") && e.target.equals("rabbitmq")),
                "a real service's edge in the same list still merges");
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
        assertTrue(text.contains("`Pricing Engine` kept as its own service node"));
        assertTrue(text.contains("not written by the language model"));
    }
}
