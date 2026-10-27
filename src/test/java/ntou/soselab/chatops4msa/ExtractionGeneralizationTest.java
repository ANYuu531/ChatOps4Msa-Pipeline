package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.DetectedStack;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ServiceRootScanner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.StackDetector;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.TreeSitterExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extraction rules the six unseen projects of 2026-09-22 forced: what the config
 * reader, the service-root scanner and the tree-sitter pass must see in a repository
 * shaped unlike petclinic / Bank of Anthos / train-ticket. Pure Java, temp dirs.
 */
public class ExtractionGeneralizationTest {

    private static void write(Path dir, String relative, String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static List<EdgeLedger.Edge> section(EdgeLedger ledger, String section) {
        return ledger.getEdges().stream().filter(e -> e.section.equals(section)).collect(Collectors.toList());
    }

    private static boolean has(EdgeLedger ledger, String section, String field, String value) {
        return section(ledger, section).stream().anyMatch(e -> value.equals(e.fields.get(field)));
    }

    // ---- ConfigExtractor ----

    @Test
    void k8sManifestsYieldTheWorkloadInventoryAndBareHostEnvs(@TempDir Path dir) throws Exception {
        write(dir, "k8s/teastore.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: teastore-persistence
                spec:
                  template:
                    spec:
                      containers:
                        - name: persistence
                          env:
                            - name: REGISTRY_HOST
                              value: "teastore-registry"
                            - name: DB_HOST
                              value: "teastore-db"
                            - name: DB_PORT
                              value: "3306"
                ---
                apiVersion: apps/v1
                kind: StatefulSet
                metadata:
                  name: teastore-db
                ---
                apiVersion: v1
                kind: Service
                metadata:
                  name: teastore-persistence
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        List<String> workloads = section(ledger, "k8s-workload").stream()
                .map(e -> e.fields.get("name")).collect(Collectors.toList());
        assertEquals(List.of("teastore-persistence", "teastore-db"), workloads, "workloads only, never a Service");
        // A bare host under a *_HOST variable is an address even without a port.
        assertTrue(has(ledger, "env-address", "host", "teastore-db"));
        assertTrue(has(ledger, "workload-env", "host", "teastore-db"));
        assertFalse(has(ledger, "env-address", "host", "3306"));
    }

    @Test
    void composeFilesAreWiringNotAServicesOwnConfig(@TempDir Path dir) throws Exception {
        // The Compose spec's default name, a map-form environment and a list-form one.
        write(dir, "compose.yml", """
                services:
                  persistence:
                    image: x/persistence
                    environment:
                      DB_HOST: "db"
                      HOST_NAME: "persistence"
                  cart:
                    image: x/cart
                    environment:
                      - REDIS_HOST=redis
                    depends_on:
                      - redis
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        assertTrue(has(ledger, "compose-dependency", "target_service", "redis"));
        assertTrue(has(ledger, "env-address", "host", "db"));
        assertTrue(has(ledger, "env-address", "host", "redis"));
        assertTrue(section(ledger, "workload-env").stream().anyMatch(e ->
                "persistence".equals(e.fields.get("workload")) && "db".equals(e.fields.get("host"))));
        assertTrue(section(ledger, "config").isEmpty(), "a compose file is not flattened into config rows");
    }

    @Test
    void aSpringShapedYamlNotCalledApplicationIsReadAndAWorkflowIsNot(@TempDir Path dir) throws Exception {
        write(dir, "config/src/main/resources/shared/account-service.yml", """
                spring:
                  data:
                    mongodb:
                      host: account-mongodb
                server:
                  port: 6000
                """);
        write(dir, ".github/workflows/build.yml", """
                name: build
                on: [push]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        assertTrue(section(ledger, "config").stream().anyMatch(e ->
                "spring.data.mongodb.host".equals(e.fields.get("key")) && "account-mongodb".equals(e.fields.get("value"))));
        assertTrue(ledger.getEdges().stream().noneMatch(e -> e.file.contains("workflows")));
    }

    @Test
    void reverseProxyConfigsNameTheirUpstreams(@TempDir Path dir) throws Exception {
        write(dir, "apache/000-default.conf", """
                <VirtualHost *:80>
                    ProxyPass        /order http://order:8080/
                    ProxyPassReverse /order http://order:8080/
                    # ProxyPass /old http://old:8080/
                </VirtualHost>
                """);
        write(dir, "web/default.conf.template", """
                location /api/cart/ {
                    proxy_pass http://${CART_HOST}:8080/;
                }
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        List<String> urls = section(ledger, "url").stream().map(e -> e.fields.get("value")).collect(Collectors.toList());
        assertTrue(urls.contains("http://order:8080/"));
        assertTrue(urls.contains("http://${CART_HOST}:8080/"));
        assertFalse(urls.contains("http://old:8080/"), "a commented directive is not a route");
    }

    // ---- ServiceRootScanner ----

    @Test
    void serviceRootsClimbGenericDirsSkipTestsAndAggregatorsAndHyphenateDots(@TempDir Path dir) throws Exception {
        write(dir, "src/cartservice/src/Dockerfile", "FROM x");
        write(dir, "src/frontend/go.mod", "module frontend");
        write(dir, "ui-tests/package.json", "{}");
        write(dir, "services/pom.xml", "<project/>");                              // aggregator over two modules
        write(dir, "services/tools.descartes.teastore.webui/pom.xml", "<project/>");
        write(dir, "services/tools.descartes.teastore.auth/pom.xml", "<project/>");
        write(dir, "pom.xml", "<project/>");                                       // repo root: never a service

        EdgeLedger ledger = new EdgeLedger();
        new ServiceRootScanner().scan(dir, ledger);
        Map<String, String> roots = section(ledger, "service-root").stream()
                .collect(Collectors.toMap(e -> e.fields.get("dir"), e -> e.fields.get("name")));

        assertEquals("cartservice", roots.get("src/cartservice"), "src/ is a wrapper, not the service");
        assertEquals("frontend", roots.get("src/frontend"));
        assertEquals("tools-descartes-teastore-webui", roots.get("services/tools.descartes.teastore.webui"));
        assertFalse(roots.containsKey("services"), "a parent module that only groups modules is not a service");
        assertFalse(roots.containsKey("ui-tests"));
        assertFalse(roots.containsKey("src/cartservice/src"));
    }

    // ---- TreeSitterExtractor ----

    @Test
    void aUrlConstantNobodyReferencesIsMarkedAndJpaIsFoundWithoutSpring(@TempDir Path dir) throws Exception {
        write(dir, "order/pom.xml", "<project/>");
        write(dir, "order/src/main/java/app/AppConstant.java", """
                package app;
                public class AppConstant {
                    public static final String USER_URL = "http://user-service/api/users";
                    public static final String PRODUCT_URL = "http://product-service/api/products";
                }
                """);
        write(dir, "order/src/main/java/app/Client.java", """
                package app;
                import javax.persistence.Entity;
                @Entity
                public class Client {
                    String fetch() { return AppConstant.PRODUCT_URL + "/1"; }
                }
                """);
        write(dir, "user/pom.xml", "<project/>");
        write(dir, "user/src/main/java/app/Other.java", """
                package app;
                public class Other { String x = AppConstant.USER_URL; }
                """);

        EdgeLedger ledger = new EdgeLedger();
        DetectedStack java = new DetectedStack("java", null, DetectedStack.Tier.GENERIC, List.of(".java"), "test");
        new TreeSitterExtractor().extract(dir, java, ledger);

        Map<String, String> flags = section(ledger, "url").stream()
                .collect(Collectors.toMap(e -> e.fields.get("value"), e -> String.valueOf(e.fields.get("unreferenced"))));
        // USER_URL is referenced only from another module: unused in its own, so declared, not used.
        assertEquals("true", flags.get("http://user-service/api/users"));
        assertEquals("null", flags.get("http://product-service/api/products"));
        // The JPA marker is a Java standard, found under the generic pack alone.
        assertTrue(has(ledger, "jpa", "marker", "Entity"));
    }

    // ---- 2026-10-23: the rows rules 15–24 read ----

    @Test
    void k8sServicesScopedEnvsAndDockerfileEnvsAreRecorded(@TempDir Path dir) throws Exception {
        write(dir, "core/k8s/core.yaml", """
                apiVersion: v1
                kind: Service
                metadata:
                  name: core-service
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: core-service-v1
                spec:
                  template:
                    spec:
                      containers:
                        - name: core
                          envFrom:
                            - configMapRef:
                                name: global-env
                          env:
                            - name: MONGO_CLIENT_HOST
                              value: core-service-mongo.aics.svc.cluster.local
                """);
        write(dir, "shipping/Dockerfile", """
                FROM openjdk:8
                ENV CART_ENDPOINT=cart:8080 \\
                    DB_HOST=mysql
                ENV INSTANA_AUTO_PROFILE true
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        assertTrue(has(ledger, "k8s-service", "name", "core-service"), "a Service is recorded beside the workloads");
        assertTrue(section(ledger, "env-address").stream().anyMatch(e ->
                "MONGO_CLIENT_HOST".equals(e.fields.get("name")) && "core-service-v1".equals(e.fields.get("workload"))),
                "a Deployment's literal env is scoped to that workload");
        assertTrue(section(ledger, "workload-env").stream().anyMatch(e ->
                "MONGO_CLIENT_HOST".equals(e.fields.get("name")) && "core-service-mongo".equals(e.fields.get("host"))));
        assertTrue(section(ledger, "env-address").stream().anyMatch(e ->
                "CART_ENDPOINT".equals(e.fields.get("name")) && "cart".equals(e.fields.get("host"))
                        && "dockerfile".equals(e.fields.get("workload"))), "ENV K=V across a continuation line");
        assertTrue(has(ledger, "env-address", "name", "DB_HOST"));
        assertFalse(has(ledger, "env-address", "name", "INSTANA_AUTO_PROFILE"), "a flag is not an address");
    }

    @Test
    void serverPortAndConfigImportAreConfigRows(@TempDir Path dir) throws Exception {
        write(dir, "cloud-config/src/main/resources/application.yml", """
                server:
                  port: 9296
                """);
        write(dir, "gateway/src/main/resources/application.yml", """
                spring:
                  config:
                    import: ${SPRING_CONFIG_IMPORT:optional:configserver:http://localhost:9296}
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);
        assertTrue(section(ledger, "config").stream().anyMatch(e -> "server.port".equals(e.fields.get("key")) && "9296".equals(e.fields.get("value"))));
        assertTrue(section(ledger, "config").stream().anyMatch(e -> "spring.config.import".equals(e.fields.get("key"))));
    }

    @Test
    void moduleDepsListArtifactsAndFlagPythonLibraries(@TempDir Path dir) throws Exception {
        write(dir, "gateway/pom.xml", """
                <project>
                  <artifactId>gateway</artifactId>
                  <dependencies>
                    <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-netflix-eureka-client</artifactId></dependency>
                    <dependency><artifactId>spring-cloud-starter-gateway</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        write(dir, "text-handler-package/setup.py", "from setuptools import setup\nsetup(name='text_handler')\n");
        write(dir, "text-handler-package/text_handler/__init__.py", "");
        write(dir, "crawler/app/requirements.txt", "requests\nredis==4.0\n");
        write(dir, "crawler/app/Dockerfile", "FROM python:3\n");

        EdgeLedger ledger = new EdgeLedger();
        new ServiceRootScanner().scan(dir, ledger);
        Map<String, Map<String, String>> deps = section(ledger, "module-deps").stream()
                .collect(Collectors.toMap(e -> e.fields.get("dir"), e -> e.fields));
        assertEquals("spring-cloud-starter-gateway,spring-cloud-starter-netflix-eureka-client", deps.get("gateway").get("artifacts"));
        assertEquals("pom.xml", deps.get("gateway").get("manifest"));
        assertEquals("true", deps.get("text-handler-package").get("library"), "setup.py, no Dockerfile: a library");
        assertEquals("text_handler", deps.get("text-handler-package").get("packages"));
        assertFalse(deps.containsKey("crawler") && "true".equals(deps.get("crawler").get("library")), "an image is a service");
        assertEquals("redis,requests", deps.get("crawler").get("artifacts"));
    }

    @Test
    void pythonUrlVariablesResolveToTheCallSiteAndDeadCodeDrawsNothing(@TempDir Path dir) throws Exception {
        write(dir, "core/app/requirements.txt", "requests\n");
        write(dir, "core/app/data_client.py", """
                import os
                import requests
                DATA_API_PREFIX = os.getenv("DATA_API_PREFIX")

                def fetch(article_url):
                    req_url = f"{DATA_API_PREFIX}/getOne"
                    response = requests.post(url=req_url, timeout=15)
                    return response

                def fetch_twitter(search_url):
                    return requests.request("GET", search_url, timeout=10)

                fetch("x")
                """);
        write(dir, "user/app/requirements.txt", "requests\n");
        write(dir, "user/app/config_client.py", """
                import os
                import requests
                CONFIG_API_PREFIX = os.getenv("CONFIG_API_PREFIX")

                def update_secret(unique_name):
                    req_url = f"{CONFIG_API_PREFIX}/secret/{unique_name}"
                    return requests.get(url=req_url, timeout=15)

                def update_jwt_secret():
                    return update_secret("jwt")

                def live_check():
                    return requests.get("http://probe.example.com/health")

                live_check()
                """);
        EdgeLedger ledger = new EdgeLedger();
        DetectedStack python = new DetectedStack("python", "web", DetectedStack.Tier.FRAMEWORK, List.of(".py"), "test");
        new TreeSitterExtractor().extract(dir, python, ledger);

        // core: the request row carries the env name and is at the call line; the getenv row yields to it.
        EdgeLedger.Edge call = section(ledger, "http-client").stream()
                .filter(e -> e.file.equals("core/app/data_client.py") && "post".equals(e.fields.get("method"))).findFirst().orElseThrow();
        assertEquals("DATA_API_PREFIX", call.fields.get("env"));
        assertEquals(7, call.line);
        assertTrue(section(ledger, "config").stream().anyMatch(e ->
                e.file.equals("core/app/data_client.py") && "DATA_API_PREFIX".equals(e.fields.get("env")) && "true".equals(e.fields.get("superseded"))));
        // requests.request("GET", …): the verb is not a URL.
        assertFalse(section(ledger, "http-client").stream().anyMatch(e -> "GET".equals(e.fields.get("url"))));

        // user: the communicator's only entry point is never called, so its rows are declared, not used.
        assertTrue(section(ledger, "config").stream().anyMatch(e ->
                "CONFIG_API_PREFIX".equals(e.fields.get("env")) && "true".equals(e.fields.get("unreferenced"))));
        assertTrue(section(ledger, "http-client").stream().anyMatch(e ->
                e.file.equals("user/app/config_client.py") && e.line == 7 && "true".equals(e.fields.get("unreferenced"))));
        assertTrue(section(ledger, "http-client").stream().anyMatch(e ->
                "http://probe.example.com/health".equals(e.fields.get("url")) && !"true".equals(e.fields.get("unreferenced"))),
                "a called function's request stays");
    }

    @Test
    void javaNamedConstantsAndFormatGetenvAreRecorded(@TempDir Path dir) throws Exception {
        write(dir, "client/pom.xml", "<project/>");
        write(dir, "client/src/main/java/registry/Service.java", """
                package registry;
                public enum Service {
                    PERSISTENCE("tools.descartes.teastore.persistence"),
                    AUTH("tools.descartes.teastore.auth");
                    private String serviceName;
                    Service(String serviceName) { this.serviceName = serviceName; }
                }
                """);
        write(dir, "webui/pom.xml", "<project/>");
        write(dir, "webui/src/main/java/webui/Servlet.java", """
                package webui;
                public class Servlet {
                    public static final String ROLE = "admin";
                    void load() {
                        ServiceLoadBalancer.loadBalanceRESTOperation(Service.PERSISTENCE, "generatedb", String.class, c -> c);
                        String cartUrl = String.format("http://%s/shipping/", getenv("CART_ENDPOINT", "cart"));
                    }
                    private String getenv(String k, String d) { return d; }
                }
                """);
        EdgeLedger ledger = new EdgeLedger();
        DetectedStack java = new DetectedStack("java", null, DetectedStack.Tier.GENERIC, List.of(".java"), "test");
        new TreeSitterExtractor().extract(dir, java, ledger);

        assertTrue(section(ledger, "name-constant").stream().anyMatch(e ->
                "Service".equals(e.fields.get("type")) && "PERSISTENCE".equals(e.fields.get("name"))
                        && "tools.descartes.teastore.persistence".equals(e.fields.get("value"))));
        assertTrue(section(ledger, "name-constant").stream().anyMatch(e ->
                "Servlet".equals(e.fields.get("type")) && "ROLE".equals(e.fields.get("name"))), "a static final String too");
        assertTrue(section(ledger, "name-ref").stream().anyMatch(e ->
                "Service".equals(e.fields.get("type")) && "PERSISTENCE".equals(e.fields.get("name")) && e.line == 5));
        EdgeLedger.Edge format = section(ledger, "url").stream()
                .filter(e -> e.fields.containsKey("env")).findFirst().orElseThrow();
        assertEquals("http://%s/shipping/", format.fields.get("value"));
        assertEquals("CART_ENDPOINT", format.fields.get("env"));
        assertEquals("cart", format.fields.get("envdefault"));
        assertTrue(section(ledger, "url").stream().anyMatch(e ->
                !e.fields.containsKey("env") && "http://%s/shipping/".equals(e.fields.get("value")) && "true".equals(e.fields.get("unreferenced"))),
                "the bare twin of the format literal draws nothing");
    }

    @Test
    void pythonImportsAndHostDefaultsAreRecorded(@TempDir Path dir) throws Exception {
        write(dir, "svc/requirements.txt", "redis\n");
        write(dir, "svc/handler.py", """
                from text_handler.extractor import get_main_text
                from text_handler import summarizer as s
                import toolkit.redis_handler
                from . import local_helper
                """);
        write(dir, "svc/redis_handler.py", """
                import redis
                class RedisDBHandler:
                    def __init__(self, host='redis-service.aics.svc.cluster.local', port=6379):
                        self.client = redis.StrictRedis(host=host, port=port)
                """);
        EdgeLedger ledger = new EdgeLedger();
        DetectedStack python = new DetectedStack("python", null, DetectedStack.Tier.GENERIC, List.of(".py"), "test");
        new TreeSitterExtractor().extract(dir, python, ledger);

        assertTrue(section(ledger, "import").stream().anyMatch(e ->
                "text_handler.extractor".equals(e.fields.get("module")) && "get_main_text".equals(e.fields.get("name"))));
        assertTrue(section(ledger, "import").stream().anyMatch(e ->
                "text_handler".equals(e.fields.get("module")) && "summarizer".equals(e.fields.get("name"))), "aliased from-import");
        assertTrue(has(ledger, "import", "module", "toolkit.redis_handler"));
        assertTrue(section(ledger, "import").stream().anyMatch(e -> ".".equals(e.fields.get("relative")) && "local_helper".equals(e.fields.get("name"))));
        assertTrue(section(ledger, "config").stream().anyMatch(e ->
                "host".equals(e.fields.get("key")) && "redis-service.aics.svc.cluster.local".equals(e.fields.get("value"))),
                "a host= parameter default names the store");
    }

    // ---- 2026-10-26: the hold-out (opentelemetry-demo, mall-swarm, hotelReservation) rows ----

    @Test
    void composeForwardsKeysFromDotEnvWithInterpolationAndReadsEnvFiles(@TempDir Path dir) throws Exception {
        write(dir, ".env", """
                # ports first, as the demo does
                CART_PORT=7070
                ENVOY_PORT=8080
                CART_ADDR=cart:${CART_PORT}
                FRONTEND_PROXY_ADDR=frontend-proxy:${ENVOY_PORT}
                LOCUST_HOST=http://${FRONTEND_PROXY_ADDR}
                OTEL_COLLECTOR_HOST=otel-collector
                POSTGRES_HOST=astronomy-db
                LLM_BASE_URL=https://local-llm.com
                POSTGRES_PASSWORD=changeit    # inline comment, cut
                """);
        write(dir, "svc.env", "QUOTE_ADDR=http://quote:8090\n");
        write(dir, "compose.yaml", """
                services:
                  frontend:
                    image: x/frontend
                    environment:
                      - CART_ADDR
                      - OTEL_EXPORTER_OTLP_ENDPOINT=http://${OTEL_COLLECTOR_HOST}:4318
                      - UNDEFINED_KEY
                  load-generator:
                    image: x/loadgen
                    environment:
                      LOCUST_HOST:
                  shipping:
                    image: x/shipping
                    env_file: ./svc.env
                  accounting:
                    image: x/accounting
                    environment:
                      - DB_CONNECTION_STRING=Host=${POSTGRES_HOST};Username=u;Password=${POSTGRES_PASSWORD};Database=d
                  agent:
                    image: x/agent
                    environment:
                      - LLM_BASE_URL
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        List<EdgeLedger.Edge> rows = section(ledger, "workload-env");
        assertTrue(rows.stream().anyMatch(e -> "frontend".equals(e.fields.get("workload")) && "cart".equals(e.fields.get("host"))),
                "a key without a value forwards the .env value, interpolated");
        assertTrue(rows.stream().anyMatch(e -> "load-generator".equals(e.fields.get("workload")) && "frontend-proxy".equals(e.fields.get("host"))),
                "two levels of ${…} in .env, and the map form with a null value");
        assertTrue(rows.stream().anyMatch(e -> "frontend".equals(e.fields.get("workload")) && "otel-collector".equals(e.fields.get("host"))),
                "a value stated in place is interpolated against .env");
        assertTrue(rows.stream().anyMatch(e -> "shipping".equals(e.fields.get("workload")) && "quote".equals(e.fields.get("host"))),
                "every key of an env_file is the service's env");
        assertTrue(rows.stream().anyMatch(e -> "accounting".equals(e.fields.get("workload")) && "astronomy-db".equals(e.fields.get("host"))),
                "an ADO.NET connection string's Host= is the host");
        assertTrue(rows.stream().anyMatch(e -> "agent".equals(e.fields.get("workload")) && "local-llm.com".equals(e.fields.get("host"))),
                "a Compose env has no namespaces: a dotted host is kept whole");
        assertTrue(section(ledger, "env-address").stream().anyMatch(e -> "CART_ADDR".equals(e.fields.get("name")) && ".env".equals(e.fields.get("via"))));
        assertFalse(rows.stream().anyMatch(e -> "UNDEFINED_KEY".equals(e.fields.get("name"))), "a key nothing defines forwards nothing");
    }

    @Test
    void composeLinkAliasesTestOverlaysAndTestServicesAndHiddenDirs(@TempDir Path dir) throws Exception {
        write(dir, "document/docker/docker-compose-app.yml", """
                version: '3'
                services:
                  mall-admin:
                    image: mall/mall-admin
                    external_links:
                      - mysql:db
                      - nacos-registry:nacos-registry
                  frontendTests:
                    image: x/tests
                    depends_on:
                      - mall-admin
                """);
        write(dir, "compose.tests.yaml", """
                services:
                  e2e:
                    image: x/e2e
                    depends_on:
                      - mall-admin
                """);
        write(dir, ".bingo/go.mod", "module tools\n");
        write(dir, "search/go.mod", "module search\n");
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);
        new ServiceRootScanner().scan(dir, ledger);

        assertTrue(section(ledger, "compose-alias").stream().anyMatch(e ->
                "mall-admin".equals(e.fields.get("service")) && "db".equals(e.fields.get("alias")) && "mysql".equals(e.fields.get("target"))));
        assertFalse(has(ledger, "compose-alias", "alias", "nacos-registry"), "an alias equal to the target is no alias");
        assertTrue(has(ledger, "compose-dependency", "target_service", "mysql"), "external_links is a dependency like links");
        assertFalse(has(ledger, "compose-service", "name", "frontendTests"), "a *tests service is a test runner");
        assertFalse(section(ledger, "compose-dependency").stream().anyMatch(e -> "frontendTests".equals(e.fields.get("source_service"))));
        assertFalse(has(ledger, "compose-service", "name", "e2e"), "compose.tests.yaml is not read at all");
        assertTrue(has(ledger, "service-root", "name", "search"));
        assertFalse(has(ledger, "service-root", "name", "bingo"), "a marker under a hidden directory is tooling");
    }

    @Test
    void addressListKeysAndEnvoyClustersAreRecorded(@TempDir Path dir) throws Exception {
        write(dir, "mall-search/src/main/resources/application-prod.yml", """
                spring:
                  cloud:
                    nacos:
                      discovery:
                        server-addr: nacos-registry:8848
                  elasticsearch:
                    uris: es:9200
                aliyun:
                  oss:
                    endpoint: oss-cn-shenzhen.aliyuncs.com
                """);
        write(dir, "proxy/envoy.tmpl.yaml", """
                static_resources:
                  listeners: []
                  clusters:
                    - name: image-provider
                      load_assignment:
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: ${IMAGE_PROVIDER_HOST}
                                      port_value: ${IMAGE_PROVIDER_PORT}
                    - name: frontend
                      hosts:
                        - socket_address: { address: frontend, port_value: 8080 }
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        assertTrue(has(ledger, "config", "key", "spring.cloud.nacos.discovery.server-addr"));
        assertTrue(has(ledger, "config", "key", "spring.elasticsearch.uris"));
        assertTrue(has(ledger, "config", "key", "aliyun.oss.endpoint"));
        assertTrue(has(ledger, "url", "value", "http://${IMAGE_PROVIDER_HOST}"), "a placeholder port is left off so the host alone can be filled");
        assertTrue(has(ledger, "url", "value", "http://frontend:8080"), "the legacy hosts form too");
        assertTrue(section(ledger, "config").stream().noneMatch(e -> String.valueOf(e.fields.get("key")).startsWith("static_resources")),
                "an Envoy config is not flattened as Spring config");
    }

    @Test
    void openApiMetadataUrlsAreNotCallsAndMyBatisIsPersistence(@TempDir Path dir) throws Exception {
        write(dir, "mall-admin/pom.xml", "<project/>");
        write(dir, "mall-admin/src/main/java/app/SpringDocConfig.java", """
                package app;
                @Configuration
                @OpenAPIDefinition(info = @Info(termsOfService = "https://terms.example.org/x"))
                @MapperScan({"app.mapper"})
                public class SpringDocConfig {
                    public OpenAPI api() {
                        return new OpenAPI()
                                .info(new Info().title("mall")
                                        .license(new License().name("Apache 2.0")
                                                .url("https://github.com/macrozheng/mall-learning")))
                                .externalDocs(new ExternalDocumentation()
                                        .description("docs")
                                        .url("http://www.macrozheng.com"));
                    }
                    ApiInfo legacy() {
                        return new ApiInfoBuilder().termsOfServiceUrl("https://github.com/FudanSELab/train-ticket")
                                .contact(new Contact("me", "https://me.example.net", "mail")).build();
                    }
                    String call() { return rest.getForObject("https://api.github.com/repos", String.class); }
                }
                """);
        EdgeLedger ledger = new EdgeLedger();
        DetectedStack java = new DetectedStack("java", null, DetectedStack.Tier.GENERIC, List.of(".java"), "test");
        new TreeSitterExtractor().extract(dir, java, ledger);

        Map<String, String> flags = section(ledger, "url").stream()
                .collect(Collectors.toMap(e -> e.fields.get("value"), e -> String.valueOf(e.fields.get("not-an-address")), (a, b) -> a));
        assertEquals("openapi-metadata", flags.get("https://github.com/macrozheng/mall-learning"), "License().url(...)");
        assertEquals("openapi-metadata", flags.get("http://www.macrozheng.com"), "ExternalDocumentation().url(...)");
        assertEquals("openapi-metadata", flags.get("https://github.com/FudanSELab/train-ticket"), "ApiInfoBuilder.termsOfServiceUrl(...)");
        assertEquals("openapi-metadata", flags.get("https://me.example.net"), "new Contact(name, url, mail)");
        assertEquals("openapi-metadata", flags.get("https://terms.example.org/x"), "@Info(termsOfService = ...)");
        assertEquals("null", flags.get("https://api.github.com/repos"), "a URL handed to an HTTP client is still a call");
        assertTrue(has(ledger, "jpa", "marker", "MapperScan"));
    }

    @Test
    void kotlinIsReadWithItsGrammarAndTheThinSurfaceIsTheFallback(@TempDir Path dir) throws Exception {
        write(dir, "fraud-detection/build.gradle.kts", "plugins { kotlin(\"jvm\") }\n");
        write(dir, "fraud-detection/src/main/kotlin/frauddetection/main.kt", """
                package frauddetection
                // System.getenv("IN_A_COMMENT")
                val topic: String = System.getenv("KAFKA_TOPIC") ?: "orders"
                fun main() {
                    val bootstrapServers = System.getenv("KAFKA_ADDR")
                    val url = "http://flagd:8013/ofrep"
                }
                @KafkaListener(topics = ["orders"])
                fun onOrder() {}
                """);
        List<DetectedStack> stacks = new StackDetector().detect(dir);
        DetectedStack kotlin = stacks.stream().filter(s -> s.language.equals("kotlin")).findFirst().orElse(null);
        assertTrue(kotlin != null && kotlin.tier == DetectedStack.Tier.GENERIC, "Kotlin is a deterministic stack");
        assertEquals(List.of("kotlin-generic"), kotlin.queryPacks());

        // Rule 28, round 4: grammar-backed.
        for (boolean grammar : new boolean[]{true, false}) {
            EdgeLedger ledger = new EdgeLedger();
            TreeSitterExtractor extractor = grammar ? new TreeSitterExtractor() : new TreeSitterExtractor().withoutGrammar("kotlin");
            assertTrue(extractor.supports("kotlin"));
            extractor.extract(dir, kotlin, ledger);
            assertTrue(has(ledger, "config", "env", "KAFKA_ADDR"), "grammar=" + grammar);
            assertTrue(section(ledger, "config").stream().anyMatch(e -> "KAFKA_TOPIC".equals(e.fields.get("env")) && "orders".equals(e.fields.get("envdefault"))));
            assertFalse(has(ledger, "config", "env", "IN_A_COMMENT"));
            assertTrue(has(ledger, "url", "value", "http://flagd:8013/ofrep"));
            assertTrue(has(ledger, "kafka-consume", "topic", "orders"));
            // …and the regex surface only when the grammar does not load, which the ledger says.
            assertEquals(!grammar, ledger.render().contains("thin regex surface"), "grammar=" + grammar);
        }
    }

    // ---- 2026-10-26, set C (yas, run-aspnetcore-microservices): rules 36–46 ----

    @Test
    void dottedHostsKeepTheirDomainAndOnlyRealDnsSuffixesAreCut(@TempDir Path dir) throws Exception {
        write(dir, "k8s/app.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: media
                  namespace: aics
                spec:
                  template:
                    spec:
                      containers:
                        - name: media
                          env:
                            - name: MONGO_HOST
                              value: core-service-mongo.aics.svc.cluster.local
                            - name: YAS_PUBLIC_URL
                              value: http://api.yas.local/media
                            - name: REDIS_HOST
                              value: redis.aics
                            - name: GITHUB_URL
                              value: https://api.github.com/v3
                """);
        write(dir, "src/docker-compose.yml", """
                services:
                  messagebroker:
                    image: rabbitmq
                    hostname: ecommerce-mq
                  catalog.api:
                    image: catalogapi
                    build:
                      context: .
                      dockerfile: Services/Catalog/Catalog.API/Dockerfile
                    environment:
                      - ConnectionStrings__Database=Server=catalogdb;Port=5432;Database=CatalogDb;User Id=postgres
                      - GrpcSettings__DiscountUrl=https://discount.grpc:8081
                      - MessageBroker__Host=amqp://ecommerce-mq:5672
                  backoffice:
                    build: ../backoffice-bff
                    image: yas-backoffice-bff
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        List<EdgeLedger.Edge> env = section(ledger, "env-address");
        assertTrue(env.stream().anyMatch(e -> "MONGO_HOST".equals(e.fields.get("name")) && "core-service-mongo".equals(e.fields.get("host"))),
                "a cluster suffix takes its namespace label with it");
        assertTrue(env.stream().anyMatch(e -> "YAS_PUBLIC_URL".equals(e.fields.get("name")) && "api.yas.local".equals(e.fields.get("host"))),
                "a reserved TLD is kept whole, never cut to `api`");
        assertTrue(env.stream().anyMatch(e -> "REDIS_HOST".equals(e.fields.get("name")) && "redis".equals(e.fields.get("host"))),
                "in a k8s manifest a two-label name is <svc>.<ns>");
        assertTrue(env.stream().anyMatch(e -> "GITHUB_URL".equals(e.fields.get("name")) && "api.github.com".equals(e.fields.get("host"))),
                "a domain is never cut to its first label");
        assertTrue(has(ledger, "k8s-namespace", "name", "aics"), "metadata.namespace is recorded");

        assertTrue(env.stream().anyMatch(e -> "GrpcSettings__DiscountUrl".equals(e.fields.get("name")) && "discount.grpc".equals(e.fields.get("host"))),
                "a dotted Compose host is kept whole");
        assertTrue(env.stream().anyMatch(e -> "ConnectionStrings__Database".equals(e.fields.get("name"))
                && "catalogdb".equals(e.fields.get("host")) && "db".equals(e.fields.get("kind"))), "a connection string is a database whatever the host is called");
        assertTrue(section(ledger, "workload-env").stream().anyMatch(e -> "MessageBroker__Host".equals(e.fields.get("name"))
                && "ecommerce-mq".equals(e.fields.get("host")) && "queue".equals(e.fields.get("kind"))), "amqp:// says broker");
        assertTrue(section(ledger, "compose-alias").stream().anyMatch(e -> "*".equals(e.fields.get("service"))
                && "ecommerce-mq".equals(e.fields.get("alias")) && "messagebroker".equals(e.fields.get("target"))), "hostname: is a global alias");
        assertTrue(section(ledger, "compose-service").stream().anyMatch(e -> "catalog.api".equals(e.fields.get("name"))
                && "src/Services/Catalog/Catalog.API".equals(e.fields.get("dockerfile_dir"))), "the Dockerfile's directory is the module");
        assertTrue(section(ledger, "compose-service").stream().anyMatch(e -> "backoffice".equals(e.fields.get("name"))
                && "backoffice-bff".equals(e.fields.get("context"))), "build: ./dir is the module, repo-relative");
    }

    @Test
    void yarpClustersAndDotnetSettingsAreReadFromAppSettings(@TempDir Path dir) throws Exception {
        write(dir, "src/ApiGateways/YarpApiGateway/appsettings.json", """
                {
                  "Logging": { "LogLevel": { "Default": "Information" } },
                  "ReverseProxy": {
                    "Routes": { "catalog-route": { "ClusterId": "catalog-cluster", "Match": { "Path": "/catalog-service/{**catch-all}" } } },
                    "Clusters": { "catalog-cluster": { "Destinations": { "destination1": { "Address": "http://catalog.api:8080" } } } }
                  }
                }
                """);
        write(dir, "src/Services/Basket/Basket.API/appsettings.json", """
                {
                  "ConnectionStrings": { "Database": "Server=localhost;Port=5433;Database=BasketDb", "Redis": "localhost:6379" },
                  "GrpcSettings": { "DiscountUrl": "https://localhost:5052" },
                  "AllowedHosts": "*"
                }
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        assertTrue(section(ledger, "url").stream().anyMatch(e -> "http://catalog.api:8080".equals(e.fields.get("value"))
                && "catalog-cluster".equals(e.fields.get("cluster"))), "a YARP destination is the gateway's upstream");
        assertTrue(has(ledger, "config", "key", "ConnectionStrings:Redis"));
        assertTrue(has(ledger, "config", "key", "GrpcSettings:DiscountUrl"));
        assertFalse(has(ledger, "config", "key", "AllowedHosts"));
        assertFalse(section(ledger, "config").stream().anyMatch(e -> String.valueOf(e.fields.get("key")).startsWith("Logging")));
        assertFalse(section(ledger, "config").stream().anyMatch(e -> String.valueOf(e.fields.get("key")).startsWith("ReverseProxy")),
                "the routing table is url rows, not config");
    }

    @Test
    void csharpIsReadWithItsGrammarAndItsConfigurationKeysBecomeEnvRows(@TempDir Path dir) throws Exception {
        write(dir, "src/Services/Basket/Basket.API/Dockerfile", "FROM mcr.microsoft.com/dotnet/aspnet:8.0\n");
        write(dir, "src/Services/Basket/Basket.API/Program.cs", """
                var builder = WebApplication.CreateBuilder(args);
                // builder.Configuration["IN_A_COMMENT"]
                builder.Services.AddMarten(opts => { opts.Connection(builder.Configuration.GetConnectionString("Database")!); });
                builder.Services.AddGrpcClient<DiscountProtoService.DiscountProtoServiceClient>(options =>
                {
                    options.Address = new Uri(builder.Configuration["GrpcSettings:DiscountUrl"]!);
                });
                var topic = Environment.GetEnvironmentVariable("KAFKA_TOPIC") ?? "orders";
                var skip = builder.Configuration.GetValue<bool>("Feature:Skip");
                """);
        write(dir, "src/Services/Basket/Basket.API/obj/Generated.cs", "var x = config[\"Generated:Key\"];\n");
        List<DetectedStack> stacks = new StackDetector().detect(dir);
        DetectedStack csharp = stacks.stream().filter(s -> s.language.equals("csharp")).findFirst().orElse(null);
        assertTrue(csharp != null && csharp.tier == DetectedStack.Tier.GENERIC, "C# is a deterministic stack, not an LLM stack");
        assertEquals(List.of("csharp-generic"), csharp.queryPacks());

        // Rule 40, round 4: grammar-backed; the thin surface keeps the same contract as the fallback.
        for (boolean grammar : new boolean[]{true, false}) {
        EdgeLedger ledger = new EdgeLedger();
        TreeSitterExtractor extractor = grammar ? new TreeSitterExtractor() : new TreeSitterExtractor().withoutGrammar("csharp");
        assertTrue(extractor.supports("csharp"));
        extractor.extract(dir, csharp, ledger);
        assertTrue(section(ledger, "config").stream().anyMatch(e -> "ConnectionStrings__Database".equals(e.fields.get("env"))
                && "ConnectionStrings:Database".equals(e.fields.get("dotnetkey")) && e.line == 3), "GetConnectionString is ConnectionStrings:X");
        assertTrue(section(ledger, "config").stream().anyMatch(e -> "GrpcSettings__DiscountUrl".equals(e.fields.get("env")) && e.line == 6),
                "an indexer key A:B is the env key A__B");
        assertTrue(section(ledger, "config").stream().anyMatch(e -> "KAFKA_TOPIC".equals(e.fields.get("env")) && "orders".equals(e.fields.get("envdefault"))));
        assertTrue(has(ledger, "config", "env", "Feature__Skip"));
        assertFalse(has(ledger, "config", "env", "IN_A_COMMENT"));
        assertFalse(has(ledger, "config", "env", "Generated__Key"), "obj/ is build output");
        assertEquals(!grammar, ledger.render().contains("thin regex surface"));
        }
    }

    @Test
    void jvmLibrariesTestTreesAndOpenApiUiKeysAreNotServiceEvidence(@TempDir Path dir) throws Exception {
        write(dir, "common-library/pom.xml", """
                <project><parent><artifactId>yas</artifactId></parent><artifactId>common-library</artifactId><packaging>jar</packaging>
                <dependencies><dependency><artifactId>spring-boot-starter-data-jpa</artifactId></dependency></dependencies></project>
                """);
        write(dir, "common-library/src/main/java/com/yas/common/Util.java", "package com.yas.common; public class Util {}\n");
        write(dir, "common-library/src/main/resources/application.properties",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://identity/realms/Yas\n");
        write(dir, "delivery/pom.xml", "<project><parent><artifactId>yas</artifactId></parent><artifactId>delivery</artifactId></project>\n");
        write(dir, "delivery/src/main/java/com/yas/delivery/DeliveryApplication.java",
                "package com.yas.delivery; @SpringBootApplication public class DeliveryApplication { public static void main(String[] a) {} }\n");
        write(dir, "delivery/src/main/resources/application.properties", """
                spring.application.name=delivery
                springdoc.oauthflow.authorization-url=http://identity/realms/Yas/protocol/openid-connect/auth
                springdoc.oauthflow.token-url=http://identity/realms/Yas/protocol/openid-connect/token
                """);
        write(dir, "payment/pom.xml", "<project><artifactId>payment</artifactId><build><plugins><plugin><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>\n");
        write(dir, "payment/src/main/resources/application.properties", "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://identity/realms/Yas\n");
        write(dir, "payment/src/it/resources/application.properties", "yas.public.url=http://storefront/complete-payment\n");
        write(dir, "automation-ui/pom.xml", "<project><artifactId>automation-ui</artifactId></project>\n");
        write(dir, "automation-ui/backoffice/pom.xml", "<project><artifactId>backoffice</artifactId></project>\n");
        write(dir, "automation-ui/backoffice/src/main/resources/application.properties", "app.url=http://storefront/\n");

        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);
        new ServiceRootScanner().scan(dir, ledger);

        Map<String, Map<String, String>> deps = section(ledger, "module-deps").stream()
                .collect(Collectors.toMap(e -> e.fields.get("dir"), e -> e.fields));
        assertEquals("true", deps.get("common-library").get("library"), "no main, no Boot plugin, no Dockerfile: a library");
        assertEquals("jvm", deps.get("common-library").get("library_kind"));
        assertEquals("common-library", deps.get("common-library").get("artifact"), "the module's own artifactId, not the parent's");
        assertFalse("true".equals(deps.get("delivery").get("library")), "a main class makes it an application");
        assertFalse("true".equals(deps.get("payment").get("library")), "the Boot plugin makes it an application");

        assertTrue(section(ledger, "config").stream().noneMatch(e -> e.file.startsWith("payment/src/it/")), "src/it is Failsafe's test tree");
        assertTrue(section(ledger, "config").stream().noneMatch(e -> e.file.startsWith("automation-ui/")), "an automation-ui suite is not a service's config");
        assertFalse(has(ledger, "service-root", "dir", "automation-ui/backoffice"), "a module under a test suite is never a service root");
        assertTrue(section(ledger, "config").stream().noneMatch(e -> String.valueOf(e.fields.get("key")).startsWith("springdoc.")),
                "Swagger UI's OAuth URLs are browser-side metadata, not calls");
        assertTrue(section(ledger, "config").stream().anyMatch(e -> e.file.startsWith("payment/src/main/")
                && "spring.security.oauth2.resourceserver.jwt.issuer-uri".equals(e.fields.get("key"))), "the issuer URI is still a dependency");
    }

    @Test
    void nginxSetVariablesAreResolvedOneLevel(@TempDir Path dir) throws Exception {
        write(dir, "nginx/templates/default.conf.template", """
                server {
                  server_name api.yas.local;
                  location /search/ {
                    #hack to prevent nginx to resolve container's host on start up
                    set $docker_search_host "search";
                    proxy_pass http://$docker_search_host;
                  }
                  location /es/ {
                    set $docker_elasticsearch_host elasticsearch;
                    proxy_pass http://$docker_elasticsearch_host:9200;
                  }
                  location /x/ {
                    proxy_pass http://$never_set_host;
                  }
                }
                """);
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);
        List<String> urls = section(ledger, "url").stream().map(e -> e.fields.get("value")).collect(Collectors.toList());
        assertTrue(urls.contains("http://search"), "a quoted set value fills the variable");
        assertTrue(urls.contains("http://elasticsearch:9200"), "a bare set value too, port kept");
        assertTrue(urls.contains("http://$never_set_host"), "a variable nothing sets stays a placeholder");
    }
}
