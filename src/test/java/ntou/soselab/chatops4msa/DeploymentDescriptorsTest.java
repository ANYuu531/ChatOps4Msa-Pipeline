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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 4, part B (2026-10-27): deployment descriptors the static layer could not read or
 * misread on hold-out D — Helm charts, image names, Compose network aliases, command lines,
 * namespaces, multi-address values, scheme chains, ingress routing.
 */
class DeploymentDescriptorsTest {

    private static void write(Path dir, String relative, String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static EdgeLedger config(Path dir) {
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);
        new ServiceRootScanner().scan(dir, ledger);
        return ledger;
    }

    private static boolean row(EdgeLedger ledger, String section, Map<String, String> fields) {
        return ledger.getEdges().stream().filter(e -> e.section.equals(section)).anyMatch(e -> fields.entrySet().stream()
                .allMatch(f -> f.getValue().equals(e.fields.get(f.getKey()))));
    }

    private static DependencyGraph merge(EdgeLedger ledger) {
        DependencyGraph g = new DependencyGraph("");
        CodeGraphMerger.merge(g, ledger.toJson().toString(), "owner/repo");
        return g;
    }

    private static boolean edge(DependencyGraph g, String from, String to) {
        return g.getEdges().stream().anyMatch(e -> e.source.equals(from) && e.target.equals(to));
    }

    // ---- B1 / rule 52: Helm charts are rendered before they are read ----

    @Test
    void helmChartsAreRenderedWithTheirDefaultsAndSubcharts(@TempDir Path dir) throws Exception {
        write(dir, "charts/backend/Chart.yaml", "apiVersion: v2\nname: backend\nversion: 0.1.0\n");
        write(dir, "charts/backend/values.yaml", "nameOverride: \"\"\nfullnameOverride: \"\"\nenv: []\nextraAnnotations: {}\n");
        write(dir, "charts/backend/templates/_helpers.tpl", """
                {{- define "backend.fullname" -}}
                {{- if .Values.fullnameOverride }}
                {{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
                {{- else }}
                {{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" }}
                {{- end }}
                {{- end }}
                """);
        write(dir, "charts/backend/templates/deployment.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: {{ include "backend.fullname" . }}
                  {{- if .Values.extraAnnotations }}
                  annotations:
                    {{- toYaml .Values.extraAnnotations | nindent 4 }}
                  {{- end }}
                spec:
                  template:
                    metadata:
                      labels:
                        app: {{ include "backend.fullname" . }}
                    spec:
                      containers:
                        - name: app
                          env:
                          {{- range .Values.env }}
                            - name: {{ .name }}
                              value: {{ .value | quote }}
                          {{- end }}
                          {{- if .Values.tracing.enabled }}
                            - name: OTEL_EXPORTER_OTLP_ENDPOINT
                              value: http://otel-collector:4317
                          {{- end }}
                """);
        write(dir, "charts/cart/Chart.yaml", """
                apiVersion: v2
                name: cart
                version: 0.2.0
                dependencies:
                  - name: backend
                    version: 0.1.0
                    repository: file://../backend
                """);
        write(dir, "charts/cart/values.yaml", """
                backend:
                  fullnameOverride: cart
                  tracing:
                    enabled: false
                  env:
                    - name: SPRING_DATASOURCE_URL
                      value: jdbc:postgresql://postgres:5432/cart
                """);
        write(dir, "charts/broken/Chart.yaml", "apiVersion: v2\nname: broken\nversion: 0.1.0\n");
        write(dir, "charts/broken/templates/x.yaml", "kind: Service\nmetadata:\n  name: {{ .Values.name\n");
        write(dir, "cart/pom.xml", "<project><artifactId>cart</artifactId></project>\n");

        EdgeLedger ledger = config(dir);
        assertTrue(row(ledger, "k8s-workload", Map.of("name", "cart", "helm", "backend")),
                "the shared chart renders as its parent's dependency, with the parent's values");
        assertFalse(ledger.getEdges().stream().anyMatch(e -> e.section.equals("k8s-workload") && e.fields.get("name").contains("backend")),
                "a chart that is only another chart's file dependency is not rendered on its own; a release-named workload is unknown");
        assertTrue(row(ledger, "workload-env", Map.of("workload", "cart", "host", "postgres")), "range over the default env list");
        assertTrue(row(ledger, "workload-env", Map.of("workload", "cart", "host", "otel-collector")),
                "a feature switched off by default is still part of what the chart deploys");
        assertTrue(ledger.render().contains("unreadable descriptor charts/broken/templates/x.yaml"), "never swallowed");
        // The chart is the only deployment description here, so it is the vocabulary.
        DependencyGraph g = merge(ledger);
        assertTrue(edge(g, "cart", "postgres"));
    }

    @Test
    void aRenderedChartBesideComposeOnlyWiresTheComposeNames(@TempDir Path dir) throws Exception {
        write(dir, "docker-compose.yml", """
                services:
                  cart:
                    build: ./cart
                  postgres:
                    image: postgres:16
                """);
        write(dir, "cart/pom.xml", "<project><artifactId>cart</artifactId></project>\n");
        write(dir, "k8s/cart/Chart.yaml", "apiVersion: v2\nname: cart\nversion: 0.1.0\n");
        write(dir, "k8s/cart/values.yaml", "db: postgresql.postgres\n");
        write(dir, "k8s/cart/templates/deployment.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: cart
                spec:
                  template:
                    spec:
                      containers:
                        - name: cart
                          env:
                            - name: SPRING_DATASOURCE_URL
                              value: jdbc:postgresql://{{ .Values.db }}:5432/cart
                """);
        DependencyGraph g = merge(config(dir));
        assertFalse(g.getEdges().stream().anyMatch(e -> e.target.equals("postgresql")),
                "the k8s variant's own database name is not a node of the Compose deployment's graph");
        List<String> nodes = g.getNodes().stream().map(n -> n.id).toList();
        assertTrue(nodes.contains("cart") && nodes.contains("postgres"), nodes.toString());
    }

