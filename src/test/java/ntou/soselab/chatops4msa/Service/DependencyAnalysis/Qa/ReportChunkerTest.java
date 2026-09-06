package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report is heading-structured Markdown; a heading is the retrieval unit, and a
 * passage must carry the path of headings above it so a hit can be cited.
 */
public class ReportChunkerTest {

    private static final String REPORT = ""
            + "## Microservice Dependency Analysis Report\n"
            + "**Repository:** `bank-of-anthos`\n\n"
            + "# 4. Synchronous Dependency Candidates\n"
            + "- Direct runtime-observed synchronous invocations: 7\n\n"
            + "### Candidate: frontend -> userservice\n"
            + "- Protocol: HTTP\n"
            + "- Runtime observed: Yes\n\n"
            + "### Candidate: ledgerwriter -> balancereader\n"
            + "- Protocol: HTTP\n"
            + "- Runtime observed: Yes\n\n"
            + "# 9. Unresolved Candidates and Unknowns\n"
            + "None.\n";

    @Test
    void splitsOnHeadingsAndKeepsTheHeadingPath() {
        List<TextChunk> chunks = ReportChunker.chunk("report", REPORT);

        assertEquals(5, chunks.size());
        assertEquals("Microservice Dependency Analysis Report", chunks.get(0).title);
        assertEquals("4. Synchronous Dependency Candidates", chunks.get(1).title);
        assertEquals("4. Synchronous Dependency Candidates › Candidate: frontend -> userservice", chunks.get(2).title);
        assertTrue(chunks.get(2).text.contains("Runtime observed: Yes"));
        assertEquals("4. Synchronous Dependency Candidates › Candidate: ledgerwriter -> balancereader", chunks.get(3).title);
        // A new level-1 heading resets the sub-heading.
        assertEquals("9. Unresolved Candidates and Unknowns", chunks.get(4).title);
        assertEquals("None.", chunks.get(4).text);
    }

    @Test
    void everyChunkNamesItsSourceAndHasAUniqueId() {
        List<TextChunk> chunks = ReportChunker.chunk("report", REPORT);
        long distinct = chunks.stream().map(c -> c.id).distinct().count();
        assertEquals(chunks.size(), distinct);
        assertTrue(chunks.stream().allMatch(c -> "report".equals(c.source)));
        assertTrue(chunks.get(2).render().startsWith("[report › 4. Synchronous Dependency Candidates › Candidate: frontend -> userservice]"));
    }

    @Test
    void oversizedSectionsAreSplitOnParagraphsWithinTheLimit() {
        StringBuilder big = new StringBuilder("# Ledger\n");
        for (int i = 0; i < 60; i++) {
            big.append("- edge-").append(i).append(" a -> b observed ").append("x".repeat(60)).append("\n\n");
        }
        List<TextChunk> chunks = ReportChunker.chunk("ledger", big.toString());

        assertTrue(chunks.size() > 1, "a 4k+ section must be split");
        assertTrue(chunks.stream().allMatch(c -> c.text.length() <= ReportChunker.MAX_CHARS));
        assertTrue(chunks.stream().allMatch(c -> "Ledger".equals(c.title)), "every piece keeps the heading");
        // Nothing lost: every edge line lands in exactly one piece.
        for (int i = 0; i < 60; i++) {
            String needle = "edge-" + i + " ";
            long hits = chunks.stream().filter(c -> c.text.contains(needle)).count();
            assertEquals(1, hits, needle);
        }
    }

    @Test
    void blankInputYieldsNothingAndPreambleWithoutHeadingIsKept() {
        assertTrue(ReportChunker.chunk("x", "").isEmpty());
        assertTrue(ReportChunker.chunk("x", null).isEmpty());

        List<TextChunk> chunks = ReportChunker.chunk("notes", "just a paragraph\nno heading");
        assertEquals(1, chunks.size());
        assertEquals("", chunks.get(0).title);
        assertEquals("[notes]\njust a paragraph\nno heading", chunks.get(0).render());
    }
}
