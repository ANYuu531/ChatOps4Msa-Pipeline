package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import ntou.soselab.chatops4msa.Service.NLPService.LLMService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Ask the report": a conversation, in a Discord thread under each report, grounded in
 * that report's evidence.
 *
 * The teacher's request was that a reader be able to ask about the details after the
 * report is posted, RAG being an acceptable means. This is retrieval-augmented in two
 * layers, following the project's standing rule — deterministic first, the language
 * model only for language:
 * <ol>
 *   <li><b>Graph grounding</b> ({@link GraphGrounding}): the nodes the question names
 *       are looked up in the dependency graph and their facts (edges, provenance,
 *       observed/declared, counts, transitive closure, tiers) are written out by code.
 *       This is where the answers to most dependency questions actually live, and it
 *       cannot hallucinate.</li>
 *   <li><b>Passage retrieval</b> ({@link ChunkRetriever}): the report and the evidence
 *       notes are chunked by heading at archive time; the passages relevant to the
 *       question are selected by BM25, fused with embedding similarity when vectors
 *       are available. This supplies the wording, roles, limitations and file:line
 *       references the graph does not carry.</li>
 * </ol>
 * The model receives both, the recent turns of the thread, and a prompt that binds it
 * to the context and to the evidence levels. Its output is one message in the thread.
 *
 * Why a thread rather than the existing mention-and-intent flow: the intent flow ends
 * in a Perform button per capability, which is right for an action and wrong for a
 * question. A thread needs no mention, keeps every question next to the report it is
 * about, and gives the conversation history a natural boundary.
 */
@Service
public class ReportQaService {

    private static final String PROMPT_FILE = "prompts/report_qa.txt";
    /** Discord's message limit is 2000; leave room for a continuation marker. */
    private static final int DISCORD_CHUNK = 1900;
    /** Turns of history (user+assistant pairs) handed back to the model. */
    private static final int HISTORY_TURNS = 6;
    private static final int HISTORY_KEEP = 40;
    private static final int PASSAGE_BUDGET_CHARS = 24000;

    /** The evidence stages worth archiving, label → checkpoint key. Raw JSON stages are not: the graph already holds them. */
    private static final Map<String, String> EVIDENCE_STAGES = new java.util.LinkedHashMap<>();

    static {
        EVIDENCE_STAGES.put("docs+code notes", DependencyAnalysisStateStore.STAGE_MERGED_NOTES);
        EVIDENCE_STAGES.put("code extraction", DependencyAnalysisStateStore.STAGE_CODE);
        EVIDENCE_STAGES.put("kubernetes/istio notes", DependencyAnalysisStateStore.STAGE_K8S);
        EVIDENCE_STAGES.put("istio runtime edge ledger", DependencyAnalysisStateStore.STAGE_TRAFFIC);
        EVIDENCE_STAGES.put("istio egress ledger", DependencyAnalysisStateStore.STAGE_EGRESS);
        EVIDENCE_STAGES.put("traffic run report", DependencyAnalysisStateStore.STAGE_TRAFFIC_REPORT);
        EVIDENCE_STAGES.put("health check", DependencyAnalysisStateStore.STAGE_HEALTH);
    }

    private final ReportArchiveStore store;
    private final LLMService llmService;
    private final JDAService jdaService;
    private final GraphQueryPlanner planner;
    private final boolean embeddingsEnabled;
    private final boolean plannerEnabled;
    private final int topK;
    private final String promptTemplate;

