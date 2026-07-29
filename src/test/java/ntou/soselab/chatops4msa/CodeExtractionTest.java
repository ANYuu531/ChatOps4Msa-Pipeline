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
}
