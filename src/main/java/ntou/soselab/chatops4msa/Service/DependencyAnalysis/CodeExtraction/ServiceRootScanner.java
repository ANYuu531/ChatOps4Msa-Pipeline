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

    public void scan(Path root, EdgeLedger ledger) {
        // dir -> the marker file that justified it (first one wins, for stable evidence).
        Map<Path, String> serviceDirs = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !SourceScanner.isIgnored(root, p))
                    .forEach(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!MARKERS.contains(name)) return;
                        Path dir = p.getParent();
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
            String serviceName = dir.getFileName() == null ? relDir : dir.getFileName().toString();
            if (serviceName.isBlank()) continue;

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("dir", relDir);
            fields.put("name", serviceName);
            ledger.add("service-root", fields, relDir + "/" + entry.getValue(), -1,
                    "High (build manifest / Dockerfile)");
        }
    }

    /** Extensions are irrelevant here; kept only so callers can share one constant. */
    static List<String> markerFiles() {
        return List.copyOf(MARKERS);
    }
}
