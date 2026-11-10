package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.GreenfieldProbeTest;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.RepoWorkspace;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.AliasResolution;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.NLPService.EmbeddingClient;
import ntou.soselab.chatops4msa.Service.NLPService.LLMService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * The graph-edit test sentences of {@code docs/graph-edit-design-2026-11-06.md} §5, through the
 * thread's real answering path ({@link ReportQaService#respond}: embedding, semantic router,
 * model planner, edit dialogue, evidence search on a fresh clone) with the real model — only
 * Discord is left out. Each scenario starts from a fresh copy of the report, so one does not
 * see another's edit; each is checked against what the design says should happen.
 *
 * <p>Calls OpenAI: run on machine B, with its {@code application.properties}
 * ({@code docs/experiments/2026-11-06-graph-edit/run-on-machine-b.sh}).
 * <pre>
 * mvn -q test -Dtest=GraphEditLiveProbeTest -Dprobe.live.repo=nashtech-garage/yas \
 *     -Dprobe.live.out=docs/experiments/2026-11-06-graph-edit/live-yas.md
 * </pre>
 */
@EnabledIfSystemProperty(named = "probe.live.repo", matches = ".+")
public class GraphEditLiveProbeTest {

    record Scenario(String name, String expect, List<String> turns, Predicate<Run> check) {}

    /** What one scenario did: each turn's reply, plan line, and whether an edit was open after it. */
    static final class Run {
        final List<String> replies = new ArrayList<>();
        final List<String> plans = new ArrayList<>();
        final List<Boolean> openAfter = new ArrayList<>();
        DependencyGraph before;
        DependencyGraph after;

        boolean added(String source, String targetPart) {
            for (DependencyGraph.Edge e : after.getEdges()) {
                if (e.source.equals(source) && e.target.toLowerCase(Locale.ROOT).contains(targetPart)
                        && e.provenance != null && e.provenance.contains(DependencyGraph.PROV_HUMAN)) return true;
            }
            return false;
        }

        boolean unchanged() {
            return after.getEdges().size() == before.getEdges().size() && after.getNodes().size() == before.getNodes().size();
        }

        String reply(int i) {
            return i < replies.size() ? replies.get(i).toLowerCase(Locale.ROOT) : "";
        }
    }

    static final List<Scenario> YAS = List.of(
            new Scenario("1 · 漏邊：kafka-connect → postgres",
                    "開啟新增、證據列出 Debezium JSON；選 1 → 建議 db → 寫入",
                    List.of("圖上少了 kafka-connect 連到 postgres 的線，幫我加上", "1", "好", "確定"),
                    r -> r.reply(0).contains("debezium") && r.added("kafka-connect", "postgres")),
            new Scenario("2 · 漏邊：storefront-nextjs → storefront（換一種說法、貼自己的出處）",
                    "開啟新增；貼 storefront/.env.production:1 → 讀到那一行、documented → 寫入",
                    List.of("storefront-nextjs 其實有呼叫 storefront，把這條線補上去", "storefront/.env.production:1", "好", "確定"),
                    r -> r.added("storefront-nextjs", "storefront")),
            new Scenario("3 · 英文、名字對不上：cart → Stripe API",
                    "問「你指的是…或新增」；新增 → 外部主機 → 沒有出處 → inferred 寫入",
                    List.of("add an edge from cart to Stripe API", "new", "4", "none", "ok", "yes"),
                    r -> r.added("cart", "stripe")),
            new Scenario("4 · 沒講來源：→ tax",
                    "反問哪個服務呼叫 tax；取消後圖不變",
                    List.of("幫我加一條連到 tax 的線", "取消"),
                    r -> r.openAfter.get(0) && r.reply(0).contains("tax") && !r.openAfter.get(1) && r.unchanged()),
            new Scenario("5 · 是問題、不是改圖",
                    "照常回答，不開新增、圖不變",
                    List.of("cart 會不會呼叫 product？"),
                    r -> !r.openAfter.get(0) && r.unchanged() && !r.plans.get(0).contains("add-edge")),
            new Scenario("6 · 新增進行中途插問題",
                    "第二句當問題回答，並提醒新增還開著；取消後圖不變",
                    List.of("圖上少了 kafka-connect 連到 postgres 的線，幫我加上", "postgres 被誰用？", "取消"),
                    r -> r.openAfter.get(1) && r.reply(1).contains("still open") && !r.openAfter.get(2) && r.unchanged()));

