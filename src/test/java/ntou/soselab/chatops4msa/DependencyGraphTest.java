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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for the dependency-graph visualization: the deterministic
 * Istio-Prometheus builder, the code-edge merge, and the Mermaid / Graphviz
 * emitters. No Spring context, so they always run.
 *
 * The fixtures model spring-petclinic-microservices — the analysis target. It is
 * a good exercise for the point that a dependency graph is NOT a traffic graph:
 * its MySQL and config-server dependencies live in code/config and are never seen
 * on the mesh, so they must still appear (dashed) even with zero traffic.
 */
public class DependencyGraphTest {

    /** api-gateway fans out to the services; every service registers with discovery-server. */
    private static final String PETCLINIC_RUNTIME = """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": [
                  {"metric": {"source_workload": "istio-ingressgateway", "destination_workload": "api-gateway"}, "value": [1, "200"]},
                  {"metric": {"source_workload": "api-gateway", "destination_workload": "customers-service"}, "value": [1, "120"]},
                  {"metric": {"source_workload": "api-gateway", "destination_workload": "vets-service"}, "value": [1, "60"]},
                  {"metric": {"source_workload": "api-gateway", "destination_workload": "visits-service"}, "value": [1, "45"]},
                  {"metric": {"source_workload": "customers-service", "destination_workload": "discovery-server"}, "value": [1, "3.0e+02"]},
                  {"metric": {"source_workload": "vets-service", "destination_workload": "discovery-server"}, "value": [1, "280"]},
                  {"metric": {"source_workload": "visits-service", "destination_workload": "discovery-server"}, "value": [1, "260"]},
                  {"metric": {"source_workload": "prometheus", "destination_workload": "api-gateway"}, "value": [1, "9999"]},
                  {"metric": {"source_workload": "api-gateway", "destination_workload": "unknown"}, "value": [1, "7"]}
                ]
              }
            }
            """;

    /** Module dir names are spring-petclinic-<service>; DBs and config-server are code-only. */
    private static final String PETCLINIC_CODE = """
            {"repo":"spring-petclinic/spring-petclinic-microservices","failed":false,"edges":[
              {"section":"config","fields":{"key":"spring.datasource.url","value":"jdbc:mysql://customers-db:3306/petclinic"},"file":"spring-petclinic-customers-service/src/main/resources/application.yml","line":12,"confidence":"High"},
              {"section":"config","fields":{"key":"spring.datasource.url","value":"jdbc:mysql://vets-db:3306/petclinic"},"file":"spring-petclinic-vets-service/src/main/resources/application.yml","line":12,"confidence":"High"},
              {"section":"http-client","fields":{"url":"http://config-server:8888/"},"file":"spring-petclinic-visits-service/src/main/java/org/x/Cfg.java","line":8,"confidence":"Medium"},
              {"section":"feign","fields":{"value":"customers-service"},"file":"spring-petclinic-api-gateway/src/main/java/org/x/CustomersServiceClient.java","line":20,"confidence":"High"},
              {"section":"http-server","fields":{"path":"/owners"},"file":"spring-petclinic-customers-service/src/main/java/org/x/OwnerResource.java","line":30,"confidence":"High"},
              {"section":"http-client","fields":{"url":"http://${downstream.host}/x"},"file":"spring-petclinic-vets-service/src/main/java/org/x/X.java","line":1,"confidence":"Medium"}
            ]}
            """;

    private static DependencyGraph runtimeGraph() {
        return RuntimeGraphBuilder.fromIstioRequests(PETCLINIC_RUNTIME, "petclinic");
    }

    private static DependencyGraph mergedGraph() {
        DependencyGraph g = runtimeGraph();
        CodeGraphMerger.merge(g, PETCLINIC_CODE, "spring-petclinic/spring-petclinic-microservices");
        return g;
    }

    // ---- runtime layer (deterministic) ----

