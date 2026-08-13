package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ExampleRequestHarvester;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The harvester finds a project's OWN example requests (load/e2e/API artefacts) by
 * convention, with no framework-specific parsing — pure-Java, no Spring.
 */
public class ExampleRequestHarvesterTest {

    private static void write(Path dir, String relative, String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void harvestsLoadAndApiArtefactsButNotOrdinarySourceOrVendored(@TempDir Path dir) throws Exception {
        // A Locust load generator — the kind of file that holds a real deposit payload.
        write(dir, "src/loadgenerator/locustfile.py",
                "self.client.post('/deposit', data={'account_num':'9099','amount':'10.00'})");
        // An OpenAPI spec carries example values too.
        write(dir, "openapi.yaml", "paths:\n  /deposit:\n    post: {}\n");
        // Ordinary application source must NOT be pulled in.
        write(dir, "src/frontend/frontend.py", "print('not an example request artefact')");
        // Vendored code is ignored by the shared scanner rules.
        write(dir, "node_modules/some-loadtest/index.js", "http.post('/x')");

        String harvested = new ExampleRequestHarvester().harvest(dir);

        assertTrue(harvested.contains("src/loadgenerator/locustfile.py"), "the load test is an example artefact");
        assertTrue(harvested.contains("/deposit"), "its real payload content is included for the generator to copy");
        assertTrue(harvested.contains("openapi.yaml"), "the API spec is an example artefact");
        assertFalse(harvested.contains("frontend.py"), "ordinary source is not an example artefact");
        assertFalse(harvested.contains("node_modules"), "vendored code is ignored");
    }

    @Test
    void yieldsEmptyStringWhenTheProjectShipsNoExamples(@TempDir Path dir) throws Exception {
        write(dir, "src/app/Main.java", "class Main {}");
        assertTrue(new ExampleRequestHarvester().harvest(dir).isEmpty(),
                "no load/e2e/API artefacts -> empty, so generation just proceeds without it");
    }
}
