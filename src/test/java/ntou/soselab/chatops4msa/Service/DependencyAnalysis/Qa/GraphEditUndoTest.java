package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphEdit;
import ntou.soselab.chatops4msa.Service.NLPService.LLMService;
import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Removing an edge and undoing an edit through the thread's own path
 * ({@link ReportQaService#respond}): the planner is a stand-in that returns the plan it
 * should, the router is off, nothing is cloned (a removal never searches; the addition
 * here has no repository to search).
 */
public class GraphEditUndoTest {

    @TempDir
    Path work;

    private ReportQaService service;
    private DependencyAnalysisStateStore stateStore;
    private DependencyGraph graph;

    @BeforeEach
    void setUp() {
        LLMService llm = Mockito.mock(LLMService.class);
        Mockito.when(llm.embed(Mockito.anyList())).thenReturn(null);
        Mockito.when(llm.callAPIFromOutside(Mockito.any(JSONArray.class))).thenAnswer(inv -> {
            JSONArray messages = inv.getArgument(0);
            String system = messages.getJSONObject(0).getString("content");
            String question = messages.getJSONObject(messages.length() - 1).getString("content");
            if (system.contains("# CONTEXT")) return "(stand-in answer to: " + question + ")";
            if (question.contains("拿掉")) return "[{\"op\":\"remove-edge\",\"args\":[\"orders\",\"ordersdb\"]}]";
            if (question.contains("少了")) return "[{\"op\":\"add-edge\",\"args\":[\"connector\",\"ordersdb\"]}]";
            return "[]";
        });
        stateStore = new DependencyAnalysisStateStore(work.resolve("dep-state").toString(), 24);
        service = new ReportQaService(new ReportArchiveStore(work.resolve("reports"), Duration.ofDays(1)),
                stateStore, llm, null, new GraphQueryPlanner(llm), false, true, false, 16, 0.51, 0.10, 0.80);
        graph = new DependencyGraph("");
        graph.addNode("orders", DependencyGraph.KIND_SERVICE);
        graph.addNode("connector", DependencyGraph.KIND_SERVICE);
        graph.addNode("ordersdb", DependencyGraph.KIND_DB);
        graph.addEdge("orders", "ordersdb", "db", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: docker-compose.yml:5");
    }

    private ReportArchive archive(String repoName) {
        ReportArchive a = new ReportArchive();
        a.id = "t-" + repoName.hashCode();
        a.userId = "tester";
        a.repoName = repoName;
        a.graphJson = graph.toJson();
        return a;
    }

    private static boolean hasEdge(ReportArchive a, String s, String t) {
        for (DependencyGraph.Edge e : DependencyGraph.fromJson(a.graphJson).getEdges()) if (e.source.equals(s) && e.target.equals(t)) return true;
        return false;
    }

    @Test
    void aRemovalIsRememberedForTheRepositoryAndUndoForgetsIt() {
        ReportArchive a = archive("acme/shop");
        String reply = service.answer(a, "把 orders 到 ordersdb 的線拿掉");
        assertTrue(reply.contains("確認要從圖上拿掉"), reply);
        assertNotNull(a.pendingEdit);

        reply = service.answer(a, "確定");
        assertTrue(reply.contains("已從圖上拿掉"), reply);
        assertNull(a.pendingEdit);
        assertFalse(hasEdge(a, "orders", "ordersdb"));
        List<GraphEdit> remembered = GraphEdit.listFromJson(stateStore.loadProjectEdits("acme/shop"));
        assertEquals(1, remembered.size());
        assertEquals(GraphEdit.REMOVE_EDGE, remembered.get(0).action);
        assertEquals(1, ReportArchive.fromJson(a.toJson()).editHistory.size(), "the undo record survives the archive");

        reply = service.answer(a, "撤銷");
        assertTrue(reply.contains("已撤銷"), reply);
        assertTrue(hasEdge(a, "orders", "ordersdb"), "the graph is back as it was");
        assertTrue(GraphEdit.listFromJson(stateStore.loadProjectEdits("acme/shop")).isEmpty(), "the next analysis will not drop it");

        reply = service.answer(a, "撤銷");
        assertTrue(reply.contains("沒有可以撤銷"), reply);
    }

    @Test
    void undoAfterAnAdditionTakesTheEdgeOffAgain() {
        ReportArchive a = archive("");
        String reply = service.answer(a, "圖上少了 connector 連到 ordersdb 的線");
        assertTrue(reply.contains("讀不到 repo"), "no repository to search: " + reply);
        service.answer(a, "沒有");
        service.answer(a, "好");
        reply = service.answer(a, "確定");
        assertTrue(reply.contains("已加進圖"), reply);
        assertTrue(hasEdge(a, "connector", "ordersdb"));

        reply = service.answer(a, "undo the last edit");
        assertTrue(reply.contains("Undone"), reply);
        assertFalse(hasEdge(a, "connector", "ordersdb"));
        assertTrue(hasEdge(a, "orders", "ordersdb"), "the other edges are untouched");
    }

    @Test
    void undoWhileAnEditIsOpenCancelsThatEditOnly() {
        ReportArchive a = archive("acme/shop");
        service.answer(a, "把 orders 到 ordersdb 的線拿掉");
        String reply = service.answer(a, "撤銷");
        assertTrue(reply.contains("取消了"), reply);
        assertNull(a.pendingEdit);
        assertTrue(hasEdge(a, "orders", "ordersdb"));
    }
}
