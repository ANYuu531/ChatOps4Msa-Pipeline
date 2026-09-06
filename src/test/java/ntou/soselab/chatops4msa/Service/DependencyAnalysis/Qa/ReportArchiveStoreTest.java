package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive is what outlives the checkpoint: it must round-trip through disk with
 * its graph, chunks, vectors and history intact, be found by its thread after a
 * restart, and expire.
 */
public class ReportArchiveStoreTest {

    @TempDir
    Path dir;

    private static ReportArchive sample() {
        ReportArchive a = new ReportArchive();
        a.id = ReportArchiveStore.newId("123");
        a.userId = "123";
        a.repoName = "GoogleCloudPlatform/bank-of-anthos";
        a.namespace = "bank-of-anthos";
        a.mode = "runtime";
        a.threadId = "thread-42";
        a.report = "# 1. Collection Status\n- ok\n";
        DependencyGraph g = new DependencyGraph("bank-of-anthos");
        g.addEdge("frontend", "userservice", "sync-http", DependencyGraph.PROV_RUNTIME, DependencyGraph.CONF_OBSERVED, true, 6, "istio");
        a.graphJson = g.toJson();
        a.coverage = "7 / 7";
        a.evidence.put("docs+code notes", "frontend calls userservice");
        TextChunk c = new TextChunk("report#0", "report", "1. Collection Status", "- ok");
        c.embedding = new double[]{0.1, 0.2, 0.3};
        a.chunks.add(c);
        a.history.add(new JSONObject().put("role", "user").put("content", "hi"));
        return a;
    }

    @Test
    void roundTripsThroughJson() {
        ReportArchive a = sample();
        ReportArchive b = ReportArchive.fromJson(new JSONObject(a.toJson().toString()));

        assertEquals(a.id, b.id);
        assertEquals("thread-42", b.threadId);
        assertEquals("runtime", b.mode);
        assertEquals(a.report, b.report);
        assertEquals("7 / 7", b.coverage);
        assertEquals("frontend calls userservice", b.evidence.get("docs+code notes"));
        assertEquals(1, b.chunks.size());
        assertEquals("1. Collection Status", b.chunks.get(0).title);
        assertEquals(3, b.chunks.get(0).embedding.length);
        assertEquals(0.2, b.chunks.get(0).embedding[1], 1e-12);
        assertTrue(b.hasEmbeddings());
        assertEquals(1, b.history.size());

        DependencyGraph g = DependencyGraph.fromJson(b.graphJson);
        assertEquals(1, g.getEdges().size());
        DependencyGraph.Edge e = g.getEdges().iterator().next();
        assertEquals("frontend", e.source);
        assertTrue(e.runtimeObserved);
        assertEquals(6, e.count);
    }

    @Test
    void isFoundByThreadAfterARestart() {
        ReportArchive a = sample();
        new ReportArchiveStore(dir, Duration.ofDays(7)).save(a);
        assertTrue(Files.exists(dir.resolve(a.id + ".json")));

        // A fresh store (a restarted bot) knows nothing until it scans the directory.
        ReportArchiveStore fresh = new ReportArchiveStore(dir, Duration.ofDays(7));
        assertTrue(fresh.isQaThread("thread-42"));
        assertFalse(fresh.isQaThread("some-other-thread"));
        ReportArchive found = fresh.findByThread("thread-42");
        assertNotNull(found);
        assertEquals(a.repoName, found.repoName);
    }

    @Test
    void expiredArchivesAreDroppedAndTheirFilesDeleted() {
        ReportArchive a = sample();
        a.createdAt = Instant.now().minus(Duration.ofDays(10));
        ReportArchiveStore store = new ReportArchiveStore(dir, Duration.ofDays(7));
        store.save(a);

        assertNull(store.get(a.id));
        assertFalse(Files.exists(dir.resolve(a.id + ".json")));
        assertFalse(store.isQaThread("thread-42"));
    }

    @Test
    void savingAgainUpdatesTheThreadIndex() {
        ReportArchiveStore store = new ReportArchiveStore(dir, Duration.ofDays(7));
        ReportArchive a = sample();
        a.threadId = "";
        store.save(a);
        assertFalse(store.isQaThread("thread-99"));

        a.threadId = "thread-99";
        store.save(a);
        assertTrue(store.isQaThread("thread-99"));
        store.remove(a.id);
        assertFalse(store.isQaThread("thread-99"));
    }

    @Test
    void idsAreFilenameSafeAndUnique() throws InterruptedException {
        String first = ReportArchiveStore.newId("user/with:odd chars");
        Thread.sleep(2);
        String second = ReportArchiveStore.newId("user/with:odd chars");
        assertTrue(first.matches("[A-Za-z0-9_-]+"));
        assertFalse(first.equals(second));
    }
}
