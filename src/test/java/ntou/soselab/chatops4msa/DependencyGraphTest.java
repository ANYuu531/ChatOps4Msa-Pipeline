package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DotEmitter;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.MermaidEmitter;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.RuntimeGraphBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for the Phase 1 dependency-graph visualization: the
 * deterministic Istio-Prometheus -> graph builder and the Mermaid emitter. No
 * Spring context, so they always run.
 *
 * The fixtures mimic a sock-shop run: real business edges plus the noise the
 * builder must drop (telemetry workloads, "unknown", self-calls).
 */
public class DependencyGraphTest {

    /** A trimmed but realistic sock-shop istio_requests_total instant-query body. */
    private static final String SOCK_SHOP = """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": [
                  {"metric": {"source_workload": "istio-ingressgateway", "destination_workload": "front-end"}, "value": [1700000000, "500"]},
                  {"metric": {"source_workload": "front-end", "destination_workload": "catalogue"}, "value": [1700000000, "123"]},
                  {"metric": {"source_workload": "front-end", "destination_workload": "carts"}, "value": [1700000000, "88"]},
                  {"metric": {"source_workload": "front-end", "destination_workload": "user"}, "value": [1700000000, "40"]},
                  {"metric": {"source_workload": "front-end", "destination_workload": "orders"}, "value": [1700000000, "12"]},
                  {"metric": {"source_workload": "orders", "destination_workload": "payment"}, "value": [1700000000, "12"]},
                  {"metric": {"source_workload": "orders", "destination_workload": "shipping"}, "value": [1700000000, "11"]},
                  {"metric": {"source_workload": "catalogue", "destination_workload": "catalogue-db"}, "value": [1700000000, "1.2e+02"]},
                  {"metric": {"source_workload": "carts", "destination_workload": "carts-db"}, "value": [1700000000, "80"]},
                  {"metric": {"source_workload": "prometheus", "destination_workload": "front-end"}, "value": [1700000000, "9999"]},
                  {"metric": {"source_workload": "front-end", "destination_workload": "unknown"}, "value": [1700000000, "7"]},
                  {"metric": {"source_workload": "user", "destination_workload": "user"}, "value": [1700000000, "3"]}
                ]
              }
            }
            """;

    @Test
    void buildsBusinessEdgesAndDropsNoise() {
        DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(SOCK_SHOP, "sock-shop");

        // 9 business edges kept; prometheus/unknown/self-call dropped.
        assertEquals(9, graph.getEdges().size());

        // prometheus (telemetry), "unknown", and the user->user self-call are gone.
        assertTrue(graph.getEdges().stream().noneMatch(e -> e.source.equals("prometheus")));
        assertTrue(graph.getEdges().stream().noneMatch(e -> e.target.equals("unknown")));
        assertTrue(graph.getEdges().stream().noneMatch(e -> e.source.equals(e.target)));
    }

    @Test
    void classifiesNodeKinds() {
        DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(SOCK_SHOP, "sock-shop");

        assertEquals(DependencyGraph.KIND_GATEWAY, kindOf(graph, "istio-ingressgateway"));
        assertEquals(DependencyGraph.KIND_DB, kindOf(graph, "catalogue-db"));
        assertEquals(DependencyGraph.KIND_DB, kindOf(graph, "carts-db"));
        assertEquals(DependencyGraph.KIND_SERVICE, kindOf(graph, "front-end"));
    }

    @Test
    void parsesScientificNotationCount() {
        DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(SOCK_SHOP, "sock-shop");
        long count = graph.getEdges().stream()
                .filter(e -> e.source.equals("catalogue") && e.target.equals("catalogue-db"))
                .mapToLong(e -> e.count).findFirst().orElse(-1);
        assertEquals(120, count); // "1.2e+02" -> 120
    }

