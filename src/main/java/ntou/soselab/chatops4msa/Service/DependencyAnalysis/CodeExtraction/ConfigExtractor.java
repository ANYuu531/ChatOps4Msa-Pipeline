package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Language-agnostic extraction of service URLs and infrastructure endpoints from
 * configuration files (Spring application.yml/properties, .env, docker-compose).
 *
 * Config is a key/value problem, not a parsing problem, so tree-sitter buys
 * nothing here — this stays a plain reader.
 */
@Component
public class ConfigExtractor {

    private static final int MAX_VALUE_LENGTH = 200;

    public void extract(Path root, EdgeLedger ledger) {
        List<Path> files = SourceScanner.filesWithExtensions(root,
                List.of(".yml", ".yaml", ".properties", ".env"));

        for (Path file : files) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean isSpringConfig = name.startsWith("application") || name.startsWith("bootstrap");
            boolean isEnv = name.equals(".env") || name.startsWith(".env.");
            boolean isCompose = name.startsWith("docker-compose");
            if (!isSpringConfig && !isEnv && !isCompose) continue;

            String relative = SourceScanner.relative(root, file);
            try {
                if (name.endsWith(".properties") || isEnv) {
                    readKeyValueFile(file, relative, ledger);
                } else {
                    Object loaded = new Yaml().load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
                    flatten("", loaded, relative, ledger);
                    // docker-compose declares each service's startup dependencies
                    // (config-server, discovery-server, …) as depends_on — the one
                    // structured place a "service -> control-plane" edge is stated when
                    // the client-side URLs are externalised to a config repo.
                    if (isCompose) extractComposeDependsOn(loaded, relative, ledger);
                }
            } catch (Exception ignored) {
                // unreadable or malformed config: not worth failing the analysis over
            }
        }
    }

    /**
     * Emits a {@code compose-dependency} edge for every {@code services.<svc>.depends_on}
     * entry: {@code source_service -> target_service}. Both the list form
     * ({@code depends_on: [config-server, discovery-server]}) and the map/long form
     * ({@code depends_on: {config-server: {condition: service_healthy}}}) are handled.
     * This is what keeps config-server/discovery-server connected in the graph even when
     * the runtime metrics for those fetches are momentarily absent (e.g. after a restart),
     * since every service declares the dependency here.
     */
    private void extractComposeDependsOn(Object loaded, String relative, EdgeLedger ledger) {
        if (!(loaded instanceof Map<?, ?> root)) return;
        if (!(root.get("services") instanceof Map<?, ?> services)) return;

        for (Map.Entry<?, ?> entry : services.entrySet()) {
            String service = String.valueOf(entry.getKey()).trim();
            if (service.isEmpty() || !(entry.getValue() instanceof Map<?, ?> config)) continue;
            Object dependsOn = config.get("depends_on");

            java.util.List<String> targets = new java.util.ArrayList<>();
            if (dependsOn instanceof List<?> list) {
                for (Object t : list) targets.add(String.valueOf(t).trim());
            } else if (dependsOn instanceof Map<?, ?> map) {
                for (Object k : map.keySet()) targets.add(String.valueOf(k).trim());
            }

            for (String target : targets) {
                if (target.isEmpty() || target.equals(service)) continue;
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("source_service", service);
                fields.put("target_service", target);
                ledger.add("compose-dependency", fields, relative, -1, "High (docker-compose depends_on)");
            }
        }
    }

    private void readKeyValueFile(Path file, String relative, EdgeLedger ledger) throws Exception {
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals < 0) continue;
            record(line.substring(0, equals).trim(), line.substring(equals + 1).trim(), relative, ledger);
        }
    }

    private void flatten(String prefix, Object node, String relative, EdgeLedger ledger) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = prefix.isEmpty()
                        ? String.valueOf(entry.getKey())
                        : prefix + "." + entry.getKey();
                flatten(key, entry.getValue(), relative, ledger);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), relative, ledger);
            }
        } else if (node != null) {
            record(prefix, String.valueOf(node), relative, ledger);
        }
    }

    private void record(String key, String value, String relative, EdgeLedger ledger) {
        if (key.isEmpty() || value.isEmpty() || !isRelevant(key, value)) return;
        if (value.length() > MAX_VALUE_LENGTH) value = value.substring(0, MAX_VALUE_LENGTH) + "...";

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", key);
        fields.put("value", value);
        String confidence = value.contains("${") ? "Medium (property indirection)" : "High";
        ledger.add("config", fields, relative, -1, confidence);
    }

    /** Keys that name another service or a piece of infrastructure. */
    private boolean isRelevant(String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);

        if (k.startsWith("spring.datasource") || k.startsWith("spring.kafka")
                || k.startsWith("spring.rabbitmq") || k.startsWith("spring.data.mongodb")
                || k.startsWith("spring.redis") || k.startsWith("spring.data.redis")
                || k.startsWith("eureka.") || k.startsWith("spring.cloud.consul")) {
            return true;
        }
        if (k.endsWith(".url") || k.endsWith(".uri") || k.endsWith(".host")
                || k.endsWith(".endpoint") || k.endsWith(".address")
                || k.endsWith("_url") || k.endsWith("_uri") || k.endsWith("_host")
                || k.endsWith("_endpoint") || k.endsWith("_addr")) {
            return true;
        }
        // A value that is plainly a URL is worth keeping whatever the key is called.
        return value.startsWith("http://") || value.startsWith("https://");
    }
}
