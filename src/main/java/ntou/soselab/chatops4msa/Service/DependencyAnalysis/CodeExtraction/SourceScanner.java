package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Shared file-walking rules, so the detector, the tree-sitter extractor and the
 * LLM extractor all agree on which files are "source" and which are noise.
 *
 * Counting vendored or generated code as source would both skew stack detection
 * and blow up the LLM tier's token budget.
 */
public final class SourceScanner {

    private SourceScanner() {
    }

    /** Directory names that never contain first-party service code. */
    private static final List<String> IGNORED_DIRECTORIES = List.of(
            ".git", "node_modules", "vendor", "target", "build", "dist", "out",
            ".venv", "venv", "__pycache__", ".gradle", ".mvn", ".idea",
            "site-packages", "third_party", "generated", "testdata");

    /** Path segments indicating tests: excluded, since test code invents fake dependencies. */
    private static final List<String> TEST_SEGMENTS = List.of(
            "/test/", "/tests/", "/testing/", "/src/test/", "/spec/", "/__tests__/");

    /** Files larger than this are skipped (minified bundles, generated sources). */
    public static final long MAX_FILE_BYTES = 512 * 1024;

    public static boolean isIgnored(Path root, Path file) {
        String relative = "/" + root.relativize(file).toString().replace('\\', '/') + "/";
        String lower = relative.toLowerCase(Locale.ROOT);

        for (String segment : TEST_SEGMENTS) {
            if (lower.contains(segment)) return true;
        }
        for (String directory : IGNORED_DIRECTORIES) {
            if (lower.contains("/" + directory + "/")) return true;
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return true;
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    /** Every non-ignored regular file under root whose name ends with one of the extensions. */
    public static List<Path> filesWithExtensions(Path root, List<String> extensions) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return extensions.stream().anyMatch(name::endsWith);
                    })
                    .filter(p -> !isIgnored(root, p))
                    .forEach(found::add);
        } catch (Exception ignored) {
            // unwalkable tree: return whatever was collected
        }
        return found;
    }

    public static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
