package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.CalibrationSupport.EmbeddingCache;
import ntou.soselab.chatops4msa.Service.NLPService.EmbeddingClient;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure AI against the grounded pipeline, on the same questions and the same report.
 *
 * <p>Three arms answer each question, all with the same model at {@code temperature=0}:
 * <ol>
 *   <li><b>report-only</b> — the whole report in the prompt, no retrieval, no router, no
 *       graph. What "just ask the LLM" means.</li>
 *   <li><b>rag</b> — the passages production's retriever returns, and nothing else. The
 *       usual RAG baseline.</li>
 *   <li><b>depweaver</b> — production: the semantic router (or, when it is not confident,
 *       the query planner) turns the question into graph queries, the engine runs them,
 *       and the answer is written over their result plus the same passages.</li>
 * </ol>
 *
 * <p>Scoring is by code, not by taste: every question in {@code qa/answer-labels.tsv} has
 * a gold answer that is a set of nodes — what the graph query engine returns for the
 * labelled query. An answer is scored on the node ids it names (precision, recall, F1)
 * and on the service-shaped names it invents that the graph does not have.
 *
 * <p><b>The asymmetry is deliberate and must be reported with the numbers</b>: arm 3 is
 * handed the result of exactly the query the gold answer comes from, so it is expected to
 * win. What the experiment measures is how far the ungrounded arms fall short on
 * questions the graph can settle, and what they make up.
 *
 * <p>Runs only with {@code -Dqa.archive=<dep-reports/…json> -Dqa.baseline=true}: it costs
 * three chat calls per question.
 */
public class PureLlmBaselineTest {

    /** Arm 1 sees the report; a very long one is cut here rather than by the API. */
    static final int REPORT_CHARS = 60000;

    /** A service-ish name: two or more lowercase segments joined by dashes. */
    static final Pattern SERVICE_SHAPED = Pattern.compile("\\b[a-z][a-z0-9]*(?:-[a-z0-9]+)+\\b");

    /**
     * A line of a query result that states an <em>absence</em>. Such a line still spells
     * the node ids of the question ("no directed path between transactionhistory and
     * ledger-db"), so reading the gold answer off the raw text would make "there is no
     * relation" look like the two-node answer — and an arm could score full recall by
     * echoing the question. These lines are dropped before the gold set is read.
     */
    static boolean statesAnAbsence(String line) {
        String l = line.toLowerCase(Locale.ROOT).strip();
        return l.startsWith("- no ") || l.startsWith("no ")
                // The echo of the query itself spells its arguments: "Query: path(a, b)".
                || l.startsWith("query:")
                || l.contains("needs nothing else to start")
                || l.contains("nothing transitively depends")
                || l.contains("is not measurable")
                || l.contains("was not measured")
                || l.contains("deployment state is unknown");
    }

    /** The gold node set: what the query engine answers, minus what it says is absent. */
    static Set<String> goldFrom(String queryResult, Collection<String> ids) {
        StringBuilder kept = new StringBuilder();
        for (String line : (queryResult == null ? "" : queryResult).split("\n")) {
            if (!statesAnAbsence(line)) kept.append(line).append('\n');
        }
        return namedNodes(kept.toString(), ids);
    }

    /**
     * @param negative        the graph's answer is "nothing". Counting names cannot judge
     *                        these: "they are unrelated — ledgerwriter and balancereader
     *                        are the ones that use ledger-db" names two services and is
     *                        both correct and useful, while a list of eleven services
     *                        under "I cannot tell" names eleven and is neither. They are
     *                        left out of the averages and read by hand in the detail.
     * @param namedBeyond     services named that the question did not itself spell — the
     *                        number to look at on a negative question, not a verdict
     * @param offGraph        service-shaped names the graph does not have
     * @param offGraphAndDocs of those, the ones not in the report either: the closest this
     *                        harness gets to "made it up"
     */
    record Score(double precision, double recall, double f1, int offGraph, int offGraphAndDocs,
                 int named, int namedBeyond, boolean negative) {
    }

