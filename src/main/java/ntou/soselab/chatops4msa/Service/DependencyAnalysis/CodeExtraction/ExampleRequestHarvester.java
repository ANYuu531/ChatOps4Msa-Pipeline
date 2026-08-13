package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Harvests the repository's OWN example requests — its load tests, end-to-end
 * tests and API specs — so the traffic-scenario generator can copy real field
 * names and valid values into deep write-path payloads instead of guessing them.
 *
 * <p>The hard part of runtime observation is the deep write edge (a checkout, a
 * deposit): it only fires on a semantically valid request, and the valid payload
 * is domain knowledge that is not derivable from endpoints + routing alone — so
 * the LLM guesses and the request 4xx's. But that payload usually already exists
 * in the repo: a Locust/k6 load generator, an e2e test, or an OpenAPI/Postman
 * example literally builds the correct request. This finds those artefacts and
 * hands their content to the generator.
 *
 * <p>Deliberately format-agnostic: it does not parse Locust vs k6 vs OpenAPI. It
 * locates the files by conventional names/paths and passes their (size-capped)
 * text through, letting the LLM read the fields. So it generalises to any project
 * that ships such artefacts, with no per-project or per-framework logic. A project
 * that ships none simply yields nothing here, and payload help falls back to the
 * failure-response feedback loop or a human hint.
 */
@Component
public class ExampleRequestHarvester {

    private static final int MAX_FILES = 12;
    private static final int MAX_FILE_BYTES = 8 * 1024;
    private static final int MAX_TOTAL_BYTES = 24 * 1024;

    /**
     * @return a rendered block of the project's example requests, or an empty
     *         string when the repository ships none. Never throws.
     */
    public String harvest(Path root) {
        List<Path> artefacts = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !SourceScanner.isIgnored(root, p))
                    .filter(p -> isExampleArtifact(SourceScanner.relative(root, p)))
                    .forEach(artefacts::add);
        } catch (Exception ignored) {
            // unwalkable tree: whatever was collected still stands
        }
        if (artefacts.isEmpty()) return "";

        // Shortest path first: a top-level locustfile/openapi is more canonical than a
        // deeply nested fixture, and this keeps the selection stable across runs.
        artefacts.sort((a, b) -> Integer.compare(a.getNameCount(), b.getNameCount()));

        StringBuilder sb = new StringBuilder();
        sb.append("# Example requests harvested from the project's own load tests, e2e tests and API specs.\n");
        sb.append("# These build REAL, valid requests against this system — copy their field names and\n");
        sb.append("# values when constructing write-path payloads (login, deposit, checkout, …).\n\n");

        int files = 0;
        int total = 0;
        for (Path file : artefacts) {
            if (files >= MAX_FILES || total >= MAX_TOTAL_BYTES) break;
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue; // binary or unreadable: skip
            }
            if (content.isBlank()) continue;
            if (content.length() > MAX_FILE_BYTES) {
                content = content.substring(0, MAX_FILE_BYTES) + "\n… (truncated)";
            }
            sb.append("## ").append(SourceScanner.relative(root, file)).append('\n');
            sb.append(content).append("\n\n");
            files++;
            total += content.length();
        }
        return files == 0 ? "" : sb.toString();
    }

    /**
     * A file whose conventional name/path marks it as a load test, an e2e test or an
     * API spec — the places a project keeps ready-made valid requests. Matched by
     * convention only, so no framework is special-cased.
     */
    private static boolean isExampleArtifact(String relativePath) {
        String path = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);

        // Load generators / load tests (Locust, k6, JMeter, Gatling).
        if (name.startsWith("locustfile")) return true;
        if (path.contains("loadgenerator") || path.contains("loadtest")
                || path.contains("load-test") || path.contains("load_test")) {
            return isSourceLike(name);
        }
        // End-to-end tests that drive real HTTP journeys.
        if (path.contains("/e2e/") || name.contains("e2e")) {
            return isSourceLike(name);
        }
        // API specifications, which carry example values.
        if (name.startsWith("openapi") || name.startsWith("swagger")) {
            return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
        }
        // Exported Postman collections are literally ordered example requests.
        if (name.contains("postman") && name.endsWith(".json")) return true;

        return false;
    }

    private static boolean isSourceLike(String name) {
        return name.endsWith(".py") || name.endsWith(".js") || name.endsWith(".ts")
                || name.endsWith(".java") || name.endsWith(".go") || name.endsWith(".rb")
                || name.endsWith(".scala") || name.endsWith(".jmx") || name.endsWith(".json");
    }
}
