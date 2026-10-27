package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
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

    /**
     * Whether a directory, Compose service or workload name says it is a test runner:
     * the directory rule above, plus a bare {@code …tests} suffix ({@code frontendTests},
     * the OpenTelemetry demo's Cypress container). Shared with the Compose reader so a
     * test container in an overlay is excluded the way a tests/ directory is (2026-10-26).
     */
    static boolean isTestName(String name) {
        if (name == null) return false;
        String n = name.trim();
        return TEST_DIR.matcher(n).find() || n.toLowerCase(Locale.ROOT).endsWith("tests")
                || TEST_ROOT_DIRS.contains(n.toLowerCase(Locale.ROOT));
    }

    /**
     * Directory names of end-to-end suites that the suffix rule does not catch: an
     * {@code automation-ui} tree holds one Selenium module per UI, each named after the
     * service it drives, so its {@code application.properties} would otherwise be read as
     * that service's (yas, 2026-10-26). Applied to every segment of a module path.
     */
    private static final Set<String> TEST_ROOT_DIRS = Set.of("automation-ui", "e2e", "__tests__");

    /** Whether any directory on the path says the module is a test suite. */
    static boolean underTestDir(String relDir) {
        for (String seg : relDir.replace('\\', '/').split("/")) {
            if (isTestName(seg)) return true;
        }
        return false;
    }

    /** A marker under a hidden directory ({@code .bingo/go.mod}, {@code .devcontainer/Dockerfile}) is tooling, not a service. */
    private static boolean inHiddenDir(Path root, Path file) {
        Path rel = root.relativize(file.getParent() == null ? file : file.getParent());
        for (Path seg : rel) {
            String s = seg.toString();
            if (s.length() > 1 && s.startsWith(".")) return true;
        }
        return false;
    }

    public void scan(Path root, EdgeLedger ledger) {
        // dir -> the marker file that justified it (first one wins, for stable provenance).
        Map<Path, String> serviceDirs = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !SourceScanner.isIgnored(root, p))
                    .forEach(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!MARKERS.contains(name)) return;
                        if (RuleTrace.hitIf(inHiddenDir(root, p), 31, "hidden " + SourceScanner.relative(root, p))) return;
                        Path dir = serviceDir(root, p.getParent());
                        if (dir == null) return;
                        RuleTrace.hitIf(!dir.equals(p.getParent()), 2, "climb " + SourceScanner.relative(root, p.getParent()));
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
            if (RuleTrace.hitIf(isAggregator(dir, serviceDirs.keySet()), 2, "aggregator " + relDir)) continue;
            String serviceName = dir.getFileName() == null ? relDir : dir.getFileName().toString();
            if (serviceName.isBlank() || RuleTrace.hitIf(TEST_DIR.matcher(serviceName).find(), 2, "test-dir " + relDir)
                    || RuleTrace.hitIf(underTestDir(relDir), 42, "test-path " + relDir)) continue;

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("dir", relDir);
            RuleTrace.hitIf(!serviceName(serviceName).equals(serviceName), 2, "name " + serviceName + "=" + serviceName(serviceName));
            fields.put("name", serviceName(serviceName));
            ledger.add("service-root", fields, relDir + "/" + entry.getValue(), -1,
                    "High (build manifest / Dockerfile)");
            recordModuleDeps(root, dir, relDir, ledger);
        }
    }

    /** The Maven artifactIds inside {@code <dependency>} blocks. */
    private static final java.util.regex.Pattern POM_ARTIFACT = java.util.regex.Pattern.compile(
            "<dependency>\\s*(?:<groupId>[^<]*</groupId>\\s*)?<artifactId>([^<]+)</artifactId>", java.util.regex.Pattern.DOTALL);
    /** Gradle coordinates: {@code implementation 'org.x:artifact:1.0'} / {@code implementation("org.x:artifact")}. */
    private static final java.util.regex.Pattern GRADLE_ARTIFACT = java.util.regex.Pattern.compile(
            "[\"']([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)(?::[^\"']*)?[\"']");

    /**
     * Records what a module declares it is built with ({@code module-deps}: the directory,
     * its dependency artifact names, and whether it is a library rather than a service).
     *
     * <p>Two merge rules read this. A client library's auto-configuration is what makes a
     * config key take effect, so a shared config file's {@code spring.rabbitmq.host} binds
     * only in modules that declare an AMQP artifact, and a module with the Eureka client
     * starter but no {@code defaultZone} registers at the framework's default. And a Python
     * package ({@code setup.py} / {@code pyproject.toml}, no Dockerfile) is not a service:
     * the calls in its code belong to the modules that import it (2026-10-23).
     */
    private static void recordModuleDeps(Path root, Path dir, String relDir, EdgeLedger ledger) {
        Set<String> artifacts = new java.util.TreeSet<>();
        String manifest = null;
        boolean pythonPackaging = false;
        boolean dockerfile = false;
        boolean appManifest = false;
        boolean jvmBuild = false;        // a pom.xml or build.gradle(.kts)
        boolean jvmRunnable = false;     // the build packages a runnable (Boot plugin, war/ear, application plugin)
        String artifactId = null;
        // The manifests may sit in a wrapper directory the scan climbed out of (app/requirements.txt),
        // so the directory itself and its generic-named children are read.
        List<Path> candidates = new java.util.ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                candidates.add(p);
                if (Files.isDirectory(p) && GENERIC_DIRS.contains(p.getFileName().toString().toLowerCase(Locale.ROOT))) {
                    try (Stream<Path> inner = Files.list(p)) {
                        candidates.addAll(inner.toList());
                    }
                }
            }
        } catch (Exception ignored) {
            // unreadable directory: record what was found
        }
        try {
            for (Path p : candidates) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith("dockerfile")) dockerfile = true;
                if (name.equals("setup.py") || name.equals("pyproject.toml")) pythonPackaging = true;
                if (name.equals("requirements.txt") || name.equals("pipfile") || name.equals("pom.xml")
                        || name.startsWith("build.gradle") || name.equals("package.json") || name.equals("go.mod")) {
                    appManifest = true;
                }
                if (!Files.isRegularFile(p) || Files.size(p) > 512 * 1024) continue;
                if (name.equals("pom.xml")) {
                    String text = Files.readString(p);
                    java.util.regex.Matcher m = POM_ARTIFACT.matcher(text);
                    while (m.find()) artifacts.add(m.group(1).trim());
                    manifest = p.getFileName().toString();
                    jvmBuild = true;
                    String packaging = firstGroup(POM_PACKAGING, text);
                    if (text.contains("spring-boot-maven-plugin") || "war".equalsIgnoreCase(packaging)
                            || "ear".equalsIgnoreCase(packaging) || text.contains("maven-shade-plugin")
                            || text.contains("maven-assembly-plugin") || text.contains("jib-maven-plugin")) {
                        jvmRunnable = true;
                    }
                    artifactId = ownArtifactId(text);
                } else if (name.startsWith("build.gradle")) {
                    String text = Files.readString(p);
                    java.util.regex.Matcher m = GRADLE_ARTIFACT.matcher(text);
                    while (m.find()) artifacts.add(m.group(2).trim());
                    manifest = p.getFileName().toString();
                    jvmBuild = true;
                    if (text.contains("org.springframework.boot") || text.contains("application")
                            || text.contains("shadow") || text.contains("jib")) {
                        jvmRunnable = true;
                    }
                } else if (name.equals("requirements.txt")) {
                    manifest = p.getFileName().toString();
                    for (String line : Files.readAllLines(p)) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#") || t.startsWith("-")) continue;
                        String pkg = t.split("[\\s\\[<>=!~;@]", 2)[0].trim().toLowerCase(Locale.ROOT);
                        if (!pkg.isEmpty()) artifacts.add(pkg);
                    }
                }
            }
        } catch (Exception ignored) {
            // unreadable directory: record what was found
        }
        // A Dockerfile may sit in a wrapper directory the scan climbed out of (app/Dockerfile).
        if (!dockerfile) {
            try (Stream<Path> stream = Files.walk(dir, 2)) {
                dockerfile = stream.anyMatch(p -> Files.isRegularFile(p)
                        && p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("dockerfile"));
            } catch (Exception ignored) {
                // keep false
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("dir", relDir);
        if (!artifacts.isEmpty()) fields.put("artifacts", String.join(",", artifacts));
        if (manifest != null) fields.put("manifest", manifest);
        if (artifactId != null) fields.put("artifact", artifactId);
        // A JVM module that nothing packages as a runnable — no Dockerfile, no Boot /
        // shade / assembly plugin, no war, and not one `main` under src/main — is a library
        // (yas's common-library, train-ticket's ts-common): its application.properties is
        // for its own tests, and its code runs inside the services that depend on it. The
        // Java analogue of the Python rule below; the merger still lets a deployment name
        // (a workload or a Compose build context) overrule it (2026-10-26).
        if (jvmBuild && !dockerfile && !jvmRunnable && !pythonPackaging
                && Files.isDirectory(dir.resolve("src")) && !hasMainMethod(dir)) {
            fields.put("library", "true");
            fields.put("library_kind", "jvm");
        }
        // A Python distribution with no image of its own is a library, not a workload; its
        // importable top-level packages let the merger find who imports it.
        if (pythonPackaging && !dockerfile && !appManifest) {
            fields.put("library", "true");
            fields.put("library_kind", "python");
            List<String> packages = new java.util.ArrayList<>();
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path p : stream.toList()) {
                    if (Files.isDirectory(p) && Files.exists(p.resolve("__init__.py"))) {
                        packages.add(p.getFileName().toString());
                    }
                }
            } catch (Exception ignored) {
                // no packages found
            }
            if (!packages.isEmpty()) fields.put("packages", String.join(",", packages));
        }
        if (fields.size() > 1) {
            ledger.add("module-deps", fields, relDir, -1, "High (build manifest)");
        }
    }

    private static final java.util.regex.Pattern POM_PACKAGING = java.util.regex.Pattern.compile("<packaging>\\s*([^<\\s]+)\\s*</packaging>");
    private static final java.util.regex.Pattern POM_ANY_ARTIFACT = java.util.regex.Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    /** A JVM entry point: Java/Kotlin/Scala/Groovy spellings of {@code main}. */
    private static final java.util.regex.Pattern MAIN_METHOD = java.util.regex.Pattern.compile(
            "\\bvoid\\s+main\\s*\\(|\\bfun\\s+main\\s*\\(|\\bdef\\s+main\\s*\\(|@SpringBootApplication");

    private static String firstGroup(java.util.regex.Pattern p, String text) {
        java.util.regex.Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * The module's own {@code <artifactId>}: the first one outside the {@code <parent>},
     * {@code <dependencies>}, {@code <dependencyManagement>} and {@code <build>} blocks.
     * A Compose service named exactly like it is the same unit under its deployment name.
     */
    static String ownArtifactId(String pom) {
        String stripped = pom
                .replaceAll("(?s)<parent>.*?</parent>", "")
                .replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "")
                .replaceAll("(?s)<dependencies>.*?</dependencies>", "")
                .replaceAll("(?s)<build>.*?</build>", "")
                .replaceAll("(?s)<profiles>.*?</profiles>", "")
                .replaceAll("(?s)<!--.*?-->", "");
        return firstGroup(POM_ANY_ARTIFACT, stripped);
    }

    /** Whether any JVM source under {@code src/main} (or a flat {@code src}) declares an entry point. */
    private static boolean hasMainMethod(Path dir) {
        Path src = dir.resolve("src");
        if (!Files.isDirectory(src)) return false;
        try (Stream<Path> stream = Files.walk(src, 16)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String rel = src.relativize(p).toString().replace('\\', '/');
                        if (rel.startsWith("test/") || rel.startsWith("it/")) return false;
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".scala") || n.endsWith(".groovy");
                    })
                    .anyMatch(p -> {
                        try {
                            if (Files.size(p) > 512 * 1024) return false;
                            return MAIN_METHOD.matcher(Files.readString(p)).find();
                        } catch (Exception e) {
                            return false;
                        }
                    });
        } catch (Exception e) {
            return false;
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
