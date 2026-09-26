package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.DetectedStack;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ServiceRootScanner;
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
}