    /** Answers run here: a question is an LLM call, seconds long, and must not hold the JDA event thread. */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "report-qa");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public ReportQaService(ReportArchiveStore store,
                           LLMService llmService,
                           @Lazy JDAService jdaService,
                           GraphQueryPlanner planner,
                           @Value("${dependency.qa.embeddings:true}") boolean embeddingsEnabled,
                           @Value("${dependency.qa.query-planner:true}") boolean plannerEnabled,
                           @Value("${dependency.qa.top-k:8}") int topK) {
        this.store = store;
        this.llmService = llmService;
        this.jdaService = jdaService;
        this.planner = planner;
        this.embeddingsEnabled = embeddingsEnabled;
        this.plannerEnabled = plannerEnabled;
        this.topK = topK;
        this.promptTemplate = loadPrompt();
    }

    // ---------- opening ----------

    /**
     * Archives a just-posted report and opens its Q&amp;A thread. Never throws: the report
     * has already been delivered, and a failure here must not be reported as a failure
     * of the report.
     */
    public void openQaThread(String userId, DependencyAnalysisStateStore.State state, String mode,
                             String report, DependencyGraph graph, String coverage) {
        try {
            ReportArchive archive = buildArchive(userId, state, mode, report, graph, coverage);
            store.save(archive);

            String intro = "💬 **Ask " + DependencyGraph.TOOL_NAME + " about this report** — `"
                    + archive.repoName + "`"
                    + (archive.namespace.isBlank() ? " (greenfield)" : " · namespace `" + archive.namespace + "`")
                    + "\nReply **in the thread below** — no need to mention the bot. Answers are grounded in the "
                    + "dependency graph and the report's evidence; when something is not in the evidence, "
                    + "I will say so rather than guess.";
            String threadName = truncate("Ask " + DependencyGraph.TOOL_NAME + " · " + shortRepo(archive.repoName), 100);
            String threadId = jdaService.sendChatOpsChannelMessageAndOpenThread(intro, threadName);
            if (threadId == null) {
                System.out.println("[WARNING] report Q&A: could not open a thread; the archive is kept but unreachable.");
                return;
            }
            archive.threadId = threadId;
            store.save(archive);

            jdaService.sendThreadMessage(threadId, starterQuestions(graph, archive));
        } catch (Exception e) {
            System.out.println("[WARNING] report Q&A thread not opened: " + e.getMessage());
        }
    }

    ReportArchive buildArchive(String userId, DependencyAnalysisStateStore.State state, String mode,
                               String report, DependencyGraph graph, String coverage) {
        ReportArchive archive = new ReportArchive();
        archive.id = ReportArchiveStore.newId(userId);
        archive.userId = userId == null ? "" : userId;
        archive.repoName = state == null || state.repoName == null ? "" : state.repoName;
        archive.namespace = state == null || state.namespace == null ? "" : state.namespace;
        archive.mode = mode == null ? "" : mode;
        archive.report = report == null ? "" : report;
        archive.graphJson = graph == null ? new JSONObject() : graph.toJson();
        archive.coverage = coverage == null ? "" : coverage;
        if (state != null) {
            for (Map.Entry<String, String> e : EVIDENCE_STAGES.entrySet()) {
                String text = state.stage(e.getValue());
                if (text != null && !text.isBlank()) archive.evidence.put(e.getKey(), text);
            }
        }
        archive.chunks.addAll(chunk(archive));
        if (embeddingsEnabled) embed(archive.chunks);
        return archive;
    }

    /** The corpus: the report first (it is what the user read), then each evidence stage. */
    static List<TextChunk> chunk(ReportArchive archive) {
        List<TextChunk> chunks = new ArrayList<>(ReportChunker.chunk("report", archive.report));
        if (!archive.coverage.isBlank()) chunks.addAll(ReportChunker.chunk("runtime coverage", archive.coverage));
        for (Map.Entry<String, String> e : archive.evidence.entrySet()) {
            chunks.addAll(ReportChunker.chunk(e.getKey(), e.getValue()));
        }
        return chunks;
    }

    /** Best effort: on any failure the chunks simply stay lexical-only. */
    private void embed(List<TextChunk> chunks) {
        if (chunks.isEmpty()) return;
        List<String> texts = new ArrayList<>();
        for (TextChunk c : chunks) texts.add(c.indexedText());
        List<double[]> vectors = llmService.embed(texts);
        if (vectors == null || vectors.size() != chunks.size()) {
            System.out.println("[WARNING] report Q&A: embeddings unavailable; retrieval is lexical only.");
            return;
        }
        for (int i = 0; i < chunks.size(); i++) chunks.get(i).embedding = vectors.get(i);
    }

    /** A few questions the reader can copy, built from the graph so they name real nodes. */
    static String starterQuestions(DependencyGraph graph, ReportArchive archive) {
        String busiest = null;
        int best = -1;
        if (graph != null) {
            for (DependencyGraph.Node n : graph.getNodes()) {
                if (!DependencyGraph.KIND_SERVICE.equals(n.kind)) continue;
                int degree = 0;
                for (DependencyGraph.Edge e : graph.getEdges()) {
                    if (e.source.equals(n.id) || e.target.equals(n.id)) degree++;
                }
                if (degree > best) {
                    best = degree;
                    busiest = n.id;
                }
            }
        }
        String x = busiest == null ? "<service>" : busiest;
        StringBuilder sb = new StringBuilder("Some things you can ask here:\n");
        sb.append("- Who depends on `").append(x).append("`, and what does it depend on?\n");
        sb.append("- `").append(x).append("` 依賴哪些服務？改了它會影響誰？\n");
        if (!"greenfield".equals(archive.mode)) {
            sb.append("- Which edges were declared but never observed at runtime, and why might that be?\n");
        }
        sb.append("- 哪些服務有用到資料庫？證據是什麼？\n");
        sb.append("- What start-up / deployment order does the graph imply?\n");
        sb.append("- What are the limitations of this report?\n");
        return sb.toString();
    }

    // ---------- answering ----------

    public boolean isQaThread(String channelId) {
        return store.isQaThread(channelId);
    }

    /** Answers asynchronously in the thread; the caller returns to the event loop at once. */
    public void ask(String threadId, String userId, String userName, String question) {
        if (question == null || question.isBlank()) return;
        executor.execute(() -> {
            try {
                ReportArchive archive = store.findByThread(threadId);
                if (archive == null) {
                    jdaService.sendThreadMessage(threadId,
                            "This report's archive has expired, so I can no longer answer from its evidence. "
                                    + "Please re-run the dependency analysis.");
                    return;
                }
                System.out.println("[DEBUG] report Q&A from " + userName + " on " + archive.repoName + ": " + question);
                String answer = answer(archive, question);
                store.save(archive);
                for (String piece : splitForDiscord(answer)) jdaService.sendThreadMessage(threadId, piece);
            } catch (Exception e) {
                e.printStackTrace();
                jdaService.sendThreadMessage(threadId, "```ml\n[ERROR] could not answer: " + e.getMessage() + "```");
            }
        });
    }

    /** One question against one archive: builds the grounded context, calls the model, records the turn. */
    String answer(ReportArchive archive, String question) {
        DependencyGraph graph = DependencyGraph.fromJson(archive.graphJson);

        double[] questionVector = null;
        if (embeddingsEnabled && archive.hasEmbeddings()) {
            List<double[]> v = llmService.embed(List.of(question));
            if (v != null && v.size() == 1) questionVector = v.get(0);
        }

        // NL -> graph query -> deterministic execution: the structured answer for
        // questions that name no node ("which edges were never exercised?") or ask
        // for a traversal ("what breaks if X goes down?"). Additive to the grounding.
        String queryResults = "";
        if (plannerEnabled) {
            List<GraphQuery> queries = planner.plan(graph, question);
            queryResults = GraphQueryEngine.execute(graph, queries);
        }
        String context = buildContext(archive, graph, question, questionVector, topK, queryResults);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", promptTemplate + "\n\n# CONTEXT\n\n" + context));
        int from = Math.max(0, archive.history.size() - HISTORY_TURNS * 2);
        for (int i = from; i < archive.history.size(); i++) messages.put(archive.history.get(i));
        messages.put(new JSONObject().put("role", "user").put("content", question));

        String reply = llmService.callAPIFromOutside(messages);
        if (reply == null || reply.isBlank()) reply = "(the model returned an empty answer)";

        archive.history.add(new JSONObject().put("role", "user").put("content", question));
        archive.history.add(new JSONObject().put("role", "assistant").put("content", reply));
        while (archive.history.size() > HISTORY_KEEP) archive.history.remove(0);
        return reply;
    }

    /**
     * The context block, in authority order: metadata, graph facts, coverage, passages.
     * Pure function of its inputs, so tests can pin what the model is shown.
     */
    static String buildContext(ReportArchive archive, DependencyGraph graph, String question,
                               double[] questionVector, int topK) {
        return buildContext(archive, graph, question, questionVector, topK, "");
    }

    /**
     * @param queryResults the executed graph-query plan (Markdown), or empty when the
     *                     planner chose no query or is disabled
     */
    static String buildContext(ReportArchive archive, DependencyGraph graph, String question,
                               double[] questionVector, int topK, String queryResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Report\n");
        sb.append("- Tool: ").append(DependencyGraph.TOOL_NAME).append('\n');
        sb.append("- Repository: ").append(archive.repoName).append('\n');
        sb.append("- Namespace: ").append(archive.namespace.isBlank() ? "(none — greenfield, static analysis)" : archive.namespace).append('\n');
        sb.append("- Mode: ").append(archive.mode).append('\n');
        sb.append("- Generated: ").append(DateTimeFormatter.ISO_INSTANT.format(archive.createdAt)).append("\n\n");

        sb.append("# 1. GRAPH FACTS (computed from the dependency graph; authoritative)\n\n");
        if (queryResults != null && !queryResults.isBlank()) {
            sb.append("## Query results (graph queries selected for this question, executed by code)\n\n");
            sb.append(queryResults).append('\n');
        }
        sb.append(GraphGrounding.ground(graph, question)).append('\n');

        sb.append("# 2. RUNTIME COVERAGE (computed; authoritative)\n\n");
        sb.append(archive.coverage.isBlank()
                ? "Not measured (greenfield run, or no service-to-service edges to score).\n\n"
                : archive.coverage + "\n\n");

        sb.append("# 3. RETRIEVED PASSAGES (report + evidence notes, selected for this question)\n\n");
        List<TextChunk> hits = ChunkRetriever.retrieve(archive.chunks, question, questionVector, topK, PASSAGE_BUDGET_CHARS);
        if (hits.isEmpty()) {
            sb.append("No passage of the report matched the question's terms.\n");
        } else {
            for (TextChunk hit : hits) sb.append(hit.render()).append("\n\n");
        }
        return sb.toString();
    }

    // ---------- helpers ----------

    /** Splits on line boundaries so no piece exceeds Discord's message limit. */
    static List<String> splitForDiscord(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            out.add("(empty answer)");
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n")) {
            while (line.length() > DISCORD_CHUNK) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                out.add(line.substring(0, DISCORD_CHUNK));
                line = line.substring(DISCORD_CHUNK);
            }
            if (current.length() + line.length() + 1 > DISCORD_CHUNK) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append('\n');
            current.append(line);
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    private static String shortRepo(String repo) {
        if (repo == null) return "";
        int slash = repo.lastIndexOf('/');
        return slash >= 0 && slash < repo.length() - 1 ? repo.substring(slash + 1) : repo;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_FILE);
            return new String(FileCopyUtils.copyToByteArray(resource.getInputStream()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WARNING] report Q&A prompt missing (" + PROMPT_FILE + "); using a minimal one.");
            return "Answer questions about the dependency-analysis report using only the CONTEXT below. "
                    + "Never invent facts; say when the context does not contain the answer.";
        }
    }
}
