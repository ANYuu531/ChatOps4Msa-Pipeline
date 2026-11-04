package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.AddressShape;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.DetectedStack;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ServiceRootScanner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.TreeSitterExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conventions ③ and ④ applied to every format and grammar (2026-11-03). Each fixture is
 * the smallest form of a wrong edge the LLM edge audit of the 100-system corpus found and
 * the author confirmed (docs/generalization/mining/README.md, 邊審查第二版).
 */
class ConventionsTest {

    private static void write(Path dir, String relative, String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static DependencyGraph merge(Path dir, String language, String extension) {
        EdgeLedger ledger = new EdgeLedger();
        if (language != null) {
            new TreeSitterExtractor().extract(dir, new DetectedStack(language, null, DetectedStack.Tier.GENERIC, List.of(extension), "test"), ledger);
        }
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

    // ---- ③ a value is a workload's own only when chosen for it, or read by it ----

    @Test
    void aSharedEnvFileIsNotEveryServicesDependency(@TempDir Path dir) throws Exception {
        // uid4oe/microservices-go-grpc: one .env for every container, the databases included.
        write(dir, "docker-compose.yml", """
                services:
                  advice:
                    build: ./advice
                    env_file: .env
                  user:
                    build: ./user
                    env_file: .env
                  postgres:
                    image: postgres:16
                    env_file: .env
                  mongodb:
                    image: mongo:7
                    env_file: .env
                """);
        write(dir, ".env", """
                MONGO_CONN=mongodb://mongodb:27017
                POSTGRES_HOST=postgres:5432
                """);
        write(dir, "advice/Dockerfile", "FROM golang:1.22\n");
        write(dir, "advice/go.mod", "module advice\n");
        write(dir, "advice/db.go", "package main\nimport \"os\"\nvar host = os.Getenv(\"POSTGRES_HOST\")\n");
        write(dir, "user/Dockerfile", "FROM golang:1.22\n");
        write(dir, "user/go.mod", "module user\n");
        write(dir, "user/db.go", "package main\nimport \"os\"\nvar addr = os.Getenv(\"MONGO_CONN\")\n");
        DependencyGraph g = merge(dir, null, null);
        assertTrue(edge(g, "advice", "postgres"), "advice reads POSTGRES_HOST");
        assertTrue(edge(g, "user", "mongodb"), "user reads MONGO_CONN");
        assertFalse(edge(g, "advice", "mongodb"));
        assertFalse(edge(g, "user", "postgres"));
        assertFalse(edge(g, "postgres", "mongodb"), "a database container reads none of it");
        assertFalse(edge(g, "mongodb", "postgres"));
    }

    @Test
    void aWholesaleConfigMapKeyIsReadOrItIsNothing(@TempDir Path dir) throws Exception {
        // hoangtien2k3/ecommerce-microservices: one ConfigMap envFrom'd into 14 services.
        write(dir, "k8s/config.yaml", """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: shop-config
                data:
                  KAFKA_SERVERS: "kafka:9092"
                """);
        for (String svc : List.of("tax-service", "order-service")) {
            write(dir, "k8s/" + svc + ".yaml", """
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: %s
                    spec:
                      template:
                        spec:
                          containers:
                            - name: %s
                              image: shop/%s
                              envFrom:
                                - configMapRef:
                                    name: shop-config
                    """.formatted(svc, svc, svc));
            write(dir, svc + "/Dockerfile", "FROM eclipse-temurin:17\n");
            write(dir, svc + "/pom.xml", "<project><artifactId>" + svc + "</artifactId></project>\n");
        }
        write(dir, "k8s/kafka.yaml", """
                apiVersion: apps/v1
                kind: StatefulSet
                metadata:
                  name: kafka
                spec:
                  template:
                    spec:
                      containers:
                        - name: kafka
                          image: bitnami/kafka
                """);
        write(dir, "order-service/src/main/resources/application.yml", """
                spring:
                  kafka:
                    bootstrap-servers: ${KAFKA_SERVERS}
                """);
        DependencyGraph g = merge(dir, null, null);
        assertTrue(edge(g, "order-service", "kafka"), "order-service reads KAFKA_SERVERS");
        assertFalse(edge(g, "tax-service", "kafka"), "tax-service is handed it and never reads it");
    }

    // ---- ② one vocabulary for "this key holds an address", whatever the spelling ----

    @Test
    void everyConventionsKeyIsReadWithTheSameWords() {
        for (String key : List.of("DB_HOST", "spring.data.mongodb.host", "RabbitMqOptions:HostName", "RabbitMqOptions__HostName",
                "--zk-host", "rocketmq.name-server", "catalogueHost")) {
            assertTrue(AddressShape.keyKind(key) == AddressShape.KeyKind.HOST, key);
        }
        for (String key : List.of("spring.kafka.bootstrap-servers", "spring.cloud.nacos.discovery.server-addr", "spring.elasticsearch.uris")) {
            assertTrue(AddressShape.keyKind(key) != AddressShape.KeyKind.NONE, key);
        }
        assertTrue(AddressShape.keyKind("SEARCH_URL") == AddressShape.KeyKind.ADDRESS);
        assertTrue(AddressShape.keyKind("spring.zipkin.base-url") == AddressShape.KeyKind.ADDRESS);
        // Who may reach this component, or its own address, is no target.
        for (String key : List.of("Cors:AllowedOrigins:0", "AllowedHosts", "RedirectUris[0]", "site_url", "//use_this_locally")) {
            assertTrue(AddressShape.keyKind(key) == AddressShape.KeyKind.NONE, key);
        }
        assertFalse(AddressShape.isHostValue("true"), "prefer-ip-address: true is a flag");
        assertTrue(AddressShape.host("optional:vault://secret/app").isEmpty(), "a Vault secret path is no host");
        assertTrue(AddressShape.isReservedHost("xxx.servicebus.windows.net"), "a sample's placeholder");
        assertTrue("pulsar".equals(AddressShape.parse("external:pulsar://broker:6650").scheme()));
        assertTrue(AddressShape.host("http:${PROMETHEUS_ADDR}").isEmpty(), "a scheme with no // is an opaque URI, not host http");
        assertTrue("mongodb".equals(AddressShape.host("mongodb:27017")), "a host named like a scheme, with a port, is a host");
    }

    // ---- ① localhost:PORT on the developer's machine is whoever publishes PORT ----

    @Test
    void aDevConfigsLocalhostPortIsTheComposeServiceThatPublishesIt(@TempDir Path dir) throws Exception {
        // mehdihadeli/go-food-delivery-microservices: Compose runs only the infrastructure;
        // each Go service's config.development.json reaches it through published ports.
        write(dir, "deployments/docker-compose.yaml", """
                services:
                  postgres:
                    image: postgres:16
                    ports:
                      - "5432:5432"
                  rabbitmq:
                    image: rabbitmq:3-management
                    ports:
                      - 5672:5672
                      - 15672:15672
                """);
        write(dir, "internal/services/catalog/go.mod", "module catalog\n");
        write(dir, "internal/services/catalog/Dockerfile", "FROM golang:1.22\n");
        write(dir, "internal/services/catalog/config/config.development.json", """
                {
                  "gormOptions": { "host": "localhost", "port": 5432, "dbName": "catalogs" },
                  "rabbitmqOptions": { "rabbitmqHostOptions": { "hostName": "localhost", "port": 5672 } },
                  "cors": { "allowedOrigins": ["http://localhost:15672"] }
                }
                """);
        write(dir, "internal/services/catalog/config/config.test.json", """
                { "gormOptions": { "host": "localhost", "port": 15672 } }
                """);
        DependencyGraph g = merge(dir, null, null);
        assertTrue(edge(g, "catalog", "postgres"));
        assertTrue(edge(g, "catalog", "rabbitmq"));
        assertTrue(g.getEdges().stream().filter(e -> e.source.equals("catalog")).allMatch(e -> DependencyGraph.CONF_INFERRED.equals(e.confidence)),
                "read back through a port, never named: inferred");
    }

    @Test
    void insideAContainerLocalhostIsTheContainerItself(@TempDir Path dir) throws Exception {
        write(dir, "docker-compose.yml", """
                services:
                  app:
                    image: shop/app
                    environment:
                      DATABASE_URL: postgres://localhost:5432/app
                  postgres:
                    image: postgres:16
                    ports:
                      - "5432:5432"
                """);
        assertFalse(edge(merge(dir, null, null), "app", "postgres"));
    }

    // ---- ④ a URL literal is an address only at a use site ----

    @Test
    void csharpUrlsThatNothingCallsAreNotDependencies(@TempDir Path dir) throws Exception {
        // SuuCat, thangchung/clean-architecture-dotnet, clean-architecture-manga.
        write(dir, "src/Api/Dockerfile", "FROM mcr.microsoft.com/dotnet/aspnet:8.0\n");
        write(dir, "src/Api/Api.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"></Project>\n");
        write(dir, "src/Api/Errors.cs", """
                public class ApiExceptionFilter {
                    void Handle(Context context) {
                        var details = new ProblemDetails {
                            Type = "https://tools.ietf.org/html/rfc7231#section-6.5.1",
                            Title = "Bad request"
                        };
                        var claim = new Claim("website", "http://bob.com");
                        var license = new OpenApiLicense { Url = new Uri("https://raw.githubusercontent.com/x/LICENSE") };
                    }
                }
                """);
        write(dir, "src/Api/Client.cs", """
                public class CatalogClient {
                    async Task Get(HttpClient http) {
                        var r = await http.GetAsync("http://catalog/api/items");
                    }
                }
                """);
        DependencyGraph g = merge(dir, "csharp", ".cs");
        assertFalse(node(g, "tools.ietf.org"));
        assertFalse(node(g, "bob.com"));
        assertFalse(node(g, "raw.githubusercontent.com"));
        assertTrue(g.getEdges().stream().anyMatch(e -> e.target.equals("catalog")), "a literal a client call uses is still an edge");
    }

    @Test
    void aJavaUriThatNamesAProblemTypeIsNotACallButAConstantACallUsesIs(@TempDir Path dir) throws Exception {
        // meysamhadeli/booking-microservices-java-spring-boot; ecommerce-microservice-backend-app.
        write(dir, "booking/Dockerfile", "FROM eclipse-temurin:17\n");
        write(dir, "booking/pom.xml", "<project><artifactId>booking</artifactId></project>\n");
        write(dir, "booking/src/main/java/app/EntryPoint.java", """
                package app;
                public class EntryPoint {
                    void commence(ProblemDetail problem) {
                        problem.setType(URI.create("https://problems/unauthorized"));
                    }
                }
                """);
        write(dir, "booking/src/main/java/app/AppConstant.java", """
                package app;
                public class AppConstant {
                    public static final String FLIGHT_API = "http://flight/api/flights";
                }
                """);
        write(dir, "booking/src/main/java/app/FlightClient.java", """
                package app;
                public class FlightClient {
                    Object get(RestTemplate restTemplate, String id) {
                        return restTemplate.getForObject(AppConstant
                                .FLIGHT_API + "/" + id, Object.class);
                    }
                }
                """);
        DependencyGraph g = merge(dir, "java", ".java");
        assertFalse(node(g, "problems"));
        assertTrue(edge(g, "booking", "flight"), "the constant reaches getForObject");
    }

    @Test
    void aSpringConfigurationPropertiesAccessorAtACallSiteIsTheUseOfTheEnvValue(@TempDir Path dir) throws Exception {
        // nashtech-garage/yas: YAS_SERVICES_PRODUCT -> yas.services.product -> record field ->
        // serviceUrlConfig.product() inside fromUriString(...). The binding record lives in a
        // shared library module. storefront binds the whole map and calls nothing.
        write(dir, "docker-compose.yml", """
                services:
                  cart:
                    build: ./cart
                    environment:
                      - YAS_SERVICES_PRODUCT=http://product/product
                      - YAS_SERVICES_MEDIA=http://media/media
                  storefront:
                    build: ./storefront
                    environment:
                      - YAS_SERVICES_CART=http://cart/cart
                  product:
                    build: ./product
                  media:
                    build: ./media
                """);
        for (String m : List.of("cart", "storefront", "product", "media")) {
            write(dir, m + "/Dockerfile", "FROM eclipse-temurin:17\n");
            write(dir, m + "/pom.xml", "<project><artifactId>" + m + "</artifactId></project>\n");
        }
        write(dir, "common-library/pom.xml", "<project><artifactId>common-library</artifactId><packaging>jar</packaging></project>\n");
        write(dir, "common-library/src/main/java/com/yas/commonlibrary/config/ServiceUrlConfig.java", """
                package com.yas.commonlibrary.config;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                @ConfigurationProperties(prefix = "yas.services")
                public record ServiceUrlConfig(String media, String product) {
                }
                """);
        write(dir, "cart/src/main/java/com/yas/cart/service/ProductService.java", """
                package com.yas.cart.service;
                import com.yas.commonlibrary.config.ServiceUrlConfig;
                public class ProductService {
                    private final RestClient restClient;
                    private final ServiceUrlConfig serviceUrlConfig;
                    public Object getProducts(List<Long> ids) {
                        final URI url = UriComponentsBuilder
                            .fromUriString(serviceUrlConfig.product())
                            .path("/storefront/products/list-featured")
                            .build().toUri();
                        return restClient.get().uri(url).retrieve().body(Object.class);
                    }
                }
                """);
        write(dir, "storefront/src/main/java/com/yas/storefrontbff/config/StoreConfig.java", """
                package com.yas.storefrontbff.config;
                import java.util.Map;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                @ConfigurationProperties(prefix = "yas")
                public record StoreConfig(Map<String, String> services) {
                }
                """);
        DependencyGraph g = merge(dir, "java", ".java");
        DependencyGraph.Edge product = g.getEdges().stream().filter(e -> e.source.equals("cart") && e.target.equals("product")).findFirst().orElse(null);
        assertTrue(product != null && DependencyGraph.CONF_DOCUMENTED.equals(product.confidence), "the accessor at the call site is the use: documented");
        assertTrue(product.provenanceRefs.stream().anyMatch(r -> r.contains("ProductService.java")), "the provenance is the call site, not the manifest");
        assertFalse(edge(g, "cart", "media"), "handed, bound, never used by cart's code: the code decides, and it says nothing");
    }

    @Test
    void anImagesCommandLineIsAUseSiteOfTheEnvItInterpolates(@TempDir Path dir) throws Exception {
        // Online Boutique / Bank of Anthos: locust --host="http://${FRONTEND_ADDR}" in the
        // Dockerfile's ENTRYPOINT; the Python reads the address nowhere.
        write(dir, "kubernetes-manifests/loadgenerator.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: loadgenerator
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: shop/loadgenerator
                          env:
                            - name: FRONTEND_ADDR
                              value: "frontend:80"
                """);
        write(dir, "kubernetes-manifests/frontend.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: frontend
                spec:
                  template:
                    spec:
                      containers:
                        - name: server
                          image: shop/frontend
                """);
        write(dir, "src/loadgenerator/requirements.txt", "locust\n");
        write(dir, "src/loadgenerator/locustfile.py", "from locust import HttpUser\nclass U(HttpUser):\n    pass\n");
        write(dir, "src/loadgenerator/Dockerfile", """
                FROM python:3.12-slim
                COPY . .
                ENTRYPOINT locust --host="http://${FRONTEND_ADDR}" --headless -u "${USERS:-10}" 2>&1
                """);
        write(dir, "src/frontend/go.mod", "module frontend\n");
        write(dir, "src/frontend/Dockerfile", "FROM golang:1.22\n");
        DependencyGraph g = merge(dir, "python", ".py");
        DependencyGraph.Edge e = g.getEdges().stream().filter(x -> x.source.equals("loadgenerator") && x.target.equals("frontend")).findFirst().orElse(null);
        assertTrue(e != null && e.provenanceRefs.stream().anyMatch(r -> r.contains("Dockerfile")), "the ENTRYPOINT is where the address is used");
    }

    @Test
    void aBaseImagesStartScriptRunsInEveryImageBuiltFromIt(@TempDir Path dir) throws Exception {
        // TeaStore: tools/build_docker.sh builds utilities/dockerbase into teastore-base; every
        // service's Dockerfile is FROM it; its start.sh writes the registry URL from
        // REGISTRY_HOST into Tomcat's context.xml; the Java reads it through JNDI.
        write(dir, "tools/build_docker.sh", """
                #!/bin/bash
                docker buildx build -t "${registry}shop-base" ../utilities/dockerbase/ --load
                """);
        write(dir, "utilities/dockerbase/Dockerfile", """
                FROM tomcat:10-jdk17
                COPY start.sh /usr/local/tomcat/bin/start.sh
                CMD /usr/local/tomcat/bin/start.sh && catalina.sh run
                """);
        write(dir, "utilities/dockerbase/start.sh", """
                #!/bin/bash
                sed -i "s|<Environment name=\\"registryURL\\" value=.*|<Environment name=\\"registryURL\\" value=\\"http://${REGISTRY_HOST}:${REGISTRY_PORT}/registry/rest/services/\\"|g" /usr/local/tomcat/conf/context.xml
                """);
        for (String s : List.of("webui", "auth")) {
            write(dir, "services/" + s + "/Dockerfile", "FROM myorg/shop-base:latest\nCOPY target/" + s + ".war /usr/local/tomcat/webapps/\n");
            write(dir, "services/" + s + "/pom.xml", "<project><artifactId>" + s + "</artifactId></project>\n");
            write(dir, "kubernetes/" + s + ".yaml", """
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: %s
                    spec:
                      template:
                        spec:
                          containers:
                            - name: %s
                              image: myorg/shop-%s
                              env:
                                - name: REGISTRY_HOST
                                  value: "registry"
                                - name: REGISTRY_PORT
                                  value: "8080"
                    """.formatted(s, s, s));
        }
        write(dir, "services/registry/pom.xml", "<project><artifactId>registry</artifactId></project>\n");
        write(dir, "services/registry/Dockerfile", "FROM myorg/shop-base:latest\n");
        write(dir, "kubernetes/registry.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: registry
                spec:
                  template:
                    spec:
                      containers:
                        - name: registry
                          image: myorg/shop-registry
                """);
        DependencyGraph g = merge(dir, null, null);
        for (String s : List.of("webui", "auth")) {
            DependencyGraph.Edge e = g.getEdges().stream().filter(x -> x.source.equals(s) && x.target.equals("registry")).findFirst().orElse(null);
            assertTrue(e != null && e.provenanceRefs.stream().anyMatch(r -> r.contains("start.sh")), s + " inherits the base image's start script");
        }
        assertFalse(edge(g, "registry", "registry"));
    }

    @Test
    void aPythonHostLiteralUnderAHostNameThatReachesAClientIsAnEdge(@TempDir Path dir) throws Exception {
        // AICS mail-service: smtp_server = 'smtp.gmail.com' handed to smtplib.SMTP two lines on.
        write(dir, "mail/Dockerfile", "FROM python:3.12\n");
        write(dir, "mail/requirements.txt", "fastapi\n");
        write(dir, "mail/app/mail.py", """
                import smtplib
                def connect(account, password):
                    smtp_server = 'smtp.gmail.com'
                    smtp_server_port = 587
                    server = smtplib.SMTP(smtp_server, smtp_server_port)
                    return server
                release = 'v1.2.3'
                docs_host = 'docs.python.org'
                """);
        DependencyGraph g = merge(dir, "python", ".py");
        assertTrue(edge(g, "mail", "smtp.gmail.com"), "the name says host, the value is a domain, smtplib.SMTP uses it");
        assertFalse(node(g, "v1.2.3"), "a dotted value under a name that is not host-shaped");
        assertFalse(node(g, "docs.python.org"), "a host nothing calls is declared, not used");
    }

    // ---- depends_on is start-up order; a link is reachability ----

    @Test
    void dependsOnIsStartupOrderAndALinkIsReachability(@TempDir Path dir) throws Exception {
        // skywalking-showcase: vm1 (node-exporter) waits for oap and never connects to it.
        // The Compose v1 era: a link is how a container reaches another by name at all.
        write(dir, "docker-compose.yml", """
                services:
                  oap:
                    image: apache/skywalking-oap-server
                  vm1:
                    image: prom/node-exporter
                    depends_on:
                      oap:
                        condition: service_healthy
                  shop-ui:
                    image: shop/ui
                    links:
                      - oap
                """);
        DependencyGraph g = merge(dir, null, null);
        DependencyGraph.Edge vm = g.getEdges().stream().filter(e -> e.source.equals("vm1") && e.target.equals("oap")).findFirst().orElse(null);
        assertTrue(vm != null && DependencyGraph.TYPE_STARTUP.equals(vm.type), "start-up order is kept as such, not as a call");
        DependencyGraph.Edge web = g.getEdges().stream().filter(e -> e.source.equals("shop-ui") && e.target.equals("oap")).findFirst().orElse(null);
        assertTrue(web != null && !DependencyGraph.TYPE_STARTUP.equals(web.type),
                "a link is how a v1 container reaches another by name: a call, whatever runs in it");
    }
}
