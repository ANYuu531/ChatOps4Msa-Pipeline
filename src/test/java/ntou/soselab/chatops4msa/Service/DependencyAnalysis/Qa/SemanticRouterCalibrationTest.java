package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.NLPService.EmbeddingClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calibrates the semantic router against the REAL embedding model, on phrasings that
 * are deliberately not among the intent examples.
 *
 * Runs only when asked ({@code -Dqa.calibrate=true}) and a key is available in
 * {@code src/main/resources/application.properties}; otherwise it is skipped, so the
 * suite stays offline. It prints, per phrasing, the best intent with its score and
 * the runner-up, then the accuracy and the score bands — which is where the
 * threshold and margin defaults come from.
 */
public class SemanticRouterCalibrationTest {

    /** Hold-out phrasings → expected intent. None of these is an example in the router. */
    private static final Map<String, String> HOLDOUT = new LinkedHashMap<>();

    static {
        HOLDOUT.put("frontend 依賴誰", "dependencies-of");
        HOLDOUT.put("userservice 會去打哪些服務", "dependencies-of");
        HOLDOUT.put("what services does ledgerwriter talk to", "dependencies-of");
        HOLDOUT.put("誰在用 balancereader", "dependents-of");
        HOLDOUT.put("which components call userservice", "dependents-of");
        HOLDOUT.put("userservice 壞掉的話哪些服務會跟著出事", "impact-of");
        HOLDOUT.put("if accounts-db is unavailable, what stops working", "impact-of");
        HOLDOUT.put("要讓 ledgerwriter 跑起來得先準備好什麼", "startup-needs");
        HOLDOUT.put("what has to be running for frontend to work", "startup-needs");
        HOLDOUT.put("frontend 到 ledger-db 中間經過哪些服務", "path");
        HOLDOUT.put("can requests from frontend reach balancereader", "path");
        HOLDOUT.put("哪幾條邊還沒有被流量驗證", "uncovered");
        HOLDOUT.put("流量還沒跑過哪些呼叫", "uncovered");
        HOLDOUT.put("which calls did the traffic miss", "uncovered");
        HOLDOUT.put("how much of the graph was covered at runtime", "uncovered");
        HOLDOUT.put("哪幾條是 runtime 觀測到的", "observed-edges");
        HOLDOUT.put("which dependencies were confirmed by real traffic", "observed-edges");
        HOLDOUT.put("哪些服務會存取 PostgreSQL", "db-users");
        HOLDOUT.put("who connects to the databases", "db-users");
        HOLDOUT.put("部署的時候應該先起誰", "deploy-order");
        HOLDOUT.put("in what sequence should the services come up", "deploy-order");
        HOLDOUT.put("有哪些服務在叢集裡找不到", "undeployed");
        HOLDOUT.put("which referenced services are not actually running", "undeployed");
        HOLDOUT.put("這個系統會打到外面的 API 嗎", "externals");
        HOLDOUT.put("what outside services does it depend on", "externals");
        HOLDOUT.put("有沒有透過 queue 溝通的服務", "async");
        HOLDOUT.put("is any communication event-driven", "async");
        HOLDOUT.put("哪些依賴只是文件寫的、程式裡沒有", "mentioned-only");
        HOLDOUT.put("which edges lack evidence beyond the docs", "mentioned-only");
        HOLDOUT.put("這份報告有查過叢集嗎", "about-report");
        HOLDOUT.put("這份報告可信嗎，主要限制在哪", "about-report");
        HOLDOUT.put("how confident is this report", "about-report");
        HOLDOUT.put("frontend 有部署嗎", "dependencies-of|dependents-of|undeployed|none");
    }

    @Test
    void calibrateAgainstTheRealEmbeddingModel() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("qa.calibrate")), "pass -Dqa.calibrate=true to run");
        Path props = Path.of("src/main/resources/application.properties");
        Assumptions.assumeTrue(Files.exists(props), "no application.properties with a key");
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(props.toFile())) {
            p.load(in);
        }
        String key = p.getProperty("openai.api.key", "");
        Assumptions.assumeTrue(!key.isBlank(), "no openai.api.key");
        EmbeddingClient client = new EmbeddingClient(
                EmbeddingClient.deriveUrl(p.getProperty("openai.api.url")), key,
                p.getProperty("openai.api.embedding-model", "text-embedding-3-small"));

        SemanticRouter router = new SemanticRouter(client::embed);
        assertTrue(router.ensureIndexed(), "could not embed the examples");

        // The Bank of Anthos node set, so the hold-out questions are masked the way
        // production masks them (frontend 依賴誰 → X 依賴誰) before routing.
        DependencyGraph graph = new DependencyGraph("bank-of-anthos");
        for (String id : List.of("frontend", "userservice", "contacts", "ledgerwriter", "balancereader",
                "transactionhistory", "accounts-db", "ledger-db", "istio-ingressgateway")) {
            graph.addNode(id, DependencyGraph.classifyKind(id));
        }

        List<String> questions = new ArrayList<>(HOLDOUT.keySet());
        List<double[]> vectors = client.embed(questions);
        assertTrue(vectors != null && vectors.size() == questions.size(), "could not embed the hold-out set");

        int correct = 0, confidentCorrect = 0, confidentWrong = 0;
        double minCorrect = 1, maxWrong = 0;
        StringBuilder table = new StringBuilder();
        table.append(String.format(Locale.ROOT, "%-52s %-16s %-16s %6s %6s %s%n", "question", "expected", "best", "score", "next", "verdict"));
        for (int i = 0; i < questions.size(); i++) {
            String q = questions.get(i);
            String expected = HOLDOUT.get(q);
            SemanticRouter.Decision d = router.routeQuestion(q, vectors.get(i), GraphGrounding.mentionedNodes(q, graph));
            boolean ok = List.of(expected.split("\\|")).contains(d.intent);
            if (ok) {
                correct++;
                minCorrect = Math.min(minCorrect, d.score);
                if (d.confident || d.intent.equals("none")) confidentCorrect++;
            } else {
                maxWrong = Math.max(maxWrong, d.score);
                if (d.confident) confidentWrong++;
            }
            table.append(String.format(Locale.ROOT, "%-52s %-16s %-16s %6.3f %6.3f %s%s%n",
                    q, expected, d.intent, d.score, d.runnerUpScore, ok ? "ok" : "WRONG", d.confident ? "" : " (unsure)"));
        }
        System.out.println(table);
        System.out.printf(Locale.ROOT, "accuracy %d/%d; confident+correct %d; confident+WRONG %d; "
                        + "lowest correct score %.3f; highest wrong score %.3f; thresholds T=%.2f M=%.2f H=%.2f%n",
                correct, questions.size(), confidentCorrect, confidentWrong, minCorrect, maxWrong,
                SemanticRouter.DEFAULT_THRESHOLD, SemanticRouter.DEFAULT_MARGIN, SemanticRouter.DEFAULT_HIGH);

        assertTrue(confidentWrong == 0, "a confident wrong route is the one outcome the thresholds must prevent");
    }
}