    /** The node ids the graph has that this text names, matched on word boundaries. */
    static Set<String> namedNodes(String text, Collection<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        String hay = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String id : ids) {
            String needle = id.toLowerCase(Locale.ROOT);
            int at = hay.indexOf(needle);
            while (at >= 0) {
                boolean leftFree = at == 0 || !Character.isLetterOrDigit(hay.charAt(at - 1));
                int end = at + needle.length();
                boolean rightFree = end >= hay.length() || !Character.isLetterOrDigit(hay.charAt(end));
                if (leftFree && rightFree) {
                    out.add(id);
                    break;
                }
                at = hay.indexOf(needle, at + 1);
            }
        }
        return out;
    }

    /** The query DSL's own vocabulary: an answer quoting `deploy-order()` names no service. */
    static final Set<String> DSL_WORDS = new LinkedHashSet<>(List.of(
            "sync-http", "edges-of-type", "db-users", "observed-edges", "unobserved-edges",
            "mentioned-only", "deploy-order", "dependencies-of", "dependents-of", "impact-of",
            "startup-needs", "graph-facts", "bank-of-anthos", "train-ticket", "sock-shop",
            "e-mail", "end-to-end", "read-only", "well-known", "up-to-date", "so-called"));

    /**
     * Service-shaped names in the text that no node of the graph has, minus the query
     * DSL's own words and anything the question itself spelled. An approximation: it
     * cannot see an invented name that reads like prose, and a hyphenated English phrase
     * can still slip through — reported as a rate, never as a count of lies.
     */
    static Set<String> offGraphNames(String text, Collection<String> ids, String question) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> known = new LinkedHashSet<>(DSL_WORDS);
        for (String id : ids) known.add(id.toLowerCase(Locale.ROOT));
        String asked = question == null ? "" : question.toLowerCase(Locale.ROOT);
        Matcher m = SERVICE_SHAPED.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String token = m.group();
            if (!known.contains(token) && !asked.contains(token)) out.add(token);
        }
        return out;
    }

    static Score score(String answer, Set<String> gold, Collection<String> ids, String question, String report) {
        Set<String> named = namedNodes(answer, ids);
        Set<String> hit = new LinkedHashSet<>(named);
        hit.retainAll(gold);
        boolean negative = gold.isEmpty();
        double precision = negative ? Double.NaN : named.isEmpty() ? 0 : (double) hit.size() / named.size();
        double recall = negative ? Double.NaN : (double) hit.size() / gold.size();
        double f1 = negative || precision + recall == 0 ? (negative ? Double.NaN : 0)
                : 2 * precision * recall / (precision + recall);
        Set<String> offGraph = offGraphNames(answer, ids, question);
        String haystack = report == null ? "" : report.toLowerCase(Locale.ROOT);
        long offDocs = offGraph.stream().filter(n -> !haystack.contains(n)).count();
        String asked = question == null ? "" : question.toLowerCase(Locale.ROOT);
        long beyond = named.stream().filter(n -> !asked.contains(n.toLowerCase(Locale.ROOT))).count();
        return new Score(precision, recall, f1, offGraph.size(), (int) offDocs, named.size(), (int) beyond, negative);
    }

    @Test
    void compareAnUngroundedModelWithTheGroundedPipeline() throws Exception {
        String archivePath = System.getProperty("qa.archive", "");
        Assumptions.assumeTrue(!archivePath.isBlank(), "pass -Dqa.archive=<dep-reports/…json> to run");
        Assumptions.assumeTrue("true".equals(System.getProperty("qa.baseline")), "pass -Dqa.baseline=true: this one calls the chat API");

        Properties props = CalibrationSupport.applicationProperties();
        ChatCompletions chat = ChatCompletions.fromOrNull(props);
        Assumptions.assumeTrue(chat != null, "no openai.api.key in src/main/resources/application.properties");

        ReportArchive archive = ReportArchive.fromJson(new JSONObject(Files.readString(Path.of(archivePath))));
        DependencyGraph graph = DependencyGraph.fromJson(archive.graphJson);
        List<String> ids = graph.getNodes().stream().map(n -> n.id).toList();
        assertTrue(!ids.isEmpty(), "the archive has no graph");

        // The questions for this archive's project.
        List<String[]> rows = new ArrayList<>();
        for (String[] c : CalibrationSupport.readTsv("/qa/answer-labels.tsv", "project")) {
            if (c.length >= 3 && archive.repoName.toLowerCase(Locale.ROOT).contains(c[0].trim().toLowerCase(Locale.ROOT))) rows.add(c);
        }
        Assumptions.assumeTrue(!rows.isEmpty(), "no labelled questions for " + archive.repoName);

        EmbeddingClient embeddings = CalibrationSupport.clientOrNull(props);
        EmbeddingCache cache = new EmbeddingCache(CalibrationSupport.CACHE_FILE, CalibrationSupport.embeddingModel(props),
                embeddings == null ? null : embeddings::embed);
        SemanticRouter router = new SemanticRouter(texts -> cache.embed(texts),
                Double.parseDouble(props.getProperty("dependency.qa.router.threshold", "0.51")),
                Double.parseDouble(props.getProperty("dependency.qa.router.margin", "0.10")),
                Double.parseDouble(props.getProperty("dependency.qa.router.high", "0.80")));
        int topK = Integer.parseInt(props.getProperty("dependency.qa.top-k", "16"));
        GraphQueryPlanner planner = new GraphQueryPlanner(null);     // only its prompt is used here
        String qaPrompt = Files.readString(Path.of("src/main/resources/prompts/report_qa.txt"));
        String report = archive.report.length() > REPORT_CHARS ? archive.report.substring(0, REPORT_CHARS) : archive.report;

        StringBuilder md = new StringBuilder("# Pure LLM vs the grounded pipeline\n\n");
        md.append("- archive: `").append(archivePath).append("` (").append(archive.repoName).append(", ").append(archive.mode).append(")\n");
        md.append("- model: `").append(chat.model()).append("`, temperature 0; ").append(rows.size()).append(" questions × 3 arms\n");
        md.append("- gold answers: the graph query engine's own result for the labelled query\n");
        md.append("- report given to arm 1: ").append(report.length()).append(" of ").append(archive.report.length()).append(" characters\n\n");

        StringBuilder csv = new StringBuilder(CalibrationSupport.csvRow("question", "arm", "gold", "named",
                "named_beyond_question", "hit_precision", "recall", "f1", "off_graph", "off_graph_and_docs", "negative", "answer_chars"));
        StringBuilder detail = new StringBuilder();
        Map<String, List<Score>> byArm = new java.util.LinkedHashMap<>();

        for (String[] row : rows) {
            String question = row[1].trim();
            List<String> args = row.length > 3 && !row[3].isBlank() ? List.of(row[3].trim().split("\\|")) : List.of();
            GraphQuery goldQuery = GraphQuery.parse(new org.json.JSONArray()
                    .put(new JSONObject().put("op", row[2].trim()).put("args", args)).toString(), graph).get(0);
            // The single-query form: the list form prepends "Query: path(a, b)", whose echo
            // of the arguments would land in the gold set as if it were the answer.
            String goldResult = GraphQueryEngine.execute(graph, goldQuery);
            Set<String> gold = goldFrom(goldResult, ids);

            double[] vector = cache.embed(List.of(question)) == null ? null : cache.embed(List.of(question)).get(0);
            List<TextChunk> passages = ChunkRetriever.retrieve(archive.chunks, question, vector, topK, 24000);
            StringBuilder rag = new StringBuilder();
            for (TextChunk c : passages) rag.append(c.render()).append("\n\n");

            // arm 3 = production: route (or plan), run, then answer over the result.
            List<DependencyGraph.Node> mentioned = GraphGrounding.mentionedNodes(question, graph);
            SemanticRouter.Decision routed = vector == null ? null : router.routeQuestion(question, vector, mentioned);
            List<GraphQuery> queries = List.of();
            String planSource;
            if (routed != null && routed.confident) {
                queries = routed.queries;
                planSource = "router " + routed;
            } else {
                String plan = chat.ask(planner.systemPrompt(graph, ReportQaService.plannerHints(archive, question, vector), ""), question);
                queries = GraphQuery.parse(plan, graph);
                planSource = "llm planner";
            }
            String queryResults = GraphQueryEngine.execute(graph, queries);
            Set<String> plannedNodes = new LinkedHashSet<>();
            for (GraphQuery q : queries) if (!"edges-of-type".equals(q.op)) plannedNodes.addAll(q.args);
            String context = ReportQaService.buildContext(archive, graph, question, vector, topK, queryResults, plannedNodes);

            Map<String, String> answers = new java.util.LinkedHashMap<>();
            answers.put("report-only", chat.ask(
                    "You answer questions about a microservice system from the dependency analysis report below. "
                            + "Answer in the language of the question. Do not invent services.\n\n# REPORT\n\n" + report,
                    question));
            answers.put("rag", chat.ask(
                    "You answer questions about a microservice system from the report passages below. "
                            + "Answer in the language of the question. Do not invent services.\n\n# PASSAGES\n\n" + rag,
                    question));
            answers.put("depweaver", chat.ask(qaPrompt + "\n\n# CONTEXT\n\n" + context, question));

            detail.append("### ").append(question).append("\n\n");
            detail.append("- gold (").append(row[2].trim()).append("): ")
                    .append(gold.isEmpty() ? "(none — the graph's answer is \"nothing\"; scored right/wrong)" : gold).append('\n');
            detail.append("- plan: ").append(planSource).append(" → ").append(queries).append("\n\n");
            for (Map.Entry<String, String> a : answers.entrySet()) {
                Score s = score(a.getValue(), gold, ids, question, archive.report);
                byArm.computeIfAbsent(a.getKey(), k -> new ArrayList<>()).add(s);
                csv.append(CalibrationSupport.csvRow(question, a.getKey(), gold.size(), s.named(), s.namedBeyond(),
                        s.precision(), s.recall(), s.f1(), s.offGraph(), s.offGraphAndDocs(), s.negative(), a.getValue().length()));
                detail.append("**").append(a.getKey()).append("** — ")
                        .append(s.negative()
                                ? "the graph answers \"nothing\" here — judge by reading: this answer names "
                                  + s.namedBeyond() + " service(s) the question did not"
                                : String.format(Locale.ROOT, "P %.2f, R %.2f", s.precision(), s.recall()))
                        .append(", off-graph names ").append(s.offGraph())
                        .append(" (not in the report either: ").append(s.offGraphAndDocs()).append(")\n\n> ")
                        .append(a.getValue().replace("\n", "\n> ")).append("\n\n");
            }
        }

        long positives = byArm.values().stream().findFirst().map(s -> s.stream().filter(x -> !x.negative()).count()).orElse(0L);
        long negatives = byArm.values().stream().findFirst().map(s -> s.stream().filter(Score::negative).count()).orElse(0L);
        md.append("Precision, recall and F1 are over the ").append(positives)
                .append(" questions whose graph answer names at least one node. The ").append(negatives)
                .append(" whose graph answer is \"nothing\" are **not** scored automatically — counting names cannot tell a useful ")
                .append("\"they are unrelated, X and Y are the ones that use it\" from a list of everything — they are read by hand in the detail below; ")
                .append("the last column is how many services each arm named there beyond the ones the question itself spells.\n\n");
        md.append("| arm | precision | recall | F1 | off-graph names / question | of those, not in the report | names beyond the question, \"nothing\" questions |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<Score>> e : byArm.entrySet()) {
            List<Score> s = e.getValue();
            List<Score> pos = s.stream().filter(x -> !x.negative()).toList();
            String negBeyond = s.stream().filter(Score::negative).map(x -> String.valueOf(x.namedBeyond()))
                    .reduce((a, b) -> a + ", " + b).orElse("—");
            md.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.2f | %.2f | %s |%n", e.getKey(),
                    mean(pos, Score::precision), mean(pos, Score::recall), mean(pos, Score::f1),
                    s.stream().mapToInt(Score::offGraph).average().orElse(0),
                    s.stream().mapToInt(Score::offGraphAndDocs).average().orElse(0),
                    negBeyond));
        }
        md.append("\n- chat calls: ").append(chat.calls()).append(" (").append(chat.promptTokens())
                .append(" prompt + ").append(chat.completionTokens()).append(" completion tokens)\n");
        md.append("\n## Every question\n\n").append(detail);

        Files.createDirectories(CalibrationSupport.OUT_DIR);
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("pure-llm-baseline.md"), md.toString());
        Files.writeString(CalibrationSupport.OUT_DIR.resolve("pure-llm-baseline.csv"), csv.toString());
        System.out.println(md);
    }

    private static double mean(List<Score> scores, java.util.function.ToDoubleFunction<Score> field) {
        return scores.stream().mapToDouble(field).filter(d -> !Double.isNaN(d)).average().orElse(Double.NaN);
    }

    // ---------- offline: the scoring ----------

    @Test
    void scoringCountsTheNodesNamedAndTheOnesOffTheGraph() {
        List<String> ids = List.of("frontend", "userservice", "accounts-db", "ledger-db");
        Set<String> gold = Set.of("userservice", "accounts-db");
        String q = "what does frontend call?";
        String report = "the deployment uses a cloud-sql-proxy sidecar";

        Score perfect = score("It calls userservice, which reads accounts-db.", gold, ids, q, report);
        assertEquals(1.0, perfect.precision(), 1e-9);
        assertEquals(1.0, perfect.recall(), 1e-9);
        assertEquals(0, perfect.offGraph());

        Score half = score("frontend and userservice", gold, ids, q, report);
        assertEquals(0.5, half.precision(), 1e-9);
        assertEquals(0.5, half.recall(), 1e-9);

        // Off the graph, and the report does not mention them either: the strongest signal.
        Score madeUp = score("It calls the auth-service and the payment-gateway.", gold, ids, q, report);
        assertEquals(0.0, madeUp.precision(), 1e-9);
        assertEquals(2, madeUp.offGraph());
        assertEquals(2, madeUp.offGraphAndDocs());

        // Off the graph but in the report: real, just not a node. Counted apart.
        Score fromDocs = score("It goes through the cloud-sql-proxy.", gold, ids, q, report);
        assertEquals(1, fromDocs.offGraph());
        assertEquals(0, fromDocs.offGraphAndDocs());

        // The query DSL's own words are not service names.
        assertEquals(Set.of(), offGraphNames("per deploy-order(), start db-users first", ids, q));
        // Neither is something the question itself spelled.
        assertEquals(Set.of(), offGraphNames("the order-service you asked about", ids, "tell me about order-service"));

        // A substring is not a mention: "accounts-database" is not accounts-db.
        assertEquals(Set.of(), namedNodes("it writes to accounts-database", ids));
        // Nor is a node id inside a longer word.
        assertEquals(Set.of("frontend"), namedNodes("the frontend, not the frontend-v2", ids));
    }

    @Test
    void aQueryThatAnswersNothingProducesAnEmptyGoldAndIsReadByHand() {
        List<String> ids = List.of("frontend", "userservice", "transactionhistory", "ledger-db", "ledgerwriter");

        // The engine spells both ids while saying they are unrelated; the gold must be empty.
        String noPath = "- no directed path between transactionhistory and ledger-db in either direction\n";
        assertEquals(Set.of(), goldFrom(noPath, ids));
        String withEcho = "Query: path(transactionhistory, ledger-db)\n- no directed path between them\n";
        assertEquals(Set.of(), goldFrom(withEcho, ids), "the query echo is not an answer");
        String noExternals = "- no external host is called by any service\n";
        assertEquals(Set.of(), goldFrom(noExternals, ids));
        String half = "- no directed path transactionhistory -> ledger-db\n- ledger-db -> frontend (1 hop(s))\n";
        assertEquals(Set.of("ledger-db", "frontend"), goldFrom(half, ids));

        Set<String> gold = goldFrom(noPath, ids);
        String q = "transactionhistory 和 ledger-db 之間有沒有關係？";
        Score bare = score("圖上兩者之間沒有任何路徑。", gold, ids, q, "");
        assertTrue(bare.negative());
        assertTrue(Double.isNaN(bare.precision()), "a negative question has no precision to report");
        assertEquals(0, bare.namedBeyond(), "the two subjects are the question's own words");

        // Naming the services that DO use ledger-db is useful, not wrong: the count says
        // one name went beyond the question, and a reader decides what that is worth.
        Score helpful = score("沒有關係；用 ledger-db 的是 ledgerwriter。", gold, ids, q, "");
        assertEquals(1, helpful.namedBeyond());
        assertFalse(Double.isNaN(helpful.offGraph() * 1.0));
    }

    @Test
    void theLabelledQuestionsAreWellFormed() throws Exception {
        List<String[]> rows = CalibrationSupport.readTsv("/qa/answer-labels.tsv", "project");
        assertTrue(rows.size() >= 20, "too few labelled questions: " + rows.size());
        for (String[] c : rows) {
            assertTrue(GraphQuery.OPS.containsKey(c[2].trim()), "unknown op " + c[2]);
            int arity = GraphQuery.OPS.get(c[2].trim());
            int args = c.length > 3 && !c[3].isBlank() ? c[3].trim().split("\\|").length : 0;
            if (arity != GraphQuery.VARIADIC) assertEquals(arity, args, c[1] + ": wrong number of args for " + c[2]);
        }
    }
}