    @Test
    void playTheTestSentences() throws Exception {
        Properties p = new Properties();
        LLMService llm;
        if ("true".equals(System.getProperty("probe.live.stand-in"))) {
            // Checks the harness (clone, archive, dialogue, transcript) without the model: the
            // planner's answers are the ones it should give, the router is off (no embeddings).
            p.setProperty("openai.api.model", "stand-in (no model called)");
            llm = standInModel();
        } else {
            Path props = Path.of("src/main/resources/application.properties");
            Assumptions.assumeTrue(Files.exists(props), "no application.properties with a key");
            try (FileInputStream in = new FileInputStream(props.toFile())) {
                p.load(in);
            }
            String key = p.getProperty("openai.api.key", "");
            Assumptions.assumeTrue(!key.isBlank(), "no openai.api.key");
            llm = liveModel(p.getProperty("openai.api.url"), key, p.getProperty("openai.api.model"),
                    p.getProperty("openai.api.embedding-model", "text-embedding-3-small"));
        }

        String repo = System.getProperty("probe.live.repo");
        Path work = Files.createTempDirectory("edit-live-");
        DependencyAnalysisStateStore stateStore = new DependencyAnalysisStateStore(work.resolve("dep-state").toString(), 24);
        ReportQaService service = new ReportQaService(new ReportArchiveStore(work.resolve("reports"), Duration.ofDays(1)),
                stateStore, llm, null, new GraphQueryPlanner(llm),
                true, true, false, 16,
                Double.parseDouble(p.getProperty("dependency.qa.router.threshold", "0.51")),
                Double.parseDouble(p.getProperty("dependency.qa.router.margin", "0.10")),
                Double.parseDouble(p.getProperty("dependency.qa.router.high", "0.80")));

        // The analysis the report came from: the same static run a greenfield request makes.
        GreenfieldProbeTest.Probe probe;
        try (RepoWorkspace ws = RepoWorkspace.clone(repo)) {
            Assumptions.assumeTrue(ws.isCloned(), "cannot clone " + repo + ": " + ws.getFailure());
            probe = GreenfieldProbeTest.run(ws.getRoot(), new AliasResolution.Answers());
        }
        DependencyAnalysisStateStore.State state = new DependencyAnalysisStateStore.State();
        state.repoName = repo;
        state.stages.put(DependencyAnalysisStateStore.STAGE_CODE_EDGES, probe.ledger.toJson().toString());
        ReportArchive base = service.buildArchive("probe", state, "greenfield", probe.summary, probe.graph, "");

        StringBuilder out = new StringBuilder("# graph-edit live probe · " + repo + "\n\n");
        out.append("model `").append(p.getProperty("openai.api.model")).append("` · graph ")
                .append(probe.graph.getNodes().size()).append(" nodes / ").append(probe.graph.getEdges().size())
                .append(" edges · router T=").append(p.getProperty("dependency.qa.router.threshold", "0.51")).append("\n\n");
        StringBuilder table = new StringBuilder("| 情境 | 預期 | 結果 |\n|---|---|---|\n");
        int passed = 0;
        for (int s = 0; s < YAS.size(); s++) {
            Scenario sc = YAS.get(s);
            ReportArchive archive = ReportArchive.fromJson(base.toJson());
            archive.id = base.id + "-" + s;
            Run run = new Run();
            run.before = DependencyGraph.fromJson(archive.graphJson);
            out.append("## ").append(sc.name).append("\n\n_預期：").append(sc.expect).append("_\n\n");
            for (String turn : sc.turns) {
                String[] captured = new String[1];
                String reply = captured(() -> service.respond(archive, turn).text, captured);
                String plan = captured[0].lines().filter(l -> l.contains("[DEBUG] graph query plan")).findFirst()
                        .map(l -> l.replaceFirst("^.*\\[DEBUG\\] ", "")).orElse("(no plan: the edit dialogue took the message)");
                run.replies.add(reply);
                run.plans.add(plan);
                run.openAfter.add(archive.pendingEdit != null);
                out.append("**user:** ").append(turn).append("\n\n`").append(plan.replace('`', '\'')).append("`\n\n**bot:**\n```\n")
                        .append(reply).append("\n```\n");
            }
            run.after = DependencyGraph.fromJson(archive.graphJson);
            boolean ok;
            try {
                ok = sc.check.test(run);
            } catch (RuntimeException e) {
                ok = false;
            }
            if (ok) passed++;
            out.append("\n**").append(ok ? "PASS" : "FAIL").append("**\n\n");
            table.append("| ").append(sc.name).append(" | ").append(sc.expect).append(" | ").append(ok ? "PASS" : "**FAIL**").append(" |\n");
        }
        String text = "# graph-edit live probe · " + repo + "\n\n" + table + "\n" + passed + "/" + YAS.size() + " passed\n\n"
                + out.substring(out.indexOf("\n\n") + 2);
        System.out.println(text);
        String outPath = System.getProperty("probe.live.out");
        if (outPath != null && !outPath.isBlank()) {
            Path target = Path.of(outPath);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, text);
        }
    }

    /** Runs the call with stdout teed into {@code log[0]}, so the plan the service prints is kept. */
    private static String captured(java.util.concurrent.Callable<String> call, String[] log) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream tee = new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                buffer.write(b);
                original.write(b);
            }
        }, true, StandardCharsets.UTF_8);
        System.setOut(tee);
        try {
            return call.call();
        } finally {
            System.setOut(original);
            log[0] = buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /** {@code -Dprobe.live.stand-in=true}: the plans the planner should return, and a placeholder answer. */
    private static LLMService standInModel() {
        LLMService llm = Mockito.mock(LLMService.class);
        Mockito.when(llm.embed(Mockito.anyList())).thenReturn(null);
        Mockito.when(llm.callAPIFromOutside(Mockito.any(JSONArray.class))).thenAnswer(inv -> {
            JSONArray messages = inv.getArgument(0);
            String system = messages.getJSONObject(0).getString("content");
            String question = messages.getJSONObject(messages.length() - 1).getString("content");
            if (system.contains("# CONTEXT")) return "(stand-in answer to: " + question + ")";
            if (question.contains("kafka-connect")) return "[{\"op\":\"add-edge\",\"args\":[\"kafka-connect\",\"postgres\"]}]";
            if (question.contains("storefront-nextjs")) return "[{\"op\":\"add-edge\",\"args\":[\"storefront-nextjs\",\"storefront\"]}]";
            if (question.contains("Stripe")) return "[{\"op\":\"add-edge\",\"args\":[\"cart\",\"Stripe API\"]}]";
            if (question.contains("tax")) return "[{\"op\":\"add-edge\",\"args\":[\"\",\"tax\"]}]";
            if (question.contains("會不會呼叫")) return "[{\"op\":\"path\",\"args\":[\"cart\",\"product\"]}]";
            if (question.contains("被誰用")) return "[{\"op\":\"dependents-of\",\"args\":[\"postgres\"]}]";
            return "[]";
        });
        return llm;
    }

    /**
     * The two calls the thread makes of the model — a chat completion and an embedding —
     * against the configured endpoint. {@link LLMService} itself needs the Spring context
     * (capability catalogue, prompt files), which would also start the Discord bot.
     */
    private static LLMService liveModel(String url, String key, String model, String embeddingModel) throws Exception {
        EmbeddingClient embeddings = new EmbeddingClient(EmbeddingClient.deriveUrl(url), key, embeddingModel);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        LLMService llm = Mockito.mock(LLMService.class);
        Mockito.when(llm.embed(Mockito.anyList())).thenAnswer(inv -> embeddings.embed(inv.getArgument(0)));
        Mockito.when(llm.callAPIFromOutside(Mockito.any(JSONArray.class))).thenAnswer(inv -> {
            JSONObject body = new JSONObject().put("model", model).put("temperature", 0).put("messages", (JSONArray) inv.getArgument(0));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            String response = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            return new JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
        });
        return llm;
    }
}
