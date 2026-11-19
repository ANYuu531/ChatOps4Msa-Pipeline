package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refiner's guards, with a bag-of-tokens embedder (as in {@link SemanticRouterTest})
 * and a scripted model. Thresholds are set so that only an example typed verbatim is
 * confident: every paraphrase is "unsure", and whether the rewording is accepted
 * depends on the guards alone. The real model and embedding are measured separately
 * ({@link QuestionRefinerHoldoutTest}).
 */
public class QuestionRefinerTest {

    private static double[] vec(String text) {
        double[] v = new double[4096];
        for (String t : ChunkRetriever.tokenize(text)) v[Math.floorMod(t.hashCode(), v.length)] += 1;
        return v;
    }

    private static final SemanticRouter.Embedder FAKE = texts -> {
        List<double[]> out = new ArrayList<>();
        for (String t : texts) out.add(vec(t));
        return out;
    };

    /** Only an exact example (cosine 1.0) clears this; any paraphrase is unsure. */
    private static SemanticRouter strictRouter() {
        return new SemanticRouter(FAKE, 0.99, 0.0, 0.995);
    }

    private static DependencyGraph graph() {
        DependencyGraph g = new DependencyGraph("ns");
        for (String s : List.of("frontend", "userservice", "ledgerwriter")) g.addNode(s, DependencyGraph.KIND_SERVICE);
        return g;
    }

    /** The model's rewording answer; the same-meaning judge (second prompt) says "same" unless told otherwise. */
    private static QuestionRefiner.Chat says(String json) {
        return says(json, "{\"same\": true}");
    }

    private static QuestionRefiner.Chat says(String json, String judgeJson) {
        return (system, user) -> QuestionRefiner.isJudgePrompt(system) ? judgeJson : json;
    }

    /** Routes as production does, then refines when the router was unsure. */
    private static QuestionRefiner.Result refine(SemanticRouter router, QuestionRefiner.Chat chat, String question) {
        List<DependencyGraph.Node> mentioned = GraphGrounding.mentionedNodes(question, graph());
        double[] vector = router.routingVector(question, vec(question), mentioned);
        SemanticRouter.Decision unsure = router.route(vector, question, mentioned);
        assertFalse(unsure.confident, "the fixture must start unsure: " + unsure);
        assertTrue(QuestionRefiner.applies(unsure, vector), "fixture must be refinable: " + unsure);
        return new QuestionRefiner(router, FAKE, chat).refine(question, vector, mentioned);
    }

    @Test
    void shortlistIsTheNearestIntentsEachWithItsNearestExamplesBestFirst() {
        SemanticRouter router = strictRouter();
        List<SemanticRouter.Candidate> c = router.shortlist(vec("哪些邊沒跑到？流量沒經過哪些邊？"), 3, 2);
        assertEquals("uncovered", c.get(0).intent.name, c.toString());
        List<String> intents = new ArrayList<>();
        for (SemanticRouter.Candidate x : c) if (!intents.contains(x.intent.name)) intents.add(x.intent.name);
        assertEquals(3, intents.size(), c.toString());
        for (String name : intents) {
            long n = c.stream().filter(x -> x.intent.name.equals(name)).count();
            assertTrue(n >= 1 && n <= 2, name + " has " + n);
        }
        // Grouped: an intent's examples are contiguous.
        String prev = null;
        List<String> seen = new ArrayList<>();
        for (SemanticRouter.Candidate x : c) {
            if (!x.intent.name.equals(prev)) {
                assertFalse(seen.contains(x.intent.name), "intent split in two: " + c);
                seen.add(x.intent.name);
                prev = x.intent.name;
            }
        }
        assertTrue(router.shortlist(null, 3, 2).isEmpty());
    }

    @Test
    void theWholeMenuListsEveryIntentWithTheNearestOnesCarryingMoreExamples() {
        SemanticRouter router = strictRouter();
        List<SemanticRouter.Candidate> nearestOnly = router.shortlist(vec("哪些邊沒跑到？流量沒經過哪些邊？"), 2, 2);
        List<SemanticRouter.Candidate> menu = router.shortlist(vec("哪些邊沒跑到？流量沒經過哪些邊？"), 2, 2, 1);
        List<String> intents = new ArrayList<>();
        for (SemanticRouter.Candidate x : menu) if (!intents.contains(x.intent.name)) intents.add(x.intent.name);
        assertEquals(SemanticRouter.INTENTS.size(), intents.size(), menu.toString());
        assertEquals("uncovered", intents.get(0));
        // The two nearest keep their two examples and come first; every other intent has exactly one.
        assertEquals(nearestOnly.stream().map(x -> x.example).toList(),
                menu.subList(0, nearestOnly.size()).stream().map(x -> x.example).toList());
        for (String name : intents.subList(2, intents.size())) {
            assertEquals(1, menu.stream().filter(x -> x.intent.name.equals(name)).count(), name);
        }
    }

