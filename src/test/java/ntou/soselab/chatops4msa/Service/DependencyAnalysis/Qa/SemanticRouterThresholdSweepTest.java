package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.CalibrationSupport.EmbeddingCache;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.CalibrationSupport.HoldoutRow;
import ntou.soselab.chatops4msa.Service.NLPService.EmbeddingClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sweeps the semantic router's three thresholds over a grid and picks them by a rule
 * written down here, instead of by eye.
 *
 * The experiment ({@link #sweepRouterThresholds}) runs only with {@code -Dqa.calibrate=true}.
 * Every text is embedded once and cached ({@link EmbeddingCache}); each hold-out
 * question is scored ONCE, and the grid replays those scores through
 * {@link SemanticRouter#decide} — the function production routes with — so the sweep
 * cannot drift from what the router does, and a re-run costs no API call.
 *
 * <b>Why the objective is asymmetric.</b> The two ways to be wrong do not cost the same.
 * Too strict: the router defers, and the model planner (one small extra call) plans the
 * query — the answer is still grounded. Too loose: the router is confidently WRONG, the
 * planner is skipped, and the answer is built from the wrong graph query — facts the
 * question needed are simply absent. So: confident-wrong must be zero, and within that,
 * cover as much as possible.
 *
 * <b>Selection rule</b> ({@link #select}): among the grid points with at most
 * {@code maxWrong} confident-wrong rows, the highest coverage; ties go to fewer wrong,
 * then the HIGHEST threshold, the LARGEST margin, the HIGHEST high — the conservative
 * end of an equally good plateau, since a sample this small does not locate the edge of
 * that plateau precisely.
 *
 * The offline tests below pin the metric and the rule on synthetic scores.
 */
public class SemanticRouterThresholdSweepTest {

    static final double[] HIGHS = {0.80, 0.85, 0.90, 0.95, 1.01};

    /** One hold-out question, scored once. */
    static final class Scored {
        final HoldoutRow row;
        final Map<String, Double> scores;
        final List<DependencyGraph.Node> mentioned;
        final String masked;
        /** Some expected intent exists in the router; otherwise the row is scored like "none". */
        final boolean routable;
        /** The intent after the rules; threshold-independent. */
        final boolean top1;

        Scored(HoldoutRow row, Map<String, Double> scores, List<DependencyGraph.Node> mentioned, String masked) {
            this.row = row;
            this.scores = scores;
            this.mentioned = mentioned;
            this.masked = masked;
            Set<String> names = CalibrationSupport.intentNames();
            boolean r = false;
            for (String e : row.expected) if (names.contains(e)) r = true;
            this.routable = r;
            SemanticRouter.Decision d = SemanticRouter.decide(scores, row.question, mentioned, 0, 0, 0);
            this.top1 = routable && row.expected.contains(d.intent);
        }

        boolean confident(double t, double m, double h) {
            return SemanticRouter.decide(scores, row.question, mentioned, t, m, h).confident;
        }

        boolean hasNode() {
            return !mentioned.isEmpty();
        }
    }

    /** Aggregate outcome over a set of rows at one grid point. */
    static final class Metrics {
        int n, routable, confident, confidentCorrect, confidentWrong, top1Correct;

        void add(Scored s, boolean conf) {
            n++;
            if (s.routable) routable++;
            if (s.top1) top1Correct++;
            if (conf) {
                confident++;
                if (s.top1) confidentCorrect++;
                else confidentWrong++;   // wrong intent, or any confident route on a no-intent row
            }
        }

        /** Share of ALL questions the router answered without the planner. */
        double coverage() {
            return ratio(confident, n);
        }

        double confidentWrongRate() {
            return ratio(confidentWrong, n);
        }

        double precision() {
            return ratio(confidentCorrect, confident);
        }

        /** Nearest-intent accuracy over the rows that have an intent; independent of the thresholds. */
        double top1Accuracy() {
            return ratio(top1Correct, routable);
        }

        /** Share of the answerable questions routed confidently AND correctly. */
        double usefulCoverage() {
            return ratio(confidentCorrect, routable);
        }

        private static double ratio(int a, int b) {
            return b == 0 ? Double.NaN : (double) a / b;
        }
    }

    static final class GridPoint {
        final double t, m, h;
        /** confident[i] for row i. */
        final boolean[] confident;

        GridPoint(double t, double m, double h, List<Scored> rows) {
            this.t = t;
            this.m = m;
            this.h = h;
            this.confident = new boolean[rows.size()];
            for (int i = 0; i < rows.size(); i++) confident[i] = rows.get(i).confident(t, m, h);
        }

        Metrics metrics(List<Scored> rows, Predicate<Scored> subset) {
            Metrics out = new Metrics();
            for (int i = 0; i < rows.size(); i++) if (subset.test(rows.get(i))) out.add(rows.get(i), confident[i]);
            return out;
        }

        String label() {
            return String.format(Locale.ROOT, "T=%.2f M=%.2f H=%s", t, m, h > 1 ? "off" : String.format(Locale.ROOT, "%.2f", h));
        }
    }

    static List<GridPoint> grid(List<Scored> rows) {
        List<GridPoint> out = new ArrayList<>();
        for (int ti = 40; ti <= 85; ti++) {
            for (int mi = 0; mi <= 12; mi++) {
                for (double h : HIGHS) out.add(new GridPoint(ti / 100.0, mi / 100.0, h, rows));
            }
        }
        return out;
    }

    /**
     * THE selection rule. Returns {@code null} when no grid point keeps confident-wrong
     * within {@code maxWrong} on the given rows.
     */
    static GridPoint select(List<GridPoint> grid, List<Scored> rows, Predicate<Scored> subset, int maxWrong) {
        GridPoint best = null;
        Metrics bestM = null;
        for (GridPoint g : grid) {
            Metrics m = g.metrics(rows, subset);
            if (m.confidentWrong > maxWrong) continue;
            if (best == null || better(g, m, best, bestM)) {
                best = g;
                bestM = m;
            }
        }
        return best;
    }

    private static boolean better(GridPoint g, Metrics m, GridPoint b, Metrics bm) {
        if (m.confident != bm.confident) return m.confident > bm.confident;
        if (m.confidentWrong != bm.confidentWrong) return m.confidentWrong < bm.confidentWrong;
        if (g.t != b.t) return g.t > b.t;
        if (g.m != b.m) return g.m > b.m;
        return g.h > b.h;
    }

    // ---------- the experiment ----------

    @Test
    void sweepRouterThresholds() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("qa.calibrate")), "pass -Dqa.calibrate=true to run");
        Properties p = CalibrationSupport.applicationProperties();
        String model = CalibrationSupport.embeddingModel(p);
        EmbeddingClient client = CalibrationSupport.clientOrNull(p);
        EmbeddingCache cache = new EmbeddingCache(CalibrationSupport.CACHE_FILE, model, client == null ? null : client::embed);

        List<HoldoutRow> holdout = CalibrationSupport.loadHoldout();
        List<String> exampleTexts = new ArrayList<>();
        for (SemanticRouter.Intent i : SemanticRouter.INTENTS) exampleTexts.addAll(i.examples);

        // Every text the run needs — examples, questions as typed, questions masked — in one batch.
        List<String> masked = new ArrayList<>();
        List<List<DependencyGraph.Node>> mentioned = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>(exampleTexts);
        for (HoldoutRow r : holdout) {
            List<DependencyGraph.Node> named = GraphGrounding.mentionedNodes(r.question, CalibrationSupport.projectGraph(r.project));
            mentioned.add(named);
            String m = GraphGrounding.maskMentions(r.question, named);
            masked.add(m);
            all.add(r.question);
            all.add(m);
        }
        List<String> allTexts = new ArrayList<>(all);
        Assumptions.assumeTrue(client != null || cache.covers(allTexts),
                "no openai.api.key and the embedding cache does not cover the hold-out set");
        assertNotNull(cache.embed(allTexts), "could not embed the examples and the hold-out set");

        double curT = Double.parseDouble(p.getProperty("dependency.qa.router.threshold", String.valueOf(SemanticRouter.DEFAULT_THRESHOLD)));
        double curM = Double.parseDouble(p.getProperty("dependency.qa.router.margin", String.valueOf(SemanticRouter.DEFAULT_MARGIN)));
        double curH = Double.parseDouble(p.getProperty("dependency.qa.router.high", String.valueOf(SemanticRouter.DEFAULT_HIGH)));

        SemanticRouter router = new SemanticRouter(cache, curT, curM, curH);
        assertTrue(router.ensureIndexed(), "could not index the examples");

        List<Scored> rows = new ArrayList<>();
        for (int i = 0; i < holdout.size(); i++) {
            HoldoutRow r = holdout.get(i);
            double[] raw = cache.embed(List.of(r.question)).get(0);
            // Exactly what routeQuestion does: the masked vector when masking changed the text.
            double[] vector = masked.get(i).equals(r.question) ? raw : cache.embed(List.of(masked.get(i))).get(0);
            Scored s = new Scored(r, router.scores(vector), mentioned.get(i), masked.get(i));
            rows.add(s);

            // The replay must BE production: same intent and verdict as the real entry point.
            SemanticRouter.Decision prod = router.routeQuestion(r.question, raw, mentioned.get(i));
            SemanticRouter.Decision replay = SemanticRouter.decide(s.scores, r.question, s.mentioned, curT, curM, curH);
            assertEquals(prod.intent, replay.intent, "replay diverged from routeQuestion: " + r.question);
            assertEquals(prod.confident, replay.confident, "replay diverged from routeQuestion: " + r.question);
        }

        List<GridPoint> grid = grid(rows);
        Map<String, Predicate<Scored>> groups = groups(rows);

        Files.createDirectories(CalibrationSupport.OUT_DIR);
        writeSweep(grid, rows, groups);
        writePerQuestion(rows, curT, curM, curH);
        String summary = summary(rows, grid, groups, exampleTexts, cache, model, curT, curM, curH);
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("summary.md"), summary);
        System.out.println(summary);
        System.out.printf(Locale.ROOT, "embedding cache: %d hits, %d misses, %d remote calls -> %s%n",
                cache.hits, cache.misses, cache.remoteCalls, CalibrationSupport.CACHE_FILE);
    }

    static Map<String, Predicate<Scored>> groups(List<Scored> rows) {
        Map<String, Predicate<Scored>> g = new LinkedHashMap<>();
        g.put("all", s -> true);
        for (String project : CalibrationSupport.PROJECT_NODES.keySet()) g.put("project=" + project, s -> s.row.project.equals(project));
        g.put("lang=zh", s -> s.row.lang().equals("zh"));
        g.put("lang=en", s -> s.row.lang().equals("en"));
        g.put("has_node=yes", Scored::hasNode);
        g.put("has_node=no", s -> !s.hasNode());
        return g;
    }

    private static void writeSweep(List<GridPoint> grid, List<Scored> rows, Map<String, Predicate<Scored>> groups) throws Exception {
        StringBuilder sb = new StringBuilder(CalibrationSupport.csvRow("T", "M", "H", "group", "n", "routable", "confident",
                "confident_correct", "confident_wrong", "coverage", "confident_wrong_rate", "precision_when_confident",
                "top1_accuracy", "useful_coverage"));
        for (GridPoint g : grid) {
            for (Map.Entry<String, Predicate<Scored>> e : groups.entrySet()) {
                Metrics m = g.metrics(rows, e.getValue());
                sb.append(CalibrationSupport.csvRow(g.t, g.m, g.h, e.getKey(), m.n, m.routable, m.confident,
                        m.confidentCorrect, m.confidentWrong, m.coverage(), m.confidentWrongRate(), m.precision(),
                        m.top1Accuracy(), m.usefulCoverage()));
            }
        }
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("sweep.csv"), sb.toString());
    }

    private static void writePerQuestion(List<Scored> rows, double t, double m, double h) throws Exception {
        StringBuilder sb = new StringBuilder(CalibrationSupport.csvRow("project", "lang", "has_node", "expected", "routable",
                "question", "masked", "mentioned", "best", "best_score", "runner_up", "runner_up_score", "top1_correct",
                "confident_at_current", "outcome_at_current", "note"));
        for (Scored s : rows) {
            SemanticRouter.Decision d = SemanticRouter.decide(s.scores, s.row.question, s.mentioned, t, m, h);
            List<String> ids = new ArrayList<>();
            for (DependencyGraph.Node n : s.mentioned) ids.add(n.id);
            sb.append(CalibrationSupport.csvRow(s.row.project, s.row.lang(), s.hasNode() ? "yes" : "no",
                    String.join("|", s.row.expected), s.routable, s.row.question, s.masked, String.join("|", ids),
                    d.intent, d.score, d.runnerUp, d.runnerUpScore, s.top1, d.confident, outcome(s, d.confident), s.row.note));
        }
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("per-question.csv"), sb.toString());
    }

    private static String outcome(Scored s, boolean confident) {
        if (confident) return s.top1 ? "confident-correct" : "CONFIDENT-WRONG";
        return s.routable ? "deferred" : "deferred (correct: no intent)";
    }

    // ---------- summary ----------

    private static String summary(List<Scored> rows, List<GridPoint> grid, Map<String, Predicate<Scored>> groups,
                                  List<String> exampleTexts, EmbeddingCache cache, String model,
                                  double curT, double curM, double curH) {
        StringBuilder sb = new StringBuilder();
        long absent = rows.stream().filter(s -> !s.routable && !s.row.expected.contains(CalibrationSupport.NONE)).count();
        sb.append("# Semantic router threshold sweep\n\n");
        sb.append("- embedding model: `").append(model).append("`\n");
        sb.append("- intents: ").append(CalibrationSupport.intentNames().size()).append(" (").append(String.join(", ", CalibrationSupport.intentNames())).append(")\n");
        sb.append("- hold-out: ").append(rows.size()).append(" questions; routable ").append(rows.stream().filter(s -> s.routable).count())
                .append("; no-intent ").append(rows.size() - rows.stream().filter(s -> s.routable).count());
        if (absent > 0) sb.append(" (of which ").append(absent).append(" expect an intent the router does not have yet, scored like none)");
        sb.append("\n- grid: T 0.40–0.85 step 0.01 × M 0.00–0.12 step 0.01 × H {0.80, 0.85, 0.90, 0.95, off} = ").append(grid.size()).append(" points\n");
        sb.append("- coverage = confident / all; confident-wrong = confident but not an expected intent (any confident route on a no-intent row); ")
                .append("precision = confident-correct / confident; top-1 = nearest intent after rules is expected, over routable rows\n\n");

        GridPoint current = new GridPoint(curT, curM, curH, rows);
        sb.append("## Current thresholds (").append(current.label()).append(")\n\n");
        sb.append(groupTable(current, rows, groups));

        GridPoint zero = select(grid, rows, s -> true, 0);
        GridPoint one = select(grid, rows, s -> true, 1);
        sb.append("\n## Selected: confident-wrong = 0, max coverage (ties → higher T, larger M, higher H)\n\n");
        sb.append(zero == null ? "No grid point reaches zero confident-wrong.\n" : "**" + zero.label() + "**\n\n" + groupTable(zero, rows, groups));
        sb.append("\n## Selected: confident-wrong ≤ 1, max coverage\n\n");
        sb.append(one == null ? "No grid point keeps confident-wrong ≤ 1.\n" : "**" + one.label() + "**\n\n" + groupTable(one, rows, groups));

        sb.append("\n## Score bands (best intent score after masking)\n\n");
        sb.append("| band | n | min | p10 | median | p90 | max |\n|---|---|---|---|---|---|---|\n");
        List<double[]> exVec = cache.embed(exampleTexts);
        List<Double> exact = new ArrayList<>(), loo = new ArrayList<>();
        int looSameIntent = 0;
        List<String> owner = new ArrayList<>();
        for (SemanticRouter.Intent i : SemanticRouter.INTENTS) owner.addAll(Collections.nCopies(i.examples.size(), i.name));
        for (int i = 0; i < exVec.size(); i++) {
            double self = Double.NEGATIVE_INFINITY, other = Double.NEGATIVE_INFINITY;
            String otherOwner = null;
            for (int j = 0; j < exVec.size(); j++) {
                double c = ChunkRetriever.cosine(exVec.get(i), exVec.get(j));
                if (c > self) self = c;
                if (j != i && c > other) {
                    other = c;
                    otherOwner = owner.get(j);
                }
            }
            exact.add(self);
            loo.add(other);
            if (owner.get(i).equals(otherOwner)) looSameIntent++;
        }
        sb.append(bandRow("example typed verbatim", exact));
        sb.append(bandRow("example, leave-one-out (nearest OTHER example)", loo));
        sb.append(bandRow("hold-out, top-1 correct", bestScores(rows, s -> s.routable && s.top1)));
        sb.append(bandRow("hold-out, top-1 wrong", bestScores(rows, s -> s.routable && !s.top1)));
        sb.append(bandRow("hold-out, no matching intent (none)", bestScores(rows, s -> s.row.expected.contains(CalibrationSupport.NONE))));
        if (absent > 0) sb.append(bandRow("hold-out, intent not in router yet", bestScores(rows, s -> !s.routable && !s.row.expected.contains(CalibrationSupport.NONE))));
        sb.append(String.format(Locale.ROOT, "%nLeave-one-out: the nearest other example belongs to the same intent for %d / %d examples.%n",
                looSameIntent, exVec.size()));

        sb.append("\n## Leave-one-project-out (select on two projects with the zero-wrong rule, evaluate on the third)\n\n");
        sb.append("| held-out project | selected on the other two | train coverage | train conf-wrong | test n | test coverage | test conf-wrong | test precision |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (String project : CalibrationSupport.PROJECT_NODES.keySet()) {
            Predicate<Scored> train = s -> !s.row.project.equals(project);
            Predicate<Scored> test = s -> s.row.project.equals(project);
            GridPoint g = select(grid, rows, train, 0);
            if (g == null) {
                sb.append("| ").append(project).append(" | (none reaches zero wrong) | | | | | | |\n");
                continue;
            }
            Metrics tr = g.metrics(rows, train), te = g.metrics(rows, test);
            sb.append("| ").append(project).append(" | ").append(g.label()).append(" | ").append(pct(tr.coverage()))
                    .append(" | ").append(tr.confidentWrong).append(" | ").append(te.n).append(" | ").append(pct(te.coverage()))
                    .append(" | ").append(te.confidentWrong).append(" | ").append(pct(te.precision())).append(" |\n");
        }

        sb.append("\n## Confident-wrong rows at the current thresholds\n\n");
        boolean any = false;
        for (int i = 0; i < rows.size(); i++) {
            Scored s = rows.get(i);
            if (current.confident[i] && !s.top1) {
                SemanticRouter.Decision d = SemanticRouter.decide(s.scores, s.row.question, s.mentioned, curT, curM, curH);
                sb.append("- `").append(s.row.question).append("` expected ").append(s.row.expected).append(" → ").append(d).append('\n');
                any = true;
            }
        }
        if (!any) sb.append("(none)\n");
        return sb.toString();
    }

    private static String groupTable(GridPoint g, List<Scored> rows, Map<String, Predicate<Scored>> groups) {
        StringBuilder sb = new StringBuilder("| group | n | coverage | confident-wrong | precision | top-1 | useful coverage |\n|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, Predicate<Scored>> e : groups.entrySet()) {
            Metrics m = g.metrics(rows, e.getValue());
            sb.append("| ").append(e.getKey()).append(" | ").append(m.n).append(" | ").append(pct(m.coverage()))
                    .append(" | ").append(m.confidentWrong).append(" (").append(pct(m.confidentWrongRate())).append(")")
                    .append(" | ").append(pct(m.precision())).append(" | ").append(pct(m.top1Accuracy()))
                    .append(" | ").append(pct(m.usefulCoverage())).append(" |\n");
        }
        return sb.toString();
    }

    private static List<Double> bestScores(List<Scored> rows, Predicate<Scored> which) {
        List<Double> out = new ArrayList<>();
        for (Scored s : rows) {
            if (!which.test(s)) continue;
            out.add(Collections.max(s.scores.values()));
        }
        return out;
    }

    private static String bandRow(String name, List<Double> values) {
        List<Double> v = new ArrayList<>(values);
        v.sort(Comparator.naturalOrder());
        return "| " + name + " | " + v.size() + " | " + CalibrationSupport.fmt(CalibrationSupport.quantile(v, 0)) + " | "
                + CalibrationSupport.fmt(CalibrationSupport.quantile(v, 0.1)) + " | " + CalibrationSupport.fmt(CalibrationSupport.quantile(v, 0.5))
                + " | " + CalibrationSupport.fmt(CalibrationSupport.quantile(v, 0.9)) + " | " + CalibrationSupport.fmt(CalibrationSupport.quantile(v, 1)) + " |\n";
    }

    private static String pct(double r) {
        return Double.isNaN(r) ? "–" : String.format(Locale.ROOT, "%.1f%%", 100 * r);
    }

    // ---------- offline: the metric and the rule ----------

    private static Map<String, Double> scores(String best, double b, String second, double s2) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String name : CalibrationSupport.intentNames()) out.put(name, 0.1);
        out.put(best, b);
        out.put(second, s2);
        return out;
    }

    private static Scored row(String expected, Map<String, Double> scores) {
        HoldoutRow r = new HoldoutRow("sock-shop", "q", new LinkedHashSet<>(List.of(expected)), "");
        return new Scored(r, scores, List.of(), "q");
    }

    @Test
    void confidentRouteOnANoIntentRowIsWrongAndDeferringIsNot() {
        List<Scored> rows = List.of(
                row("deploy-order", scores("deploy-order", 0.70, "externals", 0.50)),   // confident, right
                row("externals", scores("deploy-order", 0.66, "externals", 0.64)),      // wrong intent, thin margin
                row(CalibrationSupport.NONE, scores("async", 0.62, "externals", 0.40))); // no intent at all
        Metrics loose = new GridPoint(0.60, 0.0, 1.01, rows).metrics(rows, s -> true);
        assertEquals(3, loose.confident);
        assertEquals(2, loose.confidentWrong);
        assertEquals(2, loose.routable);
        assertEquals(0.5, loose.top1Accuracy(), 1e-9);

        Metrics strict = new GridPoint(0.65, 0.05, 1.01, rows).metrics(rows, s -> true);
        assertEquals(1, strict.confident);
        assertEquals(0, strict.confidentWrong);
        assertEquals(1.0, strict.precision(), 1e-9);
    }

    @Test
    void selectionMaximisesCoverageUnderTheWrongBudgetAndBreaksTiesConservatively() {
        List<Scored> rows = List.of(
                row("deploy-order", scores("deploy-order", 0.70, "externals", 0.50)),
                row("externals", scores("externals", 0.62, "async", 0.40)),
                row(CalibrationSupport.NONE, scores("async", 0.60, "externals", 0.59)));
        List<GridPoint> grid = grid(rows);

        // T up to 0.62 covers both routable rows; the none row (0.60, margin 0.01) must be
        // kept unsure — by T > 0.60 or M > 0.01. The rule then takes the highest such T,
        // the largest M that still admits the 0.62 row (margin 0.22), and H off.
        GridPoint zero = select(grid, rows, s -> true, 0);
        assertNotNull(zero);
        assertEquals(0.62, zero.t, 1e-9);
        assertEquals(0.12, zero.m, 1e-9);
        assertEquals(1.01, zero.h, 1e-9);
        assertEquals(2, zero.metrics(rows, s -> true).confident);

        // Allowing one wrong buys the none row too.
        GridPoint one = select(grid, rows, s -> true, 1);
        assertEquals(3, one.metrics(rows, s -> true).confident);

        // A wrong route scoring 0.99 is confident at every grid point: no point is feasible.
        List<Scored> hopeless = List.of(row("externals", scores("async", 0.99, "externals", 0.10)));
        assertNull(select(grid(hopeless), hopeless, s -> true, 0));
    }
}
