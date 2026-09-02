package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CoverageAnalyzer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DocGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DotEmitter;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphLayerAssigner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphNormalizer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.K8sGraphBuilder;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.MermaidEmitter;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.RuntimeGraphBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
              {"section":"jpa","fields":{"marker":"Entity"},"file":"spring-petclinic-customers-service/src/main/java/org/x/Owner.java","line":20,"confidence":"High"},
              {"section":"jpa","fields":{"marker":"Entity"},"file":"spring-petclinic-vets-service/src/main/java/org/x/Vet.java","line":15,"confidence":"High"},
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
    void undeployedServiceModuleDirStripsRepoPrefix() {
        // genai-service has code but no running workload: its module dir cannot align to
        // a runtime node, so the learned repo prefix is stripped and it reads as the
        // service name, not the full module directory.
        DependencyGraph g = runtimeGraph();
        String code = """
                {"repo":"spring-petclinic/spring-petclinic-microservices","failed":false,"edges":[
                  {"section":"config","fields":{"key":"customers.url","value":"http://customers-service/"},"file":"spring-petclinic-customers-service/src/main/resources/application.yml","line":5,"confidence":"High"},
                  {"section":"feign","fields":{"value":"vets-service"},"file":"spring-petclinic-genai-service/src/main/java/org/x/VetClient.java","line":10,"confidence":"High"}
                ]}
                """;
        CodeGraphMerger.merge(g, code, "spring-petclinic/spring-petclinic-microservices");
        assertNotNull(node(g, "genai-service"));
        assertTrue(g.getNodes().stream().noneMatch(n -> n.id.startsWith("spring-petclinic-")));
        assertNotNull(edge(g, "genai-service", "vets-service"));
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

    // ---- K8s deployment status (deterministic node enrichment) ----

    /** Deployment inventory: api-gateway + the three services + discovery-server run; config-server does not. */
    private static final String PETCLINIC_DEPLOYMENTS = """
            api-gateway        2026-07-20T10:15:00Z  1  1  springcommunity/spring-petclinic-api-gateway:3.0
            customers-service  2026-07-20T10:16:30Z  1  1  springcommunity/spring-petclinic-customers-service:3.0
            vets-service       2026-07-20T10:16:45Z  1  1  springcommunity/spring-petclinic-vets-service:3.0
            visits-service     2026-07-20T10:17:00Z  1  1  springcommunity/spring-petclinic-visits-service:3.0
            discovery-server   2026-07-20T10:10:00Z  1  1  springcommunity/spring-petclinic-discovery-server:3.0
            """;

    private static DependencyGraph enrichedGraph() {
        DependencyGraph g = mergedGraph();
        K8sGraphBuilder.enrich(g, PETCLINIC_DEPLOYMENTS);
        return g;
    }

    @Test
    void k8sMarksDeployedNodeWithMetadata() {
        DependencyGraph.Node n = node(enrichedGraph(), "customers-service");
        assertEquals(Boolean.TRUE, n.deployed);
        assertEquals("2026-07-20T10:16:30Z", n.deployedAt);
        assertTrue(n.image.contains("customers-service"));
        assertEquals("1/1", n.replicas);
    }

    @Test
    void k8sMarksReferencedButUndeployedServiceNotDeployed() {
        // config-server is depended on in code but is absent from the deployment inventory.
        assertEquals(Boolean.FALSE, node(enrichedGraph(), "config-server").deployed);
    }

    @Test
    void k8sDoesNotFalselyMarkDbOrExternalUndeployed() {
        DependencyGraph g = enrichedGraph();
        // An externally-managed DB is absent from Deployments; that is NOT "not deployed".
        assertNull(node(g, "customers-db").deployed);
        assertNull(node(g, "vets-db").deployed);
    }

    @Test
    void k8sBlankOrUnparsableInputLeavesGraphUnenriched() {
        DependencyGraph g = mergedGraph();
        K8sGraphBuilder.enrich(g, "");
        K8sGraphBuilder.enrich(g, null);
        K8sGraphBuilder.enrich(g, "no data\n<html>502 Bad Gateway</html>");
        for (DependencyGraph.Node n : g.getNodes()) assertNull(n.deployed);
    }

    @Test
    void k8sParsesThroughTheRealMcpJsonEnvelope() {
        // The k8s MCP server returns a CommandResult whose "output" is ONE JSON-escaped
        // string (kubectl newlines become \n), which the MCP toolkit wraps in a Markdown
        // envelope. enrich must see through the envelope + JSON + escaping. The \\n below
        // are literal backslash-n in the stored value, exactly as the server emits them.
        String out = "api-gateway  2026-07-20T10:15:00Z  1  1  reg/api-gateway:3.0"
                + "\\ncustomers-service  2026-07-20T10:16:30Z  1  1  reg/customers-service:3.0"
                + "\\nvets-service  2026-07-20T10:16:45Z  1  1  reg/vets-service:3.0"
                + "\\nvisits-service  2026-07-20T10:17:00Z  1  1  reg/visits-service:3.0"
                + "\\ndiscovery-server  2026-07-20T10:10:00Z  1  1  reg/discovery-server:3.0";
        String envelope = "MCP tool result\n\nServer:\n`k8s`\n\nTool:\n`execute_kubectl`\n\n"
                + "Arguments:\n```json\n{\n  \"command\" : \"get deployments -n petclinic -o "
                + "custom-columns=NAME:.metadata.name,CREATED:.metadata.creationTimestamp,"
                + "READY:.status.readyReplicas,REPLICAS:.spec.replicas,"
                + "IMAGE:.spec.template.spec.containers[0].image --no-headers\",\n  \"timeout\" : 20\n}\n```\n\n"
                + "Result:\n```text\n{\"status\": \"success\", \"output\": \"" + out + "\", \"exit_code\": 0}\n```\n";

        DependencyGraph g = mergedGraph();
        K8sGraphBuilder.enrich(g, envelope);
        assertEquals(Boolean.TRUE, node(g, "customers-service").deployed);
        assertEquals("2026-07-20T10:16:30Z", node(g, "customers-service").deployedAt);
        assertTrue(node(g, "customers-service").image.contains("customers-service"));
        assertEquals(Boolean.FALSE, node(g, "config-server").deployed); // absent from output
        assertNull(node(g, "customers-db").deployed);                   // db not falsely marked
    }

    @Test
    void notDeployedServiceRendersDashedInBothEmitters() {
        DependencyGraph g = enrichedGraph();
        String dot = DotEmitter.emit(g);
        assertTrue(dot.contains("(not deployed)"));
        assertTrue(dot.lines().anyMatch(l -> l.contains("\"config-server\"") && l.contains("dashed")));
        String m = MermaidEmitter.emit(g);
        assertTrue(m.contains("classDef notDeployed"));
        assertTrue(m.contains("class config_server notDeployed"));
    }

    @Test
    void deployedNodeShowsMetadataInLabels() {
        DependencyGraph g = enrichedGraph();
        // Deployment date surfaces in both the DOT label and the Mermaid label.
        assertTrue(DotEmitter.emit(g).contains("2026-07-20"));
        String m = MermaidEmitter.emit(g);
        assertTrue(m.contains("<br/>"));
        assertTrue(m.contains("2026-07-20"));
    }

    // ---- DB "really used" vs "declared" (JPA persistence signal + doc evidence) ----

    @Test
    void jpaMarkerMakesDbReallyUsedNotJustDeclared() {
        DependencyGraph g = mergedGraph();
        // customers-service has an @Entity -> its db is really used (documented tier),
        // not the weakest declared-only tier.
        assertEquals(DependencyGraph.CONF_DOCUMENTED,
                edge(g, "customers-service", "customers-db").confidence);
    }

    @Test
    void datasourceWithoutPersistenceCodeIsDeclaredOnly() {
        // A service that declares a datasource but has NO entity/repository code: the
        // db edge is the weakest tier, and renders dotted / "?" so it never reads as used.
        DependencyGraph g = runtimeGraph();
        String code = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"config","fields":{"key":"spring.datasource.url","value":"jdbc:mysql://reporting-db:3306/r"},"file":"spring-petclinic-visits-service/src/main/resources/application.yml","line":9,"confidence":"High"}
                ]}
                """;
        CodeGraphMerger.merge(g, code, "r");
        DependencyGraph.Edge e = edge(g, "visits-service", "reporting-db");
        assertNotNull(e);
        assertEquals(DependencyGraph.CONF_INFERRED, e.confidence);
        assertTrue(DotEmitter.emit(g).contains("style=dotted"));
        assertTrue(MermaidEmitter.emit(g).contains("db?"));
    }

    @Test
    void docMergerAddsDeepwikiEdgesWithEvidenceTiers() {
        DependencyGraph g = mergedGraph();
        String notes = """
                {
                  "synchronous_candidates": [
                    {"source":"API Gateway","target":"Discovery Server","dependency_type":"application","configured":"yes","evidence_reference":"docs/arch.md"},
                    {"source":"API Gateway","target":"Some Widget Registry","dependency_type":"application","configured":"yes"}
                  ],
                  "infrastructure_dependencies": [
                    {"source_component":"vets-service","target":"vets-cache","dependency_type":"cache","configured":"no","evidence_reference":"wiki"}
                  ]
                }
                """;
        DocGraphMerger.merge(g, notes);
        // configured application dependency onto an EXISTING workload -> documented, doc prov
        DependencyGraph.Edge sync = edge(g, "api-gateway", "discovery-server");
        assertNotNull(sync);
        assertTrue(sync.provenance.contains(DependencyGraph.PROV_DOC));
        assertEquals(DependencyGraph.CONF_DOCUMENTED, sync.confidence);
        assertFalse(sync.runtimeObserved);
        // a doc-only "service" that aligns to no known workload is NOT invented as a phantom
        assertTrue(g.getNodes().stream().noneMatch(n -> n.id.contains("widget")));
        // documented-only cache -> db-typed, declared-only (weakest) tier
        DependencyGraph.Edge cache = edge(g, "vets-service", "vets-cache");
        assertNotNull(cache);
        assertEquals("db", cache.type);
        assertEquals(DependencyGraph.CONF_INFERRED, cache.confidence);
    }

    @Test
    void docMergerDropsInProcessCacheLibraryAsDbNode() {
        // caffeine is an in-process cache library, not a datastore — it must NOT be
        // drawn as a db the service "uses" (that is exactly the overstated-db risk).
        DependencyGraph g = mergedGraph();
        DocGraphMerger.merge(g, """
                {"infrastructure_dependencies":[
                  {"source_component":"vets-service","target":"Caffeine","dependency_type":"cache","configured":"yes"}
                ]}
                """);
        assertNull(node(g, "caffeine"));
        assertNull(edge(g, "vets-service", "caffeine"));
    }

    @Test
    void docMergerIsNoOpOnMalformedOrEmpty() {
        DependencyGraph g = mergedGraph();
        int before = g.getEdges().size();
        DocGraphMerger.merge(g, "");
        DocGraphMerger.merge(g, null);
        DocGraphMerger.merge(g, "not json at all");
        DocGraphMerger.merge(g, "{}");
        assertEquals(before, g.getEdges().size());
    }

    @Test
    void persistenceCodePromotesADocOnlyDbToReallyUsed() {
        // petclinic externalises its datasource, so the db edge is doc-only; the JPA
        // proof in the service source must still promote it from declared to really-used.
        DependencyGraph g = mergedGraph();
        DocGraphMerger.merge(g, """
                {"infrastructure_dependencies":[
                  {"source_component":"spring-petclinic-customers-service","target":"HSQLDB","dependency_type":"database","configured":"unknown"}
                ]}
                """);
        // doc-only + configured unknown -> starts declared-only (inferred)
        assertEquals(DependencyGraph.CONF_INFERRED, edge(g, "customers-service", "hsqldb").confidence);
        // the @Entity proof in customers-service promotes it to really-used
        CodeGraphMerger.promoteReallyUsedDbs(g, CodeGraphMerger.persistenceServices(
                g, PETCLINIC_CODE, "spring-petclinic/spring-petclinic-microservices"));
        assertEquals(DependencyGraph.CONF_DOCUMENTED, edge(g, "customers-service", "hsqldb").confidence);
    }

    @Test
    void persistenceSignalIsNotJpaSpecific() {
        // The "really used" proof is language-neutral: a Python service that emits a
        // `persistence` marker (SQLAlchemy/Django) must promote its db exactly like a
        // Java @Entity does. This exercises CodeGraphMerger.PERSISTENCE_SECTIONS rather
        // than the old hardcoded "jpa" — the ORM interface generalisation.
        DependencyGraph g = RuntimeGraphBuilder.fromIstioRequests("""
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"source_workload":"istio-ingressgateway","destination_workload":"orders-service"},"value":[1,"10"]}
                ]}}
                """, "shop");
        String code = """
                {"repo":"acme/shop","failed":false,"edges":[
                  {"section":"config","fields":{"key":"DATABASE_URL","value":"postgresql://orders-db:5432/orders"},"file":"orders-service/app/db.py","line":3,"confidence":"High"},
                  {"section":"persistence","fields":{"model":"Order"},"file":"orders-service/app/models.py","line":11,"confidence":"High"}
                ]}
                """;
        CodeGraphMerger.merge(g, code, "acme/shop");
        DependencyGraph.Edge db = edge(g, "orders-service", "orders-db");
        assertNotNull(db);
        assertEquals("db", db.type);
        // promoted to really-used (documented), not left as declared-only (inferred)
        assertEquals(DependencyGraph.CONF_DOCUMENTED, db.confidence);
    }

    @Test
    void persistenceMarkerAddsNoEdgeOfItsOwn() {
        // A persistence marker is a signal, not a dependency edge — it has no target,
        // so (like an http-server or jpa row) it must not add an edge to the graph.
        DependencyGraph g = runtimeGraph();
        int before = g.getEdges().size();
        CodeGraphMerger.merge(g, """
                {"repo":"r","failed":false,"edges":[
                  {"section":"persistence","fields":{"model":"Owner"},"file":"customers-service/app/models.py","line":1,"confidence":"High"}
                ]}
                """, "r");
        assertEquals(before, g.getEdges().size());
    }

    @Test
    void docMergerAlignsDisplayNameAliasesInsteadOfDuplicating() {
        // DeepWiki writes display names: "Customers Service", "CustomersServiceClient",
        // "spring-petclinic-customers-service", "Grafana", "HSQLDB". These must align to
        // the real workloads (or be dropped as infra), never create phantom nodes.
        DependencyGraph g = mergedGraph();
        String notes = """
                {
                  "synchronous_candidates": [
                    {"source":"API Gateway","target":"CustomersServiceClient","dependency_type":"application","configured":"yes"},
                    {"source":"Visits Service","target":"Grafana","dependency_type":"application","configured":"unknown"}
                  ],
                  "infrastructure_dependencies": [
                    {"source_component":"spring-petclinic-customers-service","target":"HSQLDB","dependency_type":"database","configured":"yes"},
                    {"source_component":"spring-petclinic-vets-service","target":"MySQL","dependency_type":"database","configured":"unknown"}
                  ]
                }
                """;
        DocGraphMerger.merge(g, notes);
        // no phantom module-dir / feign-client / infra nodes
        assertTrue(g.getNodes().stream().noneMatch(n -> n.id.startsWith("spring-petclinic-")));
        assertTrue(g.getNodes().stream().noneMatch(n -> n.id.contains("client")));
        assertTrue(g.getNodes().stream().noneMatch(n -> n.id.equals("grafana") || n.id.equals("prometheus")));
        // the client alias resolved onto the real workload (edge already existed at runtime)
        assertNotNull(edge(g, "api-gateway", "customers-service"));
        // the module-dir source resolved, and the documented db appears as a real db node
        DependencyGraph.Edge db = edge(g, "customers-service", "hsqldb");
        assertNotNull(db);
        assertEquals("db", db.type);
        assertEquals(DependencyGraph.KIND_DB, kindOf(g, "hsqldb"));
        assertNotNull(edge(g, "vets-service", "mysql"));
    }

    @Test
    void docConfiguredDbPromotesADeclaredOnlyCodeEdge() {
        // A db known only from a datasource URL (declared-only) is promoted to used
        // when the docs say it is configured — the layers compose via max-confidence.
        DependencyGraph g = runtimeGraph();
        CodeGraphMerger.merge(g,
                "{\"repo\":\"r\",\"failed\":false,\"edges\":[{\"section\":\"config\",\"fields\":{\"key\":\"spring.datasource.url\",\"value\":\"jdbc:mysql://orders-db:3306/o\"},\"file\":\"spring-petclinic-visits-service/x/application.yml\",\"line\":1,\"confidence\":\"High\"}]}",
                "r");
        assertEquals(DependencyGraph.CONF_INFERRED, edge(g, "visits-service", "orders-db").confidence);
        DocGraphMerger.merge(g, """
                {"infrastructure_dependencies":[
                  {"source_component":"visits-service","target":"orders-db","dependency_type":"database","configured":"yes","evidence_reference":"cfg"}
                ]}
                """);
        assertEquals(DependencyGraph.CONF_DOCUMENTED, edge(g, "visits-service", "orders-db").confidence);
    }

    // ---- runtime traffic coverage (deterministic) ----

    @Test
    void coverageCountsDrivableBusinessEdgesOnly() {
        CoverageAnalyzer.Report r = CoverageAnalyzer.analyze(mergedGraph());
        // Drivable business sync edges: istio-ingressgateway->api-gateway and
        // api-gateway->{customers,vets,visits} — all observed. The three ->discovery-server
        // edges and visits->config-server are control-plane (excluded); the *-db edges are
        // db-type (excluded). So coverage is a clean 4/4.
        assertEquals(4, r.total);
        assertEquals(4, r.observed);
        assertEquals(100, r.percent());
        assertTrue(r.uncovered.isEmpty());
        assertFalse(r.uncovered.stream().anyMatch(s -> s.contains("discovery-server")));
        assertFalse(r.uncovered.stream().anyMatch(s -> s.contains("config-server")));
        assertFalse(r.uncovered.stream().anyMatch(s -> s.contains("customers-db")));
    }

    @Test
    void coverageExcludesControlPlanePhantomAndUndeployedEdges() {
        DependencyGraph g = new DependencyGraph("petclinic");
        g.addNode("api-gateway", DependencyGraph.KIND_SERVICE).deployed = Boolean.TRUE;
        g.addNode("customers-service", DependencyGraph.KIND_SERVICE).deployed = Boolean.TRUE;
        g.addNode("visits-service", DependencyGraph.KIND_SERVICE).deployed = Boolean.TRUE;
        // control-plane: deployed, so ONLY the name filter can drop these
        g.addNode("discovery-server", DependencyGraph.KIND_SERVICE).deployed = Boolean.TRUE;
        g.addNode("config-server", DependencyGraph.KIND_SERVICE).deployed = Boolean.TRUE;
        // a framework-library phantom and an undeployed real service: dropped by deployed=FALSE
        g.addNode("resilience4j", DependencyGraph.KIND_SERVICE).deployed = Boolean.FALSE;
        g.addNode("genai-service", DependencyGraph.KIND_SERVICE).deployed = Boolean.FALSE;

        g.addEdge("api-gateway", "customers-service", "sync-http",
                DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 12, null);   // covered
        g.addEdge("api-gateway", "visits-service", "sync-http",
                DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);     // uncovered, real
        g.addEdge("customers-service", "discovery-server", "sync-http",
                DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 5, null);     // control-plane
        g.addEdge("visits-service", "config-server", "sync-http",
                DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);     // control-plane
        g.addEdge("api-gateway", "resilience4j", "sync-http",
                DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);     // phantom lib
        g.addEdge("api-gateway", "genai-service", "sync-http",
                DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);     // undeployed

        CoverageAnalyzer.Report r = CoverageAnalyzer.analyze(g);
        // Only api-gateway->{customers-service, visits-service} are drivable business edges.
        assertEquals(2, r.total);
        assertEquals(1, r.observed);
        assertEquals(50, r.percent());
        assertEquals(List.of("api-gateway -> visits-service"), r.uncovered);
    }

    @Test
    void coverageIsEmptyWhenNoServiceEdges() {
        CoverageAnalyzer.Report r = CoverageAnalyzer.analyze(new DependencyGraph("ns"));
        assertFalse(r.hasEdges());
        assertEquals(0, r.percent());
        assertTrue(r.uncovered.isEmpty());
    }

    // ---- graph normalization (alias collapse + phantom drop) ----

    @Test
    void renameNodeMergesAliasEdgesOntoRealWorkload() {
        DependencyGraph g = new DependencyGraph("ns");
        g.addNode("api-gateway", DependencyGraph.KIND_SERVICE);
        g.addNode("customers-service", DependencyGraph.KIND_SERVICE);
        g.addNode("api-gateway-controller", DependencyGraph.KIND_SERVICE);
        g.addEdge("api-gateway", "customers-service", "sync-http",
                DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 10, "runtime");
        g.addEdge("api-gateway-controller", "customers-service", "sync-http",
                DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: X.java:3");

        g.renameNode("api-gateway-controller", "api-gateway");

        assertNull(node(g, "api-gateway-controller"));
        DependencyGraph.Edge e = edge(g, "api-gateway", "customers-service");
        assertNotNull(e);
        assertTrue(e.runtimeObserved);                                   // stays solid
        assertTrue(e.provenance.contains(DependencyGraph.PROV_RUNTIME));
        assertTrue(e.provenance.contains(DependencyGraph.PROV_CODE));    // alias evidence folded in
        assertEquals(1, g.getEdges().stream()
                .filter(x -> x.source.equals("api-gateway") && x.target.equals("customers-service"))
                .count());                                              // merged, not duplicated
    }

    @Test
    void normalizeDropsPhantomsAndCollapsesAliasesButKeepsRealNodes() {
        DependencyGraph g = new DependencyGraph("petclinic");
        g.addNode("api-gateway", DependencyGraph.KIND_SERVICE).deployed = Boolean.TRUE;
        g.addNode("customers-service", DependencyGraph.KIND_SERVICE).deployed = Boolean.TRUE;
        g.addNode("discovery-server", DependencyGraph.KIND_SERVICE).deployed = Boolean.TRUE;
        g.addNode("api-gateway-controller", DependencyGraph.KIND_SERVICE).deployed = Boolean.FALSE;
        g.addNode("resilience4j", DependencyGraph.KIND_SERVICE).deployed = Boolean.FALSE;
        g.addNode("all-services", DependencyGraph.KIND_SERVICE).deployed = Boolean.FALSE;
        g.addNode("genai-service", DependencyGraph.KIND_SERVICE).deployed = Boolean.FALSE;

        g.addEdge("api-gateway-controller", "customers-service", "sync-http",
                DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);
        g.addEdge("api-gateway", "resilience4j", "sync-http",
                DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);
        g.addEdge("all-services", "discovery-server", "sync-http",
                DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);
        g.addEdge("api-gateway", "genai-service", "sync-http",
                DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, null);

        GraphNormalizer.normalize(g);

        // framework-lib and grouping pseudo-nodes gone, with their edges
        assertNull(node(g, "resilience4j"));
        assertNull(node(g, "all-services"));
        assertTrue(g.getEdges().stream().noneMatch(e -> e.target.equals("resilience4j")));
        assertTrue(g.getEdges().stream().noneMatch(e -> e.source.equals("all-services")));
        // alias collapsed onto the real workload (edge preserved on the real node)
        assertNull(node(g, "api-gateway-controller"));
        assertNotNull(edge(g, "api-gateway", "customers-service"));
        // real nodes untouched: a live service, control-plane, and a legit undeployed service
        assertNotNull(node(g, "api-gateway"));
        assertNotNull(node(g, "discovery-server"));
        assertNotNull(node(g, "genai-service"));
        assertNotNull(edge(g, "api-gateway", "genai-service"));
    }

    // ---- compose depends_on -> control-plane edges ----

    @Test
    void composeDependsOnDrawsControlPlaneEdgesAndMergesWithRuntime() {
        // Runtime observed only api-gateway->config-server this window (the other services'
        // config fetches were not seen — e.g. config-server was restarted, resetting its
        // inbound metrics). Compose still declares every service's dependency on it.
        String runtime = """
                {"status":"success","data":{"result":[
                  {"metric":{"source_workload":"api-gateway","destination_workload":"config-server"},"value":[1,"20"]}
                ]}}""";
        String code = """
                {"repo":"x/y","failed":false,"edges":[
                  {"section":"compose-dependency","fields":{"source_service":"api-gateway","target_service":"config-server"},"file":"docker-compose.yml","line":-1},
                  {"section":"compose-dependency","fields":{"source_service":"customers-service","target_service":"config-server"},"file":"docker-compose.yml","line":-1},
                  {"section":"compose-dependency","fields":{"source_service":"customers-service","target_service":"discovery-server"},"file":"docker-compose.yml","line":-1}
                ]}""";
        DependencyGraph g = RuntimeGraphBuilder.fromIstioRequests(runtime, "petclinic");
        CodeGraphMerger.merge(g, code, "x/y");

        // observed AND declared -> solid, carries both provenance
        DependencyGraph.Edge apiToCfg = edge(g, "api-gateway", "config-server");
        assertNotNull(apiToCfg);
        assertTrue(apiToCfg.runtimeObserved);
        assertTrue(apiToCfg.provenance.contains(DependencyGraph.PROV_CODE));
        // only compose knows this one -> present but dashed (config-server stays a hub
        // even though its runtime metric for this fetch is missing)
        DependencyGraph.Edge custToCfg = edge(g, "customers-service", "config-server");
        assertNotNull(custToCfg);
        assertFalse(custToCfg.runtimeObserved);
        assertNotNull(edge(g, "customers-service", "discovery-server"));
    }

    // ---- egress fold (runtime external edges from TCP telemetry) ----

    /** config-server opens TLS to github.com; the passthrough/unknown series are opaque noise. */
    private static final String PETCLINIC_EGRESS_TCP = """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": [
                  {"metric": {"source_workload": "config-server", "destination_service_name": "github.com"}, "value": [1, "197"]},
                  {"metric": {"source_workload": "config-server", "destination_service_name": "unknown"}, "value": [1, "40"]},
                  {"metric": {"source_workload": "config-server", "destination_service_name": "PassthroughCluster"}, "value": [1, "12"]}
                ]
              }
            }
            """;

    @Test
    void egressFoldsAttributedExternalHostAsRuntimeEdgeAndSkipsPassthrough() {
        DependencyGraph g = new DependencyGraph("petclinic");
        RuntimeGraphBuilder.mergeIstioEgress(g, PETCLINIC_EGRESS_TCP);

        DependencyGraph.Edge e = edge(g, "config-server", "github.com");
        assertNotNull(e);
        assertTrue(e.runtimeObserved);
        assertEquals("external", e.type);
        assertEquals(DependencyGraph.KIND_EXTERNAL, node(g, "github.com").kind);
        assertEquals(197, e.count);
        // the opaque passthrough / unknown series are not drawn as edges
        assertEquals(1, g.getEdges().size());
        assertNull(node(g, "unknown"));
        assertNull(node(g, "PassthroughCluster"));
    }

    @Test
    void egressUpgradesCodeOnlyExternalEdgeToRuntimeObserved() {
        // Start with the code-declared, dashed external edge (as the code extractor emits).
        DependencyGraph g = new DependencyGraph("petclinic");
        g.addNode("config-server", DependencyGraph.KIND_SERVICE);
        g.addNode("github.com", DependencyGraph.KIND_EXTERNAL);
        g.addEdge("config-server", "github.com", "external",
                DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: application.yml");
        assertFalse(edge(g, "config-server", "github.com").runtimeObserved);

        RuntimeGraphBuilder.mergeIstioEgress(g, PETCLINIC_EGRESS_TCP);

        DependencyGraph.Edge e = edge(g, "config-server", "github.com");
        assertTrue(e.runtimeObserved);                                   // dashed -> solid
        assertEquals(DependencyGraph.CONF_OBSERVED, e.confidence);       // observed outranks documented
        assertTrue(e.provenance.contains(DependencyGraph.PROV_CODE));
        assertTrue(e.provenance.contains(DependencyGraph.PROV_RUNTIME)); // confirmed by both
        assertEquals(1, g.getEdges().stream()
                .filter(x -> x.source.equals("config-server") && x.target.equals("github.com"))
                .count());                                              // merged, not duplicated
    }

    // ---- greenfield: a static-only graph with NO runtime layer ----

    /**
     * A Bank-of-Anthos-shaped fixture: a NESTED layout (src/&lt;group&gt;/&lt;service&gt;/…),
     * env-indirected call targets, and the two meta-sections the extractor now emits —
     * {@code service-root} (the inventory) and {@code env-address} (the k8s ConfigMap
     * env → host table). There is deliberately no runtime graph: this is the greenfield
     * case where the vocabulary can only come from the repo itself.
     */
    private static final String GREENFIELD_CODE = """
            {"repo":"GoogleCloudPlatform/bank-of-anthos","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"src/frontend","name":"frontend"},"file":"src/frontend/pom.xml","line":-1,"confidence":"High"},
              {"section":"service-root","fields":{"dir":"src/ledger/ledgerwriter","name":"ledgerwriter"},"file":"src/ledger/ledgerwriter/pom.xml","line":-1,"confidence":"High"},
              {"section":"service-root","fields":{"dir":"src/ledger/balancereader","name":"balancereader"},"file":"src/ledger/balancereader/pom.xml","line":-1,"confidence":"High"},
              {"section":"service-root","fields":{"dir":"src/accounts/contacts","name":"contacts"},"file":"src/accounts/contacts/pyproject.toml","line":-1,"confidence":"High"},
              {"section":"service-root","fields":{"dir":"src/accounts/accounts-db","name":"accounts-db"},"file":"src/accounts/accounts-db/Dockerfile","line":-1,"confidence":"High"},
              {"section":"env-address","fields":{"name":"TRANSACTIONS_API_ADDR","host":"ledgerwriter"},"file":"kubernetes-manifests/config.yaml","line":-1,"confidence":"High"},
              {"section":"env-address","fields":{"name":"BALANCES_API_ADDR","host":"balancereader"},"file":"kubernetes-manifests/config.yaml","line":-1,"confidence":"High"},
              {"section":"env-address","fields":{"name":"CONTACTS_API_ADDR","host":"contacts"},"file":"kubernetes-manifests/config.yaml","line":-1,"confidence":"High"},
              {"section":"config","fields":{"env":"TRANSACTIONS_API_ADDR"},"file":"src/frontend/frontend.py","line":663,"confidence":"High"},
              {"section":"config","fields":{"env":"CONTACTS_API_ADDR"},"file":"src/frontend/frontend.py","line":673,"confidence":"High"},
              {"section":"config","fields":{"env":"BANK_NAME"},"file":"src/frontend/frontend.py","line":161,"confidence":"High"},
              {"section":"url","fields":{"value":"http://${BALANCES_API_ADDR}/balances"},"file":"src/ledger/ledgerwriter/src/main/java/anthos/LedgerWriterController.java","line":86,"confidence":"Medium (property indirection)"},
              {"section":"http-server","fields":{"path":"/transactions"},"file":"src/ledger/ledgerwriter/src/main/java/anthos/LedgerWriterController.java","line":132,"confidence":"High"},
              {"section":"jpa","fields":{"marker":"Entity"},"file":"src/ledger/balancereader/src/main/java/anthos/Transaction.java","line":37,"confidence":"High"}
            ]}
            """;

    private static DependencyGraph greenfieldGraph() {
        DependencyGraph g = new DependencyGraph(""); // no runtime layer
        CodeGraphMerger.merge(g, GREENFIELD_CODE, "GoogleCloudPlatform/bank-of-anthos");
        return g;
    }

    @Test
    void greenfieldAttributesNestedLayoutToTheService() {
        DependencyGraph g = greenfieldGraph();
        // frontend/frontend.py reads TRANSACTIONS_API_ADDR -> ledgerwriter, and the
        // source is the nested service dir (src/frontend), NOT the top segment "src".
        DependencyGraph.Edge e = edge(g, "frontend", "ledgerwriter");
        assertNotNull(e, "env-indirected call target must resolve with no runtime data");
        assertEquals("sync-http", e.type);
        assertFalse(e.runtimeObserved);                 // greenfield: code-only, dashed
        assertTrue(e.provenance.contains(DependencyGraph.PROV_CODE));

        assertNotNull(edge(g, "frontend", "contacts"));
    }

    @Test
    void greenfieldResolvesEnvPlaceholderInUrlLiteral() {
        // http://${BALANCES_API_ADDR}/balances in ledgerwriter source -> balancereader.
        assertNotNull(edge(greenfieldGraph(), "ledgerwriter", "balancereader"));
    }

    @Test
    void greenfieldSeedsNodesFromInventoryAndTypesDb() {
        DependencyGraph g = greenfieldGraph();
        // Every scanned service directory becomes a node, even accounts-db (typed db).
        assertNotNull(node(g, "frontend"));
        assertNotNull(node(g, "balancereader"));
        assertEquals(DependencyGraph.KIND_DB, kindOf(g, "accounts-db"));
    }

    /**
     * A train-ticket-shaped fixture: the call host is a variable, but the URL path
     * names the callee by convention (/api/v1/&lt;svc&gt;service/…). Includes a
     * multi-word service (station-food) to check the letters-only key match.
     */
    private static final String PATH_ENCODED_CODE = """
            {"repo":"FudanSELab/train-ticket","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"ts-preserve-service","name":"ts-preserve-service"},"file":"ts-preserve-service/pom.xml","line":-1,"confidence":"High"},
              {"section":"service-root","fields":{"dir":"ts-order-service","name":"ts-order-service"},"file":"ts-order-service/pom.xml","line":-1,"confidence":"High"},
              {"section":"service-root","fields":{"dir":"ts-food-service","name":"ts-food-service"},"file":"ts-food-service/pom.xml","line":-1,"confidence":"High"},
              {"section":"service-root","fields":{"dir":"ts-station-food-service","name":"ts-station-food-service"},"file":"ts-station-food-service/pom.xml","line":-1,"confidence":"High"},
              {"section":"http-client","fields":{"method":"exchange","path":"/api/v1/orderservice/order"},"file":"ts-preserve-service/src/main/java/preserve/service/PreserveServiceImpl.java","line":396,"confidence":"Medium (path only; host comes from a variable)"},
              {"section":"http-client","fields":{"method":"exchange","path":"/api/v1/stationfoodservice/stationfoodstores"},"file":"ts-food-service/src/main/java/foodsearch/service/FoodServiceImpl.java","line":286,"confidence":"Medium (path only; host comes from a variable)"}
            ]}
            """;

    @Test
    void greenfieldResolvesPathEncodedCallTarget() {
        DependencyGraph g = new DependencyGraph("");
        CodeGraphMerger.merge(g, PATH_ENCODED_CODE, "FudanSELab/train-ticket");

        // /api/v1/orderservice/... names order-service; the source is the calling module.
        DependencyGraph.Edge e = edge(g, "ts-preserve-service", "ts-order-service");
        assertNotNull(e, "a path-encoded target must resolve when the host is a variable");
        assertEquals("sync-http", e.type);
        // A multi-word service resolves too: stationfoodservice -> ts-station-food-service.
        assertNotNull(edge(g, "ts-food-service", "ts-station-food-service"));
    }

    @Test
    void greenfieldDoesNotRenderMetaSectionsAsEdges() {
        DependencyGraph g = greenfieldGraph();
        // service-root / env-address are lookup tables, never arrows; a bare env read
        // (BANK_NAME) that names no host is a signal, not an edge either.
        assertTrue(g.getEdges().stream().noneMatch(e ->
                e.target.equals("accounts-db") && e.source.equals("frontend")));
        assertNull(edge(g, "frontend", "bank_name"));
        // Exactly the three real call edges: frontend->ledgerwriter, frontend->contacts,
        // ledgerwriter->balancereader.
        assertEquals(3, g.getEdges().size());
    }

    // ---- in-mesh TCP: the database edge Istio's HTTP metrics cannot carry ----

    /**
     * The TCP query's shape, covering the three cases that matter: a meshed database
     * (workload known), an unmeshed one (workload "unknown", Service name is the only
     * identity), an external host (owned by the egress merge), and a non-db TCP callee.
     */
    private static final String PETCLINIC_TCP = """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": [
                  {"metric": {"source_workload": "customers-service", "destination_workload": "customers-db", "destination_service_name": "customers-db"}, "value": [1, "10"]},
                  {"metric": {"source_workload": "vets-service", "destination_workload": "unknown", "destination_service_name": "vets-db.petclinic.svc.cluster.local:3306"}, "value": [1, "4"]},
                  {"metric": {"source_workload": "config-server", "destination_workload": "unknown", "destination_service_name": "github.com"}, "value": [1, "197"]},
                  {"metric": {"source_workload": "api-gateway", "destination_workload": "some-tcp-service", "destination_service_name": "some-tcp-service"}, "value": [1, "5"]},
                  {"metric": {"source_workload": "prometheus", "destination_workload": "customers-db", "destination_service_name": "customers-db"}, "value": [1, "99"]}
                ]
              }
            }
            """;

    @Test
    void tcpMergeUpgradesTheCodeDeclaredDbEdgeToRuntimeObserved() {
        DependencyGraph g = mergedGraph();
        DependencyGraph.Edge before = edge(g, "customers-service", "customers-db");
        assertFalse(before.runtimeObserved);   // code-declared only, until the mesh sees TCP

        RuntimeGraphBuilder.mergeIstioTcp(g, PETCLINIC_TCP);

        DependencyGraph.Edge after = edge(g, "customers-service", "customers-db");
        assertTrue(after.runtimeObserved, "a TCP connection is the db edge's runtime evidence");
        assertEquals("db", after.type, "merging must not relabel the existing db edge");
        assertEquals(DependencyGraph.CONF_OBSERVED, after.confidence);
        // Both provenances survive: the code declared it, the mesh confirmed it.
        assertTrue(after.provenance.contains(DependencyGraph.PROV_CODE));
        assertTrue(after.provenance.contains(DependencyGraph.PROV_RUNTIME));
    }

    @Test
    void tcpMergeIdentifiesAnUnmeshedDbByItsServiceName() {
        DependencyGraph g = mergedGraph();
        RuntimeGraphBuilder.mergeIstioTcp(g, PETCLINIC_TCP);

        // destination_workload was "unknown" (no sidecar on the db pod), so the FQDN:port
        // service name is all there is — it must reduce to the same node the code edge used.
        DependencyGraph.Edge e = edge(g, "vets-service", "vets-db");
        assertNotNull(e, "an unmeshed database must still be identified, by Service name");
        assertTrue(e.runtimeObserved);
        assertEquals("db", e.type);
    }

    @Test
    void tcpMergeLeavesExternalHostsToTheEgressMergeAndDropsInfra() {
        DependencyGraph g = mergedGraph();
        RuntimeGraphBuilder.mergeIstioTcp(g, PETCLINIC_TCP);

        // github.com is an external host: mergeIstioEgress owns it (it attributes by
        // ServiceEntry). Taking it here too would give one edge two provenances.
        assertNull(edge(g, "config-server", "github.com"));
        // Telemetry scraping the db is not a business dependency.
        assertNull(edge(g, "prometheus", "customers-db"));
        // A non-db TCP callee is still a real observed edge, just not labelled db.
        assertEquals("sync-tcp", edge(g, "api-gateway", "some-tcp-service").type);
    }

    @Test
    void tcpMergeIsANoOpOnUnavailableOrMalformedInput() {
        DependencyGraph g = mergedGraph();
        int before = g.getEdges().size();
        RuntimeGraphBuilder.mergeIstioTcp(g, "PROMETHEUS_UNAVAILABLE\nEndpoint: ...");
        RuntimeGraphBuilder.mergeIstioTcp(g, "<html>502</html>");
        RuntimeGraphBuilder.mergeIstioTcp(g, "");
        RuntimeGraphBuilder.mergeIstioTcp(g, null);
        assertEquals(before, g.getEdges().size());
    }

    @Test
    void dataLayerCoverageIsMeasuredSeparatelyFromTheBusinessRatio() {
        DependencyGraph g = mergedGraph();
        RuntimeGraphBuilder.mergeIstioTcp(g, PETCLINIC_TCP);
        // Deploy the databases too: only a deployed datastore can have a connection.
        K8sGraphBuilder.enrich(g, PETCLINIC_DEPLOYMENTS
                + "customers-db  2026-07-20T10:00:00Z  1  1  mysql:8.0\n"
                + "vets-db       2026-07-20T10:00:00Z  1  1  mysql:8.0\n");

        CoverageAnalyzer.Report report = CoverageAnalyzer.analyze(g);

        assertEquals(2, report.dbTotal);
        assertEquals(2, report.dbObserved);
        assertEquals(100, report.dbPercent());
        // The business ratio must not have absorbed the db edges — that number means
        // "of the drivable service→service surface, how much did traffic reach?".
        assertTrue(report.uncovered.stream().noneMatch(e -> e.contains("-db")));
        assertFalse(report.hasDbEdges() && report.total == 0);
    }

    @Test
    void anUndeployedDatastoreIsNotCountedAgainstDataLayerCoverage() {
        // petclinic's default: the db is declared in config but never deployed (in-memory
        // HSQLDB). There is no connection to observe, so it must not cap the ratio.
        DependencyGraph g = mergedGraph();
        K8sGraphBuilder.enrich(g, PETCLINIC_DEPLOYMENTS);   // no db rows
        for (DependencyGraph.Node n : g.getNodes()) {
            if (DependencyGraph.KIND_DB.equals(n.kind)) n.deployed = Boolean.FALSE;
        }

        CoverageAnalyzer.Report report = CoverageAnalyzer.analyze(g);
        assertEquals(0, report.dbTotal);
        assertFalse(report.hasDbEdges());
    }

    // ---- layering (the graph reads as tiers, not as a hairball) ----

    @Test
    void layersRunIngressThenServicesByDepthThenDataStores() {
        DependencyGraph g = mergedGraph();
        RuntimeGraphBuilder.mergeIstioTcp(g, PETCLINIC_TCP);
        GraphLayerAssigner.assign(g);

        // ingress -> api-gateway -> the services -> their databases
        assertEquals(0, node(g, "istio-ingressgateway").layer);
        assertEquals(1, node(g, "api-gateway").layer);
        assertEquals(2, node(g, "customers-service").layer);
        assertEquals(2, node(g, "vets-service").layer);
        // A datastore is always below the deepest service, never in the middle.
        int deepestService = g.getNodes().stream()
                .filter(n -> DependencyGraph.KIND_SERVICE.equals(n.kind))
                .mapToInt(n -> n.layer).max().orElse(0);
        assertTrue(node(g, "customers-db").layer > deepestService);
        assertTrue(node(g, "vets-db").layer > deepestService);
    }

    @Test
    void aServiceReachableBothDirectlyAndViaAChainSitsBelowTheChain() {
        // gw -> a -> b and gw -> b. Shortest path would put b beside a and draw the
        // a -> b arrow sideways; longest path puts it underneath, which is the point.
        DependencyGraph g = new DependencyGraph("ns");
        g.addNode("gw", DependencyGraph.KIND_GATEWAY);
        for (String s : new String[]{"a", "b"}) g.addNode(s, DependencyGraph.KIND_SERVICE);
        g.addEdge("gw", "a", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 1, "x");
        g.addEdge("gw", "b", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 1, "x");
        g.addEdge("a", "b", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 1, "x");

        GraphLayerAssigner.assign(g);

        assertEquals(0, node(g, "gw").layer);
        assertEquals(1, node(g, "a").layer);
        assertEquals(2, node(g, "b").layer);
    }

    @Test
    void aCycleDoesNotBreakLayeringAndItsMembersShareATier() {
        // Services calling each other back is normal (train-ticket does it). Within a
        // cycle there is no "before", so its members belong on the same tier.
        DependencyGraph g = new DependencyGraph("ns");
        g.addNode("gw", DependencyGraph.KIND_GATEWAY);
        for (String s : new String[]{"a", "b", "c"}) g.addNode(s, DependencyGraph.KIND_SERVICE);
        g.addEdge("gw", "a", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 1, "x");
        g.addEdge("a", "b", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 1, "x");
        g.addEdge("b", "c", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 1, "x");
        g.addEdge("c", "a", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 1, "x");

        GraphLayerAssigner.assign(g);

        assertEquals(node(g, "a").layer, node(g, "b").layer);
        assertEquals(node(g, "b").layer, node(g, "c").layer);
        assertTrue(node(g, "a").layer > node(g, "gw").layer);
    }

    @Test
    void aGraphThatIsEntirelyOneCycleStillGetsEveryNodeLayered() {
        DependencyGraph g = new DependencyGraph("ns");
        for (String s : new String[]{"a", "b"}) g.addNode(s, DependencyGraph.KIND_SERVICE);
        g.addEdge("a", "b", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "x");
        g.addEdge("b", "a", "sync-http", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "x");

        GraphLayerAssigner.assign(g);

        for (DependencyGraph.Node n : g.getNodes()) assertNotNull(n.layer);
    }

    @Test
    void greenfieldWithNoGatewayUsesTheUncalledServiceAsTheEntry() {
        // A static/greenfield graph has no ingress node at all — BoA's frontend is only
        // an entry because nothing calls it.
        DependencyGraph g = greenfieldGraph();
        GraphLayerAssigner.assign(g);

        assertEquals(0, node(g, "frontend").layer);
        assertTrue(node(g, "ledgerwriter").layer > 0);
        assertTrue(node(g, "balancereader").layer > node(g, "ledgerwriter").layer,
                "frontend -> ledgerwriter -> balancereader must read as three tiers");
    }

    @Test
    void layeredGraphEmitsTiersInBothEmitters() {
        DependencyGraph g = mergedGraph();
        RuntimeGraphBuilder.mergeIstioTcp(g, PETCLINIC_TCP);
        GraphLayerAssigner.assign(g);

        String dot = DotEmitter.emit(g);
        assertTrue(dot.contains("rankdir=TB"), "a layered graph reads top-down");
        assertTrue(dot.contains("rank=same"), "each tier is pinned to one rank");

        String mermaid = MermaidEmitter.emit(g);
        assertTrue(mermaid.startsWith("flowchart TB"));
        assertTrue(mermaid.contains("subgraph layer0 [\"ingress\"]"));
        assertTrue(mermaid.contains("[\"data stores\"]"));
    }

    @Test
    void anUnlayeredGraphKeepsThePreviousFreeLayout() {
        // An old checkpoint (or any caller that skips the assigner) must render exactly
        // as it used to, rather than half-tiered.
        DependencyGraph g = mergedGraph();

        assertTrue(DotEmitter.emit(g).contains("rankdir=LR"));
        assertFalse(DotEmitter.emit(g).contains("rank=same"));
        assertTrue(MermaidEmitter.emit(g).startsWith("flowchart LR"));
        assertFalse(MermaidEmitter.emit(g).contains("subgraph"));
    }

    // ---- node labels stay narrow (a wide label stretches its whole tier) ----

    @Test
    void theImageDigestIsNotRenderedIntoTheLabel() {
        // A real Bank of Anthos run: the digest is 71 characters that say nothing the
        // tag does not, and it stretched the whole graph to 5000px wide.
        DependencyGraph g = new DependencyGraph("boa");
        g.addNode("frontend", DependencyGraph.KIND_SERVICE);
        g.addNode("userservice", DependencyGraph.KIND_SERVICE);
        g.addEdge("frontend", "userservice", "sync-http", DependencyGraph.PROV_RUNTIME,
                DependencyGraph.CONF_OBSERVED, true, 1, "x");
        DependencyGraph.Node n = node(g, "frontend");
        n.deployed = Boolean.TRUE;
        n.image = "us-central1-docker.pkg.dev/bank-of-anthos-ci/bank-of-anthos/frontend"
                + ":v0.6.10@sha256:076294ce717309f711743fa3b72a9809c7f156edf1c4fa58505fd9f436d65345";
        n.replicas = "1/1";
        n.deployedAt = "2026-08-12T09:00:00Z";

        String dot = DotEmitter.emit(g);
        String mermaid = MermaidEmitter.emit(g);

        assertFalse(dot.contains("sha256"), "the digest must not reach the label");
        assertFalse(mermaid.contains("sha256"));
        assertFalse(dot.contains("us-central1-docker.pkg.dev"), "nor the registry prefix");
        // The repo name repeats the node name, so only the tag is worth keeping.
        assertTrue(dot.contains("v0.6.10"));
        assertTrue(dot.contains("2026-08-12"));
        assertFalse(dot.contains("frontend:v0.6.10"), "the name is already on the line above");
    }

    @Test
    void animageWhoseNameDiffersFromTheWorkloadIsKeptInFull() {
        // petclinic: workload api-gateway runs spring-petclinic-api-gateway — that
        // difference is real information, unlike a repeated name.
        DependencyGraph g = new DependencyGraph("petclinic");
        g.addNode("api-gateway", DependencyGraph.KIND_SERVICE);
        g.addNode("customers-service", DependencyGraph.KIND_SERVICE);
        g.addEdge("api-gateway", "customers-service", "sync-http", DependencyGraph.PROV_RUNTIME,
                DependencyGraph.CONF_OBSERVED, true, 1, "x");
        DependencyGraph.Node n = node(g, "api-gateway");
        n.deployed = Boolean.TRUE;
        n.image = "petclinic-k8s/spring-petclinic-api-gateway:latest";

        assertTrue(DotEmitter.emit(g).contains("spring-petclinic-api-gateway:latest"));
    }

    @Test
    void aDatastoreClientLibraryIsNotAServiceNode() {
        // Bank of Anthos surfaced `lettuce` (the Redis client) as an edgeless node.
        DependencyGraph g = new DependencyGraph("boa");
        g.addNode("lettuce", DependencyGraph.KIND_SERVICE);
        g.addNode("accounts-db", DependencyGraph.KIND_DB);
        g.addNode("userservice", DependencyGraph.KIND_SERVICE);
        g.addEdge("userservice", "accounts-db", "db", DependencyGraph.PROV_CODE,
                DependencyGraph.CONF_DOCUMENTED, false, 0, "x");

        GraphNormalizer.normalize(g);

        assertNull(node(g, "lettuce"));
        // The database itself must survive — it is named like a library but is not one.
        assertNotNull(node(g, "accounts-db"));
    }

    @Test
    void aDatabaseWorkloadNamedAfterItsEngineIsNeverRemoved() {
        // petclinic's database Deployment is literally called `mysql`, and a
        // StatefulSet-backed one is not marked deployed=TRUE at all — so an
        // engine-name blocklist would delete real databases.
        DependencyGraph g = new DependencyGraph("petclinic");
        g.addNode("mysql", DependencyGraph.KIND_DB);
        g.addNode("postgres", DependencyGraph.KIND_DB);
        g.addNode("redis", DependencyGraph.KIND_DB);

        GraphNormalizer.normalize(g);

        assertNotNull(node(g, "mysql"));
        assertNotNull(node(g, "postgres"));
        assertNotNull(node(g, "redis"));
    }

    @Test
    void anEdgeTheDocsMerelyMentionDoesNotDiluteCoverage() {
        // A real Bank of Anthos run: DeepWiki invented three userservice -> ledger
        // edges and four edges to a generic "postgresql" duplicating the real ones.
        // Coverage fell 7/7 -> 7/10 with nothing about the system having changed.
        DependencyGraph g = new DependencyGraph("boa");
        for (String s : new String[]{"frontend", "userservice", "ledgerwriter"}) {
            g.addNode(s, DependencyGraph.KIND_SERVICE);
            node(g, s).deployed = Boolean.TRUE;
        }
        g.addNode("ledger-db", DependencyGraph.KIND_DB);
        g.addNode("postgresql", DependencyGraph.KIND_DB);

        // Real, observed.
        g.addEdge("frontend", "userservice", "sync-http", DependencyGraph.PROV_RUNTIME,
                DependencyGraph.CONF_OBSERVED, true, 10, "x");
        g.addEdge("ledgerwriter", "ledger-db", "db", DependencyGraph.PROV_RUNTIME,
                DependencyGraph.CONF_OBSERVED, true, 99, "x");
        // Merely mentioned by the docs — dotted in the graph, no usage evidence.
        g.addEdge("userservice", "ledgerwriter", "sync-http", DependencyGraph.PROV_DOC,
                DependencyGraph.CONF_INFERRED, false, 0, "doc");
        g.addEdge("ledgerwriter", "postgresql", "db", DependencyGraph.PROV_DOC,
                DependencyGraph.CONF_INFERRED, false, 0, "doc");

        CoverageAnalyzer.Report r = CoverageAnalyzer.analyze(g);

        assertEquals(1, r.total, "the invented edge must not enter the denominator");
        assertEquals(1, r.observed);
        assertEquals(100, r.percent());
        assertEquals(1, r.dbTotal, "nor the duplicate generic datastore");
        assertEquals(100, r.dbPercent());
    }

    @Test
    void aDeclaredEdgeWithUsageEvidenceStillCounts() {
        // documented (dashed) = code/doc proved it is really used, just not yet driven.
        // That IS a coverage gap and must stay in the denominator.
        DependencyGraph g = new DependencyGraph("ns");
        for (String s : new String[]{"a", "b"}) {
            g.addNode(s, DependencyGraph.KIND_SERVICE);
            node(g, s).deployed = Boolean.TRUE;
        }
        g.addEdge("a", "b", "sync-http", DependencyGraph.PROV_CODE,
                DependencyGraph.CONF_DOCUMENTED, false, 0, "feign client");

        CoverageAnalyzer.Report r = CoverageAnalyzer.analyze(g);

        assertEquals(1, r.total);
        assertEquals(0, r.observed);
        assertTrue(r.uncovered.contains("a -> b"));
    }

    private static DependencyGraph.Node node(DependencyGraph g, String id) {
        return g.getNodes().stream().filter(n -> n.id.equals(id)).findFirst().orElse(null);
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