    @Test
    void rejectedWhenTheJudgeSaysTheRewordingAsksSomethingElse() {
        // 11/19 third run: "what is missing from the namespace compared to the code?" came back as
        // "what is documented but not evidenced in code?" — routed cleanly to the intent the model
        // named, so only a comparison with the original question can catch it.
        String q = "流量 沒跑到 的邊 哪些";
        String ok = "{\"intent\": \"uncovered\", \"rewrite\": \"哪些邊沒跑到？\"}";
        QuestionRefiner.Result drift = refine(strictRouter(), says(ok, "{\"same\": false}"), q);
        assertFalse(drift.accepted, drift.toString());
        assertEquals("rewrite drifts from the question", drift.reason);
        assertEquals(Boolean.FALSE, drift.same);
        assertNotNull(drift.rerouted);
        QuestionRefiner.Result silent = refine(strictRouter(), says(ok, "not json"), q);
        assertFalse(silent.accepted, silent.toString());
        assertEquals("no judge answer", silent.reason);
        assertNull(silent.same);
        QuestionRefiner.Result same = refine(strictRouter(), says(ok), q);
        assertTrue(same.accepted, same.toString());
        assertEquals(Boolean.TRUE, same.same);
        // The judge sees both questions and nothing about intents.
        AtomicReference<String> judgeUser = new AtomicReference<>();
        QuestionRefiner.Chat spy = (s, u) -> {
            if (QuestionRefiner.isJudgePrompt(s)) {
                judgeUser.set(u);
                return "{\"same\": true}";
            }
            return ok;
        };
        refine(strictRouter(), spy, q);
        assertEquals("A: " + q + "\nB: 哪些邊沒跑到？", judgeUser.get());
    }

    @Test
    void rejectedWhenTheRewordingChangesLanguage() {
        // 11/19: "can you translate the report into Japanese?" came back as a Chinese about-report question.
        assertTrue(QuestionRefiner.sameLanguage("orders 會呼叫誰？", "orders 的下游有哪些？"));
        assertFalse(QuestionRefiner.sameLanguage("can you translate the report into Japanese?", "這份報告是怎麼產生的？"));
        QuestionRefiner.Result r = refine(strictRouter(), says("{\"intent\": \"uncovered\", \"rewrite\": \"which edges got no traffic?\"}"),
                "流量 沒跑到 的邊 哪些");
        assertFalse(r.accepted, r.toString());
        assertEquals("rewrite changes language", r.reason);
        assertNull(r.rerouted);
    }

    @Test
    void theModelSeesTheMenuNearestFirstAndTheQuestion() {
        SemanticRouter router = strictRouter();
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<String> user = new AtomicReference<>();
        QuestionRefiner.Chat spy = (s, u) -> {
            system.set(s);
            user.set(u);
            return "{\"intent\": \"none\", \"rewrite\": \"\"}";
        };
        refine(router, spy, "流量 沒跑到 的邊 哪些");
        assertEquals("流量 沒跑到 的邊 哪些", user.get());
        assertTrue(system.get().contains("- uncovered\n    \""), system.get());
        assertFalse(system.get().contains("<CANDIDATES>"));
        // The whole menu, nearest first: the right intent is never missing (11/19: 4 of 5 wrong
        // acceptances had it off a 3-intent shortlist), and uncovered leads with its full examples.
        List<String> intents = system.get().lines().filter(l -> l.matches("- [a-z-]+")).map(l -> l.substring(2)).toList();
        assertEquals(SemanticRouter.INTENTS.size(), intents.size(), system.get());
        assertEquals("uncovered", intents.get(0), system.get());
        assertTrue(system.get().lines().filter(l -> l.startsWith("    \"")).count() > intents.size(), system.get());
    }

