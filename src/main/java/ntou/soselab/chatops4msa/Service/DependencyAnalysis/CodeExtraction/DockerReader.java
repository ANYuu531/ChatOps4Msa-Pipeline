package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
import org.yaml.snakeyaml.Yaml;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dockerfiles ({@code ENV}, {@code FROM}, the command line), the images build scripts tag,
 * and a module's own dotenv files (rule 50).
 */
final class DockerReader {

    private final DescriptorRows rows;

    DockerReader(DescriptorRows rows) {
        this.rows = rows;
    }

    /** {@code ENV K=V}, {@code ENV K V} and the multi-pair / quoted forms Docker accepts. */
    static final java.util.regex.Pattern DOCKER_ENV_PAIR = java.util.regex.Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|'[^']*'|\\S+)");

    /** Every Dockerfile (any case, any suffix such as Dockerfile.prod) outside ignored trees. */
    static List<Path> dockerfiles(Path root) {
        List<Path> found = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("dockerfile"))
                    .filter(p -> !SourceScanner.isIgnored(root, p))
                    .forEach(found::add);
        } catch (Exception ignored) {
            // unwalkable tree: return whatever was collected
        }
        return found;
    }

    /**
     * The {@code ENV} lines of a Dockerfile as {@code env-address} rows scoped to the
     * image's module ({@code workload=dockerfile}; the merger attributes the file to its
     * module directory). Continuation lines ({@code \}) are joined first, since one ENV
     * instruction commonly spans several lines with one pair each.
     */
    void extractDockerfileEnv(Path file, String relative, EdgeLedger ledger) throws Exception {
        StringBuilder joined = new StringBuilder();
        List<String> logical = new java.util.ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (joined.length() == 0 && (line.isEmpty() || line.startsWith("#"))) continue;
            if (line.endsWith("\\")) {
                joined.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            joined.append(line);
            logical.add(joined.toString());
            joined.setLength(0);
        }
        if (joined.length() > 0) logical.add(joined.toString());
        String dir = relative.replace('\\', '/');
        dir = dir.contains("/") ? dir.substring(0, dir.lastIndexOf('/')) : "";
        for (String line : logical) {
            // FROM names the image this one is built on. When that image is built from a
            // directory of this repo (a build script or Compose says so), that directory's
            // command line and start scripts run inside every image built on it: TeaStore's
            // base image writes the registry URL from REGISTRY_HOST into Tomcat's context.xml
            // in its start.sh, and five services inherit it (2026-11-04).
            if (line.regionMatches(true, 0, "FROM ", 0, 5)) {
                String[] words = line.substring(5).trim().split("\\s+");
                int i = 0;
                while (i < words.length && words[i].startsWith("--")) i++;
                String stem = i < words.length ? ComposeReader.imageStem(words[i]) : null;
                if (stem != null && !stem.isEmpty()) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("dir", dir);
                    fields.put("image", stem);
                    ledger.add("dockerfile-from", fields, relative, -1, "High (Dockerfile FROM)");
                }
                continue;
            }
            // The image's command line is a use site like a Compose command: (rule 53) —
            // Online Boutique's load generator runs `locust --host="http://${FRONTEND_ADDR}"`
            // and reads the address nowhere else. Emitted as a url row of the module that
            // owns the Dockerfile; the merger fills the placeholder from that module's
            // workload env, exactly as for a URL literal in its code (2026-11-04).
            if (line.regionMatches(true, 0, "ENTRYPOINT ", 0, 11) || line.regionMatches(true, 0, "CMD ", 0, 4)) {
                String body = line.substring(line.indexOf(' ') + 1).replaceAll("[\\[\\]\"',]", " ");
                java.util.regex.Matcher url = DOCKER_COMMAND_URL.matcher(body);
                while (url.find()) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("value", url.group(1));
                    fields.put("via", "dockerfile-command");
                    RuleTrace.hit(53, "dockerfile " + relative + " " + url.group(1));
                    ledger.add("url", fields, relative, -1, "High (image command line)");
                }
                continue;
            }
            if (!line.regionMatches(true, 0, "ENV ", 0, 4)) continue;
            String body = line.substring(4).trim();
            java.util.regex.Matcher m = DOCKER_ENV_PAIR.matcher(body);
            boolean any = false;
            while (m.find()) {
                any = true;
                rows.recordEnvAddress(m.group(1), DescriptorRows.unquote(m.group(2)), null, "dockerfile", relative, ledger);
            }
            if (!any) {
                // Legacy form: ENV KEY value with spaces
                int space = body.indexOf(' ');
                if (space > 0) rows.recordEnvAddress(body.substring(0, space), DescriptorRows.unquote(body.substring(space + 1).trim()),
                        null, "dockerfile", relative, ledger);
            }
        }
        // The shell scripts beside a Dockerfile are the image's start-up code (TeaStore's
        // start.sh): a URL in them, placeholders and all, is a use site like the command line.
        Path parent = file.getParent();
        if (parent == null) return;
        try (java.util.stream.Stream<Path> siblings = Files.list(parent)) {
            for (Path script : siblings.filter(p -> p.getFileName().toString().endsWith(".sh")).sorted().toList()) {
                String[] lines;
                try {
                    if (Files.size(script) > 200_000) continue;
                    lines = Files.readString(script, StandardCharsets.UTF_8).split("\n", -1);
                } catch (Exception e) {
                    continue;
                }
                String scriptRel = dir.isEmpty() ? script.getFileName().toString() : dir + "/" + script.getFileName();
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].trim().startsWith("#")) continue;
                    java.util.regex.Matcher url = DOCKER_COMMAND_URL.matcher(lines[i].replace("\\\"", " ").replace("\"", " ").replace("'", " ").replace("|", " "));
                    while (url.find()) {
                        Map<String, String> fields = new LinkedHashMap<>();
                        fields.put("value", url.group(1));
                        fields.put("via", "image-script");
                        RuleTrace.hit(53, "image-script " + scriptRel + ":" + (i + 1) + " " + url.group(1));
                        ledger.add("url", fields, scriptRel, i + 1, "High (image start script)");
                    }
                }
            }
        } catch (Exception ignored) {
            // an unreadable directory listing leaves the Dockerfile's own rows in place
        }
    }

    /** {@code docker build -t image path} in a shell script: the directory an image is built from. */
    static final java.util.regex.Pattern DOCKER_BUILD = java.util.regex.Pattern.compile(
            "docker\\s+(?:buildx\\s+)?build\\b([^\\n;&|]*)");
    static final java.util.regex.Pattern DOCKER_BUILD_TAG = java.util.regex.Pattern.compile("(?:^|\\s)(?:-t|--tag)[=\\s]+\"?([^\\s\"]+)\"?");

    /**
     * The images this repo builds from its own directories, from build scripts: TeaStore's
     * tools/build_docker.sh runs {@code docker buildx build -t "${registry}teastore-base"
     * ../utilities/tools.descartes.teastore.dockerbase/}. Emitted as image-build rows
     * (image stem → repo-relative directory) for the merger's FROM chain.
     */
    void extractImageBuilds(Path root, EdgeLedger ledger) {
        for (Path script : SourceScanner.filesWithExtensions(root, List.of(".sh"))) {
            String text;
            try {
                if (Files.size(script) > 200_000) continue;
                text = Files.readString(script, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            if (!text.contains("docker")) continue;
            String relative = SourceScanner.relative(root, script);
            Path scriptDir = script.getParent() == null ? root : script.getParent();
            java.util.regex.Matcher b = DOCKER_BUILD.matcher(text.replace("\\\n", " "));
            while (b.find()) {
                String args = b.group(1);
                java.util.regex.Matcher t = DOCKER_BUILD_TAG.matcher(args);
                if (!t.find()) continue;
                String stem = ComposeReader.imageStem(t.group(1));
                if (stem == null || stem.isEmpty()) continue;
                // the build context is the last argument that is a path
                String context = null;
                for (String w : args.trim().split("\\s+")) {
                    if (w.startsWith("-") || w.startsWith("\"")) continue;
                    if (w.equals(".") || w.contains("/")) context = w;
                }
                if (context == null) continue;
                String dir = ComposeReader.repoRelativeDir(root, scriptDir, context.replace("\"", ""), false);
                if (dir == null) continue;
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("image", stem);
                fields.put("dir", dir);
                ledger.add("image-build", fields, relative, -1, "High (build script)");
            }
        }
    }

    /** A URL in an image's command line, its {@code ${ENV}} placeholders kept for the merger to fill. */
    static final java.util.regex.Pattern DOCKER_COMMAND_URL = java.util.regex.Pattern.compile("(https?://[^\\s]+)");

    /** {@code .env} and {@code .env.<mode>} files outside ignored trees, templates excluded. */
    static List<Path> dotenvFiles(Path root) {
        List<Path> found = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return (n.equals(".env") || n.startsWith(".env."))
                                && !n.matches(".*\\.(sample|example|template|dist|tmpl)$");
                    })
                    .filter(p -> !SourceScanner.isIgnored(root, p))
                    .forEach(found::add);
        } catch (Exception ignored) {
            // unwalkable tree: return whatever was collected
        }
        return found;
    }

    static boolean hasComposeFile(Path dir) {
        try (java.util.stream.Stream<Path> listing = Files.list(dir)) {
            return listing.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return (n.startsWith("docker-compose") || n.startsWith("compose.")) && (n.endsWith(".yml") || n.endsWith(".yaml"));
            });
        } catch (Exception e) {
            return false;
        }
    }

}
