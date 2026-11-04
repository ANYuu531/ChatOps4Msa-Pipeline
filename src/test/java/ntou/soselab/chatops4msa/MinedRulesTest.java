package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ServiceRootScanner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules 57–61 (2026-11-03): the shapes the residue mining of 100 corpus systems ranked
 * highest and the author verified (docs/generalization/mining/). Each fixture is the
 * smallest repo that shows the shape.
 */
class MinedRulesTest {

    private static void write(Path dir, String relative, String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static DependencyGraph merge(Path dir) {
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);
        new ServiceRootScanner().scan(dir, ledger);
        DependencyGraph g = new DependencyGraph("");
        CodeGraphMerger.merge(g, ledger.toJson().toString(), "owner/repo");
        return g;
    }

    private static boolean edge(DependencyGraph g, String from, String to) {
        return g.getEdges().stream().anyMatch(e -> e.source.equals(from) && e.target.equals(to));
    }

    private static boolean node(DependencyGraph g, String id) {
        return g.getNodes().stream().anyMatch(n -> n.id.equals(id));
    }

    // ---- rule 57: every document of a Spring YAML file ----

    @Test
    void everyProfileDocumentOfAnApplicationYmlIsRead(@TempDir Path dir) throws Exception {
        write(dir, "docker-compose.yml", """
                services:
                  order-service:
                    build: ./order-service
                  order-db:
                    image: mysql:8
                """);
        write(dir, "order-service/Dockerfile", "FROM eclipse-temurin:17\n");
        write(dir, "order-service/pom.xml", "<project><artifactId>order-service</artifactId></project>\n");
        write(dir, "order-service/src/main/resources/application.yml", """
                spring:
                  profiles: local
                  datasource:
                    url: jdbc:mysql://localhost:3306/orders
                ---
                spring:
                  profiles: docker
                  datasource:
                    url: jdbc:mysql://order-db:3306/orders
                ---
                spring:
                  profiles: test
                  datasource:
                    url: jdbc:mysql://test-db:3306/orders
                """);
        DependencyGraph g = merge(dir);
        assertTrue(edge(g, "order-service", "order-db"), "the docker profile's datasource is read");
        assertFalse(node(g, "test-db"), "a test profile is not the deployment");
    }

    @Test
    void aTemplateConfigIsNotTheRepositorysConfig(@TempDir Path dir) throws Exception {
        write(dir, "docker-compose.yml", """
                services:
                  cli:
                    build: ./cli
                  mysql:
                    image: mysql:8
                """);
        write(dir, "cli/Dockerfile", "FROM eclipse-temurin:17\n");
        write(dir, "cli/src/main/resources/infra-prod-template.yml", """
                spring:
                  datasource:
                    url: jdbc:mysql://mysql:3306/app
                """);
        assertFalse(edge(merge(dir), "cli", "mysql"));
    }

    // ---- rule 58: .NET Options keys ----

    @Test
    void dotnetOptionsKeysAndConnectionStringsAnywhereNameTheirHost(@TempDir Path dir) throws Exception {
        write(dir, "docker-compose.yml", """
                services:
                  identity:
                    build: ./src/Identity
                  postgres:
                    image: postgres:16
                  rabbitmq:
                    image: rabbitmq:3
                """);
        write(dir, "src/Identity/Dockerfile", "FROM mcr.microsoft.com/dotnet/aspnet:8.0\n");
        write(dir, "src/Identity/Identity.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"></Project>\n");
        write(dir, "src/Identity/appsettings.docker.json", """
                {
                  "PostgresOptions": { "ConnectionString": "Server=postgres;Port=5432;Database=identity;User Id=postgres" },
                  "RabbitMqOptions": { "HostName": "rabbitmq", "Port": 5672 }
                }
                """);
        DependencyGraph g = merge(dir);
        assertTrue(edge(g, "identity", "postgres"));
        assertTrue(edge(g, "identity", "rabbitmq"));
    }

    // ---- rule 59: a Service's selector names its workload ----

    @Test
    void aServiceSelectingOneWorkloadIsThatWorkload(@TempDir Path dir) throws Exception {
        write(dir, "products/Dockerfile", "FROM eclipse-temurin:17\n");
        write(dir, "products/pom.xml", "<project><artifactId>products</artifactId></project>\n");
        write(dir, "gateway/Dockerfile", "FROM eclipse-temurin:17\n");
        write(dir, "gateway/pom.xml", "<project><artifactId>gateway</artifactId></project>\n");
        write(dir, "k8s/products.yml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: products
                  labels:
                    app: products-pod
                spec:
                  selector:
                    matchLabels:
                      app: products-pod
                  template:
                    metadata:
                      labels:
                        app: products-pod
                    spec:
                      containers:
                        - name: products
                          image: shop/products
                ---
                apiVersion: v1
                kind: Service
                metadata:
                  name: products-svc
                spec:
                  selector:
                    app: products-pod
                  ports:
                    - port: 80
                """);
        write(dir, "k8s/gateway.yml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: gateway
                  labels:
                    app: gateway
                spec:
                  template:
                    metadata:
                      labels:
                        app: gateway
                    spec:
                      containers:
                        - name: gateway
                          image: shop/gateway
                          env:
                            - name: PRODUCTS_URL
                              value: http://products-svc:80
                """);
        DependencyGraph g = merge(dir);
        assertTrue(edge(g, "gateway", "products"), "the call to the Service lands on the workload with the code");
        assertFalse(node(g, "products-svc"));
    }

    // ---- rule 60: a mounted config file is its container's config ----

    @Test
    void aMountedScrapeConfigIsThePrometheusContainers(@TempDir Path dir) throws Exception {
        write(dir, "docker-compose.yml", """
                services:
                  prometheus:
                    image: prom/prometheus
                    volumes:
                      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml
                  orders:
                    image: shop/orders
                """);
        write(dir, "monitoring/prometheus.yml", """
                scrape_configs:
                  - job_name: orders
                    static_configs:
                      - targets: ['orders:8080']
                  - job_name: elsewhere
                    static_configs:
                      - targets: ['metrics.example.org:443']
                """);
        write(dir, "unmounted/prometheus.yml", """
                scrape_configs:
                  - job_name: orders
                    static_configs:
                      - targets: ['orders:8080']
                """);
        DependencyGraph g = merge(dir);
        assertTrue(edge(g, "prometheus", "orders"));
        assertFalse(node(g, "metrics.example.org"), "only declared names, never an external guess");
        assertFalse(node(g, "unmounted"), "a file nobody mounts has no owner");
    }

    // ---- rule 61: Ocelot's route table ----

    @Test
    void ocelotRoutesAreTheGatewaysUpstreams(@TempDir Path dir) throws Exception {
        write(dir, "docker-compose.yml", """
                services:
                  apigateway:
                    build: ./src/ApiGateway
                  cart:
                    image: shop/cart
                """);
        write(dir, "src/ApiGateway/Dockerfile", "FROM mcr.microsoft.com/dotnet/aspnet:8.0\n");
        write(dir, "src/ApiGateway/ApiGateway.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"></Project>\n");
        write(dir, "src/ApiGateway/ocelot.json", """
                {
                  "Routes": [
                    { "DownstreamPathTemplate": "/api/cart", "DownstreamScheme": "http",
                      "DownstreamHostAndPorts": [ { "Host": "cart", "Port": 80 } ],
                      "UpstreamPathTemplate": "/cart" }
                  ]
                }
                """);
        assertTrue(edge(merge(dir), "apigateway", "cart"));
    }
}
