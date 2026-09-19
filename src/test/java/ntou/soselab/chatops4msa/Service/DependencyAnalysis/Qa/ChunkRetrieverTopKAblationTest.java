package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.CalibrationSupport.EmbeddingCache;
import ntou.soselab.chatops4msa.Service.NLPService.EmbeddingClient;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A small ablation of passage retrieval: how many passages (top-k), and which ranker —
 * BM25 alone, embeddings alone, or their reciprocal-rank fusion as production runs it.
 *
 * Runs only with {@code -Dqa.archive=<path to a dep-reports/*.json>}: the corpus is a
 * real archive, because recall on a toy corpus says nothing about a report's headings.
 * Relevance comes from {@code src/test/resources/qa/retrieval-labels.tsv} — a passage is
 * relevant when its "source › title" contains one of the labelled substrings. The
 * labels shipped are examples to be corrected against the archive; the run writes the
 * archive's headings next to its results for exactly that.
 *
 * Every mode goes through the same selection production applies after ranking (stop at
 * k, skip a passage that would overflow the character budget, always keep the first),
 * so the three differ only in their ranking.
 */
public class ChunkRetrieverTopKAblationTest {

    /**
     * Wide enough to see the recall curve flatten rather than stop while it still climbs:
     * the first round stopped at 16 with recall still rising, which is no answer to "what
     * is the best k". Values above the corpus size are dropped, and the corpus size itself
     * is added, so the last point is always "every passage".
     */
    static final int[] KS = {2, 4, 6, 8, 10, 12, 16, 20, 24, 28, 32, 40, 48, 64, 80};

    /** Marginal recall per extra passage below this counts as flat (one labelled question ≈ 0.07). */
    static final double KNEE_GAIN_PER_PASSAGE = 0.01;
    /** A k whose average context costs more than this share of the passage budget is not taken. */
    static final double BUDGET_SHARE_CEILING = 0.75;

    enum Mode { BM25, EMBEDDING, RRF }

    record Point(int k, double recall, double contextChars) {
    }

    /**
     * Choosing k from the RRF curve, written before the numbers are read (the old rule —
     * "95% of the maximum recall" — is meaningless once the grid reaches the corpus size,
     * where recall is 1 by construction).
     *
     * <p>Take the smallest k whose marginal recall per extra passage has fallen below
     * {@link #KNEE_GAIN_PER_PASSAGE} — the knee, past which passages stop paying for
     * themselves — among the k whose average context stays within
     * {@link #BUDGET_SHARE_CEILING} of the passage budget. If every k on the grid is still
     * climbing, take the largest k inside the budget and say the curve had not flattened.
     */
    static int selectK(List<Point> curve, int budget) {
        List<Point> affordable = curve.stream().filter(p -> p.contextChars() <= BUDGET_SHARE_CEILING * budget).toList();
        if (affordable.isEmpty()) return curve.isEmpty() ? 0 : curve.get(0).k();
        for (int i = 1; i < affordable.size(); i++) {
            Point prev = affordable.get(i - 1), cur = affordable.get(i);
            double gainPerPassage = (cur.recall() - prev.recall()) / (cur.k() - prev.k());
            if (gainPerPassage < KNEE_GAIN_PER_PASSAGE) return prev.k();
        }
        return affordable.get(affordable.size() - 1).k();
    }

    /** Chunk indices in production's fused order (or BM25's, with no vector). */
    static List<Integer> fusedOrder(List<TextChunk> chunks, String question, double[] vector) {
        Map<TextChunk, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < chunks.size(); i++) index.put(chunks.get(i), i);
        List<Integer> out = new ArrayList<>();
        for (TextChunk c : ChunkRetriever.retrieve(chunks, question, vector, Integer.MAX_VALUE, Integer.MAX_VALUE)) out.add(index.get(c));
        return out;
    }

    /** The post-ranking selection of {@link ChunkRetriever#retrieve}, applied to any ranking. */
    static List<Integer> select(List<TextChunk> chunks, List<Integer> ranked, int k, int budget) {
        List<Integer> out = new ArrayList<>();
        int used = 0;
        for (int i : ranked) {
            if (out.size() >= k) break;
            int len = chunks.get(i).text.length();
            if (!out.isEmpty() && used + len > budget) continue;
            out.add(i);
            used += len;
        }
        return out;
    }

    static double recall(List<Integer> retrieved, Set<Integer> relevant) {
        if (relevant.isEmpty()) return Double.NaN;
        int hit = 0;
        for (int i : retrieved) if (relevant.contains(i)) hit++;
        return (double) hit / relevant.size();
    }

    /** Reciprocal rank of the first relevant passage within the retrieved list; 0 when none. */
    static double reciprocalRank(List<Integer> retrieved, Set<Integer> relevant) {
        for (int r = 0; r < retrieved.size(); r++) if (relevant.contains(retrieved.get(r))) return 1.0 / (r + 1);
        return 0;
    }

    static Set<Integer> relevant(List<TextChunk> chunks, List<String> substrings) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            String head = heading(chunks.get(i)).toLowerCase(Locale.ROOT);
            for (String s : substrings) if (!s.isBlank() && head.contains(s.trim().toLowerCase(Locale.ROOT))) out.add(i);
        }
        return out;
    }

    static String heading(TextChunk c) {
        return c.title.isEmpty() ? c.source : c.source + " › " + c.title;
    }

    @Test
    void ablateTopKAndRanker() throws Exception {
        String archivePath = System.getProperty("qa.archive", "");
        Assumptions.assumeTrue(!archivePath.isBlank(), "pass -Dqa.archive=<dep-reports/…json> to run");
        ReportArchive archive = ReportArchive.fromJson(new JSONObject(Files.readString(Path.of(archivePath))));
        List<TextChunk> chunks = archive.chunks;
        assertTrue(!chunks.isEmpty(), "the archive has no chunks");
        int budget = passageBudget();

        List<String> questions = new ArrayList<>();
        List<List<String>> labels = new ArrayList<>();
        for (String[] cols : CalibrationSupport.readTsv(CalibrationSupport.LABELS_RESOURCE, "question")) {
            if (cols.length < 2) continue;
            questions.add(cols[0].trim());
            labels.add(List.of(cols[1].split("\\|")));
        }

        // Question vectors: only when the archive carries chunk vectors to compare with.
        Properties p = CalibrationSupport.applicationProperties();
        List<double[]> vectors = null;
        String vectorNote;
        if (!archive.hasEmbeddings()) {
            vectorNote = "archive has no chunk embeddings: EMBEDDING and RRF are skipped (RRF would equal BM25)";
        } else {
            EmbeddingClient client = CalibrationSupport.clientOrNull(p);
            EmbeddingCache cache = new EmbeddingCache(CalibrationSupport.CACHE_FILE, CalibrationSupport.embeddingModel(p),
                    client == null ? null : client::embed);
            vectors = cache.embed(questions);
            vectorNote = vectors == null
                    ? "question embeddings unavailable (no key and not cached): EMBEDDING and RRF are skipped"
                    : "question embeddings from `" + CalibrationSupport.embeddingModel(p)
                    + "` — must be the model the archive's chunks were embedded with";
        }

        Files.createDirectories(CalibrationSupport.OUT_DIR);
        StringBuilder headings = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            headings.append(i).append('\t').append(chunks.get(i).text.length()).append('\t').append(heading(chunks.get(i))).append('\n');
        }
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("archive-headings.txt"), headings.toString());

        // The grid, trimmed to the corpus and always ending at "every passage".
        List<Integer> ks = new ArrayList<>();
        for (int k : KS) if (k < chunks.size()) ks.add(k);
        ks.add(chunks.size());

        StringBuilder csv = new StringBuilder(CalibrationSupport.csvRow("mode", "k", "questions", "recall_at_k", "mrr_at_k", "hit_at_k", "precision_at_k", "avg_passages", "avg_context_chars"));
        StringBuilder perQuestion = new StringBuilder(CalibrationSupport.csvRow("question", "mode", "k", "relevant", "retrieved", "recall", "rr"));
        List<String> unmatched = new ArrayList<>();
        StringBuilder md = new StringBuilder("# Retrieval top-k ablation\n\n");
        md.append("- archive: `").append(archivePath).append("` (").append(archive.repoName).append(", ").append(archive.mode)
                .append("), ").append(chunks.size()).append(" chunks; passage budget ").append(budget).append(" chars\n");
        md.append("- ").append(vectorNote).append('\n');
        md.append("- relevance: labelled heading substrings (retrieval-labels.tsv) — check them against archive-headings.txt\n\n");
        md.append("| mode | k | n | Recall@k | MRR@k | Hit@k | Precision@k | avg passages | avg context chars |\n|---|---|---|---|---|---|---|---|---|\n");

        List<Point> rrfCurve = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            if (mode != Mode.BM25 && vectors == null) continue;
            for (int k : ks) {
                double sumRecall = 0, sumRr = 0, sumHit = 0, sumChars = 0, sumPrecision = 0, sumPassages = 0;
                int n = 0;
                for (int q = 0; q < questions.size(); q++) {
                    Set<Integer> rel = relevant(chunks, labels.get(q));
                    if (rel.isEmpty()) {
                        if (mode == Mode.BM25 && k == KS[0]) unmatched.add(questions.get(q) + " ← " + labels.get(q));
                        continue;
                    }
                    double[] v = vectors == null ? null : vectors.get(q);
                    List<Integer> ranked = switch (mode) {
                        case BM25 -> fusedOrder(chunks, questions.get(q), null);
                        case EMBEDDING -> ChunkRetriever.rankSemantic(chunks, v);
                        case RRF -> fusedOrder(chunks, questions.get(q), v);
                    };
                    List<Integer> got = select(chunks, ranked, k, budget);
                    double r = recall(got, rel), rr = reciprocalRank(got, rel);
                    int chars = 0;
                    for (int i : got) chars += chunks.get(i).render().length();
                    int hits = 0;
                    for (int i : got) if (rel.contains(i)) hits++;
                    sumRecall += r;
                    sumRr += rr;
                    sumHit += rr > 0 ? 1 : 0;
                    sumChars += chars;
                    sumPassages += got.size();
                    sumPrecision += got.isEmpty() ? 0 : (double) hits / got.size();
                    n++;
                    perQuestion.append(CalibrationSupport.csvRow(questions.get(q), mode, k, rel.toString(), got.toString(), r, rr));
                }
                if (n == 0) continue;
                csv.append(CalibrationSupport.csvRow(mode, k, n, sumRecall / n, sumRr / n, sumHit / n,
                        sumPrecision / n, sumPassages / n, sumChars / n));
                md.append(String.format(Locale.ROOT, "| %s | %d | %d | %.3f | %.3f | %.3f | %.3f | %.1f | %.0f |%n",
                        mode, k, n, sumRecall / n, sumRr / n, sumHit / n, sumPrecision / n, sumPassages / n, sumChars / n));
                if (mode == Mode.RRF) rrfCurve.add(new Point(k, sumRecall / n, sumChars / n));
            }
        }

        if (!rrfCurve.isEmpty()) {
            int chosen = selectK(rrfCurve, budget);
            md.append("\n## Chosen k\n\n");
            md.append("Rule (written before the run): the smallest k whose marginal recall per extra passage falls below ")
                    .append(KNEE_GAIN_PER_PASSAGE).append(", among the k whose average context stays within ")
                    .append((int) (BUDGET_SHARE_CEILING * 100)).append("% of the ").append(budget)
                    .append("-character passage budget.\n\n");
            md.append("**k = ").append(chosen).append("** (production currently uses ")
                    .append(CalibrationSupport.applicationProperties().getProperty("dependency.qa.top-k", "?")).append(").\n\n");
            md.append("| k | Recall@k | gain per extra passage | avg context chars |\n|---|---|---|---|\n");
            for (int i = 0; i < rrfCurve.size(); i++) {
                Point point = rrfCurve.get(i);
                String gain = i == 0 ? "—" : String.format(Locale.ROOT, "%.4f",
                        (point.recall() - rrfCurve.get(i - 1).recall()) / (point.k() - rrfCurve.get(i - 1).k()));
                md.append(String.format(Locale.ROOT, "| %d | %.3f | %s | %.0f |%n", point.k(), point.recall(), gain, point.contextChars()));
            }
        }
        if (!unmatched.isEmpty()) {
            md.append("\n## Labels that match no chunk (excluded — fix them against archive-headings.txt)\n\n");
            for (String u : unmatched) md.append("- ").append(u).append('\n');
        }
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("topk-ablation.csv"), csv.toString());
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("topk-ablation-per-question.csv"), perQuestion.toString());
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("topk-ablation.md"), md.toString());
        System.out.println(md);
    }

    /** Production's budget, read rather than copied so the ablation follows it if it changes. */
    private static int passageBudget() {
        try {
            Field f = ReportQaService.class.getDeclaredField("PASSAGE_BUDGET_CHARS");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Exception e) {
            return 24000;
        }
    }

    // ---------- offline: the selection replays production, and the metrics ----------

    @Test
    void selectionOverTheFusedOrderIsExactlyWhatRetrieveReturns() {
        List<TextChunk> chunks = new ArrayList<>();
        chunks.add(new TextChunk("r#0", "report", "4. Candidates › Candidate: frontend -> userservice", "frontend calls userservice for login. " + "x".repeat(900)));
        chunks.add(new TextChunk("r#1", "report", "4. Candidates › Candidate: frontend -> contacts", "frontend calls contacts."));
        chunks.add(new TextChunk("r#2", "report", "5. Infrastructure Dependencies", "userservice uses accounts-db. " + "y".repeat(1500)));
        chunks.add(new TextChunk("r#3", "report", "9. Unknowns", "userservice retry policy is unknown."));
        chunks.get(0).embedding = new double[]{1, 0};
        chunks.get(1).embedding = new double[]{0.8, 0.2};
        chunks.get(2).embedding = new double[]{0, 1};
        chunks.get(3).embedding = new double[]{0.5, 0.5};
        double[] q = {0.9, 0.1};
        String question = "who does frontend call for userservice login?";

        Map<TextChunk, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < chunks.size(); i++) index.put(chunks.get(i), i);
        for (int k : new int[]{1, 2, 3, 4}) {
            for (int budget : new int[]{100, 1000, 3000}) {
                for (double[] v : new double[][]{null, q}) {
                    List<Integer> expected = new ArrayList<>();
                    for (TextChunk c : ChunkRetriever.retrieve(chunks, question, v, k, budget)) expected.add(index.get(c));
                    assertEquals(expected, select(chunks, fusedOrder(chunks, question, v), k, budget), "k=" + k + " budget=" + budget);
                }
            }
        }
    }

    @Test
    void theKRuleStopsAtTheKneeAndInsideTheBudget() {
        int budget = 24000;
        // Climbs steeply to 8, then flattens: 8 -> 16 gains 0.02 over eight passages.
        List<Point> knee = List.of(new Point(2, 0.40, 1400), new Point(4, 0.55, 2900),
                new Point(8, 0.70, 6400), new Point(16, 0.72, 14400), new Point(32, 0.74, 20000));
        assertEquals(8, selectK(knee, budget));

        // Still climbing everywhere, but k=32 already costs more than 75% of the budget.
        List<Point> climbing = List.of(new Point(8, 0.30, 6400), new Point(16, 0.60, 14400),
                new Point(32, 0.90, 22000));
        assertEquals(16, selectK(climbing, budget));

        // A curve that is flat from the start takes the first point, not the cheapest lie.
        assertEquals(2, selectK(List.of(new Point(2, 0.80, 1400), new Point(4, 0.80, 2900)), budget));
    }

    @Test
    void recallAndReciprocalRank() {
        Set<Integer> rel = Set.of(3, 7);
        assertEquals(0.5, recall(List.of(1, 3, 5), rel), 1e-9);
        assertEquals(0.5, reciprocalRank(List.of(1, 3, 5), rel), 1e-9);
        assertEquals(0.0, reciprocalRank(List.of(1, 2), rel), 1e-9);
        assertTrue(Double.isNaN(recall(List.of(1), Set.of())));

        List<TextChunk> chunks = List.of(
                new TextChunk("a", "report", "1. Collection Status", "t"),
                new TextChunk("b", "traffic run report", "", "t"));
        assertEquals(Set.of(0), relevant(chunks, List.of("collection status")));
        assertEquals(Set.of(1), relevant(chunks, List.of("Traffic run")));
    }
}
