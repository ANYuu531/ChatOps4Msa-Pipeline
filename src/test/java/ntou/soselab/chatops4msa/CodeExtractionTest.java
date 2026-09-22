package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for the deterministic config extraction — no Spring context.
 */
public class CodeExtractionTest {

    @Test
    void configExtractorReadsComposeDependsOn(@TempDir Path dir) throws Exception {
        // Both depends_on forms: the short list and the long map (with a condition).
        Files.writeString(dir.resolve("docker-compose.yml"), """
                services:
                  api-gateway:
                    image: springcommunity/spring-petclinic-api-gateway
                    depends_on:
                      - config-server
                      - discovery-server
                  customers-service:
                    image: springcommunity/spring-petclinic-customers-service
                    depends_on:
                      config-server:
                        condition: service_healthy
                      discovery-server:
                        condition: service_healthy
                """, StandardCharsets.UTF_8);

        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        long composeEdges = ledger.getEdges().stream()
                .filter(e -> e.section.equals("compose-dependency")).count();
        assertEquals(4, composeEdges); // api-gateway x2, customers-service x2

        assertTrue(ledger.getEdges().stream().anyMatch(e ->
                e.section.equals("compose-dependency")
                        && "api-gateway".equals(e.fields.get("source_service"))
                        && "discovery-server".equals(e.fields.get("target_service"))));
        assertTrue(ledger.getEdges().stream().anyMatch(e ->
                e.section.equals("compose-dependency")
                        && "customers-service".equals(e.fields.get("source_service"))
                        && "config-server".equals(e.fields.get("target_service"))));
    }

    @Test
    void k8sManifestsRecordWhoIsInjectedWhat(@TempDir Path dir) throws Exception {
        // A ConfigMap with one address and one non-address, and a Deployment that takes it
        // through envFrom, one key through configMapKeyRef, plus literal env values.
        Files.writeString(dir.resolve("ledger.yaml"), """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: ledger-db-config
                data:
                  SPRING_DATASOURCE_URL: "jdbc:postgresql://ledger-db:5432/postgresdb"
                  POSTGRES_DB: "postgresdb"
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: transactionhistory
                spec:
                  template:
                    spec:
                      containers:
                        - name: transactionhistory
                          env:
                            - name: VERSION
                              value: "v0.6.11"
                            - name: ENABLE_TRACING
                              value: "true"
                            - name: METRICS_ADDR
                              value: "http://metrics-collector:4317"
                            - name: PUB_KEY
                              valueFrom:
                                configMapKeyRef:
                                  name: jwt-key
                                  key: pub
                          envFrom:
                            - configMapRef:
                                name: environment-config
                            - configMapRef:
                                name: ledger-db-config
                """, StandardCharsets.UTF_8);

        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(dir, ledger);

        // The ConfigMap's address carries the ConfigMap's name; the non-address does not appear.
        assertTrue(ledger.getEdges().stream().anyMatch(e ->
                e.section.equals("env-address")
                        && "SPRING_DATASOURCE_URL".equals(e.fields.get("name"))
                        && "ledger-db".equals(e.fields.get("host"))
                        && "ledger-db-config".equals(e.fields.get("configmap"))));
        assertTrue(ledger.getEdges().stream().noneMatch(e ->
                e.section.equals("env-address") && "POSTGRES_DB".equals(e.fields.get("name"))));

        // Wiring: every envFrom ConfigMap, the configMapKeyRef ConfigMap, and the literal host.
        assertTrue(wired(ledger, "transactionhistory", "configmap", "ledger-db-config"));
        assertTrue(wired(ledger, "transactionhistory", "configmap", "environment-config"));
        assertTrue(wired(ledger, "transactionhistory", "configmap", "jwt-key"));
        assertTrue(wired(ledger, "transactionhistory", "host", "metrics-collector"));
        // "v0.6.11" and "true" are valid DNS labels but not addresses: never a host.
        assertTrue(ledger.getEdges().stream().noneMatch(e ->
                e.section.equals("workload-env") && e.fields.containsKey("host")
                        && !"metrics-collector".equals(e.fields.get("host"))));
    }

    private static boolean wired(EdgeLedger ledger, String workload, String field, String value) {
        return ledger.getEdges().stream().anyMatch(e ->
                e.section.equals("workload-env")
                        && workload.equals(e.fields.get("workload"))
                        && value.equals(e.fields.get(field)));
    }
}
