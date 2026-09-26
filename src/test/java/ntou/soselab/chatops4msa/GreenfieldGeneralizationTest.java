package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merge rules that six projects the tool had never seen forced out of it on
 * 2026-09-22 (ewolff/microservice-kubernetes, SelimHorri/ecommerce, sqshq/piggymetrics,
 * DescartesResearch/TeaStore, GoogleCloudPlatform/microservices-demo, instana/robot-shop).
 * Each fixture is the smallest ledger that reproduces one of the shapes those repos
 * have and petclinic / Bank of Anthos / train-ticket do not. All greenfield: no
 * runtime graph, the vocabulary comes from the repo alone.
 */
public class GreenfieldGeneralizationTest {

    private static DependencyGraph merge(String ledger) {
        DependencyGraph g = new DependencyGraph("");
        CodeGraphMerger.merge(g, ledger, "owner/repo");
        return g;
    }

    private static List<CodeGraphMerger.Unresolved> residue(String ledger) {
        return CodeGraphMerger.merge(new DependencyGraph(""), ledger, "owner/repo");
    }

    private static DependencyGraph.Node node(DependencyGraph g, String id) {
        return g.getNodes().stream().filter(n -> n.id.equals(id)).findFirst().orElse(null);
    }

    private static DependencyGraph.Edge edge(DependencyGraph g, String s, String t) {
        return g.getEdges().stream().filter(e -> e.source.equals(s) && e.target.equals(t)).findFirst().orElse(null);
    }

    // ---- the manifests' workload names are the vocabulary (ewolff, TeaStore, Online Boutique) ----

    private static final String WORKLOAD_VOCABULARY = """
            {"repo":"r","failed":false,"edges":[
              {"section":"k8s-workload","fields":{"name":"catalog","kind":"Deployment"},"file":"microservices.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"order","kind":"Deployment"},"file":"microservices.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"teastore-webui","kind":"Deployment"},"file":"k8s/teastore.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"teastore-db","kind":"StatefulSet"},"file":"k8s/teastore.yaml","line":-1},
              {"section":"service-root","fields":{"dir":"demo/demo-catalog","name":"microservice-kubernetes-demo-catalog"},"file":"demo/demo-catalog/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"demo/demo-order","name":"microservice-kubernetes-demo-order"},"file":"demo/demo-order/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"services/tools.descartes.teastore.webui","name":"tools-descartes-teastore-webui"},"file":"services/tools.descartes.teastore.webui/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"utilities/registryclient","name":"tools-descartes-teastore-registryclient"},"file":"utilities/registryclient/pom.xml","line":-1},
              {"section":"url","fields":{"value":"http://%s:%s/catalog/"},"file":"demo/demo-order/src/main/java/CatalogClient.java","line":66}
            ]}
            """;

    @Test
    void moduleDirectoriesAlignToTheWorkloadsTheManifestsDeclare() {
        DependencyGraph g = merge(WORKLOAD_VOCABULARY);
        // The Maven module microservice-kubernetes-demo-catalog deploys as "catalog".
        assertNotNull(node(g, "catalog"));
        assertNull(node(g, "microservice-kubernetes-demo-catalog"));
        // A reverse-domain module name aligns by suffix too, and the StatefulSet is a node.
        assertNotNull(node(g, "teastore-webui"));
        assertEquals(DependencyGraph.KIND_DB, node(g, "teastore-db").kind);
        assertNull(node(g, "tools-descartes-teastore-webui"));
    }

    @Test
    void aModuleTheManifestsDoNotDeployIsNotANodeUntilAnEdgeTouchesIt() {
        // registryclient is a library: the manifests never run it, nothing points at it.
        assertNull(node(merge(WORKLOAD_VOCABULARY), "tools-descartes-teastore-registryclient"));
    }

    @Test
    void aFormatStringUrlResolvesByItsPath() {
        // String.format("http://%s:%s/catalog/", host, port): the host is a variable, the
        // path names the callee, and the source is the module that deploys as "order".
        DependencyGraph.Edge e = edge(merge(WORKLOAD_VOCABULARY), "order", "catalog");
        assertNotNull(e);
        assertEquals("sync-http", e.type);
    }

    // ---- loopback, Feign wiring, compose targets, config values (ecommerce, piggymetrics, Robot Shop) ----

