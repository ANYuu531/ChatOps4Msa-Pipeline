package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Rule 57: a Spring profile's configuration counts only when the deployment runs that profile. */
class ActiveProfilesTest {

    private static ActiveProfiles of(boolean deployment, String... lines) {
        ActiveProfiles p = new ActiveProfiles();
        p.read(List.of(lines), deployment);
        return p;
    }

    @Test
    void composeListAndMapForms() {
        assertEquals(Set.of("prod"), of(true, "    environment:", "    - SPRING_PROFILES_ACTIVE=prod").active());
        assertEquals(Set.of("docker"), of(true, "      SPRING_PROFILES_ACTIVE: \"docker\"").active());
    }

    @Test
    void workloadEnvNameThenValue() {
        // yas's Helm values and mall-swarm's manifests.
        assertEquals(Set.of("prod"), of(true, "    - name: SPRING_PROFILES_ACTIVE", "      value: prod").active());
        assertEquals(Set.of("prod"), of(true, "  - name: spring.profiles.active", "    value: \"prod\"").active());
    }

    @Test
    void entryPointFlagEndsAtItsQuote() {
        assertEquals(Set.of("prod"), of(true,
                "<entryPoint>[\"java\", \"-jar\",\"-Dspring.profiles.active=prod\",\"/${project.build.finalName}.jar\"]</entryPoint>").active());
        // An unresolved placeholder states nothing, and the next flag is not a profile (ecommerce).
        assertTrue(of(true, "ENTRYPOINT [\"java\", \"-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE}\", \"-jar\", \"app.jar\"]").active().isEmpty());
        assertEquals(Set.of("dev"), of(true, "CMD java -Dspring.profiles.active=${PROFILE:dev} -jar app.jar").active());
    }

    @Test
    void dockerfileLegacyEnv() {
        // spring-petclinic's docker/Dockerfile.
        assertEquals(Set.of("docker"), of(true, "ENV SPRING_PROFILES_ACTIVE docker").active());
    }

    @Test
    void nestedYamlScalarAndList() {
        assertEquals(Set.of("dev"), of(false, "spring:", "  profiles:", "    active: dev", "  security:", "    x: y").active());
        assertEquals(Set.of("dev", "local"), of(false, "spring:", "  profiles:", "    active:", "    - dev", "    - local").active());
        assertEquals(Set.of("dev", "local"), of(false, "spring:", "  profiles:", "    active: [dev, local]").active());
        // The legacy document selector (spring.profiles: docker) is not an activation.
        assertTrue(of(false, "spring:", "  profiles: docker").active().isEmpty());
    }

    @Test
    void deploymentOverridesTheApplicationsOwnValue() {
        // yas: the BFF's application.yaml says dev, Compose and Helm run prod.
        ActiveProfiles p = new ActiveProfiles();
        p.read(List.of("spring:", "  profiles:", "    active: dev"), false);
        p.read(List.of("    - SPRING_PROFILES_ACTIVE=prod"), true);
        assertEquals(Set.of("prod"), p.active());
        assertTrue(p.inactive("dev"));
        assertFalse(p.inactive("prod"));
        // With no deployment statement, the application's own value decides.
        ActiveProfiles ide = of(false, "spring.profiles.active=dev");
        assertFalse(ide.inactive("dev"));
        assertTrue(ide.inactive("prod"));
    }

    @Test
    void conservativeWhenNotStated() {
        ActiveProfiles none = new ActiveProfiles();
        assertFalse(none.inactive("dev"));
        ActiveProfiles prod = of(true, "SPRING_PROFILES_ACTIVE=prod");
        assertFalse(prod.inactive("default"));
        assertFalse(prod.inactive("!prod"));
        assertFalse(prod.inactive("dev | prod"));
        assertFalse(prod.inactive(null));
        // A profile group makes other profiles active through it: no filtering.
        assertFalse(of(true, "SPRING_PROFILES_ACTIVE=prod", "spring.profiles.group.prod=db,cache").inactive("db"));
        assertFalse(of(false, "spring:", "  profiles:", "    group:", "      prod: db", "    active: prod").inactive("db"));
    }

    @Test
    void commentedStatementsDoNotCount() {
        assertTrue(of(true, "#  - SPRING_PROFILES_ACTIVE=dev").active().isEmpty());
    }

    @Test
    void profileOfFile() {
        assertEquals("dev", ActiveProfiles.profileOfFile("application-dev.yaml"));
        assertEquals("docker", ActiveProfiles.profileOfFile("bootstrap-docker.properties"));
        assertNull(ActiveProfiles.profileOfFile("application.yml"));
        assertNull(ActiveProfiles.profileOfFile("account-service.yml"));
    }

    @Test
    void devFileOfAProdDeploymentIsNotRead(@TempDir Path root) throws Exception {
        Path res = Files.createDirectories(root.resolve("bff/src/main/resources"));
        Files.writeString(res.resolve("application.yaml"), "spring:\n  profiles:\n    active: dev\n");
        Files.writeString(res.resolve("application-dev.yaml"),
                "spring:\n  cloud:\n    gateway:\n      routes:\n        - id: api_servicename_local\n          uri: http://order:8085\n");
        Files.writeString(res.resolve("application-prod.yaml"),
                "spring:\n  cloud:\n    gateway:\n      routes:\n        - id: api_cart\n          uri: http://cart\n");
        Files.writeString(root.resolve("docker-compose.yml"),
                "services:\n  bff:\n    build: ./bff\n    environment:\n      - SPRING_PROFILES_ACTIVE=prod\n");
        EdgeLedger ledger = new EdgeLedger();
        new ConfigExtractor().extract(root, ledger);
        String rows = ledger.toJson().toString();
        assertTrue(rows.contains("http://cart"), rows);
        assertFalse(rows.contains("http://order:8085"), rows);
    }
}
