package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code docs/generalization/vocabulary.md} equal to what the code enumerates, so the
 * paper's inventory of word lists cannot drift from the implementation. Run with
 * {@code -Dvocabulary.write=true} to regenerate the file after changing a list.
 */
class VocabularyInventoryTest {

    private static final Path DOC = Path.of("docs/generalization/vocabulary.md");

    @Test
    void everyListStatesItsPrincipleOriginAndAddCondition() {
        Set<String> names = new HashSet<>();
        for (Vocabulary.Entry e : Vocabulary.entries()) {
            assertTrue(names.add(e.name()), "duplicate list " + e.name());
            assertFalse(e.principle().isBlank(), e.name() + " has no principle");
            assertFalse(e.origin().isBlank(), e.name() + " names no ecosystem");
            assertFalse(e.addWhen().isBlank(), e.name() + " has no condition for adding a word");
            assertFalse(e.words().isEmpty(), e.name() + " is empty");
        }
    }

    @Test
    void aListIteratesInDeclarationOrderOnEveryRun() {
        // Set.of is salted per JVM; a suffix list tried shortest-first cuts the wrong label.
        assertEquals(List.of(".svc.cluster.local", ".cluster.local", ".svc"), Vocabulary.CLUSTER_SUFFIXES);
        assertEquals(List.of(".svc.cluster.local", ".cluster.local", ".svc"), List.copyOf(NameTable.CLUSTER_SUFFIXES == null ? List.of() : java.util.Arrays.asList(NameTable.CLUSTER_SUFFIXES)));
        assertEquals("web", NameTable.cleanHost("web.robot-shop.svc.cluster.local", Set.of("robot-shop")));
        assertEquals("web", NameTable.cleanHost("web.robot-shop.svc.cluster.local", Set.of()));
        for (Vocabulary.Entry e : Vocabulary.entries()) {
            assertEquals(e.words().size(), new java.util.LinkedHashSet<>(e.words()).size(), e.name() + " repeats a word");
        }
    }

    @Test
    void theClientCallPatternStillMatchesEveryDocumentedForm() {
        for (String line : new String[]{
                "restTemplate.getForObject(url, String.class)",
                "WebClient.create().baseUrl(\"http://x\")",
                "requests.get(\"http://x\")",
                "session.post(url)",
                "client.BaseAddress = new Uri(url);",
                "new HttpGet(url)",
                "smtplib.SMTP_SSL(host, 465)",
                "driver.get(url)",
                "grpc.insecure_channel(target)"}) {
            assertTrue(Vocabulary.CLIENT_CALL.matcher(line).find(), line);
        }
        assertFalse(Vocabulary.CLIENT_CALL.matcher("String url = base + \"/x\";").find());
    }

    @Test
    void theInventoryDocumentMatchesTheCode() throws IOException {
        String generated = Vocabulary.inventoryMarkdown();
        if (Boolean.getBoolean("vocabulary.write")) {
            Files.writeString(DOC, generated, StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(DOC), DOC + " is missing; run with -Dvocabulary.write=true");
        assertEquals(generated, Files.readString(DOC, StandardCharsets.UTF_8),
                DOC + " is out of date; run with -Dvocabulary.write=true");
    }
}
