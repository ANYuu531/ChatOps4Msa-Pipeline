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
import java.util.Set;

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
                List.of(".yml", ".yaml", ".properties", ".env", ".conf", ".conf.template"));

        for (Path file : files) {
            if (file.getFileName().toString().endsWith(".conf")
                    || file.getFileName().toString().endsWith(".conf.template")) {
                // A reverse proxy's routing table is the edge list of the front door:
                // Apache's ProxyPass and nginx's proxy_pass name the upstreams.
                try {
                    extractReverseProxy(file, SourceScanner.relative(root, file), ledger);
                } catch (Exception ignored) {
                    // unreadable: not worth failing the analysis over
                }
                continue;
            }
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean isSpringConfig = name.startsWith("application") || name.startsWith("bootstrap");
            boolean isEnv = name.equals(".env") || name.startsWith(".env.");
            // The Compose spec's default file names are compose.y*ml; docker-compose.y*ml
            // is the legacy spelling. Both are the same document.
            boolean isCompose = name.startsWith("docker-compose") || name.startsWith("compose.");
            boolean isYaml = name.endsWith(".yml") || name.endsWith(".yaml");

            String relative = SourceScanner.relative(root, file);
            try {
                if (name.endsWith(".properties") || isEnv) {
                    readKeyValueFile(file, relative, ledger);
                } else if (isCompose) {
                    Object loaded = new Yaml().load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
                    // docker-compose declares each service's startup dependencies
                    // (config-server, discovery-server, …) as depends_on — the one
                    // structured place a "service -> control-plane" edge is stated when
                    // the client-side URLs are externalised to a config repo — and each
                    // service's environment, which is the same wiring a k8s manifest
                    // carries (DB_HOST: db). It is not read as a service's own config:
                    // flattened, TeaStore's compose file made its examples/ directory the
                    // source of every database edge (2026-09-22).
                    extractComposeServices(loaded, relative, ledger);
                    extractComposeDependsOn(loaded, relative, ledger);
                    extractComposeEnvironment(loaded, relative, ledger);
                } else if (isSpringConfig) {
                    Object loaded = new Yaml().load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
                    flatten("", loaded, relative, ledger);
                } else if (isYaml) {
                    // Any other YAML is either a k8s manifest (the workload inventory and
                    // the env -> host table that resolve targets with no cluster — the
                    // greenfield case) or a Spring config document that is not called
                    // application.yml: a Spring Cloud Config repository keeps one file per
                    // client service (shared/account-service.yml). Piggymetrics's datasources
                    // all live in such files, and the analysis saw none of them (2026-09-22).
                    if (!extractK8sEnvAddresses(file, relative, ledger)) {
                        Object loaded = new Yaml().load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
                        if (looksLikeSpringConfig(loaded)) flatten("", loaded, relative, ledger);
                    }
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
    private boolean extractK8sEnvAddresses(Path file, String relative, EdgeLedger ledger) throws Exception {
        boolean anyK8s = false;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (Object doc : new Yaml().loadAll(reader)) {
                if (!(doc instanceof Map<?, ?> root)) continue;
                if (root.get("apiVersion") == null || root.get("kind") == null) continue; // not a k8s object
                anyK8s = true;
                String kind = String.valueOf(root.get("kind"));
                String name = metadataName(root);

                // The workload inventory: what the cluster would actually run. With no
                // runtime graph this is the only vocabulary that carries the real names —
                // a Maven module called microservice-kubernetes-demo-catalog deploys as
                // "catalog", and tools.descartes.teastore.webui as "teastore-webui".
                if (WORKLOAD_KINDS.contains(kind) && name != null) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("name", name);
                    fields.put("kind", kind);
                    ledger.add("k8s-workload", fields, relative, -1, "High (k8s manifest)");
                }

                if ("ConfigMap".equals(kind) && root.get("data") instanceof Map<?, ?> data) {
                    for (Map.Entry<?, ?> e : data.entrySet()) {
                        recordEnvAddress(String.valueOf(e.getKey()), String.valueOf(e.getValue()), name, relative, ledger);
                    }
                } else {
                    // Deployment / StatefulSet / Pod: containers[*].env[*] with a literal value.
                    for (Object env : containerEnvEntries(root)) {
                        if (!(env instanceof Map<?, ?> pair)) continue;
                        Object k = pair.get("name");
                        Object v = pair.get("value"); // valueFrom (configMapKeyRef) has no literal here
                        if (k != null && v != null) {
                            recordEnvAddress(String.valueOf(k), String.valueOf(v), null, relative, ledger);
                            String host = addressHost(String.valueOf(k), String.valueOf(v));
                            if (name != null && host != null) recordWorkloadEnv(name, null, host, relative, ledger);
                        } else if (k != null && pair.get("valueFrom") instanceof Map<?, ?> from
                                && from.get("configMapKeyRef") instanceof Map<?, ?> ref && ref.get("name") != null) {
                            if (name != null) recordWorkloadEnv(name, String.valueOf(ref.get("name")), null, relative, ledger);
                        }
                    }
                    // envFrom: the whole ConfigMap is injected — this is the wiring that says
                    // WHICH workload receives SPRING_DATASOURCE_URL, which no code line reads.
                    for (String configMap : containerEnvFromConfigMaps(root)) {
                        if (name != null) recordWorkloadEnv(name, configMap, null, relative, ledger);
                    }
                }
            }
        }
        return anyK8s;
    }

    /** k8s kinds that run a container — the inventory of what the cluster would actually run. */
    private static final java.util.Set<String> WORKLOAD_KINDS = java.util.Set.of(
            "Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "CronJob", "Pod");

    /**
     * Top-level keys that mark a YAML document as Spring configuration. A YAML file not
     * named application.yml is read as config only when it looks like one, so a GitHub
     * workflow, a Helm values file or a Compose file is not flattened into the ledger.
     */
    private static final java.util.Set<String> SPRING_TOP_KEYS = java.util.Set.of(
            "spring", "server", "eureka", "zuul", "ribbon", "hystrix", "feign", "management",
            "security", "logging", "resilience4j");

    private static boolean looksLikeSpringConfig(Object loaded) {
        if (!(loaded instanceof Map<?, ?> map) || map.isEmpty()) return false;
        int hits = 0;
        for (Object key : map.keySet()) {
            if (SPRING_TOP_KEYS.contains(String.valueOf(key).toLowerCase(Locale.ROOT))) hits++;
        }
        // "spring" alone is unambiguous; otherwise two Spring-shaped keys are required.
        return map.containsKey("spring") || map.containsKey("eureka") || hits >= 2;
    }

    /** {@code metadata.name} of a k8s object, or null. */
    private static String metadataName(Map<?, ?> root) {
        Object metadata = root.get("metadata");
        Object name = (metadata instanceof Map<?, ?> m) ? m.get("name") : null;
        return name == null || String.valueOf(name).isBlank() ? null : String.valueOf(name).trim();
    }

    /** The pod spec of a workload object: {@code spec.template.spec}, or {@code spec} for a bare Pod. */
    private static Map<?, ?> podSpec(Map<?, ?> root) {
        Object spec = root.get("spec");
        if ("Pod".equals(String.valueOf(root.get("kind")))) return spec instanceof Map<?, ?> s ? s : null;
        Object template = (spec instanceof Map<?, ?> s) ? s.get("template") : null;
        Object podSpec = (template instanceof Map<?, ?> t) ? t.get("spec") : null;
        return podSpec instanceof Map<?, ?> ps ? ps : null;
    }

    /** Every {@code containers[].env} entry of a workload object, flattened. */
    private List<Object> containerEnvEntries(Map<?, ?> root) {
        List<Object> out = new java.util.ArrayList<>();
        Map<?, ?> podSpec = podSpec(root);
        Object containers = podSpec == null ? null : podSpec.get("containers");
        if (containers instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> container && container.get("env") instanceof List<?> envs) {
                    out.addAll(envs);
                }
            }
        }
        return out;
    }

    /** Every {@code containers[].envFrom[].configMapRef.name} of a workload object. */
    private List<String> containerEnvFromConfigMaps(Map<?, ?> root) {
        List<String> out = new java.util.ArrayList<>();
        Map<?, ?> podSpec = podSpec(root);
        Object containers = podSpec == null ? null : podSpec.get("containers");
        if (!(containers instanceof List<?> list)) return out;
        for (Object c : list) {
            if (!(c instanceof Map<?, ?> container) || !(container.get("envFrom") instanceof List<?> froms)) continue;
            for (Object f : froms) {
                if (f instanceof Map<?, ?> from && from.get("configMapRef") instanceof Map<?, ?> ref && ref.get("name") != null) {
                    out.add(String.valueOf(ref.get("name")).trim());
                }
            }
        }
        return out;
    }

    /**
     * Records an {@code env-address} entry when the value is an address (a
     * {@code host:port} or a URL) and its host is a plausible name. Non-address
     * values (a bank name, a log level, a boolean) are ignored. When the value comes
     * from a ConfigMap, the ConfigMap's name rides along so a workload's
     * {@code envFrom} can be joined back to the hosts it receives.
     */
    private void recordEnvAddress(String key, String value, String configMap, String relative, EdgeLedger ledger) {
        if (key == null || value == null) return;
        key = key.trim();
        value = value.trim();
        if (key.isEmpty() || value.isEmpty()) return;
        String host = addressHost(key, value);
        if (host == null) return;

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", key);
        fields.put("host", host);
        if (configMap != null) fields.put("configmap", configMap);
        ledger.add("env-address", fields, relative, -1, "High (k8s manifest)");
    }

    /** Env names whose bare value is a host even without a port: DB_HOST, REGISTRY_HOST, CART_ENDPOINT. */
    private static final java.util.regex.Pattern HOST_ENV_NAME = java.util.regex.Pattern.compile(
            "(?i)(_host|_hostname|_addr|_address|_url|_uri|_endpoint|_server)$");

    /**
     * The host of a value that is an address (a URL or {@code host:port}), or null for
     * anything else — {@code "true"}, {@code "v0"}, a log level. A bare word is a valid
     * DNS label, so the shape check has to come before the host extraction — unless the
     * variable's own name says it holds a host ({@code DB_HOST: teastore-db}, the port in
     * a sibling {@code DB_PORT}), which is how TeaStore and Robot Shop wire everything.
     */
    private static String addressHost(String name, String value) {
        String v = value == null ? "" : value.trim();
        boolean addressShaped = v.contains("://") || v.matches("[^\\s/]+:\\d{2,5}(/.*)?");
        if (addressShaped) return hostOf(v);
        if (name != null && HOST_ENV_NAME.matcher(name.trim()).find()) return hostOf(v);
        return null;
    }

    /** Compose keys that are not services, in either file format. */
    private static final Set<String> COMPOSE_TOP_LEVEL_KEYS =
            Set.of("version", "services", "networks", "volumes", "configs", "secrets", "name", "include");

    /**
     * The service map of a Compose file, in both formats: {@code services:} in version 2
     * and later, and — in the version 1 file format, which the Spring Cloud samples of that
     * era use — the top level itself, where every key is a service. Version 1 is not a
     * curiosity to skip: it is where those projects state their wiring, and the dataset we
     * score against derives its edges from exactly these files (2026-09-25).
     */
    private static Map<?, ?> composeServices(Object loaded) {
        if (!(loaded instanceof Map<?, ?> root)) return null;
        if (root.get("services") instanceof Map<?, ?> services) return services;
        if (root.get("version") != null || root.get("services") != null) return null;
        Map<Object, Object> top = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : root.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (COMPOSE_TOP_LEVEL_KEYS.contains(key) || key.startsWith("x-")) continue;
            // A v1 service is a map with an image or a build context; anything else at the
            // top level of a file named docker-compose.* is not a service declaration.
            if (e.getValue() instanceof Map<?, ?> value
                    && (value.get("image") != null || value.get("build") != null)) {
                top.put(e.getKey(), e.getValue());
            }
        }
        return top.isEmpty() ? null : top;
    }

    /**
     * Every {@code services.<name>} key of a Compose file, as the deployment's own
     * vocabulary — the role a k8s workload's {@code metadata.name} plays.
     *
     * <p>Without it, a repo whose only deployment description is Compose has its nodes
     * named after source modules: {@code spring-petclinic-customers-service} rather than
     * the {@code customers-service} the deployment (and every caller's URL) uses, and the
     * same service then arrives twice — once per spelling. Found on 2026-09-25 by scoring
     * against the MicroDepGraph dataset's own graphs, where four of six projects had every
     * edge right and no node name in common with the dataset.
     */
    private void extractComposeServices(Object loaded, String relative, EdgeLedger ledger) {
        Map<?, ?> services = composeServices(loaded);
        if (services == null) return;
        for (Object key : services.keySet()) {
            String service = String.valueOf(key).trim();
            if (service.isEmpty()) continue;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("name", service);
            fields.put("kind", "ComposeService");
            ledger.add("compose-service", fields, relative, -1, "High (compose services key)");
        }
    }

    /**
     * Compose {@code services.<svc>.environment}, in both its forms (a {@code K=V} list
     * or a map), as the same {@code env-address} / {@code workload-env} rows a k8s
     * manifest yields: the env → host table, and which service receives which host.
     */
    private void extractComposeEnvironment(Object loaded, String relative, EdgeLedger ledger) {
        Map<?, ?> services = composeServices(loaded);
        if (services == null) return;
        for (Map.Entry<?, ?> entry : services.entrySet()) {
            String service = String.valueOf(entry.getKey()).trim();
            if (service.isEmpty() || !(entry.getValue() instanceof Map<?, ?> config)) continue;
            Object environment = config.get("environment");
            Map<String, String> env = new LinkedHashMap<>();
            if (environment instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getValue() != null) env.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            } else if (environment instanceof List<?> list) {
                for (Object item : list) {
                    String s = String.valueOf(item);
                    int eq = s.indexOf('=');
                    if (eq > 0) env.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
                }
            }
            for (Map.Entry<String, String> e : env.entrySet()) {
                recordEnvAddress(e.getKey(), e.getValue(), null, relative, ledger);
                String host = addressHost(e.getKey(), e.getValue());
                if (host != null) recordWorkloadEnv(service, null, host, relative, ledger);
            }
        }
    }

    /** {@code ProxyPass /order http://order:8080/} (Apache) and {@code proxy_pass http://cart:8080/;} (nginx). */
    private static final java.util.regex.Pattern PROXY_DIRECTIVE = java.util.regex.Pattern.compile(
            "(?i)^\\s*(?:ProxyPass|ProxyPassReverse|proxy_pass)\\s+(?:\\S+\\s+)?([a-z]+://\\S+?)[;\\s]*$");

    /**
     * Emits a {@code url} row for every upstream a reverse-proxy config routes to. The
     * proxy is the front door of many demo systems (ewolff's Apache, Robot Shop's nginx);
     * its config is the only place its edges exist, and it is not a language any grammar
     * covers. A commented line is skipped; a {@code ${VAR}} host is kept for the merger's
     * placeholder tables to fill.
     */
    private void extractReverseProxy(Path file, String relative, EdgeLedger ledger) throws Exception {
        int lineNo = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            java.util.regex.Matcher m = PROXY_DIRECTIVE.matcher(line);
            if (!m.matches()) continue;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("value", m.group(1));
            ledger.add("url", fields, relative, lineNo, "High (reverse-proxy config)");
        }
    }

    /**
     * Records a {@code workload-env} entry: which workload receives which ConfigMap
     * ({@code envFrom} / {@code configMapKeyRef}) or which literal host. This is the
     * deterministic answer to "does transactionhistory get SPRING_DATASOURCE_URL?"
     * when no line of its code reads the variable (Spring Boot binds it implicitly).
     */
    private void recordWorkloadEnv(String workload, String configMap, String host, String relative, EdgeLedger ledger) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workload", workload);
        if (configMap != null && !configMap.isBlank()) fields.put("configmap", configMap.trim());
        if (host != null && !host.isBlank()) fields.put("host", host.trim());
        if (fields.size() < 2) return;
        ledger.add("workload-env", fields, relative, -1, "High (k8s manifest)");
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
        Map<?, ?> services = composeServices(loaded);
        if (services == null) return;

        for (Map.Entry<?, ?> entry : services.entrySet()) {
            String service = String.valueOf(entry.getKey()).trim();
            if (service.isEmpty() || !(entry.getValue() instanceof Map<?, ?> config)) continue;

            java.util.List<String> targets = new java.util.ArrayList<>();
            // links is the version 1 file format's way of saying the same thing, and says
            // slightly more: a link exists so that this container can reach that one by
            // name. "gateway" or "db:database" — the alias after the colon is local.
            for (String key : List.of("depends_on", "links")) {
                Object declared = config.get(key);
                if (declared instanceof List<?> list) {
                    for (Object t : list) targets.add(String.valueOf(t).trim().split(":")[0].trim());
                } else if (declared instanceof Map<?, ?> map) {
                    for (Object k : map.keySet()) targets.add(String.valueOf(k).trim());
                }
            }

            for (String target : targets) {
                if (target.isEmpty() || target.equals(service)) continue;
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("source_service", service);
                fields.put("target_service", target);
                ledger.add("compose-dependency", fields, relative, -1, "High (docker-compose depends_on/links)");
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
        if (k.endsWith(".url") || k.endsWith(".uri") || k.endsWith(".host") || k.endsWith(".hostname")
                || k.endsWith(".endpoint") || k.endsWith(".address")
                || k.endsWith("_url") || k.endsWith("_uri") || k.endsWith("_host") || k.endsWith("_hostname")
                || k.endsWith("_endpoint") || k.endsWith("_addr")
                // A gateway route that names its backend by registry id (Zuul serviceId,
                // Spring Cloud Gateway's lb://) is a dependency stated in config.
                || k.endsWith(".serviceid") || k.endsWith(".service-id")) {
            return true;
        }
        // A value that is plainly a URL is worth keeping whatever the key is called.
        return value.startsWith("http://") || value.startsWith("https://");
    }
}
