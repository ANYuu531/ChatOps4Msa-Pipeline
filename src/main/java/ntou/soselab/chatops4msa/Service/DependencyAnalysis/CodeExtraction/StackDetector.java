package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Decides which languages/frameworks a cloned repository contains, by reading its
 * build manifests.
 *
 * This replaces the previous approach of asking DeepWiki "is this Spring Boot?
 * answer YES or NO" — that spent an LLM call, and a network round-trip, guessing
 * at something the manifest states outright. Detection is now deterministic.
 */
@Component
public class StackDetector {

    /** Languages we have a bundled tree-sitter grammar for. */
    private static final Map<String, List<String>> GRAMMAR_LANGUAGES = Map.of(
            "java", List.of(".java"),
            "python", List.of(".py"));

    /** Languages we can recognise but have no grammar for — these go to the LLM tier. */
    private static final Map<String, List<String>> LLM_LANGUAGES = new LinkedHashMap<>();

    static {
        LLM_LANGUAGES.put("go", List.of(".go"));
        LLM_LANGUAGES.put("javascript", List.of(".js", ".jsx", ".ts", ".tsx"));
        LLM_LANGUAGES.put("ruby", List.of(".rb"));
        LLM_LANGUAGES.put("csharp", List.of(".cs"));
        LLM_LANGUAGES.put("rust", List.of(".rs"));
        LLM_LANGUAGES.put("php", List.of(".php"));
    }

    private static final int MAX_MANIFEST_BYTES = 512 * 1024;

    /**
     * @return every stack found, ordered by how many source files back it (most first).
     *         Empty if the repository has no recognisable source at all.
     */
    public List<DetectedStack> detect(Path root) {
        Map<String, String> manifests = findManifests(root);
        Map<String, Integer> fileCounts = countSourceFiles(root);

        List<DetectedStack> stacks = new ArrayList<>();

        // --- Java ---
        if (fileCounts.getOrDefault("java", 0) > 0) {
            String manifest = firstPresent(manifests, "pom.xml", "build.gradle", "build.gradle.kts");
            String content = manifest == null ? "" : manifests.get(manifest);
            String framework = null;
            if (content.contains("spring-boot") || content.contains("spring-cloud")
                    || content.contains("org.springframework")) {
                framework = "spring";
            }
            stacks.add(new DetectedStack(
                    "java", framework,
                    framework != null ? DetectedStack.Tier.FRAMEWORK : DetectedStack.Tier.GENERIC,
                    GRAMMAR_LANGUAGES.get("java"),
                    manifest == null ? ".java sources" : manifest));
        }

        // --- Python ---
        if (fileCounts.getOrDefault("python", 0) > 0) {
            String manifest = firstPresent(manifests,
                    "requirements.txt", "pyproject.toml", "setup.py", "Pipfile");
            String content = manifest == null ? "" : manifests.get(manifest).toLowerCase(Locale.ROOT);
            String framework = null;
            if (content.contains("fastapi") || content.contains("flask") || content.contains("django")
                    || content.contains("requests") || content.contains("httpx")
                    || content.contains("kafka") || content.contains("pika")
                    || content.contains("aiohttp")) {
                framework = "web";
            }
            stacks.add(new DetectedStack(
                    "python", framework,
                    framework != null ? DetectedStack.Tier.FRAMEWORK : DetectedStack.Tier.GENERIC,
                    GRAMMAR_LANGUAGES.get("python"),
                    manifest == null ? ".py sources" : manifest));
        }

        // --- languages with no grammar: LLM tier ---
        for (Map.Entry<String, List<String>> entry : LLM_LANGUAGES.entrySet()) {
            String language = entry.getKey();
            if (fileCounts.getOrDefault(language, 0) == 0) continue;
            stacks.add(new DetectedStack(
                    language, null, DetectedStack.Tier.LLM, entry.getValue(),
                    fileCounts.get(language) + " " + String.join("/", entry.getValue()) + " files"));
        }

        stacks.sort((a, b) -> Integer.compare(
                fileCounts.getOrDefault(b.language, 0), fileCounts.getOrDefault(a.language, 0)));
        return stacks;
    }

    // ---------- helpers ----------

    private String firstPresent(Map<String, String> manifests, String... names) {
        for (String name : names) {
            if (manifests.containsKey(name)) return name;
        }
        return null;
    }

    /** Reads every build manifest in the tree, concatenating same-named ones (multi-module repos). */
    private Map<String, String> findManifests(Path root) {
        List<String> wanted = List.of(
                "pom.xml", "build.gradle", "build.gradle.kts",
                "requirements.txt", "pyproject.toml", "setup.py", "Pipfile",
                "go.mod", "package.json", "Gemfile", "Cargo.toml", "composer.json");

        Map<String, String> found = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> wanted.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            if (Files.size(p) > MAX_MANIFEST_BYTES) return;
                            String text = Files.readString(p, StandardCharsets.UTF_8);
                            found.merge(p.getFileName().toString(), text, (a, b) -> a + "\n" + b);
                        } catch (Exception ignored) {
                            // unreadable or non-UTF-8 manifest: skip
                        }
                    });
        } catch (Exception ignored) {
            // unwalkable tree: fall through with whatever was found
        }
        return found;
    }

    /** language -> number of source files, so a repo is not classified by a stray file. */
    private Map<String, Integer> countSourceFiles(Path root) {
        Map<String, String> extensionToLanguage = new LinkedHashMap<>();
        GRAMMAR_LANGUAGES.forEach((lang, exts) -> exts.forEach(e -> extensionToLanguage.put(e, lang)));
        LLM_LANGUAGES.forEach((lang, exts) -> exts.forEach(e -> extensionToLanguage.put(e, lang)));

        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !SourceScanner.isIgnored(root, p))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        if (dot < 0) return;
                        String language = extensionToLanguage.get(name.substring(dot));
                        if (language != null) counts.merge(language, 1, Integer::sum);
                    });
        } catch (Exception ignored) {
            // unwalkable tree
        }
        return counts;
    }
}
