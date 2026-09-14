package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.CalibrationSupport.HoldoutRow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the router hold-out set honest, offline: a hold-out sentence that is also an
 * example measures memory, not generalisation. The first calibration set lost 5 of its
 * 33 sentences that way (they were later added as examples), so this is checked by code
 * rather than by care.
 *
 * Duplicates are looked for in two forms — as typed, and as the router sees them after
 * node masking ("frontend 依賴誰" is the example "X 依賴誰") — and both exactly (after
 * normalising case, whitespace and punctuation) and nearly (character-bigram Jaccard).
 */
public class RouterHoldoutDatasetTest {

    /** Above this bigram overlap two sentences are "almost the same". */
    private static final double NEAR_DUPLICATE = 0.8;

    @Test
    void holdoutIsWellFormedAndBalanced() throws Exception {
        List<HoldoutRow> rows = CalibrationSupport.loadHoldout();
        assertTrue(rows.size() >= 90, "about 100 sentences expected, got " + rows.size());

        // "subgraph" is allowed whether or not the intent exists yet.
        Set<String> allowed = new HashSet<>(CalibrationSupport.intentNames());
        allowed.add(CalibrationSupport.NONE);
        allowed.add("subgraph");

        Map<String, Integer> perIntent = new LinkedHashMap<>();
        int zh = 0;
        for (HoldoutRow r : rows) {
            assertTrue(CalibrationSupport.PROJECT_NODES.containsKey(r.project), "unknown project: " + r.project);
            assertTrue(!r.expected.isEmpty(), "no expected intent: " + r.question);
            for (String e : r.expected) {
                assertTrue(allowed.contains(e), "unknown intent '" + e + "' in: " + r.question);
                perIntent.merge(e, 1, Integer::sum);
            }
            if (r.lang().equals("zh")) zh++;
        }
        for (String intent : CalibrationSupport.intentNames()) {
            assertTrue(perIntent.getOrDefault(intent, 0) >= 5, "fewer than 5 sentences for " + intent + ": " + perIntent);
        }
        assertTrue(perIntent.getOrDefault(CalibrationSupport.NONE, 0) >= 8, "too few no-intent sentences: " + perIntent);
        assertTrue(perIntent.getOrDefault("subgraph", 0) >= 8, "too few subgraph sentences: " + perIntent);
        int en = rows.size() - zh;
        assertTrue(Math.abs(zh - en) <= rows.size() / 10, "languages unbalanced: zh " + zh + ", en " + en);
        System.out.println("hold-out: " + rows.size() + " sentences, zh " + zh + " / en " + en + ", per intent " + perIntent);
    }

    @Test
    void nodeIntentsNameEnoughNodesToBeRoutable() throws Exception {
        // A node intent without its node is never confident: such a row would only ever
        // test the planner fallback, silently shrinking the routable sample.
        for (HoldoutRow r : CalibrationSupport.loadHoldout()) {
            if (r.expected.size() != 1) continue;
            SemanticRouter.Intent intent = SemanticRouter.byName(r.expected.iterator().next());
            if (intent == null) continue;
            int named = GraphGrounding.mentionedNodes(r.question, CalibrationSupport.projectGraph(r.project)).size();
            if (intent.arity == 1) assertTrue(named >= 1, "names no node: " + r.question);
            if (intent.arity == 2) assertTrue(named >= 2, "path needs two nodes: " + r.question);
        }
    }

    @Test
    void noHoldoutSentenceRepeatsAnExampleOrAnEarlierCalibrationSentence() throws Exception {
        List<String> examples = new ArrayList<>();
        for (SemanticRouter.Intent i : SemanticRouter.INTENTS) examples.addAll(i.examples);

        DependencyGraph boa = CalibrationSupport.projectGraph("bank-of-anthos");
        List<String> earlier = new ArrayList<>();
        for (String q : earlierCalibrationSentences()) {
            earlier.add(q);
            earlier.add(GraphGrounding.maskMentions(q, GraphGrounding.mentionedNodes(q, boa)));
        }

        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (HoldoutRow r : CalibrationSupport.loadHoldout()) {
            DependencyGraph g = CalibrationSupport.projectGraph(r.project);
            String masked = GraphGrounding.maskMentions(r.question, GraphGrounding.mentionedNodes(r.question, g));
            if (!seen.add(CalibrationSupport.normalize(r.question))) problems.add("repeated within the hold-out: " + r.question);
            for (String form : List.of(r.question, masked)) {
                for (String e : examples) check(problems, form, e, "example");
                for (String e : earlier) check(problems, form, e, "earlier calibration sentence");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void nearDuplicateMeasureBehaves() {
        assertEquals(1.0, bigramJaccard("X 依賴誰？", "x依賴誰"), 1e-9);
        assertTrue(bigramJaccard("哪些邊沒跑到？", "覆蓋率算出來幾趴？") < 0.1);
    }

    private static void check(List<String> problems, String form, String other, String what) {
        if (CalibrationSupport.normalize(form).equals(CalibrationSupport.normalize(other))) {
            problems.add("same as " + what + " \"" + other + "\": " + form);
        } else if (bigramJaccard(form, other) >= NEAR_DUPLICATE) {
            problems.add("nearly the same as " + what + " \"" + other + "\": " + form);
        }
    }

    static double bigramJaccard(String a, String b) {
        Set<String> x = bigrams(CalibrationSupport.normalize(a));
        Set<String> y = bigrams(CalibrationSupport.normalize(b));
        if (x.isEmpty() && y.isEmpty()) return 1.0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        int[] cps = s.codePoints().toArray();
        if (cps.length == 1) out.add(s);
        for (int i = 0; i + 1 < cps.length; i++) out.add(new String(cps, i, 2));
        return out;
    }

    /** The 33 phrasings of the first calibration, read from that test so the two cannot diverge. */
    @SuppressWarnings("unchecked")
    private static Set<String> earlierCalibrationSentences() throws Exception {
        Field f = SemanticRouterCalibrationTest.class.getDeclaredField("HOLDOUT");
        f.setAccessible(true);
        return ((Map<String, String>) f.get(null)).keySet();
    }
}
