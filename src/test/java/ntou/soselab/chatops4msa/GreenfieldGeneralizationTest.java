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

    // ---- 2026-10-23 rules 15–24: ecommerce, TeaStore, robot-shop, piggymetrics and a private Istio polyrepo ----

    /** Rule 15: a loopback address is read back to the one module that owns its port. */
    private static final String LOOPBACK_PORT = """
            {"repo":"r","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"gateway","name":"gateway"},"file":"gateway/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"cloud-config","name":"cloud-config"},"file":"cloud-config/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"a","name":"a"},"file":"a/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"b","name":"b"},"file":"b/pom.xml","line":-1},
              {"section":"config","fields":{"key":"server.port","value":"9296"},"file":"cloud-config/src/main/resources/application.yml","line":-1},
              {"section":"config","fields":{"key":"server.port","value":"8080"},"file":"a/src/main/resources/application.yml","line":-1},
              {"section":"config","fields":{"key":"server.port","value":"8080"},"file":"b/src/main/resources/application.yml","line":-1},
              {"section":"config","fields":{"key":"spring.config.import","value":"${SPRING_CONFIG_IMPORT:optional:configserver:http://localhost:9296}"},"file":"gateway/src/main/resources/application.yml","line":-1},
              {"section":"url","fields":{"value":"http://localhost:8080/health"},"file":"gateway/src/main/java/Health.java","line":9}
            ]}
            """;

    @Test
    void aLoopbackAddressResolvesToTheOneModuleOwningThePort() {
        DependencyGraph g = merge(LOOPBACK_PORT);
        DependencyGraph.Edge e = edge(g, "gateway", "cloud-config");
        assertNotNull(e, "localhost:9296 is cloud-config, the only module with server.port 9296");
        assertEquals(DependencyGraph.CONF_INFERRED, e.confidence);
        // 8080 is owned by two modules: nothing is drawn, and localhost is still never a node.
        assertNull(edge(g, "gateway", "a"));
        assertNull(edge(g, "gateway", "b"));
        assertNull(node(g, "localhost"));
    }

    /** Rule 16: the Eureka client starter with no defaultZone registers at the one Eureka server. */
    private static final String IMPLICIT_EUREKA = """
            {"repo":"r","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"registry","name":"registry"},"file":"registry/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"gateway","name":"gateway"},"file":"gateway/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"account","name":"account"},"file":"account/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"plain","name":"plain"},"file":"plain/pom.xml","line":-1},
              {"section":"module-deps","fields":{"dir":"registry","artifacts":"spring-cloud-starter-netflix-eureka-server","manifest":"pom.xml"},"file":"registry","line":-1},
              {"section":"module-deps","fields":{"dir":"gateway","artifacts":"spring-cloud-starter-netflix-eureka-client,spring-cloud-starter-gateway","manifest":"pom.xml"},"file":"gateway","line":-1},
              {"section":"module-deps","fields":{"dir":"account","artifacts":"spring-cloud-starter-netflix-eureka-client","manifest":"pom.xml"},"file":"account","line":-1},
              {"section":"module-deps","fields":{"dir":"plain","artifacts":"spring-boot-starter-web","manifest":"pom.xml"},"file":"plain","line":-1},
              {"section":"config","fields":{"key":"eureka.client.serviceUrl.defaultZone","value":"http://registry:8761/eureka/"},"file":"account/src/main/resources/application.yml","line":-1}
            ]}
            """;

    @Test
    void aEurekaClientWithoutADefaultZoneRegistersAtTheOnlyEurekaServer() {
        DependencyGraph g = merge(IMPLICIT_EUREKA);
        DependencyGraph.Edge implicit = edge(g, "gateway", "registry");
        assertNotNull(implicit, "gateway has the client starter and no eureka.client.* setting");
        assertEquals(DependencyGraph.CONF_INFERRED, implicit.confidence);
        // account states its zone: the stated edge is documented, not the implicit guess.
        assertEquals(DependencyGraph.CONF_DOCUMENTED, edge(g, "account", "registry").confidence);
        // No Eureka starter at all: no edge.
        assertNull(edge(g, "plain", "registry"));
    }

    /** Rule 17: an enum constant whose value names a module, used as a call argument, is the callee. */
    private static final String NAMED_CONSTANTS = """
            {"repo":"r","failed":false,"edges":[
              {"section":"k8s-workload","fields":{"name":"teastore-webui","kind":"Deployment"},"file":"k8s/teastore.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"teastore-persistence","kind":"Deployment"},"file":"k8s/teastore.yaml","line":-1},
              {"section":"service-root","fields":{"dir":"services/tools.descartes.teastore.webui","name":"tools-descartes-teastore-webui"},"file":"services/tools.descartes.teastore.webui/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"services/tools.descartes.teastore.persistence","name":"tools-descartes-teastore-persistence"},"file":"services/tools.descartes.teastore.persistence/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"utilities/registryclient","name":"tools-descartes-teastore-registryclient"},"file":"utilities/registryclient/pom.xml","line":-1},
              {"section":"name-constant","fields":{"type":"Service","name":"PERSISTENCE","value":"tools.descartes.teastore.persistence"},"file":"utilities/registryclient/src/main/java/Service.java","line":25},
              {"section":"name-constant","fields":{"type":"Service","name":"WEBUI","value":"tools.descartes.teastore.webui"},"file":"utilities/registryclient/src/main/java/Service.java","line":37},
              {"section":"name-constant","fields":{"type":"Currency","name":"USD","value":"USD"},"file":"services/tools.descartes.teastore.webui/src/main/java/Currency.java","line":5},
              {"section":"name-ref","fields":{"type":"Service","name":"PERSISTENCE"},"file":"services/tools.descartes.teastore.webui/src/main/java/DataBaseActionServlet.java","line":67},
              {"section":"name-ref","fields":{"type":"Service","name":"WEBUI"},"file":"services/tools.descartes.teastore.webui/src/main/java/Status.java","line":12},
              {"section":"name-ref","fields":{"type":"Currency","name":"USD"},"file":"services/tools.descartes.teastore.webui/src/main/java/Price.java","line":20},
              {"section":"name-ref","fields":{"type":"Service","name":"PERSISTENCE"},"file":"utilities/registryclient/src/main/java/LoadBalancedStoreOperations.java","line":56}
            ]}
            """;

    @Test
    void anEnumConstantNamingAModuleIsTheCalleeAtItsCallSites() {
        DependencyGraph g = merge(NAMED_CONSTANTS);
        DependencyGraph.Edge e = edge(g, "teastore-webui", "teastore-persistence");
        assertNotNull(e, "Service.PERSISTENCE = the persistence module, passed at a call site in webui");
        assertEquals(DependencyGraph.CONF_DOCUMENTED, e.confidence);
        // Self-reference, a constant that names no module, and a reference inside the shared
        // client library (not a deployable module) draw nothing.
        assertEquals(1, g.getEdges().size(), "exactly one edge: " + g.getEdges());
        assertNull(node(g, "usd"));
        assertNull(node(g, "tools-descartes-teastore-registryclient"));
    }

    /** Rule 18: String.format("http://%s/…", getenv("X", "default")) fills %s from the module's env or the default. */
    private static final String FORMAT_GETENV = """
            {"repo":"r","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"shipping","name":"shipping"},"file":"shipping/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"cart","name":"cart"},"file":"cart/package.json","line":-1},
              {"section":"service-root","fields":{"dir":"payment","name":"payment"},"file":"payment/requirements.txt","line":-1},
              {"section":"service-root","fields":{"dir":"user","name":"user"},"file":"user/package.json","line":-1},
              {"section":"env-address","fields":{"name":"CART_ENDPOINT","host":"cart","workload":"dockerfile"},"file":"shipping/Dockerfile","line":-1},
              {"section":"url","fields":{"value":"http://%s/shipping/","env":"CART_ENDPOINT","envdefault":"nowhere"},"file":"shipping/src/main/java/Controller.java","line":24},
              {"section":"url","fields":{"value":"http://%s/check/","env":"USER_ENDPOINT","envdefault":"user"},"file":"payment/src/main/java/Pay.java","line":30}
            ]}
            """;

    @Test
    void aFormatStringHostComesFromTheModulesEnvThenTheGetenvDefault() {
        DependencyGraph g = merge(FORMAT_GETENV);
        assertNotNull(edge(g, "shipping", "cart"), "the shipping image's ENV CART_ENDPOINT=cart fills %s");
        assertNull(node(g, "nowhere"), "the default loses to the declared env");
        assertNotNull(edge(g, "payment", "user"), "no env declared: the getenv default names the host");
    }

    /** Rule 19: a versioned Deployment is a version of the Service it sits beside. */
    private static final String VERSIONED_WORKLOADS = """
            {"repo":"r","failed":false,"edges":[
              {"section":"k8s-service","fields":{"name":"core-service"},"file":"acme-core-service/k8s/core-service.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"core-service-v1","kind":"Deployment"},"file":"acme-core-service/k8s/core-service.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"reviews-v1","kind":"Deployment"},"file":"acme-core-service/k8s/reviews.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"reviews-v2","kind":"Deployment"},"file":"acme-core-service/k8s/reviews.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"legacy-v1","kind":"Deployment"},"file":"acme-core-service/k8s/legacy.yaml","line":-1},
              {"section":"service-root","fields":{"dir":"acme-core-service","name":"newknowledge-core-service"},"file":"acme-core-service/app/requirements.txt","line":-1}
            ]}
            """;

    @Test
    void aVersionedDeploymentIsNamedAfterItsService() {
        DependencyGraph g = merge(VERSIONED_WORKLOADS);
        assertNotNull(node(g, "core-service"), "Deployment core-service-v1 beside Service core-service");
        assertNull(node(g, "core-service-v1"));
        assertNull(node(g, "newknowledge-core-service"), "the module aligns to the Service name");
        assertNotNull(node(g, "reviews"), "reviews-v1 and reviews-v2 differ only by version");
        assertNotNull(node(g, "legacy-v1"), "a lone -v1 with no Service is kept as is");
    }

    /** Rule 20: an env var is resolved in the reading module's own scope, not repo-wide. */
    private static final String SCOPED_ENV = """
            {"repo":"r","failed":false,"edges":[
              {"section":"k8s-service","fields":{"name":"alpha"},"file":"alpha/k8s/a.yaml","line":-1},
              {"section":"k8s-service","fields":{"name":"beta"},"file":"beta/k8s/b.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"alpha-v1","kind":"Deployment"},"file":"alpha/k8s/a.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"alpha-mongo","kind":"StatefulSet"},"file":"alpha/k8s/a.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"beta-v1","kind":"Deployment"},"file":"beta/k8s/b.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"beta-mongo","kind":"StatefulSet"},"file":"beta/k8s/b.yaml","line":-1},
              {"section":"service-root","fields":{"dir":"alpha","name":"alpha"},"file":"alpha/requirements.txt","line":-1},
              {"section":"service-root","fields":{"dir":"beta","name":"beta"},"file":"beta/requirements.txt","line":-1},
              {"section":"env-address","fields":{"name":"MONGO_CLIENT_HOST","host":"alpha-mongo","workload":"alpha-v1"},"file":"alpha/k8s/a.yaml","line":-1},
              {"section":"env-address","fields":{"name":"MONGO_CLIENT_HOST","host":"beta-mongo","workload":"beta-v1"},"file":"beta/k8s/b.yaml","line":-1},
              {"section":"config","fields":{"env":"MONGO_CLIENT_HOST"},"file":"alpha/db.py","line":9},
              {"section":"config","fields":{"env":"MONGO_CLIENT_HOST"},"file":"beta/db.py","line":9}
            ]}
            """;

    @Test
    void anEnvVarIsResolvedInTheReadingWorkloadsOwnScope() {
        DependencyGraph g = merge(SCOPED_ENV);
        assertNotNull(edge(g, "alpha", "alpha-mongo"));
        assertNotNull(edge(g, "beta", "beta-mongo"));
        assertNull(edge(g, "beta", "alpha-mongo"), "the first declaration repo-wide is not beta's value");
        assertNull(edge(g, "alpha", "beta-mongo"));
    }

    /** Rule 21: a library module's calls belong to the modules that import the very file. */
    private static final String LIBRARY_IMPORTERS = """
            {"repo":"r","failed":false,"edges":[
              {"section":"k8s-service","fields":{"name":"crawler"},"file":"crawler/k8s/c.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"crawler-v1","kind":"Deployment"},"file":"crawler/k8s/c.yaml","line":-1},
              {"section":"k8s-service","fields":{"name":"mailer"},"file":"mailer/k8s/m.yaml","line":-1},
              {"section":"k8s-workload","fields":{"name":"mailer-v1","kind":"Deployment"},"file":"mailer/k8s/m.yaml","line":-1},
              {"section":"service-root","fields":{"dir":"crawler","name":"crawler"},"file":"crawler/app/requirements.txt","line":-1},
              {"section":"service-root","fields":{"dir":"mailer","name":"mailer"},"file":"mailer/app/requirements.txt","line":-1},
              {"section":"service-root","fields":{"dir":"text-handler-package","name":"text-handler-package"},"file":"text-handler-package/setup.py","line":-1},
              {"section":"module-deps","fields":{"dir":"text-handler-package","library":"true","packages":"text_handler"},"file":"text-handler-package","line":-1},
              {"section":"import","fields":{"module":"text_handler.extractor","name":"get_main_text"},"file":"crawler/app/adapter.py","line":9},
              {"section":"import","fields":{"module":"text_handler.logger","name":"Logger"},"file":"mailer/app/log.py","line":3},
              {"section":"import","fields":{"relative":".gpt","name":"ask"},"file":"text-handler-package/text_handler/extractor.py","line":4},
              {"section":"url","fields":{"value":"http://extractorapi.com/api/v1/extractor/"},"file":"text-handler-package/text_handler/extractor.py","line":59},
              {"section":"url","fields":{"value":"https://api.openai.com/v1/chat"},"file":"text-handler-package/text_handler/gpt.py","line":12}
            ]}
            """;

    @Test
    void aLibrarysCallsAreAttributedToTheModulesImportingThatFile() {
        DependencyGraph g = merge(LIBRARY_IMPORTERS);
        assertNotNull(edge(g, "crawler", "extractorapi.com"), "crawler imports text_handler.extractor");
        assertNotNull(edge(g, "crawler", "api.openai.com"), "extractor imports .gpt inside the library: transitive");
        assertNull(edge(g, "mailer", "extractorapi.com"), "mailer imports another module of the library");
        assertNull(node(g, "text-handler-package"), "the library is never a node");
    }

    /** Rule 22: the call site carries the edge; a declaration nothing live uses draws none. */
    private static final String CALL_SITES = """
            {"repo":"r","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"core","name":"core"},"file":"core/requirements.txt","line":-1},
              {"section":"service-root","fields":{"dir":"user","name":"user"},"file":"user/requirements.txt","line":-1},
              {"section":"service-root","fields":{"dir":"data","name":"data"},"file":"data/requirements.txt","line":-1},
              {"section":"service-root","fields":{"dir":"config","name":"config"},"file":"config/requirements.txt","line":-1},
              {"section":"env-address","fields":{"name":"DATA_API_PREFIX","host":"data","configmap":"global-env"},"file":"infra/env.yaml","line":-1},
              {"section":"env-address","fields":{"name":"CONFIG_API_PREFIX","host":"config","configmap":"global-env"},"file":"infra/env.yaml","line":-1},
              {"section":"config","fields":{"env":"DATA_API_PREFIX","superseded":"true"},"file":"core/data_client.py","line":9},
              {"section":"http-client","fields":{"method":"post","urlref":"req_url","env":"DATA_API_PREFIX"},"file":"core/data_client.py","line":19},
              {"section":"config","fields":{"env":"CONFIG_API_PREFIX","unreferenced":"true"},"file":"user/config_client.py","line":13},
              {"section":"http-client","fields":{"method":"get","urlref":"req_url","env":"CONFIG_API_PREFIX","unreferenced":"true"},"file":"user/config_client.py","line":27}
            ]}
            """;

    @Test
    void theCallSiteCarriesTheEdgeAndDeadDeclarationsDrawNone() {
        DependencyGraph g = merge(CALL_SITES);
        DependencyGraph.Edge e = edge(g, "core", "data");
        assertNotNull(e);
        assertTrue(e.provenanceRefs.get(0).endsWith("core/data_client.py:19"), "provenance is the request, not the getenv: " + e.provenanceRefs);
        assertNull(edge(g, "user", "config"), "a communicator nothing calls is declared, not used");
    }

    /** Rule 23: a shared config key binds only in clients built with the library that reads it. */
    private static final String SHARED_KEYS_NEED_LIBRARIES = """
            {"repo":"r","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"config","name":"config"},"file":"config/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"account-service","name":"account-service"},"file":"account-service/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"gateway","name":"gateway"},"file":"gateway/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"unknown","name":"unknown"},"file":"unknown/Dockerfile","line":-1},
              {"section":"module-deps","fields":{"dir":"account-service","artifacts":"spring-cloud-starter-bus-amqp,spring-cloud-starter-oauth2,spring-cloud-starter-netflix-eureka-client","manifest":"pom.xml"},"file":"account-service","line":-1},
              {"section":"module-deps","fields":{"dir":"gateway","artifacts":"spring-cloud-starter-netflix-zuul,spring-cloud-starter-netflix-eureka-client","manifest":"pom.xml"},"file":"gateway","line":-1},
              {"section":"config","fields":{"key":"spring.cloud.config.uri","value":"http://config:8888"},"file":"account-service/src/main/resources/bootstrap.yml","line":-1},
              {"section":"config","fields":{"key":"spring.cloud.config.uri","value":"http://config:8888"},"file":"gateway/src/main/resources/bootstrap.yml","line":-1},
              {"section":"config","fields":{"key":"spring.cloud.config.uri","value":"http://config:8888"},"file":"unknown/src/main/resources/bootstrap.yml","line":-1},
              {"section":"config","fields":{"key":"spring.data.mongodb.host","value":"account-mongodb"},"file":"config/src/main/resources/shared/account-service.yml","line":-1},
              {"section":"config","fields":{"key":"spring.rabbitmq.host","value":"rabbitmq"},"file":"config/src/main/resources/shared/application.yml","line":-1},
              {"section":"config","fields":{"key":"security.oauth2.resource.user-info-uri","value":"http://auth-service:5000/uaa/users/current"},"file":"config/src/main/resources/shared/application.yml","line":-1},
              {"section":"config","fields":{"key":"eureka.client.serviceUrl.defaultZone","value":"http://registry:8761/eureka/"},"file":"config/src/main/resources/shared/application.yml","line":-1}
            ]}
            """;

    @Test
    void aSharedConfigKeyBindsOnlyWhereItsLibraryIsDeclared() {
        DependencyGraph g = merge(SHARED_KEYS_NEED_LIBRARIES);
        assertNotNull(edge(g, "account-service", "rabbitmq"), "account-service declares an AMQP starter");
        assertNotNull(edge(g, "account-service", "auth-service"));
        assertNull(edge(g, "gateway", "rabbitmq"), "gateway has no AMQP artifact: spring.rabbitmq.* never binds");
        assertNull(edge(g, "gateway", "auth-service"), "no OAuth2 artifact either");
        assertNotNull(edge(g, "gateway", "registry"), "both declare the Eureka client");
        // A client whose build is unknown keeps every shared key (nothing says it lacks the library).
        assertNotNull(edge(g, "unknown", "rabbitmq"));
    }

    // ---- 2026-10-26: the hold-out rules (mall-swarm, opentelemetry-demo) ----

    private static final String COMPOSE_ALIASES = """
            {"repo":"r","failed":false,"edges":[
              {"section":"compose-service","fields":{"name":"mysql","kind":"ComposeService"},"file":"document/docker/docker-compose-env.yml","line":-1},
              {"section":"compose-service","fields":{"name":"rabbitmq","kind":"ComposeService"},"file":"document/docker/docker-compose-env.yml","line":-1},
              {"section":"compose-service","fields":{"name":"elasticsearch","kind":"ComposeService"},"file":"document/docker/docker-compose-env.yml","line":-1},
              {"section":"compose-service","fields":{"name":"nacos-registry","kind":"ComposeService"},"file":"document/docker/docker-compose-env.yml","line":-1},
              {"section":"compose-service","fields":{"name":"mall-admin","kind":"ComposeService"},"file":"document/docker/docker-compose-app.yml","line":-1},
              {"section":"compose-service","fields":{"name":"mall-portal","kind":"ComposeService"},"file":"document/docker/docker-compose-app.yml","line":-1},
              {"section":"compose-service","fields":{"name":"mall-search","kind":"ComposeService"},"file":"document/docker/docker-compose-app.yml","line":-1},
              {"section":"compose-service","fields":{"name":"other","kind":"ComposeService"},"file":"document/docker/docker-compose-app.yml","line":-1},
              {"section":"compose-alias","fields":{"service":"mall-admin","alias":"db","target":"mysql"},"file":"document/docker/docker-compose-app.yml","line":-1},
              {"section":"compose-alias","fields":{"service":"mall-portal","alias":"rabbit","target":"rabbitmq"},"file":"document/docker/docker-compose-app.yml","line":-1},
              {"section":"compose-alias","fields":{"service":"mall-search","alias":"es","target":"elasticsearch"},"file":"document/docker/docker-compose-app.yml","line":-1},
              {"section":"service-root","fields":{"dir":"mall-admin","name":"mall-admin"},"file":"mall-admin/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"mall-portal","name":"mall-portal"},"file":"mall-portal/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"mall-search","name":"mall-search"},"file":"mall-search/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"other","name":"other"},"file":"other/pom.xml","line":-1},
              {"section":"config","fields":{"key":"spring.datasource.url","value":"jdbc:mysql://db:3306/mall?useSSL=false"},"file":"config/admin/mall-admin-prod.yaml","line":-1},
              {"section":"config","fields":{"key":"spring.datasource.url","value":"jdbc:mysql://db:3306/mall"},"file":"config/other/other-prod.yaml","line":-1},
              {"section":"config","fields":{"key":"spring.rabbitmq.host","value":"rabbit"},"file":"config/portal/mall-portal-prod.yaml","line":-1},
              {"section":"rabbit-consume","fields":{"queue":"mall.order.cancel"},"file":"mall-portal/src/main/java/com/macro/mall/portal/component/CancelOrderReceiver.java","line":16},
              {"section":"config","fields":{"key":"spring.elasticsearch.uris","value":"es:9200"},"file":"config/search/mall-search-prod.yaml","line":-1},
              {"section":"config","fields":{"key":"spring.cloud.nacos.discovery.server-addr","value":"nacos-registry:8848"},"file":"mall-admin/src/main/resources/application-prod.yml","line":-1},
              {"section":"config","fields":{"key":"aliyun.oss.endpoint","value":"oss-cn-shenzhen.aliyuncs.com"},"file":"mall-admin/src/main/resources/application.yml","line":-1},
              {"section":"config","fields":{"key":"minio.endpoint","value":"minio"},"file":"mall-admin/src/main/resources/application.yml","line":-1}
            ]}
            """;

    @Test
    void aComposeLinkAliasResolvesToTheServiceItNamesAndJdbcIsADatabase() {
        DependencyGraph g = merge(COMPOSE_ALIASES);
        // jdbc:mysql://db with `mysql:db` declared by mall-admin: the node is mysql, a database.
        DependencyGraph.Edge e = edge(g, "mall-admin", "mysql");
        assertNotNull(e);
        assertEquals("db", e.type);
        assertNull(node(g, "db"), "the alias is never a node");
        // A service that never declared the alias still gets it when every declaration agrees.
        DependencyGraph.Edge other = edge(g, "other", "mysql");
        assertNotNull(other, "db is mysql in every link that names it");
        assertEquals("db", other.type);
        // es:9200 under the `uris` key, through the alias, is the Elasticsearch store.
        assertEquals("db", edge(g, "mall-search", "elasticsearch").type);
    }

    @Test
    void aJdbcUrlIsADatabaseWhateverTheHostIsCalled() {
        String ledger = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"service-root","fields":{"dir":"svc","name":"svc"},"file":"svc/pom.xml","line":-1},
                  {"section":"config","fields":{"key":"spring.datasource.url","value":"jdbc:mysql://db:3306/mall"},"file":"svc/src/main/resources/application.yml","line":-1}
                ]}
                """;
        DependencyGraph g = merge(ledger);
        assertEquals(DependencyGraph.KIND_DB, node(g, "db").kind, "no alias: the host is the node, and the scheme says it is a database");
        assertEquals("db", edge(g, "svc", "db").type);
    }

    @Test
    void addressListKeysAndBareFqdnEndpointsAreTargets() {
        DependencyGraph g = merge(COMPOSE_ALIASES);
        assertNotNull(edge(g, "mall-admin", "nacos-registry"), "server-addr: host:port without a scheme");
        DependencyGraph.Edge oss = edge(g, "mall-admin", "oss-cn-shenzhen.aliyuncs.com");
        assertNotNull(oss, "an .endpoint key with a bare fully-qualified host is an external service");
        assertEquals("external", oss.type);
        assertNull(node(g, "minio"), "a bare word under .endpoint stays a signal, not a target");
    }

    @Test
    void aListenerDrawsConsumerToBrokerAndNeverAQueueNode() {
        DependencyGraph g = merge(COMPOSE_ALIASES);
        assertNull(node(g, "mall.order.cancel"), "a queue name is a destination, not a host");
        DependencyGraph.Edge e = edge(g, "mall-portal", "rabbitmq");
        assertNotNull(e, "consumer -> the broker it is configured with (spring.rabbitmq.host: rabbit, an alias of rabbitmq)");
        assertEquals("async", e.type);
        assertNull(edge(g, "rabbitmq", "mall-portal"), "never broker -> consumer");
    }

    @Test
    void aListenerWithNoBrokerInTheDeploymentIsResidueNotANode() {
        String ledger = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"service-root","fields":{"dir":"svc","name":"svc"},"file":"svc/pom.xml","line":-1},
                  {"section":"compose-service","fields":{"name":"kafka","kind":"ComposeService"},"file":"compose.yaml","line":-1},
                  {"section":"compose-service","fields":{"name":"svc","kind":"ComposeService"},"file":"compose.yaml","line":-1},
                  {"section":"kafka-consume","fields":{"topic":"orders"},"file":"svc/src/main/java/app/Consumer.java","line":9},
                  {"section":"rabbit-consume","fields":{"queue":"q"},"file":"svc/src/main/java/app/Receiver.java","line":5}
                ]}
                """;
        DependencyGraph g = merge(ledger);
        assertNotNull(edge(g, "svc", "kafka"), "the one Kafka node of the deployment is the broker");
        assertNull(node(g, "orders"));
        assertNull(node(g, "q"));
        List<CodeGraphMerger.Unresolved> residue = residue(ledger);
        assertTrue(residue.stream().anyMatch(u -> "rabbit-consume".equals(u.section) && "q".equals(u.rawTarget)),
                "no RabbitMQ anywhere: the row is residue, not a phantom node");
    }

    private static final String DEPENDS_ON_SHAPES = """
            {"repo":"r","failed":false,"edges":[
              {"section":"compose-service","fields":{"name":"frontend","kind":"ComposeService"},"file":"compose.yaml","line":-1},
              {"section":"compose-service","fields":{"name":"cart","kind":"ComposeService"},"file":"compose.yaml","line":-1},
              {"section":"compose-service","fields":{"name":"quote","kind":"ComposeService"},"file":"compose.yaml","line":-1},
              {"section":"compose-service","fields":{"name":"config-server","kind":"ComposeService"},"file":"compose.yaml","line":-1},
              {"section":"compose-service","fields":{"name":"vets-service","kind":"ComposeService"},"file":"compose.yaml","line":-1},
              {"section":"service-root","fields":{"dir":"frontend","name":"frontend"},"file":"frontend/package.json","line":-1},
              {"section":"service-root","fields":{"dir":"vets-service","name":"vets-service"},"file":"vets-service/pom.xml","line":-1},
              {"section":"compose-dependency","fields":{"source_service":"frontend","target_service":"cart"},"file":"compose.yaml","line":-1},
              {"section":"compose-dependency","fields":{"source_service":"frontend","target_service":"quote"},"file":"compose.yaml","line":-1},
              {"section":"compose-dependency","fields":{"source_service":"vets-service","target_service":"config-server"},"file":"compose.yaml","line":-1},
              {"section":"env-address","fields":{"name":"CART_ADDR","host":"cart","workload":"frontend","via":".env"},"file":"compose.yaml","line":-1},
              {"section":"workload-env","fields":{"workload":"frontend","host":"cart","name":"CART_ADDR"},"file":"compose.yaml","line":-1},
              {"section":"env-address","fields":{"name":"LLM_BASE_URL","host":"local-llm.com","workload":"frontend"},"file":"compose.yaml","line":-1},
              {"section":"workload-env","fields":{"workload":"frontend","host":"local-llm.com","name":"LLM_BASE_URL"},"file":"compose.yaml","line":-1},
              {"section":"url","fields":{"value":"https://vcr.local/"},"file":"frontend/src/patch_vcr.py","line":47},
              {"section":"url","fields":{"value":"http://api.example.com/v1"},"file":"frontend/src/sample.py","line":3}
            ]}
            """;

    @Test
    void dependsOnIsStartupOrderUnlessTheServiceStatesNoAddresses() {
        DependencyGraph g = merge(DEPENDS_ON_SHAPES);
        // frontend's env names cart: depends_on cart agrees with it -> documented.
        DependencyGraph.Edge cart = edge(g, "frontend", "cart");
        assertNotNull(cart);
        assertEquals(DependencyGraph.CONF_DOCUMENTED, cart.confidence);
        // frontend's env names addresses but none for quote: depends_on quote is startup order only.
        assertNull(edge(g, "frontend", "quote"));
        // vets-service's env names nothing: depends_on is the deployment's only wiring statement -> drawn, inferred.
        DependencyGraph.Edge cfg = edge(g, "vets-service", "config-server");
        assertNotNull(cfg);
        assertEquals(DependencyGraph.CONF_INFERRED, cfg.confidence);
        assertTrue(cfg.provenanceRefs.get(0).endsWith("#depends_on"));
    }

    @Test
    void aDomainInTheWorkloadsOwnEnvIsExternalAndReservedHostsAreNothing() {
        DependencyGraph g = merge(DEPENDS_ON_SHAPES);
        DependencyGraph.Edge llm = edge(g, "frontend", "local-llm.com");
        assertNotNull(llm, "handed an external endpoint: drawn as such, inferred");
        assertEquals("external", llm.type);
        assertEquals(DependencyGraph.CONF_INFERRED, llm.confidence);
        assertNull(node(g, "local-llm"), "never cut to a first label that reads as a service");
        assertNull(node(g, "vcr"), ".local is a reserved TLD");
        assertNull(node(g, "vcr.local"));
        assertNull(node(g, "api.example.com"));
        assertNull(node(g, "api"));
        assertTrue(residue(DEPENDS_ON_SHAPES).stream().noneMatch(u -> String.valueOf(u.rawTarget).contains("vcr.local")
                || String.valueOf(u.rawTarget).contains("example.com")), "not residue either");
    }

    // ---- 2026-10-26, set C (run-aspnetcore-microservices, yas): rules 36–46 ----

    /** A .NET solution: dotted Compose service names, connection strings, a hostname alias, YARP. */
    private static final String DOTNET_COMPOSE = """
            {"repo":"r","failed":false,"edges":[
              {"section":"compose-service","fields":{"name":"catalogdb","kind":"ComposeService"},"file":"src/docker-compose.yml","line":-1},
              {"section":"compose-service","fields":{"name":"messagebroker","kind":"ComposeService"},"file":"src/docker-compose.yml","line":-1},
              {"section":"compose-service","fields":{"name":"catalog.api","kind":"ComposeService","context":"src","dockerfile_dir":"src/Services/Catalog/Catalog.API"},"file":"src/docker-compose.yml","line":-1},
              {"section":"compose-service","fields":{"name":"basket.api","kind":"ComposeService","context":"src","dockerfile_dir":"src/Services/Basket/Basket.API"},"file":"src/docker-compose.yml","line":-1},
              {"section":"compose-service","fields":{"name":"discount.grpc","kind":"ComposeService","context":"src","dockerfile_dir":"src/Services/Discount/Discount.Grpc"},"file":"src/docker-compose.yml","line":-1},
              {"section":"compose-service","fields":{"name":"yarpapigateway","kind":"ComposeService","context":"src","dockerfile_dir":"src/ApiGateways/YarpApiGateway"},"file":"src/docker-compose.yml","line":-1},
              {"section":"service-root","fields":{"dir":"src/Services/Catalog/Catalog.API","name":"catalog-api"},"file":"src/Services/Catalog/Catalog.API/Dockerfile","line":-1},
              {"section":"service-root","fields":{"dir":"src/Services/Basket/Basket.API","name":"basket-api"},"file":"src/Services/Basket/Basket.API/Dockerfile","line":-1},
              {"section":"service-root","fields":{"dir":"src/Services/Discount/Discount.Grpc","name":"discount-grpc"},"file":"src/Services/Discount/Discount.Grpc/Dockerfile","line":-1},
              {"section":"service-root","fields":{"dir":"src/ApiGateways/YarpApiGateway","name":"yarpapigateway"},"file":"src/ApiGateways/YarpApiGateway/Dockerfile","line":-1},
              {"section":"compose-alias","fields":{"service":"*","alias":"ecommerce-mq","target":"messagebroker"},"file":"src/docker-compose.override.yml","line":-1},
              {"section":"env-address","fields":{"name":"ConnectionStrings__Database","host":"catalogdb","workload":"catalog.api","kind":"db"},"file":"src/docker-compose.override.yml","line":-1},
              {"section":"workload-env","fields":{"workload":"catalog.api","host":"catalogdb","name":"ConnectionStrings__Database","kind":"db"},"file":"src/docker-compose.override.yml","line":-1},
              {"section":"env-address","fields":{"name":"GrpcSettings__DiscountUrl","host":"discount.grpc","workload":"basket.api"},"file":"src/docker-compose.override.yml","line":-1},
              {"section":"workload-env","fields":{"workload":"basket.api","host":"discount.grpc","name":"GrpcSettings__DiscountUrl"},"file":"src/docker-compose.override.yml","line":-1},
              {"section":"env-address","fields":{"name":"MessageBroker__Host","host":"ecommerce-mq","workload":"basket.api","kind":"queue"},"file":"src/docker-compose.override.yml","line":-1},
              {"section":"workload-env","fields":{"workload":"basket.api","host":"ecommerce-mq","name":"MessageBroker__Host","kind":"queue"},"file":"src/docker-compose.override.yml","line":-1},
              {"section":"workload-env","fields":{"workload":"basket.api","host":"api.yas.local","name":"PUBLIC_URL"},"file":"src/docker-compose.override.yml","line":-1},
              {"section":"compose-dependency","fields":{"source_service":"basket.api","target_service":"messagebroker"},"file":"src/docker-compose.override.yml","line":-1},
              {"section":"config","fields":{"env":"ConnectionStrings__Database","dotnetkey":"ConnectionStrings:Database"},"file":"src/Services/Catalog/Catalog.API/Program.cs","line":20},
              {"section":"config","fields":{"env":"GrpcSettings__DiscountUrl","dotnetkey":"GrpcSettings:DiscountUrl"},"file":"src/Services/Basket/Basket.API/Program.cs","line":39},
              {"section":"url","fields":{"value":"http://catalog.api:8080","cluster":"catalog-cluster"},"file":"src/ApiGateways/YarpApiGateway/appsettings.json","line":-1}
            ]}
            """;

    @Test
    void dottedComposeServiceNamesAreNodesAndNeverCutToAFirstLabel() {
        DependencyGraph g = merge(DOTNET_COMPOSE);
        assertNotNull(node(g, "catalog.api"));
        assertNotNull(node(g, "basket.api"));
        assertNull(node(g, "catalog"), "never the first label");
        assertNull(node(g, "basket"));
        assertNull(node(g, "catalog-api"), "the module directory aligns to the service built from it");
        // GrpcSettings__DiscountUrl=https://discount.grpc:8081 names a declared service, not a domain.
        DependencyGraph.Edge grpc = edge(g, "basket.api", "discount.grpc");
        assertNotNull(grpc);
        assertEquals(DependencyGraph.KIND_SERVICE, node(g, "discount.grpc").kind, "a declared dotted name is in-cluster, not external");
        assertEquals(DependencyGraph.CONF_DOCUMENTED, grpc.confidence, "the C# read of the key is the use");
        // A YARP cluster destination is the gateway's route.
        assertNotNull(edge(g, "yarpapigateway", "catalog.api"));
        // A reserved TLD in the workload's own env draws nothing and invents no `api`.
        assertNull(node(g, "api"));
        assertNull(node(g, "api.yas.local"));
    }

    @Test
    void connectionStringsAndSchemesStateTheKindAndAHostnameIsAGlobalAlias() {
        DependencyGraph g = merge(DOTNET_COMPOSE);
        DependencyGraph.Edge db = edge(g, "catalog.api", "catalogdb");
        assertNotNull(db);
        assertEquals("db", db.type, "ConnectionStrings__X is a database whatever the host is called");
        assertEquals(DependencyGraph.KIND_DB, node(g, "catalogdb").kind);
        assertEquals(DependencyGraph.CONF_DOCUMENTED, db.confidence, "GetConnectionString(\"Database\") is the call site");
        // amqp://ecommerce-mq → the service whose hostname: it is; the alias is never a node.
        DependencyGraph.Edge mq = edge(g, "basket.api", "messagebroker");
        assertNotNull(mq);
        assertEquals("async", mq.type, "a depends_on drawn as a call is retyped once amqp:// says broker");
        assertEquals(DependencyGraph.KIND_QUEUE, node(g, "messagebroker").kind);
        assertNull(node(g, "ecommerce-mq"));
    }

    @Test
    void aNamespaceLabelIsStrippedOnlyWhenTheManifestsDeclareIt() {
        String ledger = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"k8s-namespace","fields":{"name":"aics"},"file":"k8s/core.yaml","line":-1},
                  {"section":"k8s-workload","fields":{"name":"core-service","kind":"Deployment"},"file":"k8s/core.yaml","line":-1},
                  {"section":"k8s-workload","fields":{"name":"redis-service","kind":"Deployment"},"file":"k8s/redis.yaml","line":-1},
                  {"section":"service-root","fields":{"dir":"core","name":"core-service"},"file":"core/requirements.txt","line":-1},
                  {"section":"url","fields":{"value":"http://redis-service.aics:6379/"},"file":"core/app/a.py","line":3},
                  {"section":"url","fields":{"value":"http://user-service.aics.svc.cluster.local/x"},"file":"core/app/b.py","line":4},
                  {"section":"url","fields":{"value":"https://hooks.slack.com/x"},"file":"core/app/c.py","line":5},
                  {"section":"url","fields":{"value":"http://redis-service.other/"},"file":"core/app/d.py","line":6}
                ]}
                """;
        DependencyGraph g = merge(ledger);
        assertNotNull(edge(g, "core-service", "redis-service"), "<svc>.<declared ns> is the service");
        assertNotNull(edge(g, "core-service", "user-service"), "a cluster suffix takes its namespace label with it");
        assertNotNull(edge(g, "core-service", "hooks.slack.com"), "a domain stays whole and external");
        assertEquals(DependencyGraph.KIND_EXTERNAL, node(g, "redis-service.other").kind, "an undeclared label is not a namespace: external, never cut");
        assertNull(node(g, "hooks"));
    }

    @Test
    void aComposeBuildContextOrArtifactIdMapsTheModuleToItsDeploymentName() {
        // yas: compose `backoffice: build: ./backoffice-bff` beside a k8s workload of the same name.
        String yas = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"k8s-workload","fields":{"name":"backoffice","kind":"Deployment"},"file":"yaslocal.yaml","line":-1},
                  {"section":"k8s-workload","fields":{"name":"identity","kind":"Deployment"},"file":"yaslocal.yaml","line":-1},
                  {"section":"compose-service","fields":{"name":"backoffice","kind":"ComposeService","context":"backoffice-bff"},"file":"docker-compose.yml","line":-1},
                  {"section":"service-root","fields":{"dir":"backoffice-bff","name":"backoffice-bff"},"file":"backoffice-bff/pom.xml","line":-1},
                  {"section":"config","fields":{"key":"spring.security.oauth2.client.provider.keycloak.issuer-uri","value":"http://identity/realms/Yas"},"file":"backoffice-bff/src/main/resources/application.yaml","line":-1}
                ]}
                """;
        DependencyGraph g = merge(yas);
        assertNotNull(edge(g, "backoffice", "identity"), "the module's edges belong to the service built from it");
        assertNull(node(g, "backoffice-bff"), "no second spelling, no alias question");

        // TeaStore: the pom's artifactId `webui` equals the Compose service `webui`, but the manifests say teastore-webui — rule 11 keeps the k8s spelling.
        String teastore = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"k8s-workload","fields":{"name":"teastore-webui","kind":"Deployment"},"file":"k8s/teastore.yaml","line":-1},
                  {"section":"k8s-workload","fields":{"name":"teastore-registry","kind":"Deployment"},"file":"k8s/teastore.yaml","line":-1},
                  {"section":"compose-service","fields":{"name":"webui","kind":"ComposeService"},"file":"examples/docker/docker-compose_default.yaml","line":-1},
                  {"section":"compose-service","fields":{"name":"registry","kind":"ComposeService"},"file":"examples/docker/docker-compose_default.yaml","line":-1},
                  {"section":"service-root","fields":{"dir":"services/tools.descartes.teastore.webui","name":"tools-descartes-teastore-webui"},"file":"services/tools.descartes.teastore.webui/pom.xml","line":-1},
                  {"section":"module-deps","fields":{"dir":"services/tools.descartes.teastore.webui","artifact":"webui","manifest":"pom.xml"},"file":"services/tools.descartes.teastore.webui","line":-1},
                  {"section":"url","fields":{"value":"http://teastore-registry:8080/"},"file":"services/tools.descartes.teastore.webui/src/main/java/A.java","line":9}
                ]}
                """;
        g = merge(teastore);
        assertNotNull(edge(g, "teastore-webui", "teastore-registry"));
        assertNull(node(g, "webui"), "with manifests present the Compose name only helps when it resolves to a workload");

        // ewolff's microservice: Compose-only, module microservice-demo-turbine-server has artifactId turbine = the Compose service.
        String ewolff = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"compose-service","fields":{"name":"turbine","kind":"ComposeService"},"file":"docker/docker-compose.yml","line":-1},
                  {"section":"compose-service","fields":{"name":"eureka","kind":"ComposeService"},"file":"docker/docker-compose.yml","line":-1},
                  {"section":"service-root","fields":{"dir":"microservice-demo/microservice-demo-turbine-server","name":"microservice-demo-turbine-server"},"file":"microservice-demo/microservice-demo-turbine-server/pom.xml","line":-1},
                  {"section":"module-deps","fields":{"dir":"microservice-demo/microservice-demo-turbine-server","artifact":"turbine","manifest":"pom.xml"},"file":"microservice-demo/microservice-demo-turbine-server","line":-1},
                  {"section":"config","fields":{"key":"eureka.client.serviceUrl.defaultZone","value":"http://eureka:8761/eureka/"},"file":"microservice-demo/microservice-demo-turbine-server/src/main/resources/application.properties","line":-1}
                ]}
                """;
        g = merge(ewolff);
        assertNotNull(edge(g, "turbine", "eureka"));
        assertNull(node(g, "microservice-demo-turbine-server"), "the artifactId equation merges the two spellings");
    }

    @Test
    void aJvmLibraryIsNeverANodeUnlessTheDeploymentNamesIt() {
        String ledger = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"compose-service","fields":{"name":"cart","kind":"ComposeService","context":"cart"},"file":"docker-compose.yml","line":-1},
                  {"section":"compose-service","fields":{"name":"identity","kind":"ComposeService"},"file":"docker-compose.yml","line":-1},
                  {"section":"compose-service","fields":{"name":"shared-runner","kind":"ComposeService","context":"shared"},"file":"docker-compose.yml","line":-1},
                  {"section":"service-root","fields":{"dir":"cart","name":"cart"},"file":"cart/pom.xml","line":-1},
                  {"section":"service-root","fields":{"dir":"common-library","name":"common-library"},"file":"common-library/pom.xml","line":-1},
                  {"section":"service-root","fields":{"dir":"shared","name":"shared"},"file":"shared/pom.xml","line":-1},
                  {"section":"module-deps","fields":{"dir":"common-library","library":"true","library_kind":"jvm","manifest":"pom.xml"},"file":"common-library","line":-1},
                  {"section":"module-deps","fields":{"dir":"shared","library":"true","library_kind":"jvm","manifest":"pom.xml"},"file":"shared","line":-1},
                  {"section":"config","fields":{"key":"spring.security.oauth2.resourceserver.jwt.issuer-uri","value":"http://identity/realms/Yas"},"file":"common-library/src/main/resources/application.properties","line":-1},
                  {"section":"config","fields":{"key":"spring.security.oauth2.resourceserver.jwt.issuer-uri","value":"http://identity/realms/Yas"},"file":"shared/src/main/resources/application.properties","line":-1},
                  {"section":"config","fields":{"key":"spring.security.oauth2.resourceserver.jwt.issuer-uri","value":"http://identity/realms/Yas"},"file":"cart/src/main/resources/application.properties","line":-1}
                ]}
                """;
        DependencyGraph g = merge(ledger);
        assertNull(node(g, "common-library"), "a library's test config is nobody's dependency");
        assertNull(edge(g, "common-library", "identity"));
        assertNotNull(edge(g, "cart", "identity"));
        assertNotNull(edge(g, "shared-runner", "identity"), "a 'library' the Compose file builds an image from is a service after all");
    }

    @Test
    void aBareWordUnderAnAddressKeyIsATargetOnlyWhenTheDeploymentDeclaresIt() {
        String ledger = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"k8s-workload","fields":{"name":"search","kind":"Deployment"},"file":"yaslocal.yaml","line":-1},
                  {"section":"k8s-workload","fields":{"name":"product","kind":"Deployment"},"file":"yaslocal.yaml","line":-1},
                  {"section":"compose-service","fields":{"name":"elasticsearch","kind":"ComposeService"},"file":"docker-compose.search.yml","line":-1},
                  {"section":"service-root","fields":{"dir":"search","name":"search"},"file":"search/pom.xml","line":-1},
                  {"section":"config","fields":{"key":"elasticsearch.url","value":"elasticsearch"},"file":"search/src/main/resources/application.properties","line":-1},
                  {"section":"config","fields":{"key":"feature.url","value":"disabled"},"file":"search/src/main/resources/application.properties","line":-1},
                  {"section":"config","fields":{"key":"spring.kafka.consumer.bootstrap-servers","value":"kafka:9092"},"file":"search/src/main/resources/application.properties","line":-1}
                ]}
                """;
        DependencyGraph g = merge(ledger);
        DependencyGraph.Edge es = edge(g, "search", "elasticsearch");
        assertNotNull(es, "elasticsearch.url=elasticsearch: the word is a Compose service");
        assertEquals("db", es.type);
        assertNull(node(g, "disabled"), "a word the deployment does not declare stays a signal");
        assertNotNull(edge(g, "search", "kafka"), "bootstrap-servers: host:port (rule 27)");
    }

    @Test
    void aComposeServiceTheManifestsDoNotNameIsStillTheSourceOfItsOwnEnv() {
        String ledger = """
                {"repo":"r","failed":false,"edges":[
                  {"section":"k8s-workload","fields":{"name":"keycloak","kind":"Deployment"},"file":"yaslocal.yaml","line":-1},
                  {"section":"k8s-workload","fields":{"name":"product","kind":"Deployment"},"file":"yaslocal.yaml","line":-1},
                  {"section":"service-root","fields":{"dir":"product","name":"product"},"file":"product/pom.xml","line":-1},
                  {"section":"compose-service","fields":{"name":"identity","kind":"ComposeService"},"file":"docker-compose.yml","line":-1},
                  {"section":"compose-service","fields":{"name":"postgres","kind":"ComposeService"},"file":"docker-compose.yml","line":-1},
                  {"section":"env-address","fields":{"name":"KC_DB_URL","host":"postgres","workload":"identity","kind":"db"},"file":"docker-compose.yml","line":-1},
                  {"section":"workload-env","fields":{"workload":"identity","host":"postgres","name":"KC_DB_URL","kind":"db"},"file":"docker-compose.yml","line":-1}
                ]}
                """;
        DependencyGraph g = merge(ledger);
        DependencyGraph.Edge e = edge(g, "identity", "postgres");
        assertNotNull(e, "the Compose file declares identity even though the stale manifests call it keycloak");
        assertEquals("db", e.type);
    }
}
