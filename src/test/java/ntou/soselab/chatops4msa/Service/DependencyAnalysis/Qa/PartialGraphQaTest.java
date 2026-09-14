package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.SubgraphExtractor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Draw the checkout flow" in a report thread: the model may only name seeds, which are
 * validated one by one; the slice, its text for the answer and the picture are code.
 */
public class PartialGraphQaTest {

    private static DependencyGraph shop() {
        DependencyGraph g = new DependencyGraph("sock-shop");
        for (String s : List.of("front-end", "orders", "payment", "shipping", "carts", "catalogue")) g.addNode(s, DependencyGraph.KIND_SERVICE);
        g.addNode("orders-db", DependencyGraph.KIND_DB);
        g.addEdge("front-end", "orders", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 40, "istio_requests_total");
        g.addEdge("front-end", "catalogue", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 300, "istio_requests_total");
        g.addEdge("orders", "payment", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "OrdersController.java:88");
        g.addEdge("orders", "shipping", "sync-http", DependencyGraph.PROV_DOC, DependencyGraph.CONF_INFERRED, false, 0, "wiki");
        g.addEdge("orders", "carts", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "OrdersController.java:75");
        g.addEdge("orders", "orders-db", "db", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 12, "istio_tcp_connections_opened_total");
        return g;
    }

    @Test
    void aSeedListKeepsTheRealSeedsAndDropsTheInventedOnes() {
        List<GraphQuery> plan = GraphQuery.parse(
                "[{\"op\": \"subgraph\", \"args\": [\"orders\", \"checkout-service\", \"Payment\", \"orders\"]}]", shop());
        assertEquals(List.of("subgraph(orders, payment)"), plan.stream().map(GraphQuery::toString).toList());

        assertTrue(GraphQuery.parse("[{\"op\": \"subgraph\", \"args\": [\"checkout-service\"]}]", shop()).isEmpty());
        assertTrue(GraphQuery.parse("[{\"op\": \"subgraph\", \"args\": []}]", shop()).isEmpty());

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add("\"s" + i + "\"");
        DependencyGraph wide = new DependencyGraph("wide");
        for (int i = 0; i < 12; i++) wide.addNode("s" + i, DependencyGraph.KIND_SERVICE);
        List<GraphQuery> capped = GraphQuery.parse("[{\"op\": \"subgraph\", \"args\": [" + String.join(",", many) + "]}]", wide);
        assertEquals(GraphQuery.MAX_SEEDS, capped.get(0).args.size());
    }

    @Test
    void theQueryResultSaysWhyEachNodeIsInTheSlice() {
        String out = GraphQueryEngine.execute(shop(), GraphQuery.parse(
                "[{\"op\": \"subgraph\", \"args\": [\"front-end\", \"payment\"]}]", shop()));
        assertTrue(out.contains("seeds, selected for this question"), out);
        assertTrue(out.contains("on a path between seeds: orders"), out);
        assertTrue(out.contains("front-end -> orders"), out);
        assertTrue(out.contains("orders -> payment"), out);
        // catalogue is a one-hop callee of the seed front-end, so it is context, not a seed.
        assertTrue(out.contains("one-hop neighbours of a seed:") && out.contains("catalogue"), out);
    }

    @Test
    void thePlannerIsToldTheOperatorTakesSeveralSeedsAndSeesTheHints() {
        GraphQueryPlanner planner = new GraphQueryPlanner(null);
        String prompt = planner.systemPrompt(shop(), "## flows\nCheckout goes through orders, payment and shipping.");
        assertTrue(prompt.contains("- subgraph — "), prompt);
        assertTrue(prompt.contains("(1 to " + GraphQuery.MAX_SEEDS + " args)"), prompt);
        assertTrue(prompt.contains("Checkout goes through orders"), prompt);
        assertTrue(planner.systemPrompt(shop()).contains("(none retrieved)"));
    }

    @Test
    void theRouterPassesEveryNamedNodeAsOneSeedListAndDefersFlowsToThePlanner() {
        SemanticRouter.Embedder exact = texts -> {
            List<double[]> out = new ArrayList<>();
            for (String t : texts) out.add(t.contains("部分圖") || t.contains("那一塊") ? new double[]{1, 0} : new double[]{0, 1});
            return out;
        };
        SemanticRouter router = new SemanticRouter(exact, 0.5, 0.0, 0.9);
        String q = "只畫 orders 和 payment 那一塊的圖";
        SemanticRouter.Decision d = router.route(new double[]{1, 0}, q, GraphGrounding.mentionedNodes(q, shop()));
        assertEquals("subgraph", d.intent);
        assertTrue(d.confident, d.toString());
        assertEquals(List.of("subgraph(orders, payment)"), d.queries.stream().map(GraphQuery::toString).toList());

        String flow = "畫出結帳流程相關的部分圖";
        SemanticRouter.Decision f = router.route(new double[]{1, 0}, flow, GraphGrounding.mentionedNodes(flow, shop()));
        assertEquals("subgraph", f.intent);
        assertFalse(f.confident, "a flow names no node: the planner picks the seeds");
    }

    @Test
    void thePictureIsTheSliceWithTheSeedsOutlined() {
        SubgraphExtractor.Result slice = SubgraphExtractor.extract(shop(), List.of("orders", "payment"));
        Map<String, byte[]> files = ReportQaService.partialGraphFiles(slice, "microservices-demo/sock-shop");
        assertTrue(files.containsKey("partial-orders-payment.mmd"), files.keySet().toString());
        String mermaid = new String(files.get("partial-orders-payment.mmd"));
        assertTrue(mermaid.contains("orders") && mermaid.contains("payment"));
        assertFalse(mermaid.contains("catalogue\""), "catalogue is two hops from both seeds");

        String caption = ReportQaService.partialGraphCaption(slice);
        assertTrue(caption.contains("`orders`, `payment`"), caption);
    }
}