    // ---- B2 / rule 44: an image name maps a Compose service to its module ----

    @Test
    void anImageNameMapsAComposeServiceToItsModule(@TempDir Path dir) throws Exception {
        write(dir, "deploy/docker-compose.yaml", """
                services:
                  gateway:
                    image: ${HUB}/gateway-service:${TAG}
                  songs:
                    image: ${HUB}/songs-service:${TAG}
                """);
        write(dir, "services/gateway-service/build.gradle", "plugins { id 'org.springframework.boot' }\n");
        write(dir, "services/gateway-service/Dockerfile", "FROM eclipse-temurin\n");
        write(dir, "services/gateway-service/src/main/resources/application.yaml", "routes:\n  songs:\n    uri: http://songs\n");
        write(dir, "services/songs-service/build.gradle", "plugins { id 'org.springframework.boot' }\n");
        write(dir, "services/songs-service/Dockerfile", "FROM eclipse-temurin\n");
        DependencyGraph g = merge(config(dir));
        assertTrue(edge(g, "gateway", "songs"), g.getEdges().toString());
        assertFalse(g.getNodes().stream().anyMatch(n -> n.id.equals("gateway-service")), "the module takes its deployment name");
    }

    // ---- B3 / rule 25: Compose network aliases, scoped to their network ----

    @Test
    void networkAliasesHoldOnTheirNetworkOnly(@TempDir Path dir) throws Exception {
        write(dir, "docker-compose.yaml", """
                services:
                  otel-demo-frontend:
                    image: demo/frontend
                    networks:
                      demo:
                        aliases: [ frontend ]
                  otel-demo-proxy:
                    image: demo/proxy
                    networks: [ demo ]
                    environment:
                      FRONTEND_ADDR: frontend:8080
                  frontend:
                    image: apache/apisix
                  loadgen:
                    image: demo/loadgen
                    environment:
                      TARGET_URL: http://frontend:80
                networks:
                  demo: {}
                """);
        DependencyGraph g = merge(config(dir));
        assertTrue(edge(g, "otel-demo-proxy", "otel-demo-frontend"), g.getEdges().toString());
        assertTrue(edge(g, "loadgen", "frontend"), "a reader off the network keeps the plain name");
        assertFalse(edge(g, "otel-demo-proxy", "frontend"));
    }

    // ---- B4 / rule 53: command lines are wiring; depends_on not backed by them is not drawn ----

    @Test
    void commandLineAddressesAreWiring(@TempDir Path dir) throws Exception {
        write(dir, "docker-compose.yaml", """
                services:
                  mysql:
                    image: mysql:8
                    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci
                  otel-collector:
                    image: otel/collector
                  mysql-load:
                    image: mysql:8
                    depends_on: [ otel-collector ]
                    entrypoint: bash
                    command:
                      - -c
                      - "mysql -h mysql -uroot -proot -e 'select 1'"
                  flink-job:
                    image: flink
                    command: ./bin/flink run -m jobmanager:8081 job.jar
                  jobmanager:
                    image: flink
                """);
        DependencyGraph g = merge(config(dir));
        assertTrue(edge(g, "mysql-load", "mysql"));
        assertFalse(edge(g, "mysql-load", "otel-collector"), "start-up order, not a call, once the command names the target");
        assertTrue(edge(g, "flink-job", "jobmanager"));
        assertFalse(g.getNodes().stream().anyMatch(n -> n.id.contains("utf8mb4")), "a bare word under a host-named flag must be a declared name");
    }

    // ---- B5 / rule 54: namespaces from the repo's apply commands; ambiguous names qualified ----

