package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CoverageAnalyzer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DocGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DotEmitter;
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
                    {"source":"api-gateway","target":"admin-server","dependency_type":"application","configured":"yes","evidence_reference":"docs/arch.md"}
                  ],
                  "infrastructure_dependencies": [
                    {"source_component":"vets-service","target":"vets-cache","dependency_type":"cache","configured":"no","evidence_reference":"wiki"}
                  ]
                }
                """;
        DocGraphMerger.merge(g, notes);
        // configured application dependency -> documented sync edge, doc provenance
        DependencyGraph.Edge sync = edge(g, "api-gateway", "admin-server");
        assertNotNull(sync);
        assertTrue(sync.provenance.contains(DependencyGraph.PROV_DOC));
        assertEquals(DependencyGraph.CONF_DOCUMENTED, sync.confidence);
        assertFalse(sync.runtimeObserved);
        // documented-only cache -> db-typed, declared-only (weakest) tier
        DependencyGraph.Edge cache = edge(g, "vets-service", "vets-cache");
        assertNotNull(cache);
        assertEquals("db", cache.type);
        assertEquals(DependencyGraph.CONF_INFERRED, cache.confidence);
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
    void coverageCountsServiceSyncEdgesOnly() {
        CoverageAnalyzer.Report r = CoverageAnalyzer.analyze(mergedGraph());
        // 7 observed service/gateway sync edges + 1 dashed (visits->config-server);
        // the customers-db / vets-db edges are db-type and excluded.
        assertEquals(8, r.total);
        assertEquals(7, r.observed);
        assertEquals(88, r.percent());
        assertTrue(r.uncovered.contains("visits-service -> config-server"));
        assertFalse(r.uncovered.stream().anyMatch(s -> s.contains("customers-db")));
    }

    @Test
    void coverageIsEmptyWhenNoServiceEdges() {
        CoverageAnalyzer.Report r = CoverageAnalyzer.analyze(new DependencyGraph("ns"));
        assertFalse(r.hasEdges());
        assertEquals(0, r.percent());
        assertTrue(r.uncovered.isEmpty());
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