    @Test
    void acceptedWhenTheRewordingRoutesConfidentlyToTheIntentTheModelNamed() {
        QuestionRefiner.Result r = refine(strictRouter(), says("{\"intent\": \"uncovered\", \"rewrite\": \"哪些邊沒跑到？\"}"),
                "流量 沒跑到 的邊 哪些");
        assertTrue(r.accepted, r.toString());
        assertEquals("uncovered", r.rerouted.intent);
        assertEquals(List.of("uncovered()", "unobserved-edges()"), r.rerouted.queries.stream().map(GraphQuery::toString).toList());
        assertEquals("哪些邊沒跑到？", r.rewrite);
    }

    @Test
    void rejectedWhenTheRewordingRoutesToAnotherIntentThanTheModelNamed() {
        // The model says "uncovered" but words it as the observed-edges example: drift, not accepted.
        QuestionRefiner.Result r = refine(strictRouter(), says("{\"intent\": \"uncovered\", \"rewrite\": \"哪些邊有被觀測到？\"}"),
                "流量 沒跑到 的邊 哪些");
        assertFalse(r.accepted, r.toString());
        assertEquals("rewrite routes to another intent", r.reason);
        assertNotNull(r.rerouted);
    }

    @Test
    void rejectedWhenTheRewordingIsStillUnsure() {
        QuestionRefiner.Result r = refine(strictRouter(), says("{\"intent\": \"uncovered\", \"rewrite\": \"流量 沒跑到 的邊 哪些 呢\"}"),
                "流量 沒跑到 的邊 哪些");
        assertFalse(r.accepted, r.toString());
        assertEquals("rewrite routes unsure", r.reason);
    }

    @Test
    void rejectedWhenTheModelDeclinesLeavesTheShortlistOrAnswersJunk() {
        SemanticRouter router = strictRouter();
        String q = "流量 沒跑到 的邊 哪些";
        assertEquals("model: none of these", refine(router, says("{\"intent\": \"none\", \"rewrite\": \"\"}"), q).reason);
        assertEquals("intent not on the shortlist", refine(router, says("{\"intent\": \"fly-to-mars\", \"rewrite\": \"建議的部署順序是什麼？\"}"), q).reason);
        assertEquals("unparseable answer", refine(router, says("Sure! Here is a rewrite."), q).reason);
        assertEquals("empty rewrite", refine(router, says("{\"intent\": \"uncovered\"}"), q).reason);
        assertEquals("no model answer", refine(router, (s, u) -> null, q).reason);
        assertEquals("no model answer", refine(router, (s, u) -> { throw new IllegalStateException("down"); }, q).reason);
        for (QuestionRefiner.Result r : List.of(refine(router, says("{\"intent\": \"none\", \"rewrite\": \"\"}"), q))) {
            assertFalse(r.accepted);
            assertNull(r.rerouted);
        }
    }

    @Test
    void theRewordingKeepsTheOriginalQuestionsNodesAsArguments() {
        // "frontend 會去打哪些服務" is unsure; the rewording masks to the exact example "X 依賴哪些服務？".
        QuestionRefiner.Result r = refine(strictRouter(), says("{\"intent\": \"dependencies-of\", \"rewrite\": \"frontend 依賴哪些服務？\"}"),
                "frontend 會去打 哪些 服務");
        assertTrue(r.accepted, r.toString());
        assertEquals(List.of("dependencies-of(frontend)"), r.rerouted.queries.stream().map(GraphQuery::toString).toList());
    }

    @Test
    void aQuestionMissingItsNodeOrReadingAsAnEditIsNotRefined() {
        SemanticRouter router = strictRouter();
        // impact-of wants a node; "登入服務" is not an id. Only the planner can map it.
        String q = "如果 登入服務 掛了 會影響誰？";
        SemanticRouter.Decision d = router.route(vec(q), q, List.of());
        assertTrue(d.missingNode, d.toString());
        assertFalse(QuestionRefiner.applies(d, vec(q)));
        assertFalse(QuestionRefiner.applies(d.asEdit(0.9), vec(q)));
        assertFalse(QuestionRefiner.applies(d, null));
        // A zero-arity intent never misses a node.
        String z = "流量 沒跑到 的邊 哪些";
        SemanticRouter.Decision u = router.route(vec(z), z, List.of());
        assertFalse(u.missingNode);
        assertTrue(QuestionRefiner.applies(u, vec(z)));
        // A confident decision has nothing to refine.
        SemanticRouter.Decision sure = router.route(vec("哪些邊沒跑到？"), "哪些邊沒跑到？", List.of());
        assertTrue(sure.confident);
        assertFalse(QuestionRefiner.applies(sure, vec("哪些邊沒跑到？")));
    }

