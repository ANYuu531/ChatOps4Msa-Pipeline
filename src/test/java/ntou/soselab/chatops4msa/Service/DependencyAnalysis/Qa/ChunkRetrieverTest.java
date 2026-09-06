package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retrieval has to be right about the two things this corpus is made of: service
 * identifiers (hyphenated, exact) and Chinese questions about English passages.
 */
public class ChunkRetrieverTest {

    private static TextChunk chunk(String id, String title, String text) {
        return new TextChunk(id, "report", title, text);
    }

    private static final List<TextChunk> CORPUS = List.of(
            chunk("a", "Candidate: frontend -> userservice",
                    "Protocol HTTP. Runtime observed: Yes. Evidence istio_requests_total."),
            chunk("b", "Infrastructure dependency: userservice -> accounts-db",
                    "Dependency type: database. Runtime observed: Yes. 8187 TCP connections observed."),
            chunk("c", "Candidate: ledgerwriter -> balancereader",
                    "Only when a transaction debits a local account. Runtime observed: Yes."),
            chunk("d", "Collection Status",
                    "DeepWiki: usable. Kubernetes: usable. Prometheus: usable."));

    @Test
    void tokenizerIndexesHyphenatedIdsWholeAndByPart() {
        List<String> tokens = ChunkRetriever.tokenize("userservice -> accounts-db (istio_tcp_connections_opened_total)");
        assertTrue(tokens.contains("userservice"));
        assertTrue(tokens.contains("accounts-db"));
        assertTrue(tokens.contains("accounts"));
        assertTrue(tokens.contains("db"));
        assertTrue(tokens.contains("istio_tcp_connections_opened_total"));
        assertTrue(tokens.contains("istio"));
    }

    @Test
    void tokenizerEmitsCjkCharactersAndBigrams() {
        List<String> tokens = ChunkRetriever.tokenize("資料庫frontend");
        assertTrue(tokens.contains("資"));
        assertTrue(tokens.contains("資料"));
        assertTrue(tokens.contains("料庫"));
        assertTrue(tokens.contains("frontend"), "a latin run glued to CJK is still its own token");
    }

    @Test
    void lexicalRankingFindsThePassageThatSpellsTheIdentifier() {
        List<TextChunk> hits = ChunkRetriever.retrieve(CORPUS, "how many connections does userservice open to accounts-db?", null, 2, 10000);
        assertEquals("b", hits.get(0).id);
    }

    @Test
    void aQuestionInChineseStillReachesThePassageNamingTheService() {
        List<TextChunk> hits = ChunkRetriever.retrieve(CORPUS, "ledgerwriter 什麼時候會呼叫 balancereader？", null, 1, 10000);
        assertEquals("c", hits.get(0).id);
    }

    @Test
    void unmatchedQuestionReturnsNothingRatherThanNoise() {
        assertTrue(ChunkRetriever.retrieve(CORPUS, "zzz qqq", null, 3, 10000).isEmpty());
        assertTrue(ChunkRetriever.retrieve(List.of(), "frontend", null, 3, 10000).isEmpty());
        assertTrue(ChunkRetriever.retrieve(CORPUS, "   ", null, 3, 10000).isEmpty());
    }

    @Test
    void embeddingsAreFusedWithLexicalAndPromoteAParaphrase() {
        // Two passages: one lexically matches the question word "cache", one is the
        // semantic neighbour (the database) that the question actually means.
        TextChunk lexical = chunk("l", "Notes", "A cache is mentioned in the docs.");
        TextChunk semantic = chunk("s", "Infrastructure", "userservice persists accounts in PostgreSQL.");
        lexical.embedding = new double[]{1, 0};
        semantic.embedding = new double[]{0, 1};

        List<TextChunk> lexicalOnly = ChunkRetriever.retrieve(List.of(lexical, semantic), "where is the cache?", null, 2, 10000);
        assertEquals(1, lexicalOnly.size());
        assertEquals("l", lexicalOnly.get(0).id);

        // With a question vector pointing at the semantic passage, both are returned and
        // the fused order still puts the lexical+semantic evidence first when both agree.
        List<TextChunk> fused = ChunkRetriever.retrieve(List.of(lexical, semantic), "where is the cache?", new double[]{0, 1}, 2, 10000);
        assertEquals(2, fused.size());
        assertTrue(fused.stream().anyMatch(c -> c.id.equals("s")), "the semantic neighbour is surfaced");
    }

    @Test
    void chunksWithoutVectorsAreNotPenalisedWhenTheQuestionHasOne() {
        TextChunk withVector = chunk("v", "x", "unrelated words here");
        withVector.embedding = new double[]{1, 0};
        TextChunk without = chunk("w", "x", "frontend calls userservice");
        List<TextChunk> hits = ChunkRetriever.retrieve(List.of(withVector, without), "frontend", new double[]{1, 0}, 2, 10000);
        assertTrue(hits.stream().anyMatch(c -> c.id.equals("w")), "the lexical hit survives an embedding-only rival");
    }

    @Test
    void budgetCapsWhatIsReturnedButAlwaysKeepsTheBest() {
        List<TextChunk> hits = ChunkRetriever.retrieve(CORPUS, "Runtime observed", null, 4, 10);
        assertEquals(1, hits.size(), "the first hit is kept even though it exceeds the budget");
        assertFalse(ChunkRetriever.retrieve(CORPUS, "Runtime observed", null, 4, 10000).size() < 3);
    }

    @Test
    void cosineHandlesDegenerateVectors() {
        assertEquals(Double.NEGATIVE_INFINITY, ChunkRetriever.cosine(new double[]{0, 0}, new double[]{1, 0}));
        assertEquals(Double.NEGATIVE_INFINITY, ChunkRetriever.cosine(new double[]{1}, new double[]{1, 0}));
        assertEquals(1.0, ChunkRetriever.cosine(new double[]{2, 0}, new double[]{5, 0}), 1e-9);
    }
}
