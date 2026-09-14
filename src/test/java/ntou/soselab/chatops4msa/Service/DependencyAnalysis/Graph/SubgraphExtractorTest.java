package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A partial graph is drawn for a question ("the checkout flow"), so what it must never
 * do is show something the full graph does not say: no invented edge, no upgraded
 * evidence. What it may choose is only which real nodes to include, by stated rules.
 */
public class SubgraphExtractorTest {

    /** Sock Shop, roughly: checkout = orders calling payment, shipping, carts, user. */
    private static DependencyGraph sockShop() {
        DependencyGraph g = new DependencyGraph("sock-shop");
        g.addNode("istio-ingressgateway", DependencyGraph.KIND_GATEWAY);
        for (String s : List.of("front-end", "orders", "payment", "shipping", "carts", "user", "catalogue", "queue-master")) {
            g.addNode(s, DependencyGraph.KIND_SERVICE);
        }
        g.addNode("orders-db", DependencyGraph.KIND_DB);
        g.addNode("carts-db", DependencyGraph.KIND_DB);
        g.addNode("catalogue-db", DependencyGraph.KIND_DB);
        g.addNode("rabbitmq", DependencyGraph.KIND_QUEUE);

        g.addEdge("istio-ingressgateway", "front-end", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 900, "istio_requests_total");
        g.addEdge("front-end", "orders", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 40, "istio_requests_total");
        g.addEdge("front-end", "catalogue", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 300, "istio_requests_total");
        g.addEdge("front-end", "carts", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 80, "istio_requests_total");
        g.addEdge("orders", "payment", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "OrdersController.java:88");
        g.addEdge("orders", "user", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "OrdersController.java:71");
        g.addEdge("orders", "carts", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "OrdersController.java:75");
        g.addEdge("orders", "shipping", "sync-http", DependencyGraph.PROV_DOC, DependencyGraph.CONF_INFERRED, false, 0, "wiki");
        g.addEdge("orders", "orders-db", "db", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 12, "istio_tcp_connections_opened_total");
        g.addEdge("carts", "carts-db", "db", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 9, "istio_tcp_connections_opened_total");
        g.addEdge("catalogue", "catalogue-db", "db", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 7, "istio_tcp_connections_opened_total");
        g.addEdge("shipping", "rabbitmq", "async", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "ShippingController.java:40");
        g.addEdge("queue-master", "rabbitmq", "async", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "QueueMaster.java:22");
        return g;
    }

    private static List<String> ids(DependencyGraph g) {
        List<String> out = new ArrayList<>();
        for (DependencyGraph.Node n : g.getNodes()) out.add(n.id);
        return out;
    }

    @Test
    void checkoutSeedsBringTheirConnectorsStoresAndCallersButNotUnrelatedServices() {
        SubgraphExtractor.Result r = SubgraphExtractor.extract(sockShop(), List.of("front-end", "payment", "shipping"));
        List<String> nodes = ids(r.graph);

        assertEquals(List.of("front-end", "payment", "shipping"), r.seeds);
        // orders is on the path front-end -> payment / shipping.
        assertEquals(List.of("orders"), r.connectors);
        // shipping's queue is its data; the gateway is front-end's caller.
        assertTrue(nodes.containsAll(List.of("rabbitmq", "istio-ingressgateway")), nodes.toString());
        // Several seeds are a flow: front-end's other callees are the rest of the shop, not context.
        assertFalse(nodes.contains("catalogue"), nodes.toString());
        assertFalse(nodes.contains("catalogue-db"), nodes.toString());
        assertFalse(nodes.contains("queue-master"), nodes.toString());
    }

    @Test
    void aSingleSeedAlsoShowsTheServicesItCalls() {
        List<String> nodes = ids(SubgraphExtractor.extract(sockShop(), List.of("front-end")).graph);
        assertTrue(nodes.containsAll(List.of("istio-ingressgateway", "orders", "catalogue", "carts")), nodes.toString());
        assertFalse(nodes.contains("payment"), "two hops from the seed: " + nodes);
    }

