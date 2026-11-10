package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Spring profiles a repository's deployment activates (rule 57). A profile file
 * ({@code application-dev.yaml}) or a profile document ({@code spring.config.activate.on-profile:
 * dev}) is configuration only when that profile is active; which one is active is chosen by
 * the deployment, not by the file — Compose's {@code SPRING_PROFILES_ACTIVE=prod}, a
 * workload's env, a {@code -Dspring.profiles.active=prod} entry point, or the base
 * application.yml's own {@code spring.profiles.active}. yas's two BFFs keep a developer
 * template route ({@code http://localhost:8085}, "Changes for your local development should
 * not be committed") in application-dev.yaml, while Compose and Helm both run prod: read
 * anyway, it became order and payment edges through the loopback rule (2026-11-06).
 *
 * <p>The deployment's statement overrides the application's: an environment variable or a
 * command-line flag outranks {@code spring.profiles.active} in application.yml (Spring Boot's
 * externalized-configuration order), and yas's BFFs say {@code active: dev} there.
 *
 * <p>Judged per module, not per repository (2026-11-10). A config file's module is the
 * directory above its {@code src/}; the profiles in force there are, in order:
 * <ol>
 *   <li>deployment statements <em>of that module</em> — a file under the module directory
 *       (its Dockerfile, its pom's entry point), or a block of a repository-level file
 *       whose name says which module it deploys (a Compose service's key or {@code build:}
 *       context, a workload's container {@code name:} or {@code metadata.name}, a chart
 *       value's top-level key) — the ownership convention (③) applied to a block;</li>
 *   <li>else the module's own application*.yml / bootstrap*.yml value (run from the IDE,
 *       or a module no deployment file covers: its own statement, not another module's);</li>
 *   <li>else every deployment statement of the repository pooled, else every application's
 *       own — the old per-repository reading, for a statement that could not be attributed
 *       (a root Dockerfile shared by all modules, a root {@code .env}).</li>
 * </ol>
 *
 * <p>Conservative where the answer is not stated: with no literal activation anywhere, or a
 * profile group (whose members are active through it), every profile counts, as before.
 * Activations of every deployment variant are pooled, so a profile any variant runs counts;
 * {@code default} always counts (Spring runs it when nothing is active, and a variant that
 * sets nothing exists in most repositories); a negated expression ({@code !prod}) counts.
 */
final class ActiveProfiles {

    /** {@code SPRING_PROFILES_ACTIVE=prod}, {@code spring.profiles.active: dev}, {@code -Dspring.profiles.active=prod}. */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(?:spring[._]profiles[._](?:active|include))[\"']?\\s*[:=]\\s*(.*)");
    /** {@code - name: SPRING_PROFILES_ACTIVE} of a workload's env, the value on a following line. */
    private static final Pattern ENV_NAME = Pattern.compile(
            "(?i)^\\s*-?\\s*name:\\s*[\"']?spring[._]profiles[._](?:active|include)[\"']?\\s*$");
    private static final Pattern ENV_VALUE = Pattern.compile("(?i)^\\s*value:\\s*(.*)");
    /** A Dockerfile's legacy {@code ENV SPRING_PROFILES_ACTIVE docker} (no {@code =}). */
    private static final Pattern DOCKER_ENV = Pattern.compile("(?i)^\\s*ENV\\s+SPRING_PROFILES_(?:ACTIVE|INCLUDE)\\s+(\\S.*)$");
    /** The nested YAML spelling: {@code spring:} / {@code profiles:} / {@code active: dev} or a list under it. */
    private static final Pattern PROFILES_BLOCK = Pattern.compile("^(\\s*)profiles:\\s*$");
    private static final Pattern NESTED_KEY = Pattern.compile("^(\\s*)(active|include|group):\\s*(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)-\\s*(.+)$");
    private static final Pattern PROPERTIES_GROUP = Pattern.compile("(?i)spring\\.profiles\\.group\\.");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^:}]*(?::([^}]*))?}");
    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    /** {@code application-dev.yaml}, {@code bootstrap-docker.properties}: group 1 is the profile. */
    private static final Pattern PROFILE_FILE = Pattern.compile(
            "(?i)^(?:application|bootstrap)-([A-Za-z0-9_.-]+?)\\.(?:ya?ml|properties)$");

    /** A YAML mapping key line ({@code backoffice:}), or a list item that is one ({@code - name: x}). */
    private static final Pattern YAML_KEY = Pattern.compile("^(\\s*)(?:-\\s*)?([A-Za-z0-9_.\\-]+):(?:\\s+(.*))?$");
    /** The lines of a block that say which module it deploys. */
    private static final Pattern NAMING_KEY = Pattern.compile("^\\s*(?:-\\s*)?(name|build|context|container_name):\\s*[\"']?([^\"'#\\s]+)");
    /** Keys that structure a file rather than name a module. */
    private static final Set<String> STRUCTURE_KEYS = Set.of(
            "services", "service", "spec", "template", "containers", "initcontainers", "env", "environment", "metadata",
            "deploy", "data", "stringdata", "build", "args", "labels", "spring", "profiles", "x-common", "volumes", "networks");

    /** One statement: which file, which module names its block carries, which profiles it activates. */
    private record Statement(Path file, Set<String> names, Set<String> profiles, boolean deployment) {
    }

    private final List<Statement> statements = new ArrayList<>();
    private boolean grouped;
    private Path root;
    /** The file being read and whether it is a deployment's; set by {@link #read}. */
    private Path reading;
    private boolean readingDeployment;
    private List<String> readingLines;

    /** The profiles the files under root activate: the config files given, Dockerfiles, and the build files' entry points. */
    static ActiveProfiles scan(Path root, List<Path> configFiles) {
        ActiveProfiles profiles = new ActiveProfiles();
        profiles.root = root.toAbsolutePath().normalize();
        List<Path> sources = new ArrayList<>(configFiles);
        sources.addAll(DockerReader.dockerfiles(root));
        sources.addAll(DockerReader.dotenvFiles(root));
        for (Path build : SourceScanner.filesWithExtensions(root, List.of("pom.xml", "build.gradle", "build.gradle.kts"))) {
            sources.add(build);
        }
        for (Path file : new LinkedHashSet<>(sources)) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            try {
                profiles.read(file, Files.readAllLines(file, StandardCharsets.UTF_8),
                        !(name.startsWith("application") || name.startsWith("bootstrap")));
            } catch (Exception ignored) {
                // unreadable or not text: it activates nothing
            }
        }
        return profiles;
    }

    /** One file's statements, with no file to attribute them to: they count for the whole repository. */
    void read(List<String> lines, boolean deployment) {
        read(null, lines, deployment);
    }

    /** One file's statements, into the deployment's or the applications' own, each with the module names its block carries. */
    void read(Path file, List<String> lines, boolean deployment) {
        reading = file == null ? null : file.toAbsolutePath().normalize();
        readingDeployment = deployment;
        readingLines = lines;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
            if (PROPERTIES_GROUP.matcher(line).find()) grouped = true;
            Matcher a = ASSIGNMENT.matcher(line);
            if (a.find()) {
                addValue(a.group(1), i);
                continue;
            }
            Matcher env = DOCKER_ENV.matcher(line);
            if (env.matches()) {
                addValue(env.group(1), i);
                continue;
            }
            if (ENV_NAME.matcher(line).matches()) {
                for (int j = i + 1; j < Math.min(lines.size(), i + 3); j++) {
                    Matcher v = ENV_VALUE.matcher(lines.get(j));
                    if (v.matches()) {
                        addValue(v.group(1), i);
                        break;
                    }
                }
                continue;
            }
            Matcher block = PROFILES_BLOCK.matcher(line);
            if (block.matches()) i = readProfilesBlock(lines, i, block.group(1).length());
        }
    }

    /** The {@code active:} / {@code include:} entries under a nested {@code profiles:}; returns the last line read. */
    private int readProfilesBlock(List<String> lines, int start, int indent) {
        int i = start + 1;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) { i++; continue; }
            if (leading(line) <= indent) break;
            Matcher key = NESTED_KEY.matcher(line);
            if (key.matches()) {
                int keyIndent = key.group(1).length();
                if (key.group(2).equals("group")) grouped = true;
                else if (!key.group(3).isBlank()) addValue(key.group(3), start);
                int j = i + 1;
                // A block list may sit at the key's own indent (active:\n- dev), as ecommerce writes it.
                while (j < lines.size() && (lines.get(j).isBlank() || leading(lines.get(j)) > keyIndent
                        || leading(lines.get(j)) == keyIndent && LIST_ITEM.matcher(lines.get(j)).matches())) {
                    Matcher item = LIST_ITEM.matcher(lines.get(j));
                    if (item.matches() && !key.group(2).equals("group")) addValue(item.group(2), start);
                    j++;
                }
                i = j;
                continue;
            }
            i++;
        }
        return i - 1;
    }

    /** One stated value: a comma list, a YAML flow list, a placeholder whose default is the value. */
    private void addValue(String raw, int line) {
        String value = raw.replaceAll("\\s+#.*$", "").trim();
        Matcher p = PLACEHOLDER.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (p.find()) p.appendReplacement(resolved, Matcher.quoteReplacement(p.group(1) == null ? "" : p.group(1)));
        p.appendTail(resolved);
        // The value ends where the statement does: its closing quote or bracket, or the next
        // flag of a command line (-Dspring.profiles.active=${P}", "-jar" leaves nothing).
        String v = resolved.toString().replaceFirst("^[\\[\"'`\\s]+", "");
        v = VALUE_END.split(v, 2)[0];
        Set<String> profiles = new LinkedHashSet<>();
        for (String token : v.split("[,\\s]+")) {
            if (PROFILE_NAME.matcher(token).matches()) profiles.add(token.toLowerCase(Locale.ROOT));
        }
        if (profiles.isEmpty()) return;
        Set<String> names = reading != null && isYaml(reading) ? namesAround(readingLines, line) : Set.of();
        statements.add(new Statement(reading, names, profiles, readingDeployment));
    }

    private static final Pattern VALUE_END = Pattern.compile("[\"'`\\]);<]|\\s-");

    private static boolean isYaml(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".yml") || n.endsWith(".yaml");
    }

    // ---------- which module a YAML block deploys ----------

    /**
     * The module names the block around a statement carries: every enclosing key (a Compose
     * service's key, a chart value's top-level key, a container's {@code - name:} item), the
     * naming lines of those blocks ({@code build:} context, {@code container_name:}), and
     * the document's own {@code name:} ({@code metadata.name} of a workload). Keys that are
     * YAML structure are left out.
     */
    static Set<String> namesAround(List<String> lines, int at) {
        Set<String> names = new LinkedHashSet<>();
        int indent = leading(lines.get(at));
        List<int[]> ancestors = new ArrayList<>(); // {line, indent}
        for (int i = at - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            if (line.startsWith("---")) break;
            Matcher key = YAML_KEY.matcher(line);
            if (!key.matches()) continue;
            int keyIndent = key.group(1).length();
            boolean item = line.trim().startsWith("-");
            // A list item at the statement's own indent is its parent too (env:\n- name: …).
            if (keyIndent < indent || keyIndent == indent && !item && key.group(3) == null) {
                ancestors.add(new int[]{i, keyIndent});
                indent = keyIndent;
                String k = key.group(2);
                if (item && k.equals("name") && key.group(3) != null) addName(names, key.group(3));
                else if (!item && !STRUCTURE_KEYS.contains(k.toLowerCase(Locale.ROOT))) addName(names, k);
            }
        }
        // The naming lines of each enclosing named block: build: ./backoffice-bff, container_name: x.
        // A structural block (services:, containers:) spans every module, so it is not scanned.
        for (int[] a : ancestors) {
            String head = lines.get(a[0]).trim();
            boolean itemBlock = head.startsWith("-");
            Matcher headKey = YAML_KEY.matcher(lines.get(a[0]));
            if (!itemBlock && headKey.matches() && STRUCTURE_KEYS.contains(headKey.group(2).toLowerCase(Locale.ROOT))) continue;
            int blockIndent = a[1];
            for (int i = a[0] + 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) continue;
                int l = leading(line);
                boolean item = line.trim().startsWith("-");
                if (line.startsWith("---") || l < blockIndent || l == blockIndent && (itemBlock || !item)) break;
                if (i == at) continue;
                Matcher n = NAMING_KEY.matcher(line);
                if (n.find() && !ENV_NAME.matcher(line).matches()) addName(names, n.group(2));
            }
        }
        // The document's own name: metadata.name of a workload, the project name of a Compose file.
        int from = 0;
        for (int i = at - 1; i >= 0; i--) if (lines.get(i).startsWith("---")) { from = i + 1; break; }
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i > at && line.startsWith("---")) break;
            Matcher n = NAMING_KEY.matcher(line);
            if (n.find() && n.group(1).equals("name") && leading(line) <= 2 && !ENV_NAME.matcher(line).matches()) addName(names, n.group(2));
        }
        return names;
    }

    private static void addName(Set<String> names, String value) {
        String v = value.trim();
        if (v.matches("[A-Z0-9_]+")) return;   // an env variable's name (SERVER_PORT), not a module's
        if (v.contains("/")) v = v.substring(v.lastIndexOf('/') + 1);   // ./backoffice-bff, ../x
        String k = key(v);
        if (!k.isBlank()) names.add(k);
    }

    /** A name as it is compared: letters and digits only, lower-case. */
    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static int leading(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    // ---------- what is active ----------

    /** Whether the repository states which profiles run at all. */
    boolean known() {
        return !active().isEmpty() && !grouped;
    }

    /** The repository pooled: the deployment's profiles when it states any, else the applications' own. */
    Set<String> active() {
        Set<String> deployed = new LinkedHashSet<>();
        Set<String> defaults = new LinkedHashSet<>();
        for (Statement s : statements) (s.deployment ? deployed : defaults).addAll(s.profiles);
        return deployed.isEmpty() ? defaults : deployed;
    }

    /** The profiles in force for one config file: its module's deployment, else its own statement, else the repository's. */
    Set<String> activeFor(Path configFile) {
        if (configFile == null || root == null) return active();
        Path module = moduleOf(configFile.toAbsolutePath().normalize());
        String moduleKey = module.equals(root) ? "" : key(module.getFileName().toString());
        Set<String> out = new LinkedHashSet<>();
        for (Statement s : statements) {
            if (s.deployment && (under(s.file, module) || !moduleKey.isEmpty() && s.names.contains(moduleKey))) out.addAll(s.profiles);
        }
        if (!out.isEmpty()) return out;
        for (Statement s : statements) if (!s.deployment && under(s.file, module)) out.addAll(s.profiles);
        return out.isEmpty() ? active() : out;
    }

    /** The module a config file belongs to: the directory above its {@code src/}, else its own directory. */
    private Path moduleOf(Path file) {
        Path rel = root.relativize(file);
        int src = -1;
        for (int i = 0; i < rel.getNameCount(); i++) if (rel.getName(i).toString().equals("src")) src = i;
        Path module = src > 0 ? root.resolve(rel.subpath(0, src)) : src == 0 ? root : file.getParent();
        return module == null ? root : module;
    }

    private static boolean under(Path file, Path module) {
        return file != null && file.startsWith(module);
    }

    /** The profile a Spring config file is for ({@code application-dev.yaml} → dev), or null for a base file. */
    static String profileOfFile(String fileName) {
        Matcher m = PROFILE_FILE.matcher(fileName);
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** Whether configuration for this profile expression is never loaded by the deployment (repository-wide). */
    boolean inactive(String expression) {
        return inactive(expression, active());
    }

    /** Whether configuration for this profile expression is never loaded where this config file applies. */
    boolean inactive(String expression, Path configFile) {
        return inactive(expression, activeFor(configFile));
    }

    private boolean inactive(String expression, Set<String> active) {
        if (expression == null || active.isEmpty() || grouped) return false;
        String e = expression.toLowerCase(Locale.ROOT);
        if (e.contains("!")) return false;
        for (String name : e.split("[,|&()\\s]+")) {
            if (name.isEmpty()) continue;
            if (name.equals("default") || active.contains(name)) return false;
        }
        return true;
    }
}
