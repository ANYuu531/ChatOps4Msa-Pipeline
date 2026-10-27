package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;

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

    /**
     * Path segments indicating tests: excluded, since test code invents fake dependencies.
     * {@code src/it} is Maven Failsafe's integration-test tree ({@code payment/src/it/resources/
     * application.properties} pointed payment at a browser return URL), {@code e2e} and
     * {@code automation-ui} are end-to-end suites whose sub-modules borrow the services' names
     * (yas's {@code automation-ui/backoffice}; 2026-10-26).
     */
    private static final List<String> TEST_SEGMENTS = List.of(
            "/test/", "/tests/", "/testing/", "/src/test/", "/spec/", "/__tests__/",
            "/src/it/", "/src/integration-test/", "/src/integrationtest/", "/e2e/", "/automation-ui/");

    /** The segments rule 42 added (2026-10-26); the others predate the numbered rules. */
    private static final List<String> RULE_42_SEGMENTS = List.of(
            "/__tests__/", "/src/it/", "/src/integration-test/", "/src/integrationtest/", "/e2e/", "/automation-ui/");

    /**
     * Test files by the naming convention of their toolchain rather than their directory:
     * Go's {@code _test.go} (the go tool compiles them only for {@code go test}), and the
     * {@code .spec.} / {@code .test.} infix every JavaScript/TypeScript runner (Jest, Vitest,
     * Mocha, Playwright, Cypress) uses. Read once JavaScript and Go had grammars (rule 42
     * extended, 2026-10-27): a Playwright example spec put demo.playwright.dev on a graph.
     */
    private static final java.util.regex.Pattern TEST_FILE_NAME = java.util.regex.Pattern.compile(
            "(?i).*(_test\\.go|\\.(spec|test|cy)\\.(js|jsx|mjs|cjs|ts|tsx|mts|cts))$");

    /** Files larger than this are skipped (minified bundles, generated sources). */
    public static final long MAX_FILE_BYTES = 512 * 1024;

    public static boolean isIgnored(Path root, Path file) {
        String relative = "/" + root.relativize(file).toString().replace('\\', '/') + "/";
        String lower = relative.toLowerCase(Locale.ROOT);

        for (String segment : TEST_SEGMENTS) {
            if (RuleTrace.hitIf(lower.contains(segment) && RULE_42_SEGMENTS.contains(segment), 42, "ignore " + relative) || lower.contains(segment)) return true;
        }
        for (String directory : IGNORED_DIRECTORIES) {
            if (lower.contains("/" + directory + "/")) return true;
        }
        if (RuleTrace.hitIf(TEST_FILE_NAME.matcher(file.getFileName().toString()).matches(), 42, "ignore " + relative)) return true;
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