    @Test
    void edgesAreInducedFromTheFullGraphWithTheirEvidenceUnchanged() {
        DependencyGraph full = sockShop();
        SubgraphExtractor.Result r = SubgraphExtractor.extract(full, List.of("orders", "payment"));

        for (DependencyGraph.Edge e : r.graph.getEdges()) {
            DependencyGraph.Edge original = null;
            for (DependencyGraph.Edge f : full.getEdges()) if (f.source.equals(e.source) && f.target.equals(e.target)) original = f;
            assertNotNull(original, "a partial graph must not invent " + e.source + " -> " + e.target);
            assertEquals(original.confidence, e.confidence);
            assertEquals(original.runtimeObserved, e.runtimeObserved);
            assertEquals(original.count, e.count);
            assertEquals(original.provenance, e.provenance);
            assertEquals(original.evidence, e.evidence);
        }
        // Every full-graph edge between two kept nodes is present.
        Set<String> kept = Set.copyOf(ids(r.graph));
        long expected = full.getEdges().stream().filter(e -> kept.contains(e.source) && kept.contains(e.target)).count();
        assertEquals(expected, r.graph.getEdges().size());
        for (DependencyGraph.Node n : r.graph.getNodes()) assertNotNull(n.layer, "the slice is re-layered: " + n.id);
    }

    @Test
    void unknownSeedsAreIgnoredAndNoSeedMeansNothing() {
        assertTrue(SubgraphExtractor.extract(sockShop(), List.of("checkout-service")).isEmpty());
        SubgraphExtractor.Result r = SubgraphExtractor.extract(sockShop(), List.of("checkout-service", "payment"));
        assertEquals(List.of("payment"), r.seeds);
        assertTrue(ids(r.graph).contains("orders"), "payment's caller is its context");
    }

    @Test
    void theNodeCapCutsNeighboursNotSeedsAndSaysWhatItCut() {
        DependencyGraph g = new DependencyGraph("wide");
        g.addNode("hub", DependencyGraph.KIND_SERVICE);
        for (int i = 0; i < 30; i++) {
            g.addNode("svc-" + i, DependencyGraph.KIND_SERVICE);
            g.addEdge("hub", "svc-" + i, "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "x");
        }
        SubgraphExtractor.Result r = SubgraphExtractor.extract(g, List.of("hub"));
        assertEquals(SubgraphExtractor.MAX_NODES, r.graph.getNodes().size());
        assertTrue(ids(r.graph).contains("hub"));
        assertEquals(30 - (SubgraphExtractor.MAX_NODES - 1), r.omitted.size());
    }

    @Test
    void seedsFarApartAreNotJoinedThroughTheWholeGraph() {
        DependencyGraph g = new DependencyGraph("chain");
        for (int i = 0; i <= 8; i++) g.addNode("n" + i, DependencyGraph.KIND_SERVICE);
        for (int i = 0; i < 8; i++) {
            g.addEdge("n" + i, "n" + (i + 1), "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "x");
        }
        SubgraphExtractor.Result far = SubgraphExtractor.extract(g, List.of("n0", "n8"));
        assertEquals(List.of("n0", "n8"), far.seeds);
        assertTrue(far.connectors.isEmpty(), "an 8-hop path is beyond MAX_PATH_HOPS: " + far.connectors);

        SubgraphExtractor.Result near = SubgraphExtractor.extract(g, List.of("n0", "n3"));
        assertEquals(List.of("n1", "n2"), near.connectors);
    }

    @Test
    void theDotHighlightsSeedsWithoutChangingTheFullGraphOutput() {
        SubgraphExtractor.Result r = SubgraphExtractor.extract(sockShop(), List.of("orders"));
        String dot = DotEmitter.emit(r.graph, "partial", Set.of("orders"));
        assertTrue(dot.contains("\"orders\" [") && dot.contains("penwidth=2.6"), dot);
        assertFalse(DotEmitter.emit(sockShop()).contains("penwidth=2.6"));
    }
}
