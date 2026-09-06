package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Persists {@link ReportArchive}s, one JSON file each, and finds them by the Discord
 * thread they are discussed in.
 *
 * On disk rather than in memory for the same reason the checkpoint is: a question can
 * arrive a day after the report, possibly after the bot restarted. Files expire after a
 * TTL (default a week — long enough to prepare a meeting from the report, short enough
 * that stale graphs do not answer questions about a system that has since changed).
 *
 * Lookup by thread id scans the directory on a cache miss, at most once a minute: every
 * message in every thread of the guild asks "is this one of ours?", and the directory
 * holds a handful of files, so a rate-limited scan is cheaper than an index file that
 * can go stale.
 */
@Component
public class ReportArchiveStore {

    private final Path directory;
    private final Duration ttl;

    private final ConcurrentMap<String, ReportArchive> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idByThread = new ConcurrentHashMap<>();
    private volatile Instant lastScan = Instant.EPOCH;
    private static final Duration SCAN_INTERVAL = Duration.ofMinutes(1);

    public ReportArchiveStore(
            @Value("${dependency.qa.dir:./dep-reports}") String directory,
            @Value("${dependency.qa.ttl-days:7}") long ttlDays) {
        this(Path.of(directory), Duration.ofDays(ttlDays));
    }

    public ReportArchiveStore(Path directory, Duration ttl) {
        this.directory = directory;
        this.ttl = ttl;
    }

    /** A new archive id: the user, then the instant, so files sort by time and never collide. */
    public static String newId(String userId) {
        String safe = (userId == null ? "anon" : userId).replaceAll("[^A-Za-z0-9_-]", "_");
        return safe + "-" + System.currentTimeMillis();
    }

    public void save(ReportArchive archive) {
        if (archive == null || archive.id == null || archive.id.isBlank()) return;
        byId.put(archive.id, archive);
        if (archive.threadId != null && !archive.threadId.isBlank()) idByThread.put(archive.threadId, archive.id);
        try {
            Files.createDirectories(directory);
            Files.writeString(fileOf(archive.id), archive.toJson().toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Persistence is what survives a restart; failing it must not fail the report.
            System.out.println("[WARNING] cannot persist report archive: " + e.getMessage());
        }
    }

    /** The archive discussed in this thread, or {@code null} when it is not a Q&amp;A thread or has expired. */
    public ReportArchive findByThread(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        String id = idByThread.get(threadId);
        if (id == null) {
            scanIfDue();
            id = idByThread.get(threadId);
            if (id == null) return null;
        }
        ReportArchive archive = get(id);
        if (archive == null) idByThread.remove(threadId);
        return archive;
    }

    public boolean isQaThread(String threadId) {
        return findByThread(threadId) != null;
    }

    /** Null when absent or expired (an expired file is deleted on the way). */
    public ReportArchive get(String id) {
        if (id == null || id.isBlank()) return null;
        ReportArchive archive = byId.get(id);
        if (archive == null) archive = readFromDisk(id);
        if (archive == null) return null;
        if (Duration.between(archive.createdAt, Instant.now()).compareTo(ttl) > 0) {
            remove(id);
            return null;
        }
        return archive;
    }

    public void remove(String id) {
        if (id == null) return;
        ReportArchive archive = byId.remove(id);
        if (archive != null && archive.threadId != null) idByThread.remove(archive.threadId);
        try {
            Files.deleteIfExists(fileOf(id));
        } catch (Exception ignored) {
            // a stale file is re-checked, and dropped, on the next scan
        }
    }

    // ---------- disk ----------

    private Path fileOf(String id) {
        return directory.resolve(id.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    private ReportArchive readFromDisk(String id) {
        try {
            Path file = fileOf(id);
            if (!Files.exists(file)) return null;
            ReportArchive archive = ReportArchive.fromJson(new JSONObject(Files.readString(file, StandardCharsets.UTF_8)));
            byId.put(archive.id, archive);
            if (!archive.threadId.isBlank()) idByThread.put(archive.threadId, archive.id);
            return archive;
        } catch (Exception e) {
            return null;
        }
    }

    /** Loads every archive file into the caches, dropping expired ones; rate-limited. */
    synchronized void scanIfDue() {
        Instant now = Instant.now();
        if (Duration.between(lastScan, now).compareTo(SCAN_INTERVAL) < 0) return;
        lastScan = now;
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                String id = p.getFileName().toString().replaceAll("\\.json$", "");
                get(id); // loads, indexes, or expires
            });
        } catch (Exception e) {
            System.out.println("[WARNING] cannot scan report archives: " + e.getMessage());
        }
    }

    /** For tests: forget the rate limit so the next lookup rescans. */
    void resetScanClock() {
        lastScan = Instant.EPOCH;
    }
}
