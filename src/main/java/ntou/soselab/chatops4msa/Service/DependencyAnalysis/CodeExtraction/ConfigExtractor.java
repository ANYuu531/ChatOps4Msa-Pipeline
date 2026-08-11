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
            boolean isYaml = name.endsWith(".yml") || name.endsWith(".yaml");

            String relative = SourceScanner.relative(root, file);
            try {
                if (name.endsWith(".properties") || isEnv) {
                    readKeyValueFile(file, relative, ledger);
                } else if (isSpringConfig || isCompose) {
                    Object loaded = new Yaml().load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
                    flatten("", loaded, relative, ledger);
                    // docker-compose declares each service's startup dependencies
                    // (config-server, discovery-server, …) as depends_on — the one
                    // structured place a "service -> control-plane" edge is stated when
                    // the client-side URLs are externalised to a config repo.
                    if (isCompose) extractComposeDependsOn(loaded, relative, ledger);
                } else if (isYaml) {
                    // Any other YAML may be a k8s manifest holding the env -> host table
                    // (a ConfigMap of *_API_ADDR values, or literal env in a Deployment).
                    // That table is what resolves an env-indirected call target when there
                    // is no runtime graph — the greenfield case.
                    extractK8sEnvAddresses(file, relative, ledger);
                }
            } catch (Exception ignored) {
                // unreadable or malformed config: not worth failing the analysis over
            }
        }
    }

    /**
     * Reads {@code env-address} entries ({@code name -> host}) from a Kubernetes
     * manifest: every {@code ConfigMap} data value and every literal container
     * {@code env[].value} that names a {@code host:port} or a URL. This is the
     * deterministic source for resolving env-indirected call targets (e.g.
     * {@code TRANSACTIONS_API_ADDR -> ledgerwriter}) with no cluster running.
     * Multi-document YAML (k8s files routinely use {@code ---}) is fully scanned.
     */
    private void extractK8sEnvAddresses(Path file, String relative, EdgeLedger ledger) throws Exception {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (Object doc : new Yaml().loadAll(reader)) {
                if (!(doc instanceof Map<?, ?> root)) continue;
                if (root.get("apiVersion") == null || root.get("kind") == null) continue; // not a k8s object
                String kind = String.valueOf(root.get("kind"));

                if ("ConfigMap".equals(kind) && root.get("data") instanceof Map<?, ?> data) {
                    for (Map.Entry<?, ?> e : data.entrySet()) {
                        recordEnvAddress(String.valueOf(e.getKey()), String.valueOf(e.getValue()), relative, ledger);
                    }
                } else {
                    // Deployment / StatefulSet / Pod: containers[*].env[*] with a literal value.
                    for (Object env : containerEnvEntries(root)) {
                        if (!(env instanceof Map<?, ?> pair)) continue;
                        Object k = pair.get("name");
                        Object v = pair.get("value"); // valueFrom (configMapKeyRef) has no literal here
                        if (k != null && v != null) {
                            recordEnvAddress(String.valueOf(k), String.valueOf(v), relative, ledger);
                        }
                    }
                }
            }
        }
    }

    /** Every {@code spec.template.spec.containers[].env} entry of a workload object, flattened. */
    private List<Object> containerEnvEntries(Map<?, ?> root) {
        List<Object> out = new java.util.ArrayList<>();
        Object spec = root.get("spec");
        Object template = (spec instanceof Map<?, ?> s) ? s.get("template") : null;
        Object podSpec = (template instanceof Map<?, ?> t) ? t.get("spec") : null;
        Object containers = (podSpec instanceof Map<?, ?> ps) ? ps.get("containers") : null;
        if (containers instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> container && container.get("env") instanceof List<?> envs) {
                    out.addAll(envs);
                }
            }
        }
        return out;
    }

    /**
     * Records an {@code env-address} entry when the value is an address (a
     * {@code host:port} or a URL) and its host is a plausible name. Non-address
     * values (a bank name, a log level, a boolean) are ignored.
     */
    private void recordEnvAddress(String key, String value, String relative, EdgeLedger ledger) {
        if (key == null || value == null) return;
        key = key.trim();
        value = value.trim();
        if (key.isEmpty() || value.isEmpty()) return;
        boolean addressShaped = value.contains("://") || value.matches("[^\\s/]+:\\d{2,5}(/.*)?");
        if (!addressShaped) return;
        String host = hostOf(value);
        if (host == null) return;

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", key);
        fields.put("host", host);
        ledger.add("env-address", fields, relative, -1, "High (k8s manifest)");
    }

    /** The DNS-label host of an address value, or null if it is a placeholder / not a name. */
    private static String hostOf(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);      // strip user:pass@
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash); // strip path
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon); // strip port
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);      // <svc>.<ns>.svc.cluster.local -> <svc>
        if (s.isEmpty() || s.contains("$") || s.contains("[") || s.contains("{")) return null;
        return s.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?") && !s.matches("[0-9]+") ? s : null;
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
