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
 * externalized-configuration order), and yas's BFFs say {@code active: dev} there. So when
 * any deployment file states a profile, those are the active ones; the application files'
 * own value counts only when no deployment file says (a repository run from the IDE). The
 * pooling is per repository, not per module: a module no deployment file covers is judged
 * by the others'.
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

    /** Stated by a deployment file: Compose, a manifest, a chart's values, a Dockerfile, an entry point. */
    private final Set<String> deployed = new LinkedHashSet<>();
    /** Stated by an application's own application*.yml / bootstrap*.yml. */
    private final Set<String> defaults = new LinkedHashSet<>();
    /** The set the file being read states into. */
    private Set<String> target = deployed;
    private boolean grouped;

    /** The profiles the files under root activate: the config files given, Dockerfiles, and the build files' entry points. */
    static ActiveProfiles scan(Path root, List<Path> configFiles) {
        ActiveProfiles profiles = new ActiveProfiles();
        List<Path> sources = new ArrayList<>(configFiles);
        sources.addAll(DockerReader.dockerfiles(root));
        sources.addAll(DockerReader.dotenvFiles(root));
        for (Path build : SourceScanner.filesWithExtensions(root, List.of("pom.xml", "build.gradle", "build.gradle.kts"))) {
            sources.add(build);
        }
        for (Path file : new LinkedHashSet<>(sources)) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            try {
                profiles.read(Files.readAllLines(file, StandardCharsets.UTF_8),
                        !(name.startsWith("application") || name.startsWith("bootstrap")));
            } catch (Exception ignored) {
                // unreadable or not text: it activates nothing
            }
        }
        return profiles;
    }

    /** One file's statements, into the deployment's set or the applications' own. */
    void read(List<String> lines, boolean deployment) {
        target = deployment ? deployed : defaults;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
            if (PROPERTIES_GROUP.matcher(line).find()) grouped = true;
            Matcher a = ASSIGNMENT.matcher(line);
            if (a.find()) {
                addValue(a.group(1));
                continue;
            }
            Matcher env = DOCKER_ENV.matcher(line);
            if (env.matches()) {
                addValue(env.group(1));
                continue;
            }
            if (ENV_NAME.matcher(line).matches()) {
                for (int j = i + 1; j < Math.min(lines.size(), i + 3); j++) {
                    Matcher v = ENV_VALUE.matcher(lines.get(j));
                    if (v.matches()) {
                        addValue(v.group(1));
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
                else if (!key.group(3).isBlank()) addValue(key.group(3));
                int j = i + 1;
                // A block list may sit at the key's own indent (active:\n- dev), as ecommerce writes it.
                while (j < lines.size() && (lines.get(j).isBlank() || leading(lines.get(j)) > keyIndent
                        || leading(lines.get(j)) == keyIndent && LIST_ITEM.matcher(lines.get(j)).matches())) {
                    Matcher item = LIST_ITEM.matcher(lines.get(j));
                    if (item.matches() && !key.group(2).equals("group")) addValue(item.group(2));
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
    private void addValue(String raw) {
        String value = raw.replaceAll("\\s+#.*$", "").trim();
        Matcher p = PLACEHOLDER.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (p.find()) p.appendReplacement(resolved, Matcher.quoteReplacement(p.group(1) == null ? "" : p.group(1)));
        p.appendTail(resolved);
        // The value ends where the statement does: its closing quote or bracket, or the next
        // flag of a command line (-Dspring.profiles.active=${P}", "-jar" leaves nothing).
        String v = resolved.toString().replaceFirst("^[\\[\"'`\\s]+", "");
        v = VALUE_END.split(v, 2)[0];
        for (String token : v.split("[,\\s]+")) {
            if (PROFILE_NAME.matcher(token).matches()) target.add(token.toLowerCase(Locale.ROOT));
        }
    }

    private static final Pattern VALUE_END = Pattern.compile("[\"'`\\]);<]|\\s-");

    private static int leading(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    /** Whether the repository states which profiles run at all. */
    boolean known() {
        return !active().isEmpty() && !grouped;
    }

    /** The deployment's profiles when it states any, else the applications' own. */
    Set<String> active() {
        return deployed.isEmpty() ? defaults : deployed;
    }

    /** The profile a Spring config file is for ({@code application-dev.yaml} → dev), or null for a base file. */
    static String profileOfFile(String fileName) {
        Matcher m = PROFILE_FILE.matcher(fileName);
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** Whether configuration for this profile expression is never loaded by the deployment. */
    boolean inactive(String expression) {
        if (expression == null || !known()) return false;
        String e = expression.toLowerCase(Locale.ROOT);
        if (e.contains("!")) return false;
        for (String name : e.split("[,|&()\\s]+")) {
            if (name.isEmpty()) continue;
            if (name.equals("default") || active().contains(name)) return false;
        }
        return true;
    }
}