    @Test
    void everyRuntimeEdgeIsObservedWithProvenance() {
        DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(SOCK_SHOP, "sock-shop");
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            assertTrue(edge.runtimeObserved);
            assertTrue(edge.provenance.contains(DependencyGraph.PROV_RUNTIME));
            assertEquals(DependencyGraph.CONF_OBSERVED, edge.confidence);
        }
    }

    @Test
    void emitsRenderableMermaid() {
        DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(SOCK_SHOP, "sock-shop");
        String mermaid = MermaidEmitter.emit(graph);

        assertTrue(mermaid.startsWith("flowchart LR"));
        // Hyphenated names must be quoted labels, not raw ids.
        assertTrue(mermaid.contains("[\"front-end\"]"));
        // An observed edge is a solid arrow carrying its count.
        assertTrue(mermaid.contains("-->|123|"));
        // db kind gets a classDef and a cylinder shape.
        assertTrue(mermaid.contains("classDef db"));
        assertTrue(mermaid.contains(":::db"));
    }

    @Test
    void emitsRenderableDot() {
        DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(SOCK_SHOP, "sock-shop");
        String dot = DotEmitter.emit(graph);

        assertTrue(dot.startsWith("digraph dependencies {"));
        assertTrue(dot.contains("rankdir=LR;"));
        // Hyphenated names are valid quoted ids in DOT — no remapping needed.
        assertTrue(dot.contains("\"front-end\" -> \"catalogue\""));
        // A runtime-observed edge is solid and labelled with its count.
        assertTrue(dot.contains("label=\"123\""));
        assertTrue(dot.contains("style=solid"));
        // db node uses a cylinder.
        assertTrue(dot.contains("\"catalogue-db\" [shape=cylinder"));
        assertTrue(dot.trim().endsWith("}"));
    }

    @Test
    void dotEmptyGraphIsStillValid() {
        DependencyGraph empty = new DependencyGraph("ns");
        String dot = DotEmitter.emit(empty);
        assertTrue(dot.startsWith("digraph dependencies {"));
        assertTrue(dot.contains("No dependency edges"));
        assertTrue(dot.trim().endsWith("}"));
    }

    @Test
    void unavailableAndMalformedProduceEmptyGraph() {
        assertTrue(RuntimeGraphBuilder.fromIstioRequests(
                "PROMETHEUS_UNAVAILABLE\nEndpoint: ...", "sock-shop").isEmpty());
        assertTrue(RuntimeGraphBuilder.fromIstioRequests(
                "<html>502 Bad Gateway</html>", "sock-shop").isEmpty());
        assertTrue(RuntimeGraphBuilder.fromIstioRequests("", "sock-shop").isEmpty());
        assertTrue(RuntimeGraphBuilder.fromIstioRequests(null, "sock-shop").isEmpty());

        // Empty result set is a valid, renderable "nothing observed" diagram.
        String emptyResult = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
        DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(emptyResult, "sock-shop");
        assertTrue(graph.isEmpty());
        assertTrue(MermaidEmitter.emit(graph).contains("No runtime-observed edges"));
    }

    @Test
    void mergesDuplicateEdgesKeepingMaxCount() {
        // Same directed edge reported twice (e.g. two series) must not duplicate.
        String json = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"source_workload":"a","destination_workload":"b"},"value":[1,"5"]},
                  {"metric":{"source_workload":"a","destination_workload":"b"},"value":[1,"9"]}
                ]}}
                """;
        DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(json, "ns");
        assertEquals(1, graph.getEdges().size());
        assertEquals(9, graph.getEdges().iterator().next().count);
        assertFalse(graph.isEmpty());
    }

    // ---- Part B: code-edge merge (deterministic) ----

    /** A runtime graph over sock-shop workloads, to give the merger a node vocabulary. */
    private static DependencyGraph runtimeGraph() {
        DependencyGraph g = RuntimeGraphBuilder.fromIstioRequests(SOCK_SHOP, "sock-shop");
        return g;
    }

    @Test
    void mergesResolvableCodeEdgesDeterministically() {
        DependencyGraph g = runtimeGraph();
        // catalogue->user is NOT a runtime edge in the fixture, so it must appear as
        // a dashed (code-only) edge. front-end->catalogue IS runtime, so merging the
        // code edge onto it keeps it solid and adds "code" provenance.
        String codeEdges = """
                {"repo":"myorg/sock-shop","failed":false,"edges":[
                  {"section":"http-client","fields":{"url":"http://user/profile"},"file":"catalogue/src/Svc.java","line":10,"confidence":"High"},
                  {"section":"feign","fields":{"value":"catalogue"},"file":"front-end/api/Foo.java","line":5,"confidence":"High"}
                ]}
                """;
        List<CodeGraphMerger.Unresolved> residue =
                CodeGraphMerger.merge(g, codeEdges, "myorg/sock-shop");

        assertTrue(residue.isEmpty(), "both edges should resolve deterministically");

        DependencyGraph.Edge catalogueUser = edge(g, "catalogue", "user");
        assertTrue(catalogueUser != null);
        assertFalse(catalogueUser.runtimeObserved); // code-only -> dashed
        assertTrue(catalogueUser.provenance.contains(DependencyGraph.PROV_CODE));

        DependencyGraph.Edge frontCatalogue = edge(g, "front-end", "catalogue");
        assertTrue(frontCatalogue.runtimeObserved); // stays solid (runtime)
        assertTrue(frontCatalogue.provenance.contains(DependencyGraph.PROV_RUNTIME));
        assertTrue(frontCatalogue.provenance.contains(DependencyGraph.PROV_CODE)); // confirmed by both
    }

    @Test
    void asyncBrokerBecomesQueueNode() {
        DependencyGraph g = runtimeGraph();
        String codeEdges = """
                {"repo":"myorg/sock-shop","failed":false,"edges":[
                  {"section":"rabbit-produce","fields":{"exchange":"shipping-task"},"file":"orders/src/Q.java","line":3,"confidence":"High"}
                ]}
                """;
        CodeGraphMerger.merge(g, codeEdges, "myorg/sock-shop");

        assertEquals(DependencyGraph.KIND_QUEUE, kindOf(g, "shipping-task"));
        assertTrue(edge(g, "orders", "shipping-task") != null); // produce: service -> destination
    }

    @Test
    void externalHostBecomesExternalNode() {
        DependencyGraph g = runtimeGraph();
        String codeEdges = """
                {"repo":"myorg/sock-shop","failed":false,"edges":[
                  {"section":"url","fields":{"value":"https://api.github.com/v3/repos"},"file":"user/src/Gh.java","line":8,"confidence":"High"}
                ]}
                """;
        CodeGraphMerger.merge(g, codeEdges, "myorg/sock-shop");

        assertEquals(DependencyGraph.KIND_EXTERNAL, kindOf(g, "api.github.com"));
        assertTrue(edge(g, "user", "api.github.com") != null);
    }

    @Test
    void unresolvableTargetsGoToResidueNotGuessed() {
        DependencyGraph g = runtimeGraph();
        String codeEdges = """
                {"repo":"myorg/sock-shop","failed":false,"edges":[
                  {"section":"http-client","fields":{"url":"http://${downstream.host}/x"},"file":"orders/src/Svc.java","line":1,"confidence":"Medium"},
                  {"section":"http-server","fields":{"path":"/orders"},"file":"orders/src/Api.java","line":2,"confidence":"High"}
                ]}
                """;
        int edgesBefore = g.getEdges().size();
        List<CodeGraphMerger.Unresolved> residue =
                CodeGraphMerger.merge(g, codeEdges, "myorg/sock-shop");

        // The ${...} placeholder cannot be resolved -> residue (for the LLM), not a guess.
        assertEquals(1, residue.size());
        assertEquals("http-client", residue.get(0).section);
        // http-server is inbound and is dropped entirely (not an outgoing edge, not residue).
        assertEquals(edgesBefore, g.getEdges().size());
    }

    @Test
    void failedOrEmptyCodeLedgerIsNoOp() {
        DependencyGraph g = runtimeGraph();
        int before = g.getEdges().size();
        assertTrue(CodeGraphMerger.merge(g, "{\"failed\":true,\"edges\":[]}", "r").isEmpty());
        assertTrue(CodeGraphMerger.merge(g, "", "r").isEmpty());
        assertTrue(CodeGraphMerger.merge(g, null, "r").isEmpty());
        assertTrue(CodeGraphMerger.merge(g, "not json", "r").isEmpty());
        assertEquals(before, g.getEdges().size());
    }

    private static DependencyGraph.Edge edge(DependencyGraph g, String source, String target) {
        return g.getEdges().stream()
                .filter(e -> e.source.equals(source) && e.target.equals(target))
                .findFirst().orElse(null);
    }

    private static String kindOf(DependencyGraph graph, String id) {
        return graph.getNodes().stream()
                .filter(n -> n.id.equals(id))
                .map(n -> n.kind).findFirst().orElse(null);
    }
}