    private static final String CONFIG_SHAPES = """
            {"repo":"r","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"gateway","name":"gateway"},"file":"gateway/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"account-service","name":"account-service"},"file":"account-service/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"statistics-service","name":"statistics-service"},"file":"statistics-service/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"proxy-client","name":"proxy-client"},"file":"proxy-client/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"user-service","name":"user-service"},"file":"user-service/pom.xml","line":-1},
              {"section":"url","fields":{"value":"http://localhost:8761/eureka/"},"file":"gateway/src/main/resources/application-dev.yml","line":-1},
              {"section":"feign","fields":{"attr":"name","value":"USER-SERVICE"},"file":"proxy-client/src/main/java/UserClientService.java","line":19},
              {"section":"feign","fields":{"attr":"contextId","value":"userClientService"},"file":"proxy-client/src/main/java/UserClientService.java","line":19},
              {"section":"feign","fields":{"attr":"path","value":"/user-service/api/users"},"file":"proxy-client/src/main/java/UserClientService.java","line":19},
              {"section":"feign","fields":{"attr":"name","value":"rates-client"},"file":"statistics-service/src/main/java/ExchangeRatesClient.java","line":10},
              {"section":"feign","fields":{"attr":"url","value":"${rates.url}"},"file":"statistics-service/src/main/java/ExchangeRatesClient.java","line":10},
              {"section":"config","fields":{"key":"rates.url","value":"https://api.exchangeratesapi.io"},"file":"statistics-service/src/main/resources/application.yml","line":-1},
              {"section":"config","fields":{"key":"spring.data.mongodb.host","value":"account-mongodb"},"file":"account-service/src/main/resources/application.yml","line":-1},
              {"section":"config","fields":{"key":"spring.data.mongodb.username","value":"user"},"file":"account-service/src/main/resources/application.yml","line":-1},
              {"section":"config","fields":{"key":"zuul.routes.account-service.serviceId","value":"account-service"},"file":"gateway/src/main/resources/application.yml","line":-1},
              {"section":"compose-dependency","fields":{"source_service":"account-service","target_service":"rabbitmq"},"file":"docker-compose.yml","line":-1},
              {"section":"url","fields":{"value":"http://{user}:8080/check/{id}"},"file":"proxy-client/src/main/java/Pay.java","line":64},
              {"section":"url","fields":{"value":"http://${USER_SERVICE_HOST}:8080/"},"file":"gateway/nginx.conf","line":65}
            ]}
            """;

    @Test
    void loopbackIsNeverANode() {
        DependencyGraph g = merge(CONFIG_SHAPES);
        assertNull(node(g, "localhost"));
        assertTrue(g.getEdges().stream().noneMatch(e -> e.target.equals("localhost")));
    }

    @Test
    void feignContextIdAndPathAreWiringNotTargets() {
        DependencyGraph g = merge(CONFIG_SHAPES);
        assertNotNull(edge(g, "proxy-client", "user-service"));
        assertNull(node(g, "userclientservice"));
        assertNull(node(g, "user-service-api-users"));
    }

    @Test
    void aFeignClientWithAUrlTakesItsTargetFromTheUrlNotItsBeanName() {
        DependencyGraph g = merge(CONFIG_SHAPES);
        // ${rates.url} is filled from the property table and is an external host.
        DependencyGraph.Edge e = edge(g, "statistics-service", "api.exchangeratesapi.io");
        assertNotNull(e);
        assertEquals("external", e.type);
        assertNull(node(g, "rates-client"));
    }

    @Test
    void aBareHostUnderAHostKeyIsATarget() {
        DependencyGraph g = merge(CONFIG_SHAPES);
        DependencyGraph.Edge db = edge(g, "account-service", "account-mongodb");
        assertNotNull(db);
        assertEquals("db", db.type);
        // A registry id under a gateway route is a service edge.
        assertNotNull(edge(g, "gateway", "account-service"));
        // A username is not.
        assertNull(node(g, "user"));
    }

    @Test
    void aComposeDependencyTargetBecomesANode() {
        DependencyGraph g = merge(CONFIG_SHAPES);
        assertEquals(DependencyGraph.KIND_QUEUE, node(g, "rabbitmq").kind);
        assertNotNull(edge(g, "account-service", "rabbitmq"));
    }

