package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Routes a question to a graph-query intent by meaning rather than by keyword.
 *
 * Each intent carries a handful of example phrasings in both languages. The examples
 * are embedded once; a question is scored against every example by cosine and takes
 * the intent of its nearest one, provided the score clears a threshold and beats the
 * runner-up intent by a margin. The question's vector is the one the passage
 * retriever already computes, so routing costs no extra call.
 *
 * Why this replaced the keyword rules: the rules were an enumeration, and the first
 * real run produced two phrasings the enumeration had not thought of within four
 * questions. Examples generalise — a paraphrase lands near the examples it means —
 * and adding an intent is adding sentences, not regular expressions. What remains
 * rule-shaped is one negation check, because "edges that were observed" and "edges
 * that were NOT observed" sit close together in embedding space and mean opposite
 * things.
 *
 * When the router is not confident, the caller falls back to the model planner, which
 * sees the node ids and handles what neither examples nor rules anticipated.
 */
public final class SemanticRouter {

    /** Anything that turns texts into vectors; {@code null} on failure. */
    public interface Embedder {
        List<double[]> embed(List<String> texts);
    }

    /** One routable meaning: the queries it expands to and the phrasings that mean it. */
    public static final class Intent {
        public final String name;
        /** Node arguments the ops take: 0, 1 (each named node, up to two) or 2 (a pair). */
        public final int arity;
        public final List<String> ops;
        public final List<String> examples;
        /** True for questions about the report itself: no query, and no planner call either. */
        public final boolean aboutReport;

        Intent(String name, int arity, List<String> ops, boolean aboutReport, String... examples) {
            this.name = name;
            this.arity = arity;
            this.ops = ops;
            this.aboutReport = aboutReport;
            this.examples = List.of(examples);
        }
    }

    public static final class Decision {
        public final String intent;
        public final double score;
        public final String runnerUp;
        public final double runnerUpScore;
        public final boolean confident;
        public final List<GraphQuery> queries;
        public final boolean skipLlm;