    @Test
    void runtimeKeepsBusinessEdgesDropsNoise() {
        DependencyGraph g = runtimeGraph();
        assertEquals(7, g.getEdges().size()); // 8 series - prometheus - unknown, minus none else
        assertTrue(g.getEdges().stream().noneMatch(e -> e.source.equals("prometheus")));
        assertTrue(g.getEdges().stream().noneMatch(e -> e.target.equals("unknown")));
        for (DependencyGraph.Edge e : g.getEdges()) {
            assertTrue(e.runtimeObserved);
            assertTrue(e.provenance.contains(DependencyGraph.PROV_RUNTIME));
        }
    }

    @Test
    void parsesScientificNotationCount() {
        long c = edge(runtimeGraph(), "customers-service", "discovery-server").count;
        assertEquals(300, c); // "3.0e+02"
    }

    // ---- code merge makes it a dependency graph, not a traffic graph ----

    @Test
    void codeDbDependencyAppearsEvenWithoutTraffic() {
        DependencyGraph g = mergedGraph();
        // The mesh never observes MySQL, but the config declares it -> a dashed db edge.
        assertEquals(DependencyGraph.KIND_DB, kindOf(g, "customers-db"));
        assertEquals(DependencyGraph.KIND_DB, kindOf(g, "vets-db"));
        DependencyGraph.Edge db = edge(g, "customers-service", "customers-db");
        assertNotNull(db);
        assertFalse(db.runtimeObserved);                       // dashed: code-only
        assertEquals("db", db.type);
        assertTrue(db.provenance.contains(DependencyGraph.PROV_CODE));
    }

    @Test
    void codeIntroducesUnseenServiceNode() {
        DependencyGraph g = mergedGraph();
        // config-server got no traffic in the fixture, but the code depends on it.
        assertEquals(DependencyGraph.KIND_SERVICE, kindOf(g, "config-server"));
        DependencyGraph.Edge e = edge(g, "visits-service", "config-server");
        assertNotNull(e);
        assertFalse(e.runtimeObserved);
    }

    @Test
    void codeConfirmingRuntimeEdgeStaysSolid() {
        DependencyGraph g = mergedGraph();
        // api-gateway -> customers-service is both observed AND in code (a Feign client).
        DependencyGraph.Edge e = edge(g, "api-gateway", "customers-service");
        assertTrue(e.runtimeObserved);                          // stays solid
        assertTrue(e.provenance.contains(DependencyGraph.PROV_RUNTIME));
        assertTrue(e.provenance.contains(DependencyGraph.PROV_CODE)); // confirmed by both
    }

    @Test
    void moduleDirResolvesToWorkloadNoDuplicateNode() {
        DependencyGraph g = mergedGraph();
        // "spring-petclinic-customers-service" must map to the workload customers-service,
        // never create a second node under the module directory name.
        assertTrue(g.getNodes().stream().noneMatch(n -> n.id.startsWith("spring-petclinic-")));
    }

    @Test
    void placeholderTargetGoesToResidue() {
        DependencyGraph g = runtimeGraph();
        List<CodeGraphMerger.Unresolved> residue =
                CodeGraphMerger.merge(g, PETCLINIC_CODE, "spring-petclinic/spring-petclinic-microservices");
        assertEquals(1, residue.size());                        // the ${...} url
        assertEquals("http-client", residue.get(0).section);
    }