    @Test
    void aFormatHostNamedAfterTheServiceResolvesToIt() {
        assertNotNull(edge(merge(CONFIG_SHAPES), "proxy-client", "user-service"));
    }

    @Test
    void aVariableNamedAfterTheServiceResolvesAsInferred() {
        DependencyGraph.Edge e = edge(merge(CONFIG_SHAPES), "gateway", "user-service");
        assertNotNull(e, "${USER_SERVICE_HOST} names user-service by its variable alone");
        assertEquals(DependencyGraph.CONF_INFERRED, e.confidence);
    }

    @Test
    void aConfigValueThatIsNoAddressIsNotResidue() {
        // spring.data.mongodb.username: user must not reach the LLM as an unresolved edge.
        assertTrue(residue(CONFIG_SHAPES).stream().noneMatch(u -> "config".equals(u.section)));
    }

    // ---- a Spring Cloud Config repository's layout (piggymetrics) ----

    private static final String CONFIG_REPO = """
            {"repo":"r","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"config","name":"config"},"file":"config/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"registry","name":"registry"},"file":"registry/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"account-service","name":"account-service"},"file":"account-service/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"notification-service","name":"notification-service"},"file":"notification-service/pom.xml","line":-1},
              {"section":"config","fields":{"key":"spring.cloud.config.uri","value":"http://config:8888"},"file":"account-service/src/main/resources/bootstrap.yml","line":-1},
              {"section":"config","fields":{"key":"spring.cloud.config.uri","value":"http://config:8888"},"file":"notification-service/src/main/resources/bootstrap.yml","line":-1},
              {"section":"config","fields":{"key":"spring.data.mongodb.host","value":"account-mongodb"},"file":"config/src/main/resources/shared/account-service.yml","line":-1},
              {"section":"config","fields":{"key":"eureka.client.serviceUrl.defaultZone","value":"http://registry:8761/eureka/"},"file":"config/src/main/resources/shared/application.yml","line":-1}
            ]}
            """;

    @Test
    void aConfigFileNamedAfterAServiceConfiguresThatService() {
        DependencyGraph g = merge(CONFIG_REPO);
        assertNotNull(edge(g, "account-service", "account-mongodb"));
        assertNull(edge(g, "config", "account-mongodb"), "the config server does not use the datasource it serves");
    }

    @Test
    void theSharedConfigFileFansOutToEveryConfigClient() {
        DependencyGraph g = merge(CONFIG_REPO);
        assertNotNull(edge(g, "account-service", "registry"));
        assertNotNull(edge(g, "notification-service", "registry"));
        assertNull(edge(g, "config", "registry"));
        assertNotNull(edge(g, "account-service", "config"), "bootstrap's config uri is the client -> config edge");
    }

    // ---- @Value defaults and unreferenced constants (ewolff, ecommerce) ----

    private static final String VALUE_DEFAULTS = """
            {"repo":"r","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"order","name":"order"},"file":"order/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"catalog","name":"catalog"},"file":"catalog/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"customer","name":"customer"},"file":"customer/pom.xml","line":-1},
              {"section":"config","fields":{"property":"${catalog.service.host:catalog}"},"file":"order/src/main/java/CatalogClient.java","line":36},
              {"section":"config","fields":{"property":"${server.port:8080}"},"file":"order/src/main/java/App.java","line":10},
              {"section":"url","fields":{"value":"http://customer:8080/customer/","unreferenced":"true"},"file":"order/src/main/java/AppConstant.java","line":20},
              {"section":"url-constant","fields":{"name":"CUSTOMER_URL","value":"http://customer:8080/customer/"},"file":"order/src/main/java/AppConstant.java","line":20}
            ]}
            """;

    @Test
    void aValueDefaultNamesTheHost() {
        DependencyGraph g = merge(VALUE_DEFAULTS);
        assertNotNull(edge(g, "order", "catalog"));
        assertNull(node(g, "8080"));
    }

    @Test
    void anUnreferencedUrlConstantDrawsNoEdgeAndLeavesNoResidue() {
        DependencyGraph g = merge(VALUE_DEFAULTS);
        assertNull(edge(g, "order", "customer"));
        assertTrue(residue(VALUE_DEFAULTS).isEmpty());
    }
}
