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
        namespaces.clear();
        List<Path> files = SourceScanner.filesWithExtensions(root,
                List.of(".yml", ".yaml", ".properties", ".env", ".conf", ".conf.template"));
        // A Compose project's .env, interpolated the way Compose does, per project directory.
        Map<Path, Map<String, String>> projectEnvs = new java.util.HashMap<>();

        // .NET's configuration file. Its keys are the same ones the Compose environment
        // overrides with `__` (ConnectionStrings__Database), and a YARP gateway's routing
        // table lives nowhere else (2026-10-26, run-aspnetcore-microservices).
        for (Path json : SourceScanner.filesWithExtensions(root, List.of(".json"))) {
            if (!json.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("appsettings")) continue;
            try {
                extractDotnetAppSettings(json, SourceScanner.relative(root, json), ledger);
            } catch (Exception ignored) {
                // malformed JSON (comments, trailing commas): not worth failing the analysis over
            }
        }

        // A Dockerfile's ENV lines are the image's own environment: Robot Shop's shipping
        // image says CART_ENDPOINT=cart:8080 nowhere else (2026-10-23). They are scoped to
        // the module that owns the Dockerfile, like a workload's own env block.
        for (Path dockerfile : dockerfiles(root)) {
            try {
                extractDockerfileEnv(dockerfile, SourceScanner.relative(root, dockerfile), ledger);
            } catch (Exception ignored) {
                // unreadable: not worth failing the analysis over
            }
        }

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
                    // A Compose overlay whose name says it runs tests (compose.tests.yaml,
                    // docker-compose-e2e.yml) declares test runners, not workloads: the same
                    // exclusion the service-root scan applies to a tests/ directory (2026-10-26).
                    if (isTestCompose(name)) continue;
                    Object loaded = new Yaml().load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
                    // docker-compose declares each service's startup dependencies
                    // (config-server, discovery-server, …) as depends_on — the one
                    // structured place a "service -> control-plane" edge is stated when
                    // the client-side URLs are externalised to a config repo — and each
                    // service's environment, which is the same wiring a k8s manifest
                    // carries (DB_HOST: db). It is not read as a service's own config:
                    // flattened, TeaStore's compose file made its examples/ directory the
                    // source of every database edge (2026-09-22).
                    Map<String, String> projectEnv = projectEnvs.computeIfAbsent(file.getParent(), ConfigExtractor::projectEnv);
                    extractComposeServices(loaded, root, file.getParent(), projectEnv, relative, ledger);
                    extractComposeDependsOn(loaded, relative, ledger);
                    extractComposeEnvironment(loaded, root, file.getParent(), projectEnv, relative, ledger);
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
                        // An Envoy static config is a reverse proxy's routing table in YAML:
                        // its clusters are the upstreams, as nginx's proxy_pass are.
                        if (!extractEnvoyClusters(loaded, relative, ledger) && looksLikeSpringConfig(loaded)) {
                            flatten("", loaded, relative, ledger);
                        }
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

                // The namespaces the manifests declare — as a Namespace object or as any
                // object's metadata.namespace. A host written <svc>.<ns> is in-cluster only
                // when <ns> is one of these; the merger strips exactly that label and no
                // other (2026-10-26).
                String namespace = "Namespace".equals(kind) ? name : metadataNamespace(root);
                if (namespace != null && namespaces.add(namespace)) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("name", namespace);
                    ledger.add("k8s-namespace", fields, relative, -1, "High (k8s manifest)");
                }

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
                // A Service is the name callers use. It is recorded beside the workloads so
                // the merger can tell a versioned deployment (reviews-v1, the Istio bookinfo
                // convention) from the service it is a version of (reviews) — the Deployment
                // carries the version, the Service does not (2026-10-23).
                if ("Service".equals(kind) && name != null) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("name", name);
                    ledger.add("k8s-service", fields, relative, -1, "High (k8s manifest)");
                }

                if ("ConfigMap".equals(kind) && root.get("data") instanceof Map<?, ?> data) {
                    for (Map.Entry<?, ?> e : data.entrySet()) {
                        recordEnvAddress(String.valueOf(e.getKey()), String.valueOf(e.getValue()), name, null, relative, ledger);
                    }
                } else {
                    // Deployment / StatefulSet / Pod: containers[*].env[*] with a literal value.
                    // The value is scoped to THIS workload: four manifests may each declare
                    // MONGO_CLIENT_HOST with a different database, and only the workload's own
                    // value is what its code reads.
                    for (Object env : containerEnvEntries(root)) {
                        if (!(env instanceof Map<?, ?> pair)) continue;
                        Object k = pair.get("name");
                        Object v = pair.get("value"); // valueFrom (configMapKeyRef) has no literal here
                        if (k != null && v != null) {
                            recordEnvAddress(String.valueOf(k), String.valueOf(v), null, name, relative, ledger);
                            String host = addressHost(String.valueOf(k), String.valueOf(v));
                            if (name != null && host != null) recordWorkloadEnv(name, null, host, String.valueOf(k), String.valueOf(v), relative, ledger);
                        } else if (k != null && pair.get("valueFrom") instanceof Map<?, ?> from
                                && from.get("configMapKeyRef") instanceof Map<?, ?> ref && ref.get("name") != null) {
                            if (name != null) recordWorkloadEnv(name, String.valueOf(ref.get("name")), null, null, relative, ledger);
                        }
                    }
                    // envFrom: the whole ConfigMap is injected — this is the wiring that says
                    // WHICH workload receives SPRING_DATASOURCE_URL, which no code line reads.
                    for (String configMap : containerEnvFromConfigMaps(root)) {
                        if (name != null) recordWorkloadEnv(name, configMap, null, null, relative, ledger);
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

    /** Namespaces already recorded in this extraction (one {@code k8s-namespace} row each). */
    private final Set<String> namespaces = new java.util.LinkedHashSet<>();

    /** {@code metadata.namespace} of a k8s object, or null. */
    private static String metadataNamespace(Map<?, ?> root) {
        Object metadata = root.get("metadata");
        Object ns = (metadata instanceof Map<?, ?> m) ? m.get("namespace") : null;
        if (ns == null) return null;
        String s = String.valueOf(ns).trim().toLowerCase(Locale.ROOT);
        return s.matches(DNS_LABEL) ? s : null;
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
    private void recordEnvAddress(String key, String value, String configMap, String workload,
                                  String relative, EdgeLedger ledger) {
        recordEnvAddress(key, value, configMap, workload, relative, ledger, null);
    }

    /** @param via the file the value was forwarded from ({@code .env}), or null when stated in place */
    private void recordEnvAddress(String key, String value, String configMap, String workload,
                                  String relative, EdgeLedger ledger, String via) {
        recordEnvAddress(key, value, configMap, workload, relative, ledger, via, false);
    }

    private void recordEnvAddress(String key, String value, String configMap, String workload,
                                  String relative, EdgeLedger ledger, String via, boolean keepDomain) {
        if (key == null || value == null) return;
        key = key.trim();
        value = value.trim();
        if (key.isEmpty() || value.isEmpty()) return;
        String host = addressHost(key, value, keepDomain);
        if (host == null) return;

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", key);
        fields.put("host", host);
        if (configMap != null) fields.put("configmap", configMap);
        // The scope the value holds in: a workload's own env block, or a Dockerfile's
        // module (marked "dockerfile"; the merger resolves the module from the file).
        if (workload != null) fields.put("workload", workload);
        if (via != null) fields.put("via", via);
        String kind = kindHint(key, value);
        if (kind != null) fields.put("kind", kind);
        ledger.add("env-address", fields, relative, -1, "High (k8s manifest)");
    }

    /** Connection schemes that say the target is a database, and the ones that say it is a broker. */
    private static final Set<String> DB_SCHEMES = Set.of("jdbc", "r2dbc", "mysql", "postgres", "postgresql", "mongodb",
            "mongodb+srv", "redis", "rediss", "cassandra", "mariadb", "sqlserver", "oracle", "couchdb", "neo4j", "bolt", "memcached");
    private static final Set<String> QUEUE_SCHEMES = Set.of("amqp", "amqps", "kafka", "nats", "mqtt", "mqtts", "stomp");

    /**
     * What the VALUE (or the variable's own name) says the target is, when it says
     * anything: a {@code key=value;} connection string and a {@code ConnectionStrings__X}
     * variable are a database whatever the host is called ({@code catalogdb} carries no
     * database hint; .NET names its stores freely), an {@code amqp://} URL is a broker.
     * Null when the shape is neutral — the merger then classifies by the host's name as
     * before (2026-10-26, run-aspnetcore-microservices).
     */
    static String kindHint(String name, String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("connectionstrings__") || n.startsWith("connectionstrings:")) return "db";
        if (v.startsWith("jdbc:") || v.startsWith("r2dbc:")) return "db";
        int scheme = v.indexOf("://");
        if (scheme > 0) {
            String s = v.substring(0, scheme);
            if (DB_SCHEMES.contains(s)) return "db";
            if (QUEUE_SCHEMES.contains(s)) return "queue";
            return null;
        }
        if (v.indexOf('=') > 0 && v.indexOf(';') > 0 && CONNECTION_STRING_HOST.matcher(v).find()) return "db";
        return null;
    }

    /** {@code ENV K=V}, {@code ENV K V} and the multi-pair / quoted forms Docker accepts. */
    private static final java.util.regex.Pattern DOCKER_ENV_PAIR = java.util.regex.Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|'[^']*'|\\S+)");

    /** Every Dockerfile (any case, any suffix such as Dockerfile.prod) outside ignored trees. */
    private static List<Path> dockerfiles(Path root) {
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
    private void extractDockerfileEnv(Path file, String relative, EdgeLedger ledger) throws Exception {
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
        for (String line : logical) {
            if (!line.regionMatches(true, 0, "ENV ", 0, 4)) continue;
            String body = line.substring(4).trim();
            java.util.regex.Matcher m = DOCKER_ENV_PAIR.matcher(body);
            boolean any = false;
            while (m.find()) {
                any = true;
                recordEnvAddress(m.group(1), unquote(m.group(2)), null, "dockerfile", relative, ledger);
            }
            if (!any) {
                // Legacy form: ENV KEY value with spaces
                int space = body.indexOf(' ');
                if (space > 0) recordEnvAddress(body.substring(0, space), unquote(body.substring(space + 1).trim()),
                        null, "dockerfile", relative, ledger);
            }
        }
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** The host key of a {@code key=value;key=value} connection string (Npgsql, SqlClient, ODBC). */
    private static final java.util.regex.Pattern CONNECTION_STRING_HOST = java.util.regex.Pattern.compile(
            "(?i)(?:^|;)\\s*(?:host|server|data source|hostname|address|addr|network address)\\s*=\\s*([^;,]+)");

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
        return addressHost(name, value, false);
    }

    /** @param keepDomain see {@link #hostOf(String, boolean)} */
    private static String addressHost(String name, String value, boolean keepDomain) {
        String v = value == null ? "" : value.trim();
        // An ADO.NET / Npgsql connection string names its host as a key=value pair:
        // Host=astronomy-db;Username=…;Database=… (2026-10-26, OpenTelemetry demo's accounting).
        if (v.indexOf('=') > 0 && v.indexOf(';') > 0) {
            java.util.regex.Matcher m = CONNECTION_STRING_HOST.matcher(v);
            if (m.find()) return hostOf(m.group(1).replaceFirst("(?i)^tcp:", ""), keepDomain);
        }
        boolean addressShaped = v.contains("://") || v.matches("[^\\s/]+:\\d{2,5}(/.*)?");
        if (addressShaped) return hostOf(v, keepDomain);
        if (name != null && HOST_ENV_NAME.matcher(name.trim()).find()) return hostOf(v, keepDomain);
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
    private void extractComposeServices(Object loaded, Path root, Path composeDir, Map<String, String> projectEnv,
                                        String relative, EdgeLedger ledger) {
        Map<?, ?> services = composeServices(loaded);
        if (services == null) return;
        for (Map.Entry<?, ?> entry : services.entrySet()) {
            String service = String.valueOf(entry.getKey()).trim();
            // A service whose name says it is a test runner (frontendTests, e2e-tests) is a
            // build unit, never a workload — as a tests/ directory is (2026-10-26).
            if (service.isEmpty() || ServiceRootScanner.isTestName(service)) continue;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("name", service);
            fields.put("kind", "ComposeService");
            Map<?, ?> config = entry.getValue() instanceof Map<?, ?> m ? m : Map.of();
            // build: the module directory this service is built from — the one statement
            // in a repo that ties a module to its deployment name when the two are spelled
            // differently (yas: `backoffice: build: ./backoffice-bff`). The Dockerfile's own
            // directory is the module when the context is the repo (.NET solutions build
            // every service from the solution root with a per-project Dockerfile). Both are
            // recorded repo-relative so the merger can equate them with a service-root dir
            // (2026-10-26).
            Object build = config.get("build");
            String context;
            if (build instanceof Map<?, ?> b) {
                Object ctx = b.get("context");
                context = ctx == null ? "." : String.valueOf(ctx);
            } else {
                context = build == null ? null : String.valueOf(build);
            }
            String dockerfile = build instanceof Map<?, ?> b && b.get("dockerfile") != null ? String.valueOf(b.get("dockerfile")) : null;
            if (context != null) {
                String contextDir = repoRelativeDir(root, composeDir, interpolate(context.trim(), projectEnv, 0), false);
                if (contextDir != null) fields.put("context", contextDir);
                if (dockerfile != null) {
                    String dockerfileDir = repoRelativeDir(root, composeDir,
                            interpolate(context.trim(), projectEnv, 0) + "/" + interpolate(dockerfile.trim(), projectEnv, 0), true);
                    if (dockerfileDir != null) fields.put("dockerfile_dir", dockerfileDir);
                }
            }
            ledger.add("compose-service", fields, relative, -1, "High (compose services key)");

            // hostname: the container's own host name, which other services then use as the
            // address (`hostname: ecommerce-mq` on the messagebroker service, and
            // MessageBroker__Host=amqp://ecommerce-mq:5672 elsewhere). It is an alias of the
            // service for every reader, recorded like a links alias with a global scope
            // (rule 25 extended, 2026-10-26).
            Object hostname = config.get("hostname");
            if (hostname != null) {
                String alias = interpolate(String.valueOf(hostname).trim(), projectEnv, 0).toLowerCase(Locale.ROOT);
                if (!alias.isEmpty() && !alias.contains("$") && !alias.equalsIgnoreCase(service) && alias.matches(DNS_LABEL + "|" + DNS_NAME)) {
                    Map<String, String> aliasFields = new LinkedHashMap<>();
                    aliasFields.put("service", "*");
                    aliasFields.put("alias", alias);
                    aliasFields.put("target", service);
                    ledger.add("compose-alias", aliasFields, relative, -1, "High (compose hostname)");
                }
            }
        }
    }

    /**
     * A Compose path resolved against the Compose file's directory and expressed relative to
     * the repo root ("" for the root itself); null when it escapes the repo or is a placeholder.
     * With {@code parentOfFile} the path names a file and its directory is returned.
     */
    private static String repoRelativeDir(Path root, Path composeDir, String composePath, boolean parentOfFile) {
        if (composePath == null || composePath.isBlank() || composePath.contains("$")) return null;
        try {
            Path resolved = composeDir.resolve(composePath).normalize();
            if (parentOfFile) resolved = resolved.getParent();
            Path normalRoot = root.normalize();
            if (resolved == null || !resolved.startsWith(normalRoot)) return null;
            return normalRoot.relativize(resolved).toString().replace('\\', '/');
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compose {@code services.<svc>.environment}, in both its forms (a {@code K=V} list
     * or a map), as the same {@code env-address} / {@code workload-env} rows a k8s
     * manifest yields: the env → host table, and which service receives which host.
     *
     * <p>Values are interpolated the way Compose does ({@code ${VAR}}, {@code ${VAR:-d}})
     * against the project's {@code .env}, and a key written without a value ({@code -
     * CART_ADDR}) forwards that variable from the project's {@code .env} / the service's
     * {@code env_file}. That is how a modern Compose file states its wiring: the
     * OpenTelemetry demo keeps every address in {@code .env} and names only the keys per
     * service, so the forwarded value is the service's own — and nothing else in the repo
     * says which service gets which address (2026-10-26).
     */
    private void extractComposeEnvironment(Object loaded, Path root, Path composeDir, Map<String, String> projectEnv,
                                           String relative, EdgeLedger ledger) {
        Map<?, ?> services = composeServices(loaded);
        if (services == null) return;
        for (Map.Entry<?, ?> entry : services.entrySet()) {
            String service = String.valueOf(entry.getKey()).trim();
            if (service.isEmpty() || !(entry.getValue() instanceof Map<?, ?> config)) continue;
            if (ServiceRootScanner.isTestName(service)) continue;

            // env_file: every key in the file is injected into this service.
            Map<String, String> env = new LinkedHashMap<>();
            Map<String, String> fileEnv = new LinkedHashMap<>();
            Object envFile = config.get("env_file");
            List<?> envFiles = envFile instanceof List<?> l ? l : (envFile == null ? List.of() : List.of(envFile));
            for (Object f : envFiles) {
                String p = f instanceof Map<?, ?> m ? String.valueOf(m.get("path")) : String.valueOf(f);
                p = interpolate(p, projectEnv, 0);
                if (p.isBlank() || p.contains("$")) continue;
                Path resolved = composeDir.resolve(p).normalize();
                if (!resolved.startsWith(root.normalize())) continue;
                for (Map.Entry<String, String> e : readEnvFile(resolved).entrySet()) {
                    fileEnv.put(e.getKey(), interpolate(e.getValue(), projectEnv, 0));
                }
            }
            env.putAll(fileEnv);
            Set<String> forwarded = new java.util.HashSet<>();

            Object environment = config.get("environment");
            if (environment instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String key = String.valueOf(e.getKey()).trim();
                    if (e.getValue() != null) {
                        env.put(key, interpolate(String.valueOf(e.getValue()), projectEnv, 0));
                    } else {
                        String value = fileEnv.containsKey(key) ? fileEnv.get(key) : projectEnv.get(key);
                        if (value != null) {
                            env.put(key, value);
                            forwarded.add(key);
                        }
                    }
                }
            } else if (environment instanceof List<?> list) {
                for (Object item : list) {
                    String s = String.valueOf(item).trim();
                    int eq = s.indexOf('=');
                    if (eq > 0) {
                        env.put(s.substring(0, eq).trim(), interpolate(s.substring(eq + 1).trim(), projectEnv, 0));
                    } else if (!s.isEmpty()) {
                        String value = fileEnv.containsKey(s) ? fileEnv.get(s) : projectEnv.get(s);
                        if (value != null) {
                            env.put(s, value);
                            forwarded.add(s);
                        }
                    }
                }
            }
            for (Map.Entry<String, String> e : env.entrySet()) {
                // Compose has no namespaces: a dotted host here is a domain, kept whole so the
                // merger can see it is external rather than inventing a node from its first label.
                recordEnvAddress(e.getKey(), e.getValue(), null, service, relative, ledger,
                        forwarded.contains(e.getKey()) ? ".env" : null, true);
                String host = addressHost(e.getKey(), e.getValue(), true);
                if (host != null) recordWorkloadEnv(service, null, host, e.getKey(), e.getValue(), relative, ledger);
            }
        }
    }

    /**
     * Compose variable syntax: {@code ${VAR}}, {@code ${VAR:-default}}, {@code ${VAR-default}},
     * {@code ${VAR:?err}}, {@code ${VAR:+alt}} and bare {@code $VAR}.
     */
    private static final java.util.regex.Pattern COMPOSE_VAR = java.util.regex.Pattern.compile(
            "\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?:(:?[-?+])([^}]*))?}|\\$([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * The value with Compose placeholders filled from {@code env}, recursively (a {@code .env}
     * value may itself reference another: {@code LOCUST_HOST=http://${FRONTEND_PROXY_ADDR}}
     * with {@code FRONTEND_PROXY_ADDR=frontend-proxy:${ENVOY_PORT}}), bounded. A variable
     * nothing defines keeps its placeholder, so it is still recognised as unresolved.
     */
    static String interpolate(String value, Map<String, String> env, int depth) {
        if (value == null || value.indexOf('$') < 0 || depth > 5) return value;
        java.util.regex.Matcher m = COMPOSE_VAR.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(4);
            String op = m.group(2);
            String alt = m.group(3) == null ? "" : m.group(3);
            String v = env.get(name);
            boolean set = v != null && (op == null || !op.startsWith(":") || !v.isEmpty());
            String replacement;
            if (op != null && op.endsWith("+")) replacement = set ? alt : "";
            else if (set) replacement = v;
            else if (op != null && op.endsWith("-")) replacement = alt;
            else replacement = m.group();
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        String out = sb.toString();
        return out.equals(value) ? out : interpolate(out, env, depth + 1);
    }

    /** The project directory's {@code .env}, interpolated against itself as Compose does. */
    private static Map<String, String> projectEnv(Path dir) {
        Map<String, String> env = readEnvFile(dir == null ? null : dir.resolve(".env"));
        for (Map.Entry<String, String> e : env.entrySet()) e.setValue(interpolate(e.getValue(), env, 0));
        return env;
    }

    /** {@code KEY=VALUE} lines of an env file, quotes stripped and unquoted inline comments cut; empty when absent. */
    static Map<String, String> readEnvFile(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) return out;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).trim();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_.-]*")) continue;
                if (value.startsWith("\"") || value.startsWith("'")) {
                    int close = value.indexOf(value.charAt(0), 1);
                    value = close > 0 ? value.substring(1, close) : unquote(value);
                } else {
                    int hash = value.indexOf(" #");
                    if (hash >= 0) value = value.substring(0, hash).trim();
                    if (value.startsWith("#")) value = "";
                }
                out.put(key, value);
            }
        } catch (Exception ignored) {
            // unreadable env file: nothing to forward
        }
        return out;
    }

    /** {@code compose.tests.yaml}, {@code docker-compose-e2e.yml}: a test overlay, by a name segment. */
    private static final java.util.regex.Pattern TEST_FILE_SEGMENT = java.util.regex.Pattern.compile(
            "(?i)(^|[.\\-_])(e2e|tests?)([.\\-_]|$)");

    static boolean isTestCompose(String lowerName) {
        String stem = lowerName.replaceAll("\\.ya?ml$", "");
        return TEST_FILE_SEGMENT.matcher(stem).find();
    }

    /**
     * Reads a .NET {@code appsettings*.json}: the YARP reverse proxy's upstreams
     * ({@code ReverseProxy:Clusters:<c>:Destinations:<d>:Address}) become {@code url} rows,
     * as nginx's {@code proxy_pass} and Envoy's clusters do (rules 6 and 34); every other
     * leaf whose key or value says it is an address becomes a {@code config} row under its
     * colon-joined key ({@code ConnectionStrings:Database}, {@code GrpcSettings:DiscountUrl}),
     * the spelling .NET code reads it by. A Compose override of the same key is spelled
     * {@code ConnectionStrings__Database}; the merger equates the two.
     */
    private void extractDotnetAppSettings(Path file, String relative, EdgeLedger ledger) throws Exception {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        org.json.JSONObject root = new org.json.JSONObject(text);
        walkDotnetSettings("", root, relative, ledger);
    }

    private void walkDotnetSettings(String prefix, Object node, String relative, EdgeLedger ledger) {
        if (node instanceof org.json.JSONObject obj) {
            for (String key : obj.keySet()) {
                walkDotnetSettings(prefix.isEmpty() ? key : prefix + ":" + key, obj.get(key), relative, ledger);
            }
        } else if (node instanceof org.json.JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) walkDotnetSettings(prefix + ":" + i, arr.get(i), relative, ledger);
        } else if (node != null && !org.json.JSONObject.NULL.equals(node)) {
            String value = String.valueOf(node).trim();
            if (prefix.isEmpty() || value.isEmpty()) return;
            java.util.regex.Matcher yarp = YARP_DESTINATION.matcher(prefix);
            if (yarp.matches()) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("value", value);
                fields.put("cluster", yarp.group(1));
                ledger.add("url", fields, relative, -1, "High (yarp reverse proxy)");
                return;
            }
            if (!isRelevantDotnetKey(prefix, value)) return;
            if (value.length() > MAX_VALUE_LENGTH) value = value.substring(0, MAX_VALUE_LENGTH) + "...";
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("key", prefix);
            fields.put("value", value);
            ledger.add("config", fields, relative, -1, "High");
        }
    }

    /** {@code ReverseProxy:Clusters:<cluster>:Destinations:<name>:Address}. */
    private static final java.util.regex.Pattern YARP_DESTINATION = java.util.regex.Pattern.compile(
            "(?i)ReverseProxy:Clusters:([^:]+):Destinations:[^:]+:Address");

    /** The last segment of a .NET key, camel-cased, ends with the word that says it holds an address. */
    private static final java.util.regex.Pattern DOTNET_ADDRESS_KEY = java.util.regex.Pattern.compile(
            "(?i)(url|uri|address|host|hostname|endpoint|server|bootstrapservers)$");

    private boolean isRelevantDotnetKey(String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.startsWith("connectionstrings:")) return true;
        String last = k.substring(k.lastIndexOf(':') + 1);
        if (DOTNET_ADDRESS_KEY.matcher(last).find()) return true;
        return value.startsWith("http://") || value.startsWith("https://") || value.contains("://");
    }

    /**
     * Emits a {@code url} row per upstream of an Envoy static configuration: every
     * {@code static_resources.clusters[].…socket_address} ({@code load_assignment} or the
     * legacy {@code hosts}). An Envoy in front of a system is a reverse proxy like nginx,
     * and its clusters are its {@code proxy_pass} lines; the OpenTelemetry demo's
     * frontend-proxy has no other statement of what it routes to (2026-10-26). A
     * placeholder port is omitted so a {@code ${HOST}} alone can still be filled by the
     * merger's env tables. Returns whether the document was an Envoy config.
     */
    private boolean extractEnvoyClusters(Object loaded, String relative, EdgeLedger ledger) {
        if (!(loaded instanceof Map<?, ?> root) || !(root.get("static_resources") instanceof Map<?, ?> resources)) return false;
        if (!(resources.get("clusters") instanceof List<?> clusters)) return false;
        for (Object c : clusters) {
            if (!(c instanceof Map<?, ?> cluster)) continue;
            List<Map<?, ?>> addresses = new java.util.ArrayList<>();
            collectSocketAddresses(cluster, addresses, 0);
            for (Map<?, ?> sa : addresses) {
                Object address = sa.get("address");
                Object port = sa.get("port_value");
                if (address == null || String.valueOf(address).isBlank()) continue;
                String value = "http://" + String.valueOf(address).trim();
                if (port != null && String.valueOf(port).matches("\\d{2,5}")) value += ":" + port;
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("value", value);
                if (cluster.get("name") != null) fields.put("cluster", String.valueOf(cluster.get("name")));
                ledger.add("url", fields, relative, -1, "High (envoy static config)");
            }
        }
        return true;
    }

    private static void collectSocketAddresses(Object node, List<Map<?, ?>> out, int depth) {
        if (depth > 8) return;
        if (node instanceof Map<?, ?> map) {
            if (map.get("socket_address") instanceof Map<?, ?> sa) out.add(sa);
            for (Object v : map.values()) collectSocketAddresses(v, out, depth + 1);
        } else if (node instanceof List<?> list) {
            for (Object v : list) collectSocketAddresses(v, out, depth + 1);
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
        // nginx `set $var "value";` — the one-level indirection nginx configs use so the
        // upstream is resolved at request time rather than at start-up (`set
        // $docker_search_host "search"; proxy_pass http://$docker_search_host;`). The
        // variable is file-local and literal, so it is filled in here; a variable set to
        // anything but a literal, or never set, stays a placeholder (2026-10-26, yas).
        Map<String, String> nginxVars = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            java.util.regex.Matcher set = NGINX_SET.matcher(line);
            if (set.matches()) {
                String value = unquote(set.group(2));
                if (!value.contains("$")) nginxVars.put(set.group(1), value);
                continue;
            }
            java.util.regex.Matcher m = PROXY_DIRECTIVE.matcher(line);
            if (!m.matches()) continue;
            String target = m.group(1);
            if (target.indexOf('$') >= 0 && !nginxVars.isEmpty()) {
                java.util.regex.Matcher v = NGINX_VAR.matcher(target);
                StringBuilder sb = new StringBuilder();
                while (v.find()) {
                    String value = nginxVars.get(v.group(1));
                    v.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value != null ? value : v.group()));
                }
                v.appendTail(sb);
                target = sb.toString();
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("value", target);
            ledger.add("url", fields, relative, lineNo, "High (reverse-proxy config)");
        }
    }

    /** nginx {@code set $name value;} with a bare or quoted literal value. */
    private static final java.util.regex.Pattern NGINX_SET = java.util.regex.Pattern.compile(
            "^set\\s+\\$([A-Za-z_][A-Za-z0-9_]*)\\s+(\"[^\"]*\"|'[^']*'|[^\\s;]+)\\s*;\\s*$");
    /** A bare nginx variable reference inside a directive argument ({@code $docker_search_host}; not {@code ${…}}). */
    private static final java.util.regex.Pattern NGINX_VAR = java.util.regex.Pattern.compile(
            "\\$([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * Records a {@code workload-env} entry: which workload receives which ConfigMap
     * ({@code envFrom} / {@code configMapKeyRef}) or which literal host. This is the
     * deterministic answer to "does transactionhistory get SPRING_DATASOURCE_URL?"
     * when no line of its code reads the variable (Spring Boot binds it implicitly).
     */
    private void recordWorkloadEnv(String workload, String configMap, String host, String envName,
                                   String relative, EdgeLedger ledger) {
        recordWorkloadEnv(workload, configMap, host, envName, null, relative, ledger);
    }

    /** @param value the raw env value, when known, so its shape can say what kind of target the host is */
    private void recordWorkloadEnv(String workload, String configMap, String host, String envName, String value,
                                   String relative, EdgeLedger ledger) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workload", workload);
        if (configMap != null && !configMap.isBlank()) fields.put("configmap", configMap.trim());
        if (host != null && !host.isBlank()) fields.put("host", host.trim());
        if (fields.size() < 2) return;
        if (envName != null && !envName.isBlank()) fields.put("name", envName.trim());
        String kind = kindHint(envName, value);
        if (kind != null) fields.put("kind", kind);
        ledger.add("workload-env", fields, relative, -1, "High (k8s manifest)");
    }

    /** One DNS label, and a dotted name of several. */
    private static final String DNS_LABEL = "[a-z0-9]([a-z0-9-]*[a-z0-9])?";
    private static final String DNS_NAME = DNS_LABEL + "(\\." + DNS_LABEL + ")+";

    /** The in-cluster DNS suffixes a Kubernetes Service name may carry; the label before them is the namespace. */
    static final String[] CLUSTER_SUFFIXES = {".svc.cluster.local", ".cluster.local", ".svc"};

    /** RFC 2606 / 6761 / 6762 top-level domains: a host under them is a placeholder, never a deployment. */
    private static final String[] RESERVED_TLDS = {".local", ".test", ".example", ".invalid", ".localhost"};

    /**
     * @param keepDomain true for a Compose environment, where there are no namespaces: a dotted
     *                   name is a domain (or a dotted Compose service name, {@code catalog.api})
     *                   and is kept whole. False for a k8s manifest, where a two-label name is the
     *                   cluster DNS short form {@code <svc>.<ns>} and only then is cut to the
     *                   service. In both worlds only a real DNS suffix is ever stripped: a cluster
     *                   suffix takes its namespace label with it, a reserved TLD is kept whole so
     *                   the merger drops the host as a whole ({@code api.yas.local} used to come
     *                   out as a service called {@code api}; 2026-10-26), and a domain of three or
     *                   more labels is never cut to its first label.
     */
    private static String hostOf(String raw, boolean keepDomain) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);      // strip user:pass@
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash); // strip path
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon); // strip port
        if (s.isEmpty() || s.contains("$") || s.contains("[") || s.contains("{")) return null;
        if (s.matches("[0-9.]+")) return null;     // an IP or a bare number is not a name
        boolean cluster = false;
        for (String suffix : CLUSTER_SUFFIXES) {
            if (s.endsWith(suffix) && s.length() > suffix.length()) {
                s = s.substring(0, s.length() - suffix.length());
                cluster = true;
                break;
            }
        }
        int lastDot = s.lastIndexOf('.');
        if (cluster) {
            if (lastDot > 0) s = s.substring(0, lastDot);            // <svc>.<ns>.svc.cluster.local -> <svc>
        } else if (!keepDomain && lastDot > 0 && s.indexOf('.') == lastDot && !hasReservedTld(s)) {
            s = s.substring(0, lastDot);                              // k8s manifest: <svc>.<ns> -> <svc>
        }
        if (s.matches(DNS_LABEL)) return s;
        return s.matches(DNS_NAME) ? s : null;
    }

    private static boolean hasReservedTld(String host) {
        for (String tld : RESERVED_TLDS) if (host.endsWith(tld)) return true;
        return false;
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
            if (ServiceRootScanner.isTestName(service)) continue;

            java.util.List<String> targets = new java.util.ArrayList<>();
            // links is the version 1 file format's way of saying the same thing, and says
            // slightly more: a link exists so that this container can reach that one by
            // name. "gateway" or "db:database" — the alias after the colon is local to
            // this service, and is what its config then calls the target (mall-swarm's
            // jdbc:mysql://db for `mysql:db`), so it is recorded as a compose-alias row.
            // external_links is the same declaration for a container another Compose
            // file runs (2026-10-26).
            for (String key : List.of("depends_on", "links", "external_links")) {
                Object declared = config.get(key);
                if (declared instanceof List<?> list) {
                    for (Object t : list) {
                        String[] parts = String.valueOf(t).trim().split(":", 2);
                        String target = parts[0].trim();
                        targets.add(target);
                        String alias = parts.length > 1 ? parts[1].trim() : "";
                        if (!alias.isEmpty() && !target.isEmpty() && !alias.equalsIgnoreCase(target)) {
                            Map<String, String> fields = new LinkedHashMap<>();
                            fields.put("service", service);
                            fields.put("alias", alias);
                            fields.put("target", target);
                            ledger.add("compose-alias", fields, relative, -1, "High (docker-compose links alias)");
                        }
                    }
                } else if (declared instanceof Map<?, ?> map) {
                    for (Object k : map.keySet()) targets.add(String.valueOf(k).trim());
                }
            }

            for (String target : targets) {
                if (target.isEmpty() || target.equals(service) || ServiceRootScanner.isTestName(target)) continue;
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

    /**
     * Config namespaces of the OpenAPI UI libraries. Their URL values ({@code
     * springdoc.oauthflow.authorization-url}, {@code springdoc.swagger-ui.url}) are what the
     * browser-side Swagger UI opens — the config form of the metadata URLs rule 29 drops in
     * code. A module with no manifest (yas's delivery) got its only edge from one of these
     * (2026-10-26).
     */
    private static final String[] OPENAPI_UI_PREFIXES = {"springdoc.", "springfox.", "swagger.", "knife4j."};

    /** Keys that name another service or a piece of infrastructure. */
    private boolean isRelevant(String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);

        for (String prefix : OPENAPI_UI_PREFIXES) {
            if (k.startsWith(prefix)) return false;
        }

        if (k.startsWith("spring.datasource") || k.startsWith("spring.kafka")
                || k.startsWith("spring.rabbitmq") || k.startsWith("spring.data.mongodb")
                || k.startsWith("spring.redis") || k.startsWith("spring.data.redis")
                || k.startsWith("eureka.") || k.startsWith("spring.cloud.consul")
                // Spring Cloud Alibaba's registry / config centre (server-addr: host:port, no scheme)
                || k.startsWith("spring.cloud.nacos") || k.startsWith("spring.cloud.zookeeper")) {
            return true;
        }
        // Address lists: Spring's Elasticsearch `uris`, the older `cluster-nodes`, a
        // registry's `server-addr` — the value is host:port, so the shape alone would not
        // say it is a target (2026-10-26, mall-swarm).
        if (k.matches(".*\\.(uris|cluster-nodes|server-addr|addresses|bootstrap-servers)(\\[\\d+])?$")) {
            return true;
        }
        // The port a service listens on, and the config-server import whose value is a
        // placeholder with a localhost default: together they let a loopback address in a
        // client declaration be read back to the one module that owns the port (2026-10-23).
        if (k.equals("server.port") || k.startsWith("spring.config.import")
                || k.startsWith("spring.cloud.config.uri")) {
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