    @Test
    void asyncBrokerBecomesQueueNode() {
        DependencyGraph g = runtimeGraph();
        String code = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"rabbit-produce","fields":{"exchange":"vets.updates"},"file":"spring-petclinic-vets-service/src/main/java/org/x/Pub.java","line":3,"confidence":"High"}
                ]}
                """;
        CodeGraphMerger.merge(g, code, "r");
        assertEquals(DependencyGraph.KIND_QUEUE, kindOf(g, "vets.updates"));
        DependencyGraph.Edge e = edge(g, "vets-service", "vets.updates");
        assertNotNull(e);
        assertEquals("async", e.type);
    }

    @Test
    void publicHostBecomesExternalNode() {
        DependencyGraph g = runtimeGraph();
        String code = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"url","fields":{"value":"https://api.stripe.com/v1/charges"},"file":"spring-petclinic-visits-service/src/main/java/org/x/Pay.java","line":8,"confidence":"High"}
                ]}
                """;
        CodeGraphMerger.merge(g, code, "r");
        assertEquals(DependencyGraph.KIND_EXTERNAL, kindOf(g, "api.stripe.com"));
        DependencyGraph.Edge e = edge(g, "visits-service", "api.stripe.com");
        assertNotNull(e);
        assertEquals("external", e.type);
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

    // ---- classifier ----

    @Test
    void classifyKindByConvention() {
        assertEquals(DependencyGraph.KIND_DB, DependencyGraph.classifyKind("customers-db"));
        assertEquals(DependencyGraph.KIND_DB, DependencyGraph.classifyKind("mysql"));
        assertEquals(DependencyGraph.KIND_QUEUE, DependencyGraph.classifyKind("rabbitmq"));
        assertEquals(DependencyGraph.KIND_GATEWAY, DependencyGraph.classifyKind("istio-ingressgateway"));
        assertEquals(DependencyGraph.KIND_SERVICE, DependencyGraph.classifyKind("customers-service"));
        assertEquals(DependencyGraph.KIND_SERVICE, DependencyGraph.classifyKind("api-gateway"));
    }

    // ---- encoding: provenance primary, type as label, count demoted ----

    @Test
    void mermaidEncodesProvenanceAndTypeNotCount() {
        String m = MermaidEmitter.emit(mergedGraph());
        assertTrue(m.startsWith("flowchart LR"));
        // runtime edge: solid, no numeric label
        assertTrue(m.contains("api_gateway --> customers_service"));
        // db dependency: dashed, labelled by type, cylinder node
        assertTrue(m.contains("-. db .->"));
        assertTrue(m.contains("[(\"customers-db\")]"));
        assertTrue(m.contains(":::db"));
        // request count is NOT the headline anymore
        assertFalse(m.contains("|120|"));
        assertFalse(m.contains("|300|"));
    }

    @Test
    void dotEncodesProvenanceAndTypeNotCount() {
        String d = DotEmitter.emit(mergedGraph());
        assertTrue(d.startsWith("digraph dependencies {"));
        assertTrue(d.contains("\"customers-db\" [shape=cylinder"));
        assertTrue(d.contains("label=\"db\""));       // type is the label
        assertTrue(d.contains("style=dashed"));       // code-only db edge
        assertTrue(d.contains("style=solid"));        // runtime edges
        assertTrue(d.contains("penwidth="));          // count demoted to weight
        assertFalse(d.contains("label=\"120\""));     // no numeric count label
        assertTrue(d.trim().endsWith("}"));
    }

    @Test
    void unavailableAndMalformedProduceEmptyGraph() {
        assertTrue(RuntimeGraphBuilder.fromIstioRequests(
                "PROMETHEUS_UNAVAILABLE\nEndpoint: ...", "petclinic").isEmpty());
        assertTrue(RuntimeGraphBuilder.fromIstioRequests(
                "<html>502 Bad Gateway</html>", "petclinic").isEmpty());
        assertTrue(RuntimeGraphBuilder.fromIstioRequests("", "petclinic").isEmpty());
        assertTrue(RuntimeGraphBuilder.fromIstioRequests(null, "petclinic").isEmpty());

        String emptyResult = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
        DependencyGraph g = RuntimeGraphBuilder.fromIstioRequests(emptyResult, "petclinic");
        assertTrue(g.isEmpty());
        assertTrue(MermaidEmitter.emit(g).contains("No runtime-observed edges"));
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
