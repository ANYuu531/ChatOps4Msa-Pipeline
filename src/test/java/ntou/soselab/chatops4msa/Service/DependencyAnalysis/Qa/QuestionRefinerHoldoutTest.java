package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.CalibrationSupport.ChatCache;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.CalibrationSupport.EmbeddingCache;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.CalibrationSupport.HoldoutRow;
import ntou.soselab.chatops4msa.Service.NLPService.EmbeddingClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the question refiner does to the hold-out, against the real model.
 *
 * Runs only with {@code -Dqa.calibrate=true} (machine B). Every question is routed
 * exactly as production routes it (cached embeddings, current thresholds); the model is
 * asked ONCE per question for a rewording and ONCE for a plan, both cached — after that
 * every band width and every threshold set below is an offline replay, like the
 * threshold sweep.
 *
 * <b>Part 1 — the rows the router is unsure about</b> (today: the planner's). Three arms:
 * <ol>
 *   <li><b>planner</b> (status quo): the planner prompt with the project's node ids; the
 *       plan's operators are mapped back to intents.</li>
 *   <li><b>rewrite</b> (the 2026-11-12 feedback, with the guards of {@link QuestionRefiner}):
 *       the rewording routed with the same thresholds, accepted only when confident and
 *       in agreement with the model's own label; rejected rows go to the planner, so the
 *       pipeline outcome is "rewrite if accepted, else planner".</li>
 *   <li><b>pick</b> (the alternative without a rewrite): the model's own label trusted as it
 *       is whenever it is on the shortlist. Same call, no re-routing — what the re-routing
 *       guard buys.</li>
 * </ol>
 *
 * <b>Part 2 — the band of the second opinion.</b> A confident route that only just cleared
 * T or M may be asked for a second opinion ({@link QuestionRefiner#secondOpinion}). How
 * far from T and M counts as "only just" is a threshold, so it is swept: a grid of
 * (bandScore, bandLead) replayed over the rows confident at the current thresholds.
 * The harm it can do is to overrule a correct route (DISAGREE on a correct row, and the
 * planner then does worse); the good it can do is to catch a confident-WRONG route. At the
 * current thresholds the hold-out has no confident-wrong row in-sample — they were chosen
 * for that — so the benefit is measured on the rows that LOOSER thresholds route
 * confidently wrong (the ≤1-wrong grid point and the pre-9/14 thresholds), with the
 * band taken relative to those thresholds: the question is whether "close to the
 * thresholds" is where the wrong ones live, and whether the second opinion disagrees on
 * them.
 *
 * <b>Selection rule</b> ({@link #selectBand}): harm at the current thresholds must be zero;
 * then the most wrong routes caught (current + loose sets); then the fewest rows in the
 * band at the current thresholds (fewest extra calls); ties to the narrower band. If no
 * band catches anything the band stays off (0, 0).
 */
public class QuestionRefinerHoldoutTest {

    /** The planner's operators, as intents of the hold-out. */
    static String intentOfOp(String op) {
        if ("unobserved-edges".equals(op)) return "uncovered";
        if (GraphQuery.EDIT_OPS.contains(op)) return "edit";
        return op;
    }

    /** One hold-out row, scored once, with everything the model said about it. */
    static final class Row {
        final HoldoutRow row;
        final List<DependencyGraph.Node> mentioned;
        final boolean routable;
        Map<String, Double> scores;
        double editScore = Double.NEGATIVE_INFINITY;
        /** The refiner could run: no missing node, not edit-like (both threshold-independent). */
        boolean refinable;
        QuestionRefiner.Result refined;
        /** The rewording's scores and edit score, so its route replays under any thresholds. */
        Map<String, Double> rewriteScores;
        double rewriteEditScore = Double.NEGATIVE_INFINITY;
        List<GraphQuery> plan;
        String planError = "";

        Row(HoldoutRow row, List<DependencyGraph.Node> mentioned) {
            this.row = row;
            this.mentioned = mentioned;
            boolean r = false;
            for (String e : row.expected) if (CalibrationSupport.intentNames().contains(e)) r = true;
            this.routable = r;
        }

        boolean expectsNone() {
            return !routable;
        }

        boolean correct(String intent) {
            return routable && row.expected.contains(intent);
        }

        /** Production's route under the given thresholds (the rules, the confidence test, the edit veto). */
        SemanticRouter.Decision route(double t, double m, double h) {
            SemanticRouter.Decision d = SemanticRouter.decide(scores, row.question, mentioned, t, m, h);
            return editScore > d.score ? d.asEdit(editScore) : d;
        }

        /** The refiner's acceptance test on the rewording, under the given thresholds; null when there is no rewording to route. */
        SemanticRouter.Decision rewriteRoute(double t, double m, double h) {
            if (rewriteScores == null || refined == null) return null;
            SemanticRouter.Decision d = SemanticRouter.decide(rewriteScores, refined.rewrite, mentioned, t, m, h);
            return rewriteEditScore > d.score ? d.asEdit(rewriteEditScore) : d;
        }

        boolean accepted(double t, double m, double h) {
            SemanticRouter.Decision d = rewriteRoute(t, m, h);
            return d != null && d.confident && d.intent.equals(refined.proposedIntent);
        }

        QuestionRefiner.Verdict verdict(double t, double m, double h, String originalIntent) {
            if (!accepted(t, m, h)) return QuestionRefiner.Verdict.FAILED;
            return rewriteRoute(t, m, h).intent.equals(originalIntent) ? QuestionRefiner.Verdict.AGREE : QuestionRefiner.Verdict.DISAGREE;
        }

        // ----- the planner arm -----

        /** correct / wrong / none (empty plan). On a no-intent row an empty plan is correct, a plan is wrong. */
        String plannerOutcome() {
            if (plan == null) return "no answer";
            if (plan.isEmpty()) return expectsNone() ? "correct" : "none";
            for (GraphQuery q : plan) if (correct(intentOfOp(q.op))) return "correct";
            return "wrong";
        }

        String plannerIntents() {
            Set<String> s = new LinkedHashSet<>();
            if (plan != null) for (GraphQuery q : plan) s.add(intentOfOp(q.op));
            return String.join("|", s);
        }

        // ----- the rewrite arm (deferred rows, current thresholds) -----

        /** accepted-correct / accepted-wrong / rejected / skipped. */
        String rewriteOutcome(double t, double m, double h) {
            if (!refinable || refined == null) return "skipped";
            if (!accepted(t, m, h)) return "rejected";
            return correct(rewriteRoute(t, m, h).intent) ? "accepted-correct" : "accepted-wrong";
        }

        /** The pipeline: the rewording when accepted, else the planner. */
        String pipelineOutcome(double t, double m, double h) {
            String r = rewriteOutcome(t, m, h);
            if (r.startsWith("accepted")) return r.substring("accepted-".length());
            return plannerOutcome();
        }

        // ----- the pick arm -----

        /** correct / wrong / none: the model's label trusted as it is (when on the shortlist). */
        String pickOutcome() {
            if (!refinable || refined == null) return "skipped";
            String p = refined.proposedIntent;
            if (QuestionRefiner.NONE.equals(p) || !refined.shortlist.contains(p)) return expectsNone() ? "correct" : "none";
            return correct(p) ? "correct" : "wrong";
        }
    }

    // ---------- the band sweep (pure replay) ----------

    static final class Thresholds {
        final String name;
        final double t, m, h;

        Thresholds(String name, double t, double m, double h) {
            this.name = name;
            this.t = t;
            this.m = m;
            this.h = h;
        }

        String label() {
            return String.format(Locale.ROOT, "%s (T=%.2f M=%.2f H=%s)", name, t, m, h > 1 ? "off" : String.format(Locale.ROOT, "%.2f", h));
        }
    }

    static final class Band {
        final double score, lead;
        /** Every confident row below H, whatever its distance to T and M. */
        final boolean all;

        Band(double score, double lead, boolean all) {
            this.score = score;
            this.lead = lead;
            this.all = all;
        }

        boolean contains(SemanticRouter.Decision d, Thresholds th) {
            if (all) return d.confident && d.score < th.h;
            return SemanticRouter.marginal(d, th.t, th.m, th.h, score, lead);
        }

        String label() {
            return all ? "all below H" : String.format(Locale.ROOT, "%.2f/%.2f", score, lead);
        }

        boolean off() {
            return !all && score == 0 && lead == 0;
        }
    }

    /** What the second opinion does to the rows confident under one threshold set, with one band. */
    static final class BandMetrics {
        int confident, confidentWrong, inBand, agree, disagree, failed;
        /** A correct route overruled: the planner then was correct (lost coverage only) or was not (harm). */
        int lost, harm;
        /** A wrong route overruled (good), or in the band but not overruled, or outside the band. */
        int caught, missed, outside;

        static BandMetrics of(List<Row> rows, Thresholds th, Band band) {
            BandMetrics out = new BandMetrics();
            for (Row r : rows) {
                SemanticRouter.Decision d = r.route(th.t, th.m, th.h);
                if (!d.confident) continue;
                out.confident++;
                boolean right = r.correct(d.intent);
                if (!right) out.confidentWrong++;
                if (!band.contains(d, th) || !r.refinable) {
                    if (!right) out.outside++;
                    continue;
                }
                out.inBand++;
                QuestionRefiner.Verdict v = r.verdict(th.t, th.m, th.h, d.intent);
                if (v == QuestionRefiner.Verdict.AGREE) out.agree++;
                else if (v == QuestionRefiner.Verdict.FAILED) out.failed++;
                else out.disagree++;
                if (v != QuestionRefiner.Verdict.DISAGREE) {
                    if (!right) out.missed++;
                } else if (right) {
                    if ("correct".equals(r.plannerOutcome())) out.lost++;
                    else out.harm++;
                } else {
                    out.caught++;
                }
            }
            return out;
        }
    }

    static List<Band> bandGrid() {
        List<Band> out = new ArrayList<>();
        for (int s = 0; s <= 30; s++) for (int l = 0; l <= 20; l++) out.add(new Band(s / 100.0, l / 100.0, false));
        out.add(new Band(0, 0, true));
        return out;
    }

    /** THE selection rule; see the class comment. Never null: falls back to the band being off. */
    static Band selectBand(List<Row> rows, Thresholds current, List<Thresholds> loose, List<Band> grid) {
        Band best = null;
        int bestCaught = -1, bestInBand = Integer.MAX_VALUE;
        for (Band b : grid) {
            BandMetrics cur = BandMetrics.of(rows, current, b);
            if (cur.harm > 0) continue;
            int caught = cur.caught;
            for (Thresholds th : loose) caught += BandMetrics.of(rows, th, b).caught;
            boolean better = caught > bestCaught
                    || (caught == bestCaught && cur.inBand < bestInBand)
                    || (caught == bestCaught && cur.inBand == bestInBand && best != null && narrower(b, best));
            if (better) {
                best = b;
                bestCaught = caught;
                bestInBand = cur.inBand;
            }
        }
        if (best == null || bestCaught == 0) return new Band(0, 0, false);
        return best;
    }

    private static boolean narrower(Band a, Band b) {
        if (a.all != b.all) return !a.all;
        if (a.score != b.score) return a.score < b.score;
        return a.lead < b.lead;
    }

    // ---------- the experiment ----------

    @Test
    void refineTheHoldoutAgainstTheRealModel() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("qa.calibrate")), "pass -Dqa.calibrate=true to run");
        Properties p = CalibrationSupport.applicationProperties();
        String embeddingModel = CalibrationSupport.embeddingModel(p);
        EmbeddingClient client = CalibrationSupport.clientOrNull(p);
        EmbeddingCache embeddings = new EmbeddingCache(CalibrationSupport.CACHE_FILE, embeddingModel, client == null ? null : client::embed);
        ChatCompletions remote = ChatCompletions.fromOrNull(p);
        ChatCache chat = new ChatCache(CalibrationSupport.CHAT_CACHE_FILE, remote == null ? p.getProperty("openai.api.model", "?") : remote.model(), remote);
        Assumptions.assumeTrue(remote != null || !chat.isEmpty(), "no openai.api.key and no chat cache");

        Thresholds current = new Thresholds("current",
                Double.parseDouble(p.getProperty("dependency.qa.router.threshold", String.valueOf(SemanticRouter.DEFAULT_THRESHOLD))),
                Double.parseDouble(p.getProperty("dependency.qa.router.margin", String.valueOf(SemanticRouter.DEFAULT_MARGIN))),
                Double.parseDouble(p.getProperty("dependency.qa.router.high", String.valueOf(SemanticRouter.DEFAULT_HIGH))));
        SemanticRouter router = new SemanticRouter(embeddings, current.t, current.m, current.h);
        assertTrue(router.ensureIndexed(), "could not index the examples");
        QuestionRefiner refiner = new QuestionRefiner(router, embeddings, chat);
        GraphQueryPlanner plannerPrompt = new GraphQueryPlanner(null);

        List<Row> rows = new ArrayList<>();
        List<SemanticRouterThresholdSweepTest.Scored> scored = new ArrayList<>();
        for (HoldoutRow r : CalibrationSupport.loadHoldout()) {
            DependencyGraph graph = CalibrationSupport.projectGraph(r.project);
            Row row = new Row(r, GraphGrounding.mentionedNodes(r.question, graph));
            List<double[]> raw = embeddings.embed(List.of(r.question));
            assertNotNull(raw, "could not embed: " + r.question);
            double[] vector = router.routingVector(r.question, raw.get(0), row.mentioned);
            row.scores = router.scores(vector);
            row.editScore = router.editScore(vector);
            scored.add(new SemanticRouterThresholdSweepTest.Scored(r, row.scores, row.mentioned, GraphGrounding.maskMentions(r.question, row.mentioned)));

            // The replay must BE production.
            SemanticRouter.Decision prod = router.routeQuestion(r.question, raw.get(0), row.mentioned);
            SemanticRouter.Decision replay = row.route(current.t, current.m, current.h);
            assertEquals(prod.intent + " " + prod.confident, replay.intent + " " + replay.confident, "diverged: " + r.question);

            row.refinable = !replay.missingNode && !replay.editLike;
            if (row.refinable) {
                row.refined = refiner.refine(r.question, vector, row.mentioned);
                if (!row.refined.rewrite.isBlank()) {
                    String masked = GraphGrounding.maskMentions(row.refined.rewrite, row.mentioned);
                    List<double[]> rv = embeddings.embed(List.of(masked));
                    assertNotNull(rv, "could not embed the rewording of: " + r.question);
                    row.rewriteScores = router.scores(rv.get(0));
                    row.rewriteEditScore = router.editScore(rv.get(0));
                    // The replay of the rewording must be what the refiner decided.
                    assertEquals(row.refined.accepted, row.accepted(current.t, current.m, current.h), "rewrite replay diverged: " + r.question);
                    if (row.refined.accepted) assertEquals(row.refined.rerouted.intent, row.rewriteRoute(current.t, current.m, current.h).intent);
                }
            }
            String answer = chat.complete(plannerPrompt.systemPrompt(graph), r.question);
            if (answer == null) row.planError = "no answer";
            else {
                try {
                    row.plan = GraphQuery.parse(answer, graph);
                } catch (Exception e) {
                    row.plan = List.of();
                    row.planError = "unparseable";
                }
            }
            rows.add(row);
        }

        // The looser threshold sets whose confident-wrong rows stand in for the ones that
        // would slip through: the ≤1-wrong point of the sweep's own rule, and the thresholds
        // in force before the 2026-09-14 sweep (confidently wrong on 10 of these rows).
        List<Thresholds> loose = new ArrayList<>();
        SemanticRouterThresholdSweepTest.GridPoint one = SemanticRouterThresholdSweepTest.select(
                SemanticRouterThresholdSweepTest.grid(scored), scored, s -> true, 1);
        if (one != null) loose.add(new Thresholds("≤1 wrong", one.t, one.m, one.h));
        loose.add(new Thresholds("pre-9/14", 0.58, 0.04, 0.85));

        List<Band> grid = bandGrid();
        Band selected = selectBand(rows, current, loose, grid);

        Files.createDirectories(CalibrationSupport.OUT_DIR);
        writePerQuestion(rows, current, selected);
        writeBandCsv(rows, current, loose, grid);
        String summary = summary(rows, embeddingModel, chat, current, loose, grid, selected);
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("refine-summary.md"), summary);
        System.out.println(summary);
        System.out.printf(Locale.ROOT, "embedding cache: %d hits, %d misses; chat cache: %d hits, %d misses -> %s%n",
                embeddings.hits, embeddings.misses, chat.hits, chat.misses, CalibrationSupport.OUT_DIR);
    }

    // ---------- output ----------

    private static void writePerQuestion(List<Row> rows, Thresholds cur, Band selected) throws Exception {
        StringBuilder sb = new StringBuilder(CalibrationSupport.csvRow("project", "lang", "has_node", "expected", "question",
                "router_intent", "router_score", "router_runner_up", "router_confident", "in_selected_band", "second_opinion",
                "refinable", "shortlist", "model_intent", "rewrite", "rewrite_route", "rewrite_outcome", "pick_outcome",
                "planner_intents", "planner_outcome", "pipeline_outcome", "note"));
        for (Row r : rows) {
            SemanticRouter.Decision d = r.route(cur.t, cur.m, cur.h);
            boolean deferred = !d.confident;
            boolean inBand = !deferred && r.refinable && selected.contains(d, cur);
            SemanticRouter.Decision rr = r.rewriteRoute(cur.t, cur.m, cur.h);
            sb.append(CalibrationSupport.csvRow(r.row.project, r.row.lang(), r.mentioned.isEmpty() ? "no" : "yes",
                    String.join("|", r.row.expected), r.row.question,
                    d.intent, d.score, d.runnerUp + " " + CalibrationSupport.fmt(d.runnerUpScore), d.confident,
                    deferred ? "" : inBand, deferred || !r.refinable ? "" : r.verdict(cur.t, cur.m, cur.h, d.intent),
                    r.refinable, r.refined == null ? "" : String.join("|", r.refined.shortlist),
                    r.refined == null ? "" : r.refined.proposedIntent, r.refined == null ? "" : r.refined.rewrite,
                    rr == null ? "" : rr.toString(),
                    deferred ? r.rewriteOutcome(cur.t, cur.m, cur.h) : "", deferred ? r.pickOutcome() : "",
                    r.plannerIntents(), r.plannerOutcome() + (r.planError.isEmpty() ? "" : " (" + r.planError + ")"),
                    deferred ? r.pipelineOutcome(cur.t, cur.m, cur.h) : "", r.row.note));
        }
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("refine-per-question.csv"), sb.toString());
    }

    private static void writeBandCsv(List<Row> rows, Thresholds cur, List<Thresholds> loose, List<Band> grid) throws Exception {
        StringBuilder sb = new StringBuilder(CalibrationSupport.csvRow("thresholds", "T", "M", "H", "band_score", "band_lead", "band_all",
                "confident", "confident_wrong", "in_band", "agree", "disagree", "failed", "lost", "harm", "caught", "missed", "outside"));
        List<Thresholds> all = new ArrayList<>();
        all.add(cur);
        all.addAll(loose);
        for (Thresholds th : all) {
            for (Band b : grid) {
                BandMetrics m = BandMetrics.of(rows, th, b);
                sb.append(CalibrationSupport.csvRow(th.name, th.t, th.m, th.h, b.score, b.lead, b.all, m.confident, m.confidentWrong,
                        m.inBand, m.agree, m.disagree, m.failed, m.lost, m.harm, m.caught, m.missed, m.outside));
            }
        }
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("refine-band.csv"), sb.toString());
    }

    private static Map<String, Predicate<Row>> groups() {
        Map<String, Predicate<Row>> g = new LinkedHashMap<>();
        g.put("all", r -> true);
        for (String project : CalibrationSupport.PROJECT_NODES.keySet()) g.put("project=" + project, r -> r.row.project.equals(project));
        g.put("lang=zh", r -> r.row.lang().equals("zh"));
        g.put("lang=en", r -> r.row.lang().equals("en"));
        g.put("has_node=yes", r -> !r.mentioned.isEmpty());
        g.put("has_node=no", r -> r.mentioned.isEmpty());
        return g;
    }

    private static String summary(List<Row> rows, String embeddingModel, ChatCache chat, Thresholds cur, List<Thresholds> loose,
                                  List<Band> grid, Band selected) {
        double t = cur.t, m = cur.m, h = cur.h;
        StringBuilder sb = new StringBuilder();
        sb.append("# Question refiner on the hold-out\n\n");
        sb.append("- embedding model: `").append(embeddingModel).append("`; chat model: `").append(chat.model()).append("`\n");
        sb.append(String.format(Locale.ROOT, "- thresholds T=%.2f M=%.2f H=%.2f; shortlist %d intents × %d examples%n",
                t, m, h, QuestionRefiner.INTENTS, QuestionRefiner.EXAMPLES_PER_INTENT));
        Predicate<Row> deferred = r -> !r.route(t, m, h).confident;
        long n = rows.size(), nDeferred = rows.stream().filter(deferred).count();
        long refinableDeferred = rows.stream().filter(r -> deferred.test(r) && r.refinable).count();
        sb.append("- hold-out: ").append(n).append(" questions; router confident on ").append(n - nDeferred)
                .append("; deferred ").append(nDeferred).append(" (refiner ran on ").append(refinableDeferred)
                .append(", skipped ").append(nDeferred - refinableDeferred).append(": missing node or edit-like)\n\n");

        sb.append("## Part 1 — coverage and confident-wrong: router alone vs router + accepted rewordings\n\n");
        sb.append("| group | n | router confident | router conf-wrong | + accepted | + accepted wrong | coverage before | coverage after | conf-wrong after |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, Predicate<Row>> g : groups().entrySet()) {
            List<Row> in = rows.stream().filter(g.getValue()).toList();
            long conf = in.stream().filter(r -> !deferred.test(r)).count();
            long confWrong = in.stream().filter(r -> !deferred.test(r) && !r.correct(r.route(t, m, h).intent)).count();
            long acc = in.stream().filter(r -> deferred.test(r) && r.rewriteOutcome(t, m, h).startsWith("accepted")).count();
            long accWrong = in.stream().filter(r -> deferred.test(r) && r.rewriteOutcome(t, m, h).equals("accepted-wrong")).count();
            sb.append("| ").append(g.getKey()).append(" | ").append(in.size()).append(" | ").append(conf).append(" | ").append(confWrong)
                    .append(" | ").append(acc).append(" | ").append(accWrong)
                    .append(" | ").append(pct(conf, in.size())).append(" | ").append(pct(conf + acc, in.size()))
                    .append(" | ").append(confWrong + accWrong).append(" |\n");
        }

        sb.append("\n## Part 1 — the deferred rows: what each arm made of them\n\n");
        sb.append("(planner = status quo; rewrite = reworded and re-routed, accepted or rejected; pipeline = rewrite if accepted, else planner; pick = the model's label as it is)\n\n");
        sb.append("| group | deferred | planner correct | planner wrong | planner none | rewrite accepted | accepted wrong | pipeline correct | pipeline wrong | pipeline none | pick correct | pick wrong | pick none |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, Predicate<Row>> g : groups().entrySet()) {
            List<Row> in = rows.stream().filter(r -> deferred.test(r) && g.getValue().test(r)).toList();
            sb.append("| ").append(g.getKey()).append(" | ").append(in.size())
                    .append(" | ").append(count(in, Row::plannerOutcome, "correct")).append(" | ").append(count(in, Row::plannerOutcome, "wrong"))
                    .append(" | ").append(count(in, Row::plannerOutcome, "none"))
                    .append(" | ").append(in.stream().filter(r -> r.rewriteOutcome(t, m, h).startsWith("accepted")).count())
                    .append(" | ").append(count(in, r -> r.rewriteOutcome(t, m, h), "accepted-wrong"))
                    .append(" | ").append(count(in, r -> r.pipelineOutcome(t, m, h), "correct")).append(" | ").append(count(in, r -> r.pipelineOutcome(t, m, h), "wrong"))
                    .append(" | ").append(count(in, r -> r.pipelineOutcome(t, m, h), "none"))
                    .append(" | ").append(count(in, Row::pickOutcome, "correct")).append(" | ").append(count(in, Row::pickOutcome, "wrong"))
                    .append(" | ").append(count(in, Row::pickOutcome, "none")).append(" |\n");
        }

        sb.append("\n### Why rewordings were rejected (deferred rows)\n\n");
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (Row r : rows) {
            if (deferred.test(r) && r.refinable && r.refined != null && !r.refined.accepted) reasons.merge(r.refined.reason, 1, Integer::sum);
        }
        if (reasons.isEmpty()) sb.append("(none rejected)\n");
        for (Map.Entry<String, Integer> e : reasons.entrySet()) sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');

        sb.append("\n### Accepted rewordings that were WRONG\n\n");
        boolean any = false;
        for (Row r : rows) {
            if (deferred.test(r) && r.rewriteOutcome(t, m, h).equals("accepted-wrong")) {
                sb.append("- `").append(r.row.question).append("` expected ").append(r.row.expected).append(" → \"")
                        .append(r.refined.rewrite).append("\" → ").append(r.rewriteRoute(t, m, h)).append('\n');
                any = true;
            }
        }
        if (!any) sb.append("(none)\n");

        sb.append("\n### Accepted rewordings (correct)\n\n");
        for (Row r : rows) {
            if (deferred.test(r) && r.rewriteOutcome(t, m, h).equals("accepted-correct")) {
                SemanticRouter.Decision rr = r.rewriteRoute(t, m, h);
                sb.append("- `").append(r.row.question).append("` → \"").append(r.refined.rewrite).append("\" → ")
                        .append(rr.intent).append(String.format(Locale.ROOT, " %.3f", rr.score)).append('\n');
            }
        }

        sb.append("\n### Rows where the pipeline and the planner differ\n\n");
        any = false;
        for (Row r : rows) {
            if (deferred.test(r) && !r.pipelineOutcome(t, m, h).equals(r.plannerOutcome())) {
                sb.append("- `").append(r.row.question).append("` expected ").append(r.row.expected)
                        .append(": planner ").append(r.plannerOutcome()).append(" [").append(r.plannerIntents()).append("]")
                        .append(", pipeline ").append(r.pipelineOutcome(t, m, h)).append(" (").append(r.rewriteOutcome(t, m, h)).append(")\n");
                any = true;
            }
        }
        if (!any) sb.append("(none)\n");

        // ----- Part 2 -----
        sb.append("\n## Part 2 — the second-opinion band\n\n");
        sb.append("- band = confident rows below H whose score is within band_score of T, or whose lead is within band_lead of M; ")
                .append("grid band_score 0.00–0.30 × band_lead 0.00–0.20 step 0.01, plus \"all below H\" (").append(grid.size()).append(" points)\n");
        sb.append("- verdict: AGREE = the rewording routes confidently to the same intent; DISAGREE = to another intent (the planner decides); FAILED = no usable rewording (route stands)\n");
        sb.append("- lost = correct route overruled, planner also correct; harm = correct route overruled, planner not; caught = wrong route overruled; missed = wrong route in the band but not overruled; outside = wrong route outside the band\n");
        sb.append("- rule: harm = 0 at the current thresholds; then most caught (current + loose sets); then fewest rows in the band at the current thresholds; ties to the narrower band; nothing caught anywhere → band off\n\n");
        sb.append("**Selected: ").append(selected.off() ? "off (0/0) — no band catches a wrong route without harm" : selected.label()).append("**\n\n");

        List<Band> shown = new ArrayList<>(List.of(new Band(0, 0, false), new Band(0.03, 0.03, false), new Band(0.05, 0.05, false),
                new Band(0.10, 0.10, false), new Band(0, 0, true)));
        if (!selected.off() && shown.stream().noneMatch(b -> b.label().equals(selected.label()))) shown.add(selected);
        List<Thresholds> sets = new ArrayList<>();
        sets.add(cur);
        sets.addAll(loose);
        for (Thresholds th : sets) {
            sb.append("### ").append(th.label()).append("\n\n");
            sb.append("| band | confident | confident-wrong | in band | agree | disagree | failed | lost | harm | caught | missed | outside |\n");
            sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (Band b : shown) {
                BandMetrics bm = BandMetrics.of(rows, th, b);
                sb.append("| ").append(b.label()).append(b.label().equals(selected.label()) ? " (selected)" : "")
                        .append(" | ").append(bm.confident).append(" | ").append(bm.confidentWrong).append(" | ").append(bm.inBand)
                        .append(" | ").append(bm.agree).append(" | ").append(bm.disagree).append(" | ").append(bm.failed)
                        .append(" | ").append(bm.lost).append(" | ").append(bm.harm).append(" | ").append(bm.caught)
                        .append(" | ").append(bm.missed).append(" | ").append(bm.outside).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("### The confident-wrong rows of the loose sets: where they sit and what the second opinion said\n\n");
        sb.append("| thresholds | question | expected | routed | score − T | lead − M | in selected band | verdict |\n|---|---|---|---|---|---|---|---|\n");
        any = false;
        for (Thresholds th : loose) {
            for (Row r : rows) {
                SemanticRouter.Decision d = r.route(th.t, th.m, th.h);
                if (!d.confident || r.correct(d.intent)) continue;
                any = true;
                sb.append("| ").append(th.name).append(" | `").append(r.row.question).append("` | ").append(r.row.expected)
                        .append(" | ").append(d.intent).append(String.format(Locale.ROOT, " %.3f", d.score))
                        .append(String.format(Locale.ROOT, " | %+.3f | %+.3f | ", d.score - th.t, (d.score - d.runnerUpScore) - th.m))
                        .append(r.refinable && selected.contains(d, th)).append(" | ")
                        .append(r.refinable ? r.verdict(th.t, th.m, th.h, d.intent).toString() : "(not refinable)").append(" |\n");
            }
        }
        if (!any) sb.append("(none)\n");

        sb.append("\n### Correct routes the selected band would overrule at the current thresholds\n\n");
        any = false;
        if (!selected.off()) {
            for (Row r : rows) {
                SemanticRouter.Decision d = r.route(t, m, h);
                if (!d.confident || !r.refinable || !selected.contains(d, cur)) continue;
                if (r.correct(d.intent) && r.verdict(t, m, h, d.intent) == QuestionRefiner.Verdict.DISAGREE) {
                    sb.append("- `").append(r.row.question).append("` ").append(d).append(" → \"").append(r.refined.rewrite).append("\" → ")
                            .append(r.rewriteRoute(t, m, h).intent).append("; planner ").append(r.plannerOutcome()).append('\n');
                    any = true;
                }
            }
        }
        if (!any) sb.append("(none)\n");
        return sb.toString();
    }

    private static long count(List<Row> in, java.util.function.Function<Row, String> f, String value) {
        return in.stream().filter(r -> f.apply(r).equals(value)).count();
    }

    private static String pct(long a, long b) {
        return b == 0 ? "–" : String.format(Locale.ROOT, "%.1f%%", 100.0 * a / b);
    }

    // ---------- offline: the band metric and the rule on synthetic rows ----------

    private static Map<String, Double> scores(String best, double b, String second, double s2) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String name : CalibrationSupport.intentNames()) out.put(name, 0.1);
        out.put(best, b);
        out.put(second, s2);
        return out;
    }

    /** A row with its route, the rewording's route (an exact example: score 1.0), and the planner's op. */
    private static Row row(String expected, Map<String, Double> route, String modelIntent, String rewriteIntent, String plannerOp) {
        Row r = new Row(new HoldoutRow("sock-shop", "q " + expected, new LinkedHashSet<>(List.of(expected)), ""), List.of());
        r.scores = route;
        r.refinable = true;
        r.refined = new QuestionRefiner.Result(List.of(modelIntent), modelIntent, "rewrite", null, false, "");
        r.rewriteScores = scores(rewriteIntent, 1.0, "about-report", 0.2);
        r.plan = plannerOp == null ? List.of() : List.of(new GraphQuery(plannerOp, List.of()));
        return r;
    }

    @Test
    void bandMetricsTellLostHarmCaughtAndMissedApart() {
        Thresholds th = new Thresholds("t", 0.51, 0.10, 0.80);
        List<Row> rows = List.of(
                // Correct, just over T (0.53), rewording agrees: confirmed.
                row("deploy-order", scores("deploy-order", 0.53, "externals", 0.30), "deploy-order", "deploy-order", "deploy-order"),
                // Correct, just over M (lead 0.11), rewording disagrees, planner correct: lost coverage only.
                row("externals", scores("externals", 0.70, "async", 0.59), "async", "async", "externals"),
                // Correct, just over M, rewording disagrees, planner wrong: harm.
                row("async", scores("async", 0.70, "externals", 0.59), "externals", "externals", "db-users"),
                // WRONG route (expected externals, routed async), just over T, rewording disagrees: caught.
                row("externals", scores("async", 0.54, "externals", 0.40), "externals", "externals", "externals"),
                // WRONG, just over T, rewording fails (model intent off the shortlist by routing elsewhere): missed.
                row("externals", scores("async", 0.54, "externals", 0.40), "externals", "db-users", "externals"),
                // WRONG but comfortably confident (0.75, lead 0.30): outside a narrow band.
                row("externals", scores("async", 0.75, "externals", 0.45), "externals", "externals", "externals"),
                // Deferred (0.45): not confident, not counted here.
                row("externals", scores("externals", 0.45, "async", 0.40), "externals", "externals", "externals"));
        BandMetrics m = BandMetrics.of(rows, th, new Band(0.05, 0.05, false));
        assertEquals(6, m.confident);
        assertEquals(3, m.confidentWrong);
        assertEquals(5, m.inBand);
        assertEquals(1, m.agree);
        assertEquals(3, m.disagree);
        assertEquals(1, m.failed);
        assertEquals(1, m.lost);
        assertEquals(1, m.harm);
        assertEquals(1, m.caught);
        assertEquals(1, m.missed);
        assertEquals(1, m.outside);

        BandMetrics all = BandMetrics.of(rows, th, new Band(0, 0, true));
        assertEquals(6, all.inBand);
        assertEquals(2, all.caught);
        assertEquals(0, all.outside);
        assertEquals(0, BandMetrics.of(rows, th, new Band(0, 0, false)).inBand);
    }

    @Test
    void bandSelectionCatchesWrongRoutesWithoutHarmAndTakesTheNarrowestSuchBand() {
        Thresholds cur = new Thresholds("current", 0.51, 0.10, 0.80);
        Thresholds loose = new Thresholds("loose", 0.45, 0.05, 0.80);
        Row harmOnWideLead = // correct at both; disagree + planner wrong; lead 0.16: harm once band_lead > 0.06
                row("async", scores("async", 0.70, "externals", 0.54), "externals", "externals", "db-users");
        Row wrongAtLoose = // wrong at the loose thresholds only (0.48 < 0.51); 0.03 over loose T, lead 0.075 is 0.025 over loose M
                row("externals", scores("async", 0.48, "externals", 0.405), "externals", "externals", "externals");
        Row comfortable = // correct, comfortably confident, agrees: no effect in any band
                row("deploy-order", scores("deploy-order", 0.60, "externals", 0.30), "deploy-order", "deploy-order", "deploy-order");

        Band b = selectBand(List.of(harmOnWideLead, wrongAtLoose, comfortable), cur, List.of(loose), bandGrid());
        assertTrue(!b.all, b.label());
        // One caught, by band_score ≥ 0.04 or band_lead ≥ 0.03; the rule takes the narrower (score first): 0.00/0.03.
        assertEquals(0.0, b.score, 1e-9, b.label());
        assertEquals(0.03, b.lead, 1e-9, b.label());

        // The same wrong row with a wide lead can only be caught by band_score ≥ 0.04 — but a
        // correct row 0.02 over T is harmed by band_score ≥ 0.03. Infeasible: the band stays off.
        Row harmOnScore = row("async", scores("async", 0.53, "externals", 0.20), "externals", "externals", "db-users");
        Row wrongWideLead = row("externals", scores("async", 0.48, "externals", 0.10), "externals", "externals", "externals");
        assertTrue(selectBand(List.of(harmOnScore, wrongWideLead, comfortable), cur, List.of(loose), bandGrid()).off());

        // Nothing to catch anywhere: off.
        assertTrue(selectBand(List.of(harmOnWideLead, comfortable), cur, List.of(loose), bandGrid()).off());
    }
}