    @Test
    void marginalIsAConfidentRouteWithinTheBandOfTOrMAndNeverAboveH() {
        SemanticRouter router = new SemanticRouter(FAKE, 0.51, 0.10, 0.80);
        SemanticRouter.Decision justOverT = new SemanticRouter.Decision("externals", 0.541, "async", 0.439, true, List.of(), false);
        SemanticRouter.Decision justOverM = new SemanticRouter.Decision("externals", 0.70, "async", 0.598, true, List.of(), false);
        SemanticRouter.Decision comfortable = new SemanticRouter.Decision("externals", 0.70, "async", 0.40, true, List.of(), false);
        SemanticRouter.Decision exempt = new SemanticRouter.Decision("externals", 0.85, "async", 0.80, true, List.of(), false);
        SemanticRouter.Decision unsure = new SemanticRouter.Decision("externals", 0.50, "async", 0.40, false, List.of(), false);
        // The 0.541 case: 0.031 over T, 0.002 over M.
        assertTrue(router.marginal(justOverT, 0.05, 0.0));
        assertTrue(router.marginal(justOverT, 0.0, 0.05));
        assertFalse(router.marginal(justOverT, 0.03, 0.0));
        assertTrue(router.marginal(justOverM, 0.0, 0.05));
        assertFalse(router.marginal(justOverM, 0.05, 0.0));
        assertFalse(router.marginal(comfortable, 0.05, 0.05));
        assertFalse(router.marginal(exempt, 0.30, 0.20));
        assertFalse(router.marginal(unsure, 0.30, 0.20));
        assertFalse(router.marginal(null, 0.30, 0.20));
        // Band zero: off, whatever the route.
        assertFalse(new QuestionRefiner(router, FAKE, says("{}")).marginal(justOverT));
        assertTrue(new QuestionRefiner(router, FAKE, says("{}"), 0.05, 0.05).marginal(justOverT));
    }

    @Test
    void secondOpinionAgreesDisagreesOrFails() {
        SemanticRouter router = strictRouter();
        String q = "哪些邊沒跑到？";
        SemanticRouter.Decision original = router.route(vec(q), q, List.of());
        assertTrue(original.confident);
        QuestionRefiner agree = new QuestionRefiner(router, FAKE, says("{\"intent\": \"uncovered\", \"rewrite\": \"流量沒經過哪些邊？\"}"), 0.05, 0.05);
        assertEquals(QuestionRefiner.Verdict.AGREE, agree.secondOpinion(q, vec(q), List.of(), original).verdict);
        QuestionRefiner disagree = new QuestionRefiner(router, FAKE, says("{\"intent\": \"observed-edges\", \"rewrite\": \"哪些邊有被觀測到？\"}"), 0.05, 0.05);
        assertEquals(QuestionRefiner.Verdict.DISAGREE, disagree.secondOpinion(q, vec(q), List.of(), original).verdict);
        QuestionRefiner none = new QuestionRefiner(router, FAKE, says("{\"intent\": \"none\", \"rewrite\": \"\"}"), 0.05, 0.05);
        assertEquals(QuestionRefiner.Verdict.FAILED, none.secondOpinion(q, vec(q), List.of(), original).verdict);
        QuestionRefiner drift = new QuestionRefiner(router, FAKE, says("{\"intent\": \"uncovered\", \"rewrite\": \"哪些邊有被觀測到？\"}"), 0.05, 0.05);
        assertEquals(QuestionRefiner.Verdict.FAILED, drift.secondOpinion(q, vec(q), List.of(), original).verdict);
    }

    @Test
    void parseTakesTheObjectOutOfAFenceAndNormalisesTheIntent() {
        QuestionRefiner.Proposal p = QuestionRefiner.parse("```json\n{\"intent\": \"Uncovered\", \"rewrite\": \"哪些邊 \\n 沒跑到？\"}\n```");
        assertEquals("uncovered", p.intent);
        assertEquals("哪些邊 沒跑到？", p.rewrite);
        assertEquals("none", QuestionRefiner.parse("{}").intent);
        assertNull(QuestionRefiner.parse("no json here"));
        assertNull(QuestionRefiner.parse(null));
    }
}
