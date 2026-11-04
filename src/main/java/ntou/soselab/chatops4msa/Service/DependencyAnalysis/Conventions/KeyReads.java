package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.SourceScanner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Convention ③ for a source injected wholesale (an {@code envFrom} ConfigMap, an env_file): a key
 * in it is a workload's only if the workload reads it. This pass finds the readers: the key's
 * name in a module's code or own config, or the framework's documented binding (Spring Boot's
 * relaxed binding, ASP.NET Core's {@code __}). One {@code key-read} row per file and key.
 */
public final class KeyReads {

    private KeyReads() {
    }

    /** Where a key of a shared source can be read: source code and the module's own config files. */
    static final List<String> KEY_READ_EXTENSIONS = Vocabulary.KEY_READ_EXTENSIONS;

    /**
     * Convention ③ (rule 14 widened, 2026-11-03): a value is a workload's own when someone
     * chose it for that workload — its own env block, a {@code configMapKeyRef}, a key the
     * Compose file forwards by name. A source injected wholesale — an {@code envFrom}
     * ConfigMap, an env_file — chooses nothing; a key in it is the workload's only if the
     * workload reads it. The edge audit of the 100-system corpus found seven edges drawn from
     * shared env_files and ConfigMaps to workloads that never read them, a postgres container
     * among them; rule 14 had said this for ConfigMaps of service addresses only.
     *
     * <p>Reading is shown three ways, all in the repository: the key's name in the module's
     * code or own config ({@code os.Getenv("MONGO_CONN")}, {@code ${KAFKA_SERVERS}}), or the
     * framework's documented binding of environment variables — Spring Boot's relaxed binding
     * ({@code SPRING_DATASOURCE_URL} is {@code spring.datasource.url}) for a module that builds
     * with Spring Boot, ASP.NET Core's {@code __} separator ({@code ConnectionStrings__Db}) for
     * a .NET project. One {@code key-read} row per file and key; the merger joins the file to
     * its service. Deployment descriptors and the sources themselves are not readers.
     */
    public static void record(Path root, EdgeLedger ledger) {
        Set<String> keys = new java.util.TreeSet<>();
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            String key = e.fields.get("name");
            if (key == null || key.isBlank()) continue;
            if ("env-address".equals(e.section) && e.fields.containsKey("configmap")) keys.add(key);
            if ("workload-env".equals(e.section) && e.fields.containsKey("shared")) keys.add(key);
        }
        if (keys.isEmpty()) return;
        java.util.regex.Pattern any = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])("
                + String.join("|", keys.stream().map(java.util.regex.Pattern::quote).toList()) + ")(?![A-Za-z0-9_])");
        boolean spring = keys.stream().anyMatch(k -> k.startsWith("SPRING_"));
        boolean dotnet = keys.stream().anyMatch(k -> k.contains("__"));
        for (Path file : SourceScanner.filesWithExtensions(root, KEY_READ_EXTENSIONS)) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.startsWith("docker-compose") || name.startsWith("compose.") || name.startsWith(".env") || name.endsWith(".env")) continue;
            String text;
            try {
                if (Files.size(file) > 1_000_000) continue;
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            if ((name.endsWith(".yml") || name.endsWith(".yaml")) && K8S_KIND.matcher(text).find()) continue;
            String relative = SourceScanner.relative(root, file);
            Set<String> seen = new java.util.HashSet<>();
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                java.util.regex.Matcher m = any.matcher(lines[i]);
                while (m.find()) {
                    if (seen.add(m.group(1))) addKeyRead(m.group(1), null, relative, i + 1, ledger);
                }
            }
            if (spring && (name.equals("pom.xml") || name.startsWith("build.gradle")) && text.contains("spring-boot")) {
                for (String k : keys) if (k.startsWith("SPRING_") && seen.add(k)) addKeyRead(k, "spring", relative, -1, ledger);
            }
            if (dotnet && name.endsWith(".csproj")) {
                for (String k : keys) if (k.contains("__") && seen.add(k)) addKeyRead(k, "dotnet", relative, -1, ledger);
            }
        }
    }

    static final java.util.regex.Pattern K8S_KIND = java.util.regex.Pattern.compile("(?m)^\\s*kind:\\s*[A-Z]\\w+\\s*$");

    static void addKeyRead(String key, String binding, String relative, int line, EdgeLedger ledger) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", key);
        if (binding != null) fields.put("binding", binding);
        ledger.add("key-read", fields, relative, line, binding == null ? "High (key named in the reader)" : "Medium (framework binding)");
    }

}
