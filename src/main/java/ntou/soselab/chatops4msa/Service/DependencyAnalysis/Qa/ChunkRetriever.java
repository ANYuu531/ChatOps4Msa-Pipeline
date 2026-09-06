package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the passages of a report archive that are relevant to a question.
 *
 * Two rankers, fused:
 * <ul>
 *   <li><b>Lexical (BM25)</b> — always available, deterministic, and exactly right for
 *       the identifiers this corpus is full of: a question naming {@code ledgerwriter}
 *       or {@code accounts-db} should surface the passages that spell that name.</li>
 *   <li><b>Embedding (cosine)</b> — used when the chunks and the question carry vectors,
 *       so a paraphrase ("which services talk to the database?") still finds the
 *       passages that say "persistence", "JPA", "PostgreSQL".</li>
 * </ul>
 * The two rankings are combined by reciprocal rank fusion, which needs no calibration
 * between a BM25 score and a cosine — and degrades gracefully to pure BM25 when there
 * are no vectors (embeddings disabled, or the embedding call failed at archive time).
 *
 * The tokenizer knows two things about this corpus: hyphenated identifiers are indexed
 * both whole and by part ({@code accounts-db} → {@code accounts-db, accounts, db}), and
 * CJK text has no word boundaries, so it is indexed as characters and character bigrams
 * — a Chinese question still matches the Chinese passages of a report.
 */
public final class ChunkRetriever {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int RRF_K = 60;

    private static final Pattern RUN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_\\-.]*");
    private static final Pattern SEPARATOR = Pattern.compile("[_\\-.]+");

    private ChunkRetriever() {
    }

    /**
     * @param chunks            the corpus
     * @param question          the user's question
     * @param questionEmbedding the question's vector, or {@code null} for lexical-only
     * @param topK              at most this many passages
     * @param charBudget        stop adding passages once their text exceeds this many
     *                          characters (the first passage is always kept)
     * @return the passages, best first; empty when nothing matches at all
     */
    public static List<TextChunk> retrieve(List<TextChunk> chunks, String question,
                                           double[] questionEmbedding, int topK, int charBudget) {
        if (chunks == null || chunks.isEmpty() || question == null || question.isBlank()) return List.of();

        List<Integer> lexical = rankLexical(chunks, question);
        List<Integer> semantic = questionEmbedding == null ? List.of() : rankSemantic(chunks, questionEmbedding);

        Map<Integer, Double> fused = new HashMap<>();
        for (int rank = 0; rank < lexical.size(); rank++) {
            fused.merge(lexical.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < semantic.size(); rank++) {
            fused.merge(semantic.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }

        List<Map.Entry<Integer, Double>> ranked = new ArrayList<>(fused.entrySet());
        ranked.sort((a, b) -> {
            int byScore = Double.compare(b.getValue(), a.getValue());
            return byScore != 0 ? byScore : Integer.compare(a.getKey(), b.getKey()); // stable
        });

        List<TextChunk> out = new ArrayList<>();
        int used = 0;
        for (Map.Entry<Integer, Double> entry : ranked) {
            if (out.size() >= topK) break;
            TextChunk chunk = chunks.get(entry.getKey());
            if (!out.isEmpty() && used + chunk.text.length() > charBudget) continue;
            out.add(chunk);
            used += chunk.text.length();
        }
        return out;
    }

    /** Chunk indices with a positive BM25 score, best first. */
    static List<Integer> rankLexical(List<TextChunk> chunks, String question) {
        int n = chunks.size();
        List<List<String>> docs = new ArrayList<>(n);
        Map<String, Integer> df = new HashMap<>();
        double totalLength = 0;
        for (TextChunk chunk : chunks) {
            List<String> tokens = tokenize(chunk.indexedText());
            docs.add(tokens);
            totalLength += tokens.size();
            for (String t : new LinkedHashSet<>(tokens)) df.merge(t, 1, Integer::sum);
        }
        double avgdl = n == 0 ? 1 : Math.max(1, totalLength / n);

        Set<String> query = new LinkedHashSet<>(tokenize(question));
        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            List<String> tokens = docs.get(i);
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) tf.merge(t, 1, Integer::sum);
            double score = 0;
            for (String q : query) {
                Integer f = tf.get(q);
                if (f == null) continue;
                int d = df.getOrDefault(q, 0);
                double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
                double norm = f * (K1 + 1) / (f + K1 * (1 - B + B * tokens.size() / avgdl));
                score += idf * norm;
            }
            scores[i] = score;
        }
        return order(scores, 0);
    }

    /** Chunk indices with an embedding, by cosine similarity to the question, best first. */
    static List<Integer> rankSemantic(List<TextChunk> chunks, double[] question) {
        double[] scores = new double[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            double[] v = chunks.get(i).embedding;
            scores[i] = v == null ? Double.NEGATIVE_INFINITY : cosine(question, v);
        }
        return order(scores, Double.NEGATIVE_INFINITY);
    }

    /** Indices whose score exceeds {@code floor}, descending; ties keep corpus order. */
    private static List<Integer> order(double[] scores, double floor) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) if (scores[i] > floor) idx.add(i);
        idx.sort((a, b) -> {
            int byScore = Double.compare(scores[b], scores[a]);
            return byScore != 0 ? byScore : Integer.compare(a, b);
        });
        return idx;
    }

    static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return Double.NEGATIVE_INFINITY;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return Double.NEGATIVE_INFINITY;
        return dot / Math.sqrt(na * nb);
    }

    /**
     * Lower-cases and splits into index terms. Latin runs are split on non-word
     * characters; a run containing {@code - _ .} is emitted whole and by part; CJK
     * characters are emitted singly and as bigrams.
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) return tokens;
        Matcher m = RUN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String run = m.group();
            StringBuilder latin = new StringBuilder();
            StringBuilder cjk = new StringBuilder();
            for (int i = 0; i < run.length(); ) {
                int cp = run.codePointAt(i);
                if (isCjk(cp)) {
                    flushLatin(tokens, latin);
                    cjk.appendCodePoint(cp);
                } else {
                    flushCjk(tokens, cjk);
                    latin.appendCodePoint(cp);
                }
                i += Character.charCount(cp);
            }
            flushLatin(tokens, latin);
            flushCjk(tokens, cjk);
        }
        return tokens;
    }

    private static void flushLatin(List<String> tokens, StringBuilder latin) {
        if (latin.length() == 0) return;
        String run = latin.toString().replaceAll("^[_\\-.]+|[_\\-.]+$", "");
        latin.setLength(0);
        if (run.isEmpty()) return;
        tokens.add(run);
        if (SEPARATOR.matcher(run).find()) {
            for (String part : SEPARATOR.split(run)) {
                if (part.length() >= 2 && !part.equals(run)) tokens.add(part);
            }
        }
    }

    private static void flushCjk(List<String> tokens, StringBuilder cjk) {
        if (cjk.length() == 0) return;
        int[] cps = cjk.codePoints().toArray();
        cjk.setLength(0);
        for (int i = 0; i < cps.length; i++) {
            tokens.add(new String(Character.toChars(cps[i])));
            if (i + 1 < cps.length) {
                tokens.add(new String(Character.toChars(cps[i])) + new String(Character.toChars(cps[i + 1])));
            }
        }
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