        Decision(String intent, double score, String runnerUp, double runnerUpScore,
                 boolean confident, List<GraphQuery> queries, boolean skipLlm) {
            this.intent = intent;
            this.score = score;
            this.runnerUp = runnerUp;
            this.runnerUpScore = runnerUpScore;
            this.confident = confident;
            this.queries = queries;
            this.skipLlm = skipLlm;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s %.2f (next %s %.2f)%s", intent, score, runnerUp, runnerUpScore,
                    confident ? "" : " unsure");
        }
    }

    public static final List<Intent> INTENTS = List.of(
            new Intent("dependencies-of", 1, List.of("dependencies-of"), false,
                    "X 依賴哪些服務？", "X 會呼叫誰？", "X 用到了什麼？", "X 的下游有哪些？", "X 需要連到哪些東西？",
                    "what does X depend on?", "what does X call?", "which services does X use?", "what is downstream of X?"),
            new Intent("dependents-of", 1, List.of("dependents-of"), false,
                    "誰依賴 X？", "誰會呼叫 X？", "X 的上游是誰？", "哪些服務會用到 X？", "X 被誰呼叫？",
                    "who depends on X?", "who calls X?", "which services use X?", "what is upstream of X?"),
            new Intent("impact-of", 1, List.of("impact-of"), false,
                    "如果 X 掛了會影響誰？", "改了 X 會波及哪些服務？", "X 故障的影響範圍有多大？", "X 停掉之後哪些功能會壞？", "X 出問題誰會受影響？",
                    "what breaks if X goes down?", "who is affected if X fails?", "what is the blast radius of X?", "impact of changing X?"),
            new Intent("startup-needs", 1, List.of("startup-needs"), false,
                    "X 要能運作，前面得先起哪些服務？", "X 啟動前需要哪些東西在跑？", "X 的前置依賴是什麼？", "要讓 X 正常，哪些服務必須先好？",
                    "what must be running before X works?", "what does X need to start?", "prerequisites for X?", "what has to be up for X?"),
            new Intent("path", 2, List.of("path"), false,
                    "X 怎麼連到 Y？", "X 到 Y 的路徑是什麼？", "X 會不會呼叫 Y？", "X 跟 Y 之間怎麼走？", "從 X 到 Y 要經過誰？",
                    "how does X reach Y?", "does X call Y?", "is there a path from X to Y?", "how are X and Y connected?"),
            new Intent("uncovered", 0, List.of("uncovered", "unobserved-edges"), false,
                    "哪些邊沒跑到？", "哪些邊沒有被流量覆蓋？", "哪些邊是宣告了但沒觀測到的？", "覆蓋率是多少？", "還有哪些呼叫沒被驅動到？", "流量沒經過哪些邊？",
                    "which edges were never observed at runtime?", "what was not exercised by traffic?", "what is the runtime coverage?", "which declared edges have no traffic?"),
            new Intent("observed-edges", 0, List.of("observed-edges"), false,
                    "哪些邊有被觀測到？", "哪幾條是 runtime 觀測到的？", "實線的邊有哪些？", "Istio 真的看到哪些呼叫？", "有流量的邊是哪些？",
                    "which edges did Istio actually observe?", "what was seen at runtime?", "which calls have runtime evidence?", "list the observed edges"),
            new Intent("db-users", 0, List.of("db-users"), false,
                    "哪些服務有用到資料庫？", "誰會連資料庫？", "資料庫的依賴有哪些？", "哪些服務會寫入 DB？", "資料層的邊有哪些？",
                    "which services use a database?", "who talks to the db?", "what are the datastore dependencies?", "which services persist data?"),
            new Intent("deploy-order", 0, List.of("deploy-order"), false,
                    "建議的部署順序是什麼？", "應該先部署哪個服務？", "啟動順序怎麼排？", "照這張圖要怎麼排上線順序？", "先起哪些、後起哪些？",
                    "what order should we deploy in?", "what is the start-up order?", "what should be brought up first?", "deployment sequence?"),
            new Intent("undeployed", 0, List.of("undeployed"), false,
                    "哪些服務沒部署？", "有引用但沒在跑的服務有哪些？", "叢集裡缺了哪些服務？", "哪些是宣告了但沒上線的？",
                    "which services are not deployed?", "what is referenced but missing from the cluster?", "which workloads are not running?"),
            new Intent("externals", 0, List.of("externals"), false,
                    "有哪些外部依賴？", "有沒有呼叫第三方服務？", "會連到 mesh 外面的有哪些？", "外部主機有哪些？",
                    "what are the external dependencies?", "does it call any third-party API?", "which external hosts are called?"),
            new Intent("async", 0, List.of("async"), false,
                    "有沒有訊息佇列？", "非同步的邊有哪些？", "有用 Kafka 或 RabbitMQ 嗎？", "誰在發事件、誰在收？",
                    "is there a message broker?", "what asynchronous communication is there?", "any queues or events?", "who publishes and who consumes?"),
            new Intent("mentioned-only", 0, List.of("mentioned-only"), false,
                    "哪些邊只被文件提到？", "只有文件說、沒有程式證據的邊有哪些？", "點線的邊是哪些？", "哪些依賴沒有使用證據？",
                    "which edges are mentioned only?", "which edges have no usage evidence?", "what is documented but not evidenced in code?"),
            new Intent("about-report", 0, List.of(), true,
                    "這份報告有查過叢集嗎？", "這份報告的限制是什麼？", "報告是怎麼產生的？", "收集狀態如何？", "報告和圖有沒有矛盾？", "第 5 節是誰寫的？", "這是 greenfield 還是 runtime？",
                    "what are the limitations of this report?", "was the cluster queried?", "what is the collection status?", "how was this report produced?", "are there conflicts in the report?")
    );

    /** "沒觀測到" sits next to "觀測到"; the one word decides which intent is meant. */
    private static final Pattern NEGATION = Pattern.compile(
            "沒|未|不曾|從未|never|not\\b|n't|without|no traffic|no runtime", Pattern.CASE_INSENSITIVE);

    static final double DEFAULT_THRESHOLD = 0.55;
    static final double DEFAULT_MARGIN = 0.03;
    /** Above this the runner-up margin is not required: the match is unambiguous on its own. */
    static final double DEFAULT_HIGH = 0.72;

    private final Embedder embedder;
    private final double threshold;
    private final double margin;
    private final double high;

    private final List<Intent> exampleOwner = new ArrayList<>();
    private final List<String> exampleText = new ArrayList<>();
    private volatile List<double[]> exampleVectors;

    public SemanticRouter(Embedder embedder) {
        this(embedder, DEFAULT_THRESHOLD, DEFAULT_MARGIN, DEFAULT_HIGH);
    }

    public SemanticRouter(Embedder embedder, double threshold, double margin, double high) {
        this.embedder = embedder;
        this.threshold = threshold;
        this.margin = margin;
        this.high = high;
        for (Intent intent : INTENTS) {
            for (String example : intent.examples) {
                exampleOwner.add(intent);
                exampleText.add(example);
            }
        }
    }

    /** Embeds the examples once; false when the embedder is unavailable (routing then never happens). */
    public synchronized boolean ensureIndexed() {
        if (exampleVectors != null) return true;
        List<double[]> vectors = embedder == null ? null : embedder.embed(exampleText);
        if (vectors == null || vectors.size() != exampleText.size()) return false;
        exampleVectors = vectors;
        return true;
    }

    /** Per-intent score: the best cosine over that intent's examples. Insertion order of {@link #INTENTS}. */
    public Map<String, Double> scores(double[] questionVector) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Intent intent : INTENTS) out.put(intent.name, Double.NEGATIVE_INFINITY);
        if (questionVector == null || !ensureIndexed()) return out;
        for (int i = 0; i < exampleVectors.size(); i++) {
            double s = ChunkRetriever.cosine(questionVector, exampleVectors.get(i));
            String name = exampleOwner.get(i).name;
            if (s > out.get(name)) out.put(name, s);
        }
        return out;
    }

    /**
     * @param questionVector the question's embedding (null → not confident)
     * @param question       the question text, for the negation check
     * @param mentioned      the nodes the question names, for the arguments
     */
    public Decision route(double[] questionVector, String question, List<DependencyGraph.Node> mentioned) {
        Map<String, Double> scores = scores(questionVector);
        String best = null, second = null;
        double bestScore = Double.NEGATIVE_INFINITY, secondScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                second = best;
                secondScore = bestScore;
                best = e.getKey();
                bestScore = e.getValue();
            } else if (e.getValue() > secondScore) {
                second = e.getKey();
                secondScore = e.getValue();
            }
        }
        if (best == null || bestScore == Double.NEGATIVE_INFINITY) {
            return new Decision("none", 0, "none", 0, false, List.of(), false);
        }

        // The one rule: a negated "observed" is "not observed".
        if ("observed-edges".equals(best) && question != null && NEGATION.matcher(question).find()) {
            best = "uncovered";
        }

        boolean confident = bestScore >= high || (bestScore >= threshold && bestScore - secondScore >= margin);
        Intent intent = byName(best);
        List<GraphQuery> queries = new ArrayList<>();
        if (confident && intent != null) {
            List<DependencyGraph.Node> named = mentioned == null ? List.of() : mentioned;
            if (intent.arity == 0) {
                for (String op : intent.ops) queries.add(new GraphQuery(op, List.of()));
            } else if (intent.arity == 1) {
                for (int i = 0; i < named.size() && i < 2; i++) {
                    for (String op : intent.ops) queries.add(new GraphQuery(op, List.of(named.get(i).id)));
                }
                if (named.isEmpty()) confident = false; // the model planner can still resolve the node
            } else {
                if (named.size() >= 2) queries.add(new GraphQuery("path", List.of(named.get(0).id, named.get(1).id)));
                else confident = false;
            }
        }
        boolean skipLlm = confident && intent != null && intent.aboutReport;
        return new Decision(best, bestScore, second == null ? "none" : second, secondScore == Double.NEGATIVE_INFINITY ? 0 : secondScore,
                confident, queries, skipLlm);
    }

    static Intent byName(String name) {
        for (Intent i : INTENTS) if (i.name.equals(name)) return i;
        return null;
    }
}
