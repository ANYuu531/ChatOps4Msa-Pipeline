package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The router's decision logic, with a fake embedder so no network is needed: the
 * vector of a text is a bag of its tokens, so a question shares a direction with the
 * examples it shares words with. The real embedding is calibrated separately
 * ({@link SemanticRouterCalibrationTest}); these tests pin thresholds, margins,
 * arity handling and the negation rule.
 */
public class SemanticRouterTest {

    /** Bag-of-tokens vectors over a fixed vocabulary, so cosine is a word-overlap measure. */
    private static final List<String> VOCAB = new ArrayList<>();

    private static double[] vec(String text) {
        List<String> tokens = ChunkRetriever.tokenize(text);
        for (String t : tokens) if (!VOCAB.contains(t)) VOCAB.add(t);
        double[] v = new double[4096];
        for (String t : tokens) v[Math.floorMod(t.hashCode(), v.length)] += 1;
        return v;
    }

    private static final SemanticRouter.Embedder FAKE = texts -> {
        List<double[]> out = new ArrayList<>();
        for (String t : texts) out.add(vec(t));
        return out;
    };

    private static DependencyGraph graph() {
        DependencyGraph g = new DependencyGraph("ns");
        for (String s : List.of("frontend", "userservice", "ledgerwriter")) g.addNode(s, DependencyGraph.KIND_SERVICE);
        return g;
    }

    private static SemanticRouter.Decision route(SemanticRouter router, String question) {
        return router.route(vec(question), question, GraphGrounding.mentionedNodes(question, graph()));
    }

    @Test
    void exactExampleRoutesToItsIntentWithItsArguments() {
        SemanticRouter router = new SemanticRouter(FAKE, 0.3, 0.0, 0.9);
        SemanticRouter.Decision d = route(router, "如果 userservice 掛了會影響誰？");
        assertTrue(d.confident, d.toString());
        assertEquals("impact-of", d.intent);
        assertEquals(List.of("impact-of(userservice)"), d.queries.stream().map(GraphQuery::toString).toList());
    }

    @Test
    void zeroArityIntentExpandsToItsQueries() {
        SemanticRouter router = new SemanticRouter(FAKE, 0.3, 0.0, 0.9);
        SemanticRouter.Decision d = route(router, "哪些邊沒跑到？");
        assertTrue(d.confident, d.toString());
        assertEquals(List.of("uncovered()", "unobserved-edges()"), d.queries.stream().map(GraphQuery::toString).toList());
    }

    @Test
    void negatedObservedBecomesUncovered() {
        SemanticRouter router = new SemanticRouter(FAKE, 0.3, 0.0, 0.9);
        // Word overlap pulls this toward "observed-edges"; the negation flips it.
        SemanticRouter.Decision d = route(router, "哪些邊沒有被觀測到？實線的邊有哪些？");
        assertEquals("uncovered", d.intent, d.toString());
    }

    @Test
    void nodeIntentWithoutANamedNodeIsNotConfident() {
        SemanticRouter router = new SemanticRouter(FAKE, 0.3, 0.0, 0.9);
        SemanticRouter.Decision d = route(router, "如果登入服務掛了會影響誰？");
        assertEquals("impact-of", d.intent);
        assertFalse(d.confident, "no node to put in impact-of(...); the model planner may resolve it");
        assertTrue(d.queries.isEmpty());
    }

    @Test
    void pathNeedsTwoNodes() {
        SemanticRouter router = new SemanticRouter(FAKE, 0.3, 0.0, 0.9);
        SemanticRouter.Decision two = route(router, "frontend 怎麼連到 ledgerwriter？");
        assertEquals(List.of("path(frontend, ledgerwriter)"), two.queries.stream().map(GraphQuery::toString).toList());
        SemanticRouter.Decision one = route(router, "frontend 怎麼連到 Y？");
        assertFalse(one.confident);
    }

    @Test
    void aboutReportSkipsThePlannerWithNoQueries() {
        SemanticRouter router = new SemanticRouter(FAKE, 0.3, 0.0, 0.9);
        SemanticRouter.Decision d = route(router, "這份報告有查過叢集嗎？");
        assertTrue(d.confident, d.toString());
        assertTrue(d.skipLlm);
        assertTrue(d.queries.isEmpty());
    }

    @Test
    void lowScoreOrThinMarginIsNotConfident() {
        // A threshold no cosine can reach: even an exact example is "unsure".
        SemanticRouter strict = new SemanticRouter(FAKE, 1.01, 0.5, 1.5);
        SemanticRouter.Decision d = route(strict, "哪些邊沒跑到？");
        assertFalse(d.confident);
        assertFalse(d.skipLlm);

        // Reachable threshold but an impossible margin: an exact match still fails on ambiguity.
        SemanticRouter thin = new SemanticRouter(FAKE, 0.3, 1.5, 1.5);
        assertFalse(route(thin, "哪些邊沒跑到？").confident);

        SemanticRouter none = new SemanticRouter(FAKE, 0.3, 0.0, 0.9);
        SemanticRouter.Decision unrelated = route(none, "zzzz qqqq");
        assertFalse(unrelated.confident);
    }

    @Test
    void unavailableEmbedderNeverRoutes() {
        SemanticRouter router = new SemanticRouter(texts -> null);
        assertFalse(router.ensureIndexed());
        SemanticRouter.Decision d = router.route(new double[]{1, 0}, "哪些邊沒跑到？", List.of());
        assertFalse(d.confident);
        assertEquals("none", d.intent);
    }
}