    @Test
    void theSameServiceNameInTwoNamespacesIsTwoNodes(@TempDir Path dir) throws Exception {
        write(dir, "deploy.sh", """
                kubectl apply -f agency.yaml -n travel-agency
                kubectl apply -f portal.yaml -n travel-portal
                """);
        write(dir, "agency.yaml", """
                apiVersion: v1
                kind: Service
                metadata:
                  name: travels
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: travels-v1
                spec:
                  template:
                    spec:
                      containers:
                        - name: travels
                          env:
                            - name: CARS_SERVICE
                              value: http://cars.travel-agency:8000
                ---
                apiVersion: v1
                kind: Service
                metadata:
                  name: cars
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: cars
                """);
        write(dir, "portal.yaml", """
                apiVersion: v1
                kind: Service
                metadata:
                  name: travels
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: travels
                spec:
                  template:
                    spec:
                      containers:
                        - name: portal
                          env:
                            - name: TRAVELS_AGENCY_SERVICE
                              value: http://travels.travel-agency:8000
                """);
        write(dir, "cars/go.mod", "module example.com/cars\n");
        EdgeLedger ledger = config(dir);
        assertTrue(row(ledger, "k8s-namespace", Map.of("name", "travel-agency")));
        DependencyGraph g = merge(ledger);
        assertTrue(edge(g, "travels.travel-portal", "travels.travel-agency"), g.getEdges().toString());
        assertTrue(edge(g, "travels.travel-agency", "cars"), "a name that is not ambiguous stays plain");
    }

    // ---- B6 / rule 55: a value listing several services; B7: non-hosts and scheme chains ----

    @Test
    void listsSchemeChainsAndNonHostValues(@TempDir Path dir) throws Exception {
        write(dir, "docker-compose.yaml", """
                services:
                  control:
                    image: demo/control
                    environment:
                      PORTAL_SERVICES: "voyages.fr;http://voyages:8000,viaggi.it;http://viaggi:8000"
                      NACOS: nacos-0:8848,nacos-1:8848
                  voyages:
                    image: demo/portal
                  viaggi:
                    image: demo/portal
                  nacos-0:
                    image: nacos
                  nacos-1:
                    image: nacos
                  app:
                    image: demo/app
                    environment:
                      SW_AGENT_NAME_SERVER: agent::app
                  broker:
                    image: pulsar
                    environment:
                      metadataStoreUrl: zk:zookeeper:2181
                  zookeeper:
                    image: zookeeper
                """);
        DependencyGraph g = merge(config(dir));
        assertTrue(edge(g, "control", "voyages") && edge(g, "control", "viaggi"), g.getEdges().toString());
        assertTrue(edge(g, "control", "nacos-0") && !edge(g, "control", "nacos-1"), "replicas of one cluster are one target (rule 27)");
        assertFalse(g.getNodes().stream().anyMatch(n -> n.id.equals("agent")), "agent::app is a service name, not an address");
        assertTrue(edge(g, "broker", "zookeeper") && !g.getNodes().stream().anyMatch(n -> n.id.equals("zk")));
    }

    // ---- B8 / rule 56: ingress routing ----

    @Test
    void istioIngressRoutesAndApisixUpstreams(@TempDir Path dir) throws Exception {
        write(dir, "k8s/app.yaml", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: frontend
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: cart
                """);
        write(dir, "frontend/package.json", "{ \"name\": \"frontend\" }\n");
        write(dir, "cart/package.json", "{ \"name\": \"cart\" }\n");
        write(dir, "istio/gateway.yaml", """
                apiVersion: networking.istio.io/v1alpha3
                kind: Gateway
                metadata:
                  name: frontend-gateway
                spec:
                  selector:
                    istio: ingressgateway
                ---
                apiVersion: networking.istio.io/v1alpha3
                kind: VirtualService
                metadata:
                  name: frontend-ingress
                spec:
                  hosts: ["*"]
                  gateways: [ frontend-gateway ]
                  http:
                    - route:
                        - destination:
                            host: frontend
                ---
                apiVersion: networking.istio.io/v1alpha3
                kind: VirtualService
                metadata:
                  name: cart-canary
                spec:
                  hosts: [ cart ]
                  http:
                    - route:
                        - destination:
                            host: cart
                """);
        DependencyGraph g = merge(config(dir));
        assertTrue(edge(g, "istio-ingressgateway", "frontend"), g.getEdges().toString());
        assertFalse(g.getEdges().stream().anyMatch(e -> e.target.equals("cart")), "a mesh-only VirtualService is traffic shaping");

        Path d2 = dir.resolve("second");
        write(d2, "deploy/docker-compose.yaml", """
                services:
                  frontend:
                    image: apache/apisix
                    volumes:
                      - ./config/apisix/apisix.yaml:/usr/local/apisix/conf/apisix.yaml
                  app:
                    image: demo/app
                """);
        write(d2, "deploy/config/apisix/apisix.yaml", """
                routes:
                  - uri: /*
                    upstream_id: 1
                upstreams:
                  - id: 1
                    nodes:
                      "app:80": 1
                #END
                """);
        DependencyGraph g2 = merge(config(d2));
        assertTrue(edge(g2, "frontend", "app"), g2.getEdges().toString());
    }
}
