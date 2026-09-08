package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules are what make a recognised question shape independent of the model: the
 * same words, in either language, must produce the same queries every time — and a
 * plain question about a named node must produce none, so no model call is spent.
 */
public class RulePlannerTest {

    private static DependencyGraph graph() {
        DependencyGraph g = new DependencyGraph("ns");
        for (String s : List.of("frontend", "userservice", "ledgerwriter", "balancereader", "accounts-db")) g.addNode(s, DependencyGraph.classifyKind(s));
        g.addNode("istio-ingressgateway", DependencyGraph.KIND_GATEWAY);
        g.addEdge("frontend", "userservice", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 1, null);
        return g;
    }

    private static List<String> plan(String question) {
        DependencyGraph g = graph();
        return RulePlanner.plan(question, GraphGrounding.mentionedNodes(question, g), g).stream().map(GraphQuery::toString).toList();
    }

    @Test
    void impactAndStartupInBothLanguages() {
        assertEquals(List.of("impact-of(userservice)"), plan("如果 userservice 掛了會影響誰？"));
        assertEquals(List.of("impact-of(userservice)"), plan("What breaks if userservice goes down?"));
        assertEquals(List.of("startup-needs(ledgerwriter)"), plan("ledgerwriter 要能運作前面得先起哪些？"));
        assertEquals(List.of("startup-needs(ledgerwriter)"), plan("What must be running before ledgerwriter works?"));
    }

    @Test
    void orderCoverageAndSetQuestionsNeedNoNode() {
        assertEquals(List.of("deploy-order()"), plan("建議的部署順序是什麼？"));
        assertEquals(List.of("deploy-order()"), plan("What order should we deploy things in?"));
        assertEquals(List.of("uncovered()", "unobserved-edges()"), plan("哪些邊是程式碼有宣告但流量沒跑到的？"));
        assertEquals(List.of("uncovered()", "unobserved-edges()"), plan("Which edges were never observed at runtime?"));
        assertEquals(List.of("observed-edges()"), plan("哪些邊是有被觀測到的？"));
        assertEquals(List.of("db-users()"), plan("哪些服務有用到資料庫？"));
        assertEquals(List.of("undeployed()"), plan("有哪些服務沒部署？"));
        assertEquals(List.of("externals()"), plan("有沒有外部依賴？"));
        assertEquals(List.of("async()"), plan("Is there a message queue or broker?"));
        assertEquals(List.of("mentioned-only()"), plan("哪些邊只被文件提到？"));
    }

    @Test
    void twoNamedNodesAskForTheirPath() {
        assertEquals(List.of("path(frontend, accounts-db)"), plan("frontend 怎麼連到 accounts-db？"));
        assertEquals(List.of("path(ledgerwriter, balancereader)"), plan("does ledgerwriter call balancereader?"));
    }

    @Test
    void aPlainQuestionAboutOneNodeYieldsNothingAndNeedsNoModel() {
        DependencyGraph g = graph();
        String q = "frontend 依賴誰？";
        List<DependencyGraph.Node> named = GraphGrounding.mentionedNodes(q, g);
        assertTrue(RulePlanner.plan(q, named, g).isEmpty());
        assertFalse(RulePlanner.needsLlmPlanner(q, named), "the fact sheet answers it");
    }

    @Test
    void modelPlannerIsWantedForUnnamedOrSetQuestions() {
        DependencyGraph g = graph();
        assertTrue(RulePlanner.needsLlmPlanner("what is the overall shape?", GraphGrounding.mentionedNodes("what is the overall shape?", g)));
        String set = "frontend 依賴哪些服務？";
        assertTrue(RulePlanner.needsLlmPlanner(set, GraphGrounding.mentionedNodes(set, g)));
        assertFalse(RulePlanner.needsLlmPlanner("  ", List.of()));
    }

    @Test
    void phrasingsFromTheFirstRealRunAreRules() {
        // Both went to the model planner on 2026-09-08; the first should be a rule,
        // the second should spend no call at all.
        assertEquals(List.of("observed-edges()"), plan("哪幾條是 runtime 觀測到的"));
        assertEquals(List.of("uncovered()", "unobserved-edges()"), plan("哪些邊沒跑到"));
        DependencyGraph g = graph();
        String meta = "這份報告有查過叢集嗎？";
        assertTrue(RulePlanner.plan(meta, List.of(), g).isEmpty());
        assertFalse(RulePlanner.needsLlmPlanner(meta, List.of()));
        assertFalse(RulePlanner.needsLlmPlanner("What are the limitations of this report?", List.of()));
    }

    @Test
    void dbRuleDoesNotFireWhenANodeIsNamed() {
        assertTrue(plan("userservice 用哪個資料庫？").isEmpty(), "the fact sheet lists its db edge");
    }

    @Test
    void capsAtFourQueries() {
        List<String> many = plan("哪些外部依賴、佇列、資料庫、沒部署的服務、只被提到的邊？");
        assertEquals(RulePlanner.MAX_QUERIES, many.size());
    }
}
