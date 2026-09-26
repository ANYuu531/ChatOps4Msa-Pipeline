package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds the service directories in a repository — the modules that a build
 * manifest or a Dockerfile marks as a deployable unit — and records each as a
 * {@code service-root} ledger entry ({@code dir}, {@code name}).
 *
 * <p>This is the layout-neutral answer to "which directory is a service, and what
 * is it called". The runtime graph normally supplies that vocabulary (real k8s
 * workload names), but a <b>greenfield</b> analysis has no cluster: the services
 * are only knowable from the repository itself. {@link
 * ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger} seeds
 * its node vocabulary and its source-attribution map from these entries when no
 * runtime graph exists, so a nested layout ({@code src/<group>/<service>/…}) is
 * attributed to {@code <service>}, not to the top path segment {@code src}.
 *
 * <p>These entries are a signal, not a dependency edge; the merger consumes them
 * and never renders them as arrows.
 */
@Component
public class ServiceRootScanner {

    /** Files whose presence marks a directory as a buildable/deployable service. */
    private static final Set<String> MARKERS = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts",
            "requirements.txt", "pyproject.toml", "setup.py", "Pipfile",
            "go.mod", "package.json", "Gemfile", "Cargo.toml", "composer.json",
            "dockerfile");

    /**
     * Directory names that hold a service's files without naming it. A marker found in
     * {@code src/cartservice/src/Dockerfile} belongs to {@code cartservice}, not to a
     * service called "src" — Online Boutique put exactly that phantom on the graph
     * (2026-09-22).
     */
    private static final Set<String> GENERIC_DIRS = Set.of(
            "src", "app", "main", "docker", "build", "cmd", "server", "backend");

    /** A directory of tests is a build unit, never a workload (ui-tests, e2e-tests). */
    private static final java.util.regex.Pattern TEST_DIR = java.util.regex.Pattern.compile(
            "(?i)(^|[-_.])(e2e|tests?|spec|specs)$|^tests?([-_.]|$)");

    public void scan(Path root, EdgeLedger ledger) {
        // dir -> the marker file that justified it (first one wins, for stable evidence).
        Map<Path, String> serviceDirs = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !SourceScanner.isIgnored(root, p))
                    .forEach(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!MARKERS.contains(name)) return;
                        Path dir = serviceDir(root, p.getParent());
                        if (dir == null) return;
                        serviceDirs.putIfAbsent(dir, p.getFileName().toString());
                    });
        } catch (Exception ignored) {
            // unwalkable tree: emit whatever was found
        }

        for (Map.Entry<Path, String> entry : serviceDirs.entrySet()) {
            Path dir = entry.getKey();
            String relDir = SourceScanner.relative(root, dir);
            // A marker at the repo root names no single service (it is an aggregator
            // pom or a top-level Dockerfile); attributing it to the clone directory
            // would invent a node. Only sub-directories are services.
            if (relDir.isBlank()) continue;
            // The same holds one level down: a module directory whose only role is to
            // hold other modules (a Maven parent pom over services/, an examples/
            // folder of Dockerfiles) is a grouping, not a workload.
            if (isAggregator(dir, serviceDirs.keySet())) continue;
            String serviceName = dir.getFileName() == null ? relDir : dir.getFileName().toString();
            if (serviceName.isBlank() || TEST_DIR.matcher(serviceName).find()) continue;

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("dir", relDir);
            fields.put("name", serviceName(serviceName));
            ledger.add("service-root", fields, relDir + "/" + entry.getValue(), -1,
                    "High (build manifest / Dockerfile)");
        }
    }

    /**
     * The directory a marker file names as a service: its parent, climbed out of any
     * generic wrapper directory ({@code src/}, {@code app/}) as long as the climb stays
     * below the repository root.
     */
    private static Path serviceDir(Path root, Path dir) {
        while (dir != null && !dir.equals(root)) {
            Path name = dir.getFileName();
            if (name == null || !GENERIC_DIRS.contains(name.toString().toLowerCase(Locale.ROOT))) return dir;
            Path parent = dir.getParent();
            if (parent == null) return dir;
            dir = parent; // reaching the root yields a blank relative dir, which the caller skips
        }
        return dir;
    }

    /** A candidate directory that contains at least two other candidates only groups them. */
    private static boolean isAggregator(Path dir, Set<Path> candidates) {
        int nested = 0;
        for (Path other : candidates) {
            if (!other.equals(dir) && other.startsWith(dir) && ++nested >= 2) return true;
        }
        return false;
    }

    /**
     * The node-safe spelling of a module directory. A Maven module named by reverse
     * domain ({@code tools.descartes.teastore.webui}) keeps its words as hyphens, so the
     * workload it deploys as ({@code teastore-webui}) can still be found by suffix; cut
     * at the first dot, six TeaStore services collapsed into one node called "tools".
     */
    static String serviceName(String dirName) {
        String s = dirName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? dirName : s;
    }

    /** Extensions are irrelevant here; kept only so callers can share one constant. */
    static List<String> markerFiles() {
        return List.copyOf(MARKERS);
    }
}
