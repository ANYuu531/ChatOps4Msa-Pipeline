package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
import org.springframework.stereotype.Component;
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
        appliedNamespaces.clear();
        appliedNamespaces.putAll(appliedNamespaces(root, ledger));
        List<Path> files = SourceScanner.filesWithExtensions(root,
                List.of(".yml", ".yaml", ".properties", ".env", ".conf", ".conf.template"));
        // A Compose project's .env, interpolated the way Compose does, per project directory.
        Map<Path, Map<String, String>> projectEnvs = new java.util.HashMap<>();

        // .NET's configuration file. Its keys are the same ones the Compose environment
        // overrides with `__` (ConnectionStrings__Database), and a YARP gateway's routing
        // table lives nowhere else (2026-10-26, run-aspnetcore-microservices).
        for (Path json : SourceScanner.filesWithExtensions(root, List.of(".json"))) {
            String jsonName = json.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean ocelot = jsonName.startsWith("ocelot");
            if (!jsonName.startsWith("appsettings") && !ocelot) {
                // Convention ②: an address is an address whatever file it is in. A JSON file of
                // no known kind (a Go service's config.development.json, a gateway's routes.json)
                // gives candidate rows, kept by the merger only toward a declared name and only
                // for the component that owns the file (its module, or the one container that
                // mounts it), as a mounted YAML's are (rule 60).
                if (!TOOLING_JSON.matcher(jsonName).find() && !TEST_CONFIG_NAME.matcher(jsonName).find()) {
                    try {
                        if (Files.size(json) <= 512_000) {
                            String text = Files.readString(json, StandardCharsets.UTF_8);
                            if (text.startsWith("\uFEFF")) text = text.substring(1);
                            mountedConfigAddresses("", new Yaml().load(text), SourceScanner.relative(root, json), ledger);
                        }
                    } catch (Exception ignored) {
                        // not JSON a YAML loader reads (comments, a JSON Lines file): no candidates
                    }
                }
                continue;
            }
            try {
                if (ocelot) extractOcelotRoutes(json, SourceScanner.relative(root, json), ledger);
                else extractDotnetAppSettings(json, SourceScanner.relative(root, json), ledger);
            } catch (Exception e) {
                // malformed JSON (comments, trailing commas): not worth failing the analysis over, but said
                ledger.addWarning("unreadable descriptor " + SourceScanner.relative(root, json) + ": " + HelmChartRenderer.firstLine(e.getMessage()));
            }
        }

        // A Dockerfile's ENV lines are the image's own environment: Robot Shop's shipping
        // image says CART_ENDPOINT=cart:8080 nowhere else (2026-10-23). They are scoped to
        // the module that owns the Dockerfile, like a workload's own env block.
        for (Path dockerfile : dockerfiles(root)) {
            try {
                extractDockerfileEnv(dockerfile, SourceScanner.relative(root, dockerfile), ledger);
            } catch (Exception e) {
                ledger.addWarning("unreadable descriptor " + SourceScanner.relative(root, dockerfile) + ": " + HelmChartRenderer.firstLine(e.getMessage()));
            }
        }
        // The images those Dockerfiles are built into, when a build script names them.
        extractImageBuilds(root, ledger);

        // Rule 50: a dotenv file inside a module (.env, .env.production) is that module's own
        // environment, the way a Dockerfile's ENV is — what dotenv, Next.js and Vite load at
        // start. A .env beside a Compose file is the Compose project's (read above, per
        // project) and is not a module scope. Templates (.env.sample / .example) are not an
        // environment; loopback values are a developer's machine and say nothing.
        for (Path dotenv : dotenvFiles(root)) {
            try {
                Path dir = dotenv.getParent();
                if (dir != null && hasComposeFile(dir)) continue;
                String relative = SourceScanner.relative(root, dotenv);
                for (Map.Entry<String, String> e : readEnvFile(dotenv).entrySet()) {
                    String host = addressHost(e.getKey(), e.getValue());
                    if (host == null || AddressShape.isLoopback(host)) continue;
                    RuleTrace.hit(50, "dotenv " + relative + " " + e.getKey() + "=" + host);
                    recordEnvAddress(e.getKey(), e.getValue(), null, "dockerfile", relative, ledger);
                }
            } catch (Exception e) {
                ledger.addWarning("unreadable descriptor " + SourceScanner.relative(root, dotenv) + ": " + e.getMessage());
            }
        }

        // Rule 52: Helm charts are rendered first, and their templates/ are not read as plain YAML.
        Set<Path> chartTemplates = HelmChartRenderer.templateDirs(root);
        for (HelmChartRenderer.Rendered r : HelmChartRenderer.render(root, ledger::addWarning)) {
            try {
                extractK8sYaml(r.yaml(), r.template(), ledger, r.chart());
            } catch (Exception e) {
                ledger.addWarning("unreadable descriptor " + r.template() + " (rendered from chart " + r.chart() + "): "
                        + HelmChartRenderer.firstLine(e.getMessage()));
            }
        }

        for (Path file : files) {
            if (underAny(file, chartTemplates)) continue;
            if (file.getFileName().toString().endsWith(".conf")
                    || file.getFileName().toString().endsWith(".conf.template")) {
                // A reverse proxy's routing table is the edge list of the front door:
                // Apache's ProxyPass and nginx's proxy_pass name the upstreams.
                try {
                    extractReverseProxy(file, SourceScanner.relative(root, file), ledger);
                } catch (Exception e) {
                    ledger.addWarning("unreadable descriptor " + SourceScanner.relative(root, file) + ": " + HelmChartRenderer.firstLine(e.getMessage()));
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
            // A config file whose name says it is a template or a sample (mate-infra-prod-template.yml,
            // application-example.properties) is a starting point for someone else's deployment,
            // as .env.sample is (rule 50), not this repo's configuration (rule 57, 2026-11-03).
            if (!isEnv && !isCompose && (isYaml || name.endsWith(".properties"))
                    && RuleTrace.hitIf(TEMPLATE_CONFIG.matcher(name).find(), 57, "template " + relative)) continue;
            // The build tool's own settings (gradle/wrapper/gradle-wrapper.properties,
            // .mvn/wrapper/maven-wrapper.properties: where to download the build tool) are
            // not the service's runtime configuration.
            if (name.endsWith("-wrapper.properties")) continue;
            try {
                if (name.endsWith(".properties") || isEnv) {
                    readKeyValueFile(file, relative, ledger);
                } else if (isCompose) {
                    // A Compose overlay whose name says it runs tests (compose.tests.yaml,
                    // docker-compose-e2e.yml) declares test runners, not workloads: the same
                    // exclusion the service-root scan applies to a tests/ directory (2026-10-26).
                    if (RuleTrace.hitIf(isTestCompose(name), 31, "skip-compose " + relative)) continue;
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
                    for (Object doc : springDocuments(file, relative)) flatten("", doc, relative, ledger);
                } else if (isYaml) {
                    // Any other YAML is either a k8s manifest (the workload inventory and
                    // the env -> host table that resolve targets with no cluster — the
                    // greenfield case) or a Spring config document that is not called
                    // application.yml: a Spring Cloud Config repository keeps one file per
                    // client service (shared/account-service.yml). Piggymetrics's datasources
                    // all live in such files, and the analysis saw none of them (2026-09-22).
                    if (!extractK8sEnvAddresses(file, relative, ledger)) {
                        List<Object> docs = springDocuments(file, relative);
                        Object loaded = docs.isEmpty() ? null : docs.get(0);
                        // An Envoy static config is a reverse proxy's routing table in YAML:
                        // its clusters are the upstreams, as nginx's proxy_pass are.
                        if (docs.size() <= 1 && (extractEnvoyClusters(loaded, relative, ledger) || extractApisixRoutes(loaded, relative, ledger))) {
                            continue;
                        }
                        for (Object doc : docs) {
                            if (looksLikeSpringConfig(doc)) {
                                RuleTrace.hit(3, "spring-yml " + relative);
                                flatten("", doc, relative, ledger);
                            } else {
                                mountedConfigAddresses("", doc, relative, ledger);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Unreadable or malformed config does not fail the analysis — but it is said:
                // a deployment descriptor that silently vanishes is a missing layer the
                // reader cannot see (skywalking-showcase's whole Helm chart, 2026-10-27).
                ledger.addWarning("unreadable descriptor " + relative + ": " + HelmChartRenderer.firstLine(e.getMessage()));
            }
        }
        recordKeyReads(root, ledger);
    }

    /** Where a key of a shared source can be read: source code and the module's own config files. */
    private static final List<String> KEY_READ_EXTENSIONS = List.of(".java", ".kt", ".cs", ".py", ".go", ".js", ".mjs", ".ts",
            ".php", ".rb", ".rs", ".scala", ".yml", ".yaml", ".properties", ".json", ".toml", ".ini", ".conf", ".xml", ".sh",
            ".gradle", ".kts", ".csproj");

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
    private void recordKeyReads(Path root, EdgeLedger ledger) {
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

    private static final java.util.regex.Pattern K8S_KIND = java.util.regex.Pattern.compile("(?m)^\\s*kind:\\s*[A-Z]\\w+\\s*$");

    private static void addKeyRead(String key, String binding, String relative, int line, EdgeLedger ledger) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", key);
        if (binding != null) fields.put("binding", binding);
        ledger.add("key-read", fields, relative, line, binding == null ? "High (key named in the reader)" : "Medium (framework binding)");
    }

    /**
     * Every document of a Spring-style YAML file (rule 57). Spring Boot keeps one profile per
     * {@code ---} document in one application.yml ({@code spring.profiles: docker} /
     * {@code spring.config.activate.on-profile: docker}); a single-document load threw on the
     * second {@code ---} and the whole file was dropped as unreadable, with its datasource
     * and discovery addresses (12 of the 100 mining systems, 92 files; 2026-11-03). The
     * active profile is not known statically, so every document is read; a document whose
     * profile names a test run is skipped, as test directories are (rule 42).
     */
    private static List<Object> springDocuments(Path file, String relative) throws java.io.IOException {
        List<Object> docs = new ArrayList<>();
        for (Object doc : new Yaml().loadAll(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            if (doc == null) continue;
            String profile = documentProfile(doc);
            if (RuleTrace.hitIf(profile != null && TEST_PROFILE.matcher(profile).find(), 57, "test-profile " + relative + " " + profile)) continue;
            docs.add(doc);
        }
        RuleTrace.hitIf(docs.size() > 1, 57, "documents " + relative + " " + docs.size());
        return docs;
    }

    /** JSON a tool reads, not the component: package manifests, compiler and linter settings, launch profiles, API schemas. */
    private static final java.util.regex.Pattern TOOLING_JSON = java.util.regex.Pattern.compile(
            "(?i)^(package(-lock)?|composer|tsconfig.*|jsconfig.*|angular|nx|lerna|project|workspace|turbo|vercel|netlify|firebase"
            + "|launchsettings|manifest|swagger|openapi|renovate|\\.?eslintrc.*|\\.?prettierrc.*|\\.?babelrc|babel\\.config|jest\\.config"
            + "|global\\.json|nuget|libman|bundleconfig|app\\.json|cdk|tslint|typedoc)(\\.json)?$|\\.(schema|lock|map)\\.json$|-lock\\.json$");

    /** A config file for a test run: config.test.json, settings.tests.json. */
    private static final java.util.regex.Pattern TEST_CONFIG_NAME = java.util.regex.Pattern.compile("(?i)[._-](test|tests|spec|e2e|it)[._-]");

    private static final java.util.regex.Pattern TEMPLATE_CONFIG = java.util.regex.Pattern.compile("(?i)[-_.](template|sample|example)s?\\.(ya?ml|properties)$");

    private static final java.util.regex.Pattern TEST_PROFILE = java.util.regex.Pattern.compile("(?i)(^|[,\\s])(test|tests|it|integration-test)($|[,\\s])");

    /**
     * Rule 60: the addresses in a YAML file of no known kind — a Prometheus scrape config, a
     * Grafana datasource, an OpenTelemetry Collector or Tempo config, an Alertmanager target —
     * as candidate {@code url} rows. Such a file is the configuration of whichever container
     * mounts it (prometheus.yml into prometheus), so the merger keeps a row only when exactly
     * one Compose service mounts the file and the host is a name the deployment declares; any
     * other row is dropped, not residue. The most frequent residue shape of the 100 mining
     * systems (scrape targets in 14, datasources in 8, collector exporters in 5; 2026-11-03).
     */
    private static void mountedConfigAddresses(String key, Object node, String relative, EdgeLedger ledger) {
        if (AddressShape.isInboundKey(key) || AddressShape.isCommentKey(key)) return;
        if (node instanceof Map<?, ?> map) {
            // Convention ②: a host and its port in sibling keys are one address ({"host": "localhost",
            // "port": 5432}, {"hostName": "rabbitmq", "port": 5672}) — as DB_HOST beside DB_PORT is.
            for (String[] hp : siblingHostPorts(map)) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("value", hp[1]);
                fields.put("key", key.isEmpty() ? hp[0] : key + "." + hp[0]);
                fields.put("mounted", "candidate");
                ledger.add("url", fields, relative, -1, "Medium (host and port keys)");
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                mountedConfigAddresses(key.isEmpty() ? String.valueOf(e.getKey()) : key + "." + e.getKey(), e.getValue(), relative, ledger);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) mountedConfigAddresses(key, item, relative, ledger);
        } else if (node instanceof String value) {
            String v = value.trim();
            if (v.length() > MAX_VALUE_LENGTH || !AddressShape.isAddressShaped(v) || AddressShape.host(v).isEmpty()) return;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("value", v);
            fields.put("key", key);
            fields.put("mounted", "candidate");
            ledger.add("url", fields, relative, -1, "Medium (mounted config)");
        }
    }


    /**
     * {key, "host:port"} for each port key beside a host key of one map: the host key is one
     * whose name says host ({@link AddressShape.KeyKind#HOST}) with a bare host value, the port
     * keys {@code port} or a name ending in port ({@code httpPort}, {@code tcpPort}) with a number.
     */
    private static List<String[]> siblingHostPorts(Map<?, ?> map) {
        List<String[]> out = new ArrayList<>();
        String hostKey = null, host = null;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (e.getValue() instanceof String v && AddressShape.keyKind(k) == AddressShape.KeyKind.HOST
                    && AddressShape.isHostValue(v) && !v.contains(":") && !v.contains(",")) {
                hostKey = k;
                host = v.trim();
                break;
            }
        }
        if (host == null) return out;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String k = String.valueOf(e.getKey());
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim().replaceFirst("^:", "");
            if (k.toLowerCase(Locale.ROOT).endsWith("port") && v.matches("\\d{2,5}")) out.add(new String[]{hostKey, host + ":" + v});
        }
        return out;
    }

    /** {@code spring.profiles} or {@code spring.config.activate.on-profile} of one document, or null. */
    private static String documentProfile(Object doc) {
        if (!(doc instanceof Map<?, ?> root) || !(root.get("spring") instanceof Map<?, ?> spring)) return null;
        Object p = spring.get("profiles");
        if (p instanceof String s) return s;
        if (spring.get("config") instanceof Map<?, ?> config && config.get("activate") instanceof Map<?, ?> activate
                && activate.get("on-profile") != null) {
            return String.valueOf(activate.get("on-profile"));
        }
        return null;
    }

    private static boolean underAny(Path file, Set<Path> dirs) {
        Path f = file.normalize();
        for (Path d : dirs) if (f.startsWith(d)) return true;
        return false;
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
        return extractK8sYaml(Files.readString(file, StandardCharsets.UTF_8), relative, ledger, null);
    }

    /** @param chart the Helm chart the text was rendered from, or null for a plain manifest */
    private boolean extractK8sYaml(String text, String relative, EdgeLedger ledger, String chart) throws Exception {
        boolean anyK8s = false;
        renderingChart = chart;
        try {
            for (Object doc : new Yaml().loadAll(text)) {
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
                // A manifest with no metadata.namespace goes where the repo's own apply command
                // puts it (`kubectl apply -f travel_agency.yaml -n travel-agency`, rule 54).
                if (namespace == null && !"Namespace".equals(kind)) namespace = appliedNamespaces.get(relative);
                currentNamespace = "Namespace".equals(kind) ? null : namespace;
                if (namespace != null && namespaces.add(namespace)) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("name", namespace);
                    ledger.add("k8s-namespace", fields, relative, -1, "High (k8s manifest)");
                }

                // The workload inventory: what the cluster would actually run. With no
                // runtime graph this is the only vocabulary that carries the real names —
                // a Maven module called microservice-kubernetes-demo-catalog deploys as
                // "catalog", and tools.descartes.teastore.webui as "teastore-webui".
                if (name != null && name.contains("$")) name = null; // a rendered name nothing could fill
                if (WORKLOAD_KINDS.contains(kind) && name != null) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("name", name);
                    fields.put("kind", kind);
                    if (chart != null) fields.put("helm", chart);
                    String labels = podLabels(root);
                    if (labels != null) fields.put("labels", labels);
                    String image = firstImage(root);
                    if (image != null && imageStem(image) != null) {
                        fields.put("image", imageStem(image));
                        String registry = imageRegistry(image);
                        if (registry != null) fields.put("image_registry", registry);
                    }
                    if (namespace != null) fields.put("namespace", namespace);
                    ledger.add("k8s-workload", fields, relative, -1, "High (k8s manifest)");
                }
                // A Service is the name callers use. It is recorded beside the workloads so
                // the merger can tell a versioned deployment (reviews-v1, the Istio bookinfo
                // convention) from the service it is a version of (reviews) — the Deployment
                // carries the version, the Service does not (2026-10-23).
                if ("Service".equals(kind) && name != null) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("name", name);
                    if (chart != null) fields.put("helm", chart);
                    Object spec = root.get("spec");
                    if (spec instanceof Map<?, ?> sp && sp.get("selector") instanceof Map<?, ?> sel && !sel.isEmpty()) {
                        fields.put("selector", labelString(sel));
                    }
                    if (namespace != null) fields.put("namespace", namespace);
                    ledger.add("k8s-service", fields, relative, -1, "High (k8s manifest)");
                }

                // Rule 56: Istio ingress routing — a Gateway's selector names the ingress workload,
                // a VirtualService bound to a gateway routes it to its destinations.
                if ("Gateway".equals(kind) && String.valueOf(root.get("apiVersion")).startsWith("networking.istio.io")) {
                    String workload = gatewayWorkload(root);
                    if (name != null && workload != null) {
                        Map<String, String> fields = new LinkedHashMap<>();
                        fields.put("name", name);
                        fields.put("workload", workload);
                        if (namespace != null) fields.put("namespace", namespace);
                        ledger.add("k8s-gateway", fields, relative, -1, "High (istio gateway)");
                    }
                }
                if ("VirtualService".equals(kind) && String.valueOf(root.get("apiVersion")).startsWith("networking.istio.io")) {
                    extractVirtualService(root, namespace, relative, ledger);
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
                            for (String[] hv : hostsOf(String.valueOf(k), String.valueOf(v), false)) {
                                if (name != null) recordWorkloadEnv(name, null, hv[0], String.valueOf(k), hv[1], relative, ledger);
                            }
                        } else if (k != null && pair.get("valueFrom") instanceof Map<?, ?> from
                                && from.get("configMapKeyRef") instanceof Map<?, ?> ref && ref.get("name") != null) {
                            if (name != null) {
                                // configMapKeyRef picks one key: that value, and only it, is this workload's own (convention ③).
                                recordWorkloadEnv(name, String.valueOf(ref.get("name")), null, String.valueOf(k), null, relative, ledger,
                                        null, ref.get("key") == null ? null : String.valueOf(ref.get("key")));
                            }
                        }
                    }
                    // Rule 53: the containers' command / args.
                    if (name != null) {
                        for (Object cmd : containerCommands(root)) {
                            for (String[] hit : commandAddresses(cmd, null)) {
                                RuleTrace.hit(53, "k8s " + name + " " + hit[0] + "=" + hit[1]);
                                recordCommandAddress(name, hit, "command", relative, ledger);
                            }
                        }
                    }
                    // envFrom: the whole ConfigMap is injected — this is the wiring that says
                    // WHICH workload receives SPRING_DATASOURCE_URL, which no code line reads.
                    for (String configMap : containerEnvFromConfigMaps(root)) {
                        if (name != null) recordWorkloadEnv(name, configMap, null, null, relative, ledger);
                    }
                }
            }
        } finally {
            renderingChart = null;
            currentNamespace = null;
        }
        return anyK8s;
    }

    /** The namespace of the k8s object being read (declared, or applied by a script), or null. */
    private String currentNamespace;
    /** Repo-relative manifest path → the namespace the repo's own scripts apply it into. */
    private final Map<String, String> appliedNamespaces = new LinkedHashMap<>();

    /** {@code kubectl apply|create|replace … -f <file> … -n|--namespace <ns>} on one line. */
    private static final java.util.regex.Pattern KUBECTL_APPLY = java.util.regex.Pattern.compile(
            "\\bkubectl\\s+(?:[^\\n]*?\\s)?(?:apply|create|replace)\\b([^\\n]*)");
    private static final java.util.regex.Pattern KUBECTL_FILE = java.util.regex.Pattern.compile(
            "(?:^|\\s)(?:-f|--filename)(?:=|\\s+)(\\S+)");
    private static final java.util.regex.Pattern KUBECTL_NS = java.util.regex.Pattern.compile(
            "(?:^|\\s)(?:-n|--namespace)(?:=|\\s+)([a-z0-9]([a-z0-9-]*[a-z0-9])?)(?=\\s|$)");

    /**
     * Rule 54: where a manifest that declares no namespace is applied. The repo's deploy
     * scripts (shell, Makefile) say it: {@code kubectl apply -f travel_agency.yaml -n
     * travel-agency}. The file is resolved against the script's directory, then the repo root;
     * a namespace written as a variable is not taken; a file applied into two namespaces has
     * none. These namespaces are declared namespaces like a Namespace object's.
     */
    private Map<String, String> appliedNamespaces(Path root, EdgeLedger ledger) {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        List<Path> scripts = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".sh") || n.endsWith(".bash") || n.equals("makefile") || n.endsWith(".mk");
                    })
                    .filter(p -> !SourceScanner.isIgnored(root, p))
                    .forEach(scripts::add);
        } catch (Exception ignored) {
            // unwalkable tree: no scripts
        }
        for (Path script : scripts) {
            String text;
            try {
                text = Files.readString(script, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            java.util.regex.Matcher m = KUBECTL_APPLY.matcher(text);
            while (m.find()) {
                String args = m.group(1);
                java.util.regex.Matcher ns = KUBECTL_NS.matcher(args);
                if (!ns.find()) continue;
                java.util.regex.Matcher f = KUBECTL_FILE.matcher(args);
                while (f.find()) {
                    String file = f.group(1).replaceAll("^[\"']|[\"']$", "");
                    if (file.contains("$") || file.contains("://")) continue;
                    for (Path base : new Path[]{script.getParent(), root}) {
                        Path p = base.resolve(file).normalize();
                        if (Files.isRegularFile(p) && p.startsWith(root.normalize())) {
                            found.computeIfAbsent(SourceScanner.relative(root, p), k -> new java.util.LinkedHashSet<>()).add(ns.group(1));
                            break;
                        }
                    }
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : found.entrySet()) {
            if (e.getValue().size() != 1) continue;
            String ns = e.getValue().iterator().next();
            out.put(e.getKey(), ns);
            RuleTrace.hit(54, "applied " + e.getKey() + " -n " + ns);
            if (namespaces.add(ns)) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("name", ns);
                fields.put("via", "kubectl -n");
                ledger.add("k8s-namespace", fields, e.getKey(), -1, "High (deploy script)");
            }
        }
        return out;
    }

    /** The namespace label of an in-cluster host ({@code travels.travel-agency:8000}, {@code x.ns.svc.cluster.local}), or null. */
    public static String namespaceLabel(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        for (String suffix : CLUSTER_SUFFIXES) {
            if (s.endsWith(suffix) && s.length() > suffix.length()) {
                s = s.substring(0, s.length() - suffix.length());
                break;
            }
        }
        String[] labels = s.split("\\.");
        return labels.length == 2 && labels[1].matches(DNS_LABEL) ? labels[1] : null;
    }

    /** The Helm chart the manifest being read was rendered from (rule 52), or null for a plain manifest. */
    private String renderingChart;

    /**
     * The workload an Istio Gateway runs on: its {@code spec.selector}. The Istio installation's
     * convention is the label {@code istio: <x>} on the Deployment {@code istio-<x>} — the
     * default ingress is {@code istio: ingressgateway} on {@code istio-ingressgateway}; any other
     * selector names its workload by its {@code app} label (or its single value).
     */
    private static String gatewayWorkload(Map<?, ?> root) {
        Object spec = root.get("spec");
        if (!(spec instanceof Map<?, ?> sp) || !(sp.get("selector") instanceof Map<?, ?> sel) || sel.isEmpty()) return null;
        Object istio = sel.get("istio");
        if (istio != null) return "istio-" + String.valueOf(istio).trim().toLowerCase(Locale.ROOT);
        Object app = sel.get("app");
        if (app != null) return String.valueOf(app).trim().toLowerCase(Locale.ROOT);
        return sel.size() == 1 ? String.valueOf(sel.values().iterator().next()).trim().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Rule 56: a VirtualService bound to a Gateway routes the ingress to each route's
     * destination host — {@code istio-ingressgateway → frontend} for Online Boutique's and
     * Bank of Anthos's istio manifests. One bound only to {@code mesh} (or to nothing) is
     * in-mesh traffic shaping (canaries, fault injection): its destinations are the service
     * itself, not edges. Graded documented, as declared routing is everywhere else.
     */
    private void extractVirtualService(Map<?, ?> root, String namespace, String relative, EdgeLedger ledger) {
        Object spec = root.get("spec");
        if (!(spec instanceof Map<?, ?> sp)) return;
        List<String> gateways = new java.util.ArrayList<>();
        if (sp.get("gateways") instanceof List<?> gl) for (Object g : gl) if (!"mesh".equals(String.valueOf(g).trim())) gateways.add(String.valueOf(g).trim());
        if (gateways.isEmpty()) return;
        Set<String> hosts = new java.util.LinkedHashSet<>();
        for (String section : List.of("http", "tcp", "tls")) {
            if (!(sp.get(section) instanceof List<?> routes)) continue;
            for (Object r : routes) {
                if (!(r instanceof Map<?, ?> route) || !(route.get("route") instanceof List<?> dests)) continue;
                for (Object d : dests) {
                    if (d instanceof Map<?, ?> dm && dm.get("destination") instanceof Map<?, ?> dest && dest.get("host") != null) {
                        hosts.add(String.valueOf(dest.get("host")).trim());
                    }
                }
            }
        }
        for (String gateway : gateways) {
            for (String host : hosts) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("gateway", gateway.contains("/") ? gateway.substring(gateway.indexOf('/') + 1) : gateway);
                fields.put("host", host);
                if (namespace != null) fields.put("namespace", namespace);
                RuleTrace.hit(56, "istio " + gateway + " -> " + host);
                ledger.add("ingress-route", fields, relative, -1, "High (istio virtualservice)");
            }
        }
    }

    /**
     * Rule 56: an APISIX standalone configuration ({@code routes:} / {@code upstreams:} whose
     * entries carry {@code nodes}) is a reverse proxy's routing table, read like nginx's
     * proxy_pass and Envoy's clusters: one {@code url} row per upstream node. The proxy is the
     * container the file is mounted into (the merger's mount attribution). Returns whether the
     * document was one.
     */
    private boolean extractApisixRoutes(Object loaded, String relative, EdgeLedger ledger) {
        if (!(loaded instanceof Map<?, ?> root)) return false;
        List<Object> upstreams = new java.util.ArrayList<>();
        if (root.get("upstreams") instanceof List<?> ul) upstreams.addAll(ul);
        if (root.get("routes") instanceof List<?> rl) {
            for (Object r : rl) if (r instanceof Map<?, ?> route && route.get("upstream") != null) upstreams.add(route.get("upstream"));
        }
        boolean any = false;
        for (Object u : upstreams) {
            if (!(u instanceof Map<?, ?> up)) continue;
            Object nodes = up.get("nodes");
            List<String> addresses = new java.util.ArrayList<>();
            if (nodes instanceof Map<?, ?> nm) for (Object k : nm.keySet()) addresses.add(String.valueOf(k).trim());
            if (nodes instanceof List<?> nl) {
                for (Object n : nl) {
                    if (n instanceof Map<?, ?> node && node.get("host") != null) {
                        addresses.add(String.valueOf(node.get("host")).trim() + (node.get("port") != null ? ":" + node.get("port") : ""));
                    }
                }
            }
            for (String a : addresses) {
                if (a.isEmpty()) continue;
                any = true;
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("value", "http://" + a);
                fields.put("proxy", "apisix");
                RuleTrace.hit(56, "apisix " + a);
                ledger.add("url", fields, relative, -1, "High (apisix routes)");
            }
        }
        return any;
    }

    /** {@code spec.template.metadata.labels} of a workload (a bare Pod's own labels), as sorted {@code k=v,…}; null if none. */
    private static String podLabels(Map<?, ?> root) {
        Object labels = null;
        if ("Pod".equals(String.valueOf(root.get("kind")))) {
            Object md = root.get("metadata");
            labels = md instanceof Map<?, ?> m ? m.get("labels") : null;
        } else {
            Object spec = root.get("spec");
            Object template = spec instanceof Map<?, ?> s ? s.get("template") : null;
            Object md = template instanceof Map<?, ?> t ? t.get("metadata") : null;
            labels = md instanceof Map<?, ?> m ? m.get("labels") : null;
        }
        return labels instanceof Map<?, ?> l && !l.isEmpty() ? labelString(l) : null;
    }

    private static String labelString(Map<?, ?> labels) {
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
        for (Map.Entry<?, ?> e : labels.entrySet()) sorted.put(String.valueOf(e.getKey()).trim(), String.valueOf(e.getValue()).trim());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
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

    /** The first container's image of a workload object, or null. */
    private static String firstImage(Map<?, ?> root) {
        Map<?, ?> podSpec = podSpec(root);
        Object containers = podSpec == null ? null : podSpec.get("containers");
        if (containers instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> c && c.get("image") != null) {
            return String.valueOf(c.get("image"));
        }
        return null;
    }

    /** Every {@code containers[].command} and {@code containers[].args} of a workload object. */
    private List<Object> containerCommands(Map<?, ?> root) {
        List<Object> out = new java.util.ArrayList<>();
        Map<?, ?> podSpec = podSpec(root);
        Object containers = podSpec == null ? null : podSpec.get("containers");
        if (containers instanceof List<?> list) {
            for (Object c : list) {
                if (!(c instanceof Map<?, ?> container)) continue;
                if (container.get("command") != null) out.add(container.get("command"));
                if (container.get("args") != null) out.add(container.get("args"));
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
        List<String[]> list = addressList(key, value, keepDomain);
        if (!list.isEmpty()) {
            // Rule 55: a value listing several services yields one row per address.
            for (String[] a : list) {
                RuleTrace.hit(55, "list " + key + " " + a[0]);
                recordEnvAddressHost(key, a[0], a[1], configMap, workload, relative, ledger, via, keepDomain, true);
            }
            return;
        }
        String host = addressHost(key, value, keepDomain);
        if (host == null) return;
        recordEnvAddressHost(key, host, value, configMap, workload, relative, ledger, via, keepDomain, false);
    }

    private void recordEnvAddressHost(String key, String host, String value, String configMap, String workload,
                                      String relative, EdgeLedger ledger, String via, boolean keepDomain, boolean listed) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", key);
        fields.put("host", host);
        if (listed) fields.put("list", "true");
        if (configMap != null) fields.put("configmap", configMap);
        // The scope the value holds in: a workload's own env block, or a Dockerfile's
        // module (marked "dockerfile"; the merger resolves the module from the file).
        if (workload != null) fields.put("workload", workload);
        if (via != null) fields.put("via", via);
        String kind = kindHint(key, value);
        if (kind != null) fields.put("kind", kind);
        if (bareUnderAddressKey(key, value)) fields.put("bare", "true");
        if (renderingChart != null) fields.put("helm", renderingChart);
        if (currentNamespace != null) fields.put("namespace", currentNamespace);
        String hostNs = keepDomain ? null : namespaceLabel(value);
        if (hostNs != null) fields.put("host_ns", hostNs);
        ledger.add("env-address", fields, relative, -1, "High (k8s manifest)");
    }

    /**
     * A single word under a URL / URI / endpoint key ({@code SEARCH_URL=elasticsearch}): a host
     * only if the deployment declares that name (rule 45), which the merger checks. A host-named
     * key ({@code DB_HOST}) takes a bare name as is (rule 5); a dotted name is a domain.
     */
    static boolean bareUnderAddressKey(String key, String value) {
        return value != null && AddressShape.keyKind(key) == AddressShape.KeyKind.ADDRESS
                && value.trim().matches("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(:\\d{1,5})?");
    }

    /** {host, the token it came from} per address of a value: each of a list (rule 55), else the one address; empty if none. */
    private static List<String[]> hostsOf(String name, String value, boolean keepDomain) {
        List<String[]> list = addressList(name, value, keepDomain);
        if (!list.isEmpty()) return list;
        String host = addressHost(name, value, keepDomain);
        return host == null ? List.of() : List.<String[]>of(new String[]{host, value});
    }

    /**
     * Rule 55: a value that lists several addresses — separated by {@code ,} {@code ;} or
     * whitespace, as travels' {@code PORTAL_SERVICES="voyages.fr;http://voyages.travel-portal:8000,
     * viaggi.it;http://viaggi…"} does — names each of them; only the address-shaped tokens (a
     * URL, a {@code host:port}) count, so the labels between them are not hosts. A list of
     * replicas of one cluster ({@code nacos-0…, nacos-1…, nacos-2…}: the names differ only in
     * digits) is one target, as rule 27 has always read it: an empty result leaves the value to
     * the single-address rules. Returns {host, token} per distinct service.
     */
    static List<String[]> addressList(String name, String value, boolean keepDomain) {
        if (value == null || !value.matches("(?s).*[,;\\s].*")) return List.of();
        List<String[]> out = new java.util.ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (String token : splitAddresses(value)) {
            if (!(token.contains("://") || token.matches("[^\\s/]+:\\d{2,5}(/.*)?"))) continue;
            String host = addressHost(name, token, keepDomain);
            if (host != null && seen.add(host)) out.add(new String[]{host, token});
        }
        if (out.size() < 2) return List.of();
        Set<String> stems = new java.util.HashSet<>();
        for (String[] a : out) stems.add(a[0].replaceAll("\\d+", ""));
        return stems.size() == 1 ? List.of() : out;
    }

    /**
     * What the VALUE (or the variable's own name) says the target is, when it says
     * anything: a {@code key=value;} connection string and a {@code ConnectionStrings__X}
     * variable are a database whatever the host is called ({@code catalogdb} carries no
     * database hint; .NET names its stores freely), an {@code amqp://} URL is a broker.
     * Null when the shape is neutral — the merger then classifies by the host's name as
     * before (2026-10-26, run-aspnetcore-microservices).
     */
    static String kindHint(String name, String value) {
        return AddressShape.kind(name, value);
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
                String stem = i < words.length ? imageStem(words[i]) : null;
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
                recordEnvAddress(m.group(1), unquote(m.group(2)), null, "dockerfile", relative, ledger);
            }
            if (!any) {
                // Legacy form: ENV KEY value with spaces
                int space = body.indexOf(' ');
                if (space > 0) recordEnvAddress(body.substring(0, space), unquote(body.substring(space + 1).trim()),
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
    private static final java.util.regex.Pattern DOCKER_BUILD = java.util.regex.Pattern.compile(
            "docker\\s+(?:buildx\\s+)?build\\b([^\\n;&|]*)");
    private static final java.util.regex.Pattern DOCKER_BUILD_TAG = java.util.regex.Pattern.compile("(?:^|\\s)(?:-t|--tag)[=\\s]+\"?([^\\s\"]+)\"?");

    /**
     * The images this repo builds from its own directories, from build scripts: TeaStore's
     * tools/build_docker.sh runs {@code docker buildx build -t "${registry}teastore-base"
     * ../utilities/tools.descartes.teastore.dockerbase/}. Emitted as image-build rows
     * (image stem → repo-relative directory) for the merger's FROM chain.
     */
    private void extractImageBuilds(Path root, EdgeLedger ledger) {
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
                String stem = imageStem(t.group(1));
                if (stem == null || stem.isEmpty()) continue;
                // the build context is the last argument that is a path
                String context = null;
                for (String w : args.trim().split("\\s+")) {
                    if (w.startsWith("-") || w.startsWith("\"")) continue;
                    if (w.equals(".") || w.contains("/")) context = w;
                }
                if (context == null) continue;
                String dir = repoRelativeDir(root, scriptDir, context.replace("\"", ""), false);
                if (dir == null) continue;
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("image", stem);
                fields.put("dir", dir);
                ledger.add("image-build", fields, relative, -1, "High (build script)");
            }
        }
    }

    /** A URL in an image's command line, its {@code ${ENV}} placeholders kept for the merger to fill. */
    private static final java.util.regex.Pattern DOCKER_COMMAND_URL = java.util.regex.Pattern.compile("(https?://[^\\s]+)");

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static final java.util.regex.Pattern CONNECTION_STRING_HOST = AddressShape.CONNECTION_STRING_HOST;

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
            if (RuleTrace.hitIf(m.find(), 32, "connstr " + name)) return hostOf(m.group(1).replaceFirst("(?i)^tcp:", ""), keepDomain);
        }
        if (AddressShape.isAddressShaped(v)) return hostOf(v, keepDomain);
        // The variable's name says it holds a host, so the value must look like one: a DNS
        // name, optionally with a port. SW_AGENT_NAME_SERVER: agent::app is a SkyWalking
        // service name, not an address, and its first segment is no host (2026-10-27).
        if (AddressShape.keyKind(name) != AddressShape.KeyKind.NONE) {
            if (RuleTrace.hitIf(!AddressShape.isHostValue(v) || v.matches(".*[,;].*"), 5, "not-a-host " + name + "=" + v)) return null;
            RuleTrace.hit(5, "env " + name + "=" + v);
            return hostOf(v, keepDomain);
        }
        return null;
    }


    /** {@code .env} and {@code .env.<mode>} files outside ignored trees, templates excluded. */
    private static List<Path> dotenvFiles(Path root) {
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

    private static boolean hasComposeFile(Path dir) {
        try (java.util.stream.Stream<Path> listing = Files.list(dir)) {
            return listing.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return (n.startsWith("docker-compose") || n.startsWith("compose.")) && (n.endsWith(".yml") || n.endsWith(".yaml"));
            });
        } catch (Exception e) {
            return false;
        }
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
        extractComposeNetworkAliases(services, projectEnv, relative, ledger);
        if (!(((Map<?, ?>) loaded).get("services") instanceof Map)) RuleTrace.hit(12, "v1 " + relative);
        for (Map.Entry<?, ?> entry : services.entrySet()) {
            String service = String.valueOf(entry.getKey()).trim();
            // A service whose name says it is a test runner (frontendTests, e2e-tests) is a
            // build unit, never a workload — as a tests/ directory is (2026-10-26).
            if (service.isEmpty() || RuleTrace.hitIf(ServiceRootScanner.isTestName(service), 31, "skip-service " + service)) continue;
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
                fields.put("built", "true"); // this repo's own code runs in it
                // build: plus image: names the image this directory is built into (FROM chain).
                if (config.get("image") != null) {
                    String builtImage = imageStem(String.valueOf(config.get("image")));
                    if (builtImage != null && !builtImage.isEmpty()) fields.put("image_built", builtImage);
                }
                String contextDir = repoRelativeDir(root, composeDir, interpolate(context.trim(), projectEnv, 0), false);
                if (contextDir != null) fields.put("context", contextDir);
                if (dockerfile != null) {
                    String dockerfileDir = repoRelativeDir(root, composeDir,
                            interpolate(context.trim(), projectEnv, 0) + "/" + interpolate(dockerfile.trim(), projectEnv, 0), true);
                    if (dockerfileDir != null) fields.put("dockerfile_dir", dockerfileDir);
                }
            }
            // image: with no build: the image is this repo's module only if its name says so —
            // the last path segment, tag and variables dropped (`${HUB}/gateway-service:${TAG}`
            // → gateway-service). The merger equates it with a module exactly (rule 44).
            if (context == null && config.get("image") != null) {
                String image = imageStem(String.valueOf(config.get("image")));
                if (image != null) {
                    fields.put("image", image);
                    String registry = imageRegistry(String.valueOf(config.get("image")));
                    if (registry != null) fields.put("image_registry", registry);
                }
            }
            ledger.add("compose-service", fields, relative, -1, "High (compose services key)");

            // A file the service mounts (`./config/apisix/apisix.yaml:/usr/local/apisix/conf/apisix.yaml`)
            // is that container's configuration, wherever it sits in the repo (rule 56's attribution).
            if (config.get("volumes") instanceof List<?> volumes) {
                for (Object v : volumes) {
                    String src = v instanceof Map<?, ?> vm ? String.valueOf(vm.get("source")) : String.valueOf(v).split(":", 2)[0];
                    src = interpolate(src.trim(), projectEnv, 0);
                    if (src.isEmpty() || src.contains("$") || !(src.startsWith(".") || src.contains("/"))) continue;
                    try {
                        Path p = composeDir.resolve(src).normalize();
                        if (!p.startsWith(root.normalize()) || !Files.isRegularFile(p)) continue;
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("service", service);
                        m.put("file", SourceScanner.relative(root, p));
                        ledger.add("compose-mount", m, relative, -1, "High (compose volume)");
                    } catch (Exception ignored) {
                        // an unresolvable mount source names no repository file
                    }
                }
            }

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
                    RuleTrace.hit(37, "hostname " + alias + "=" + service);
                    ledger.add("compose-alias", aliasFields, relative, -1, "High (compose hostname)");
                }
            }
        }
    }

    /** The address tokens of a value that may list several (B6): split on {@code ,} {@code ;} and whitespace. */
    static List<String> splitAddresses(String value) {
        List<String> out = new java.util.ArrayList<>();
        for (String p : value.split("[,;\\s]+")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    /** {@code ghcr.io/org/yas-cart:latest} → {@code yas-cart}; {@code ${HUB}/app:${TAG}} → {@code app}; null if nothing literal is left. */
    static String imageStem(String image) {
        String s = image.trim();
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);                       // digest
        int slash = s.lastIndexOf('/');
        String last = slash >= 0 ? s.substring(slash + 1) : s;
        int colon = last.indexOf(':');
        if (colon >= 0) last = last.substring(0, colon);           // tag
        last = last.replaceAll("\\$\\{[^}]*}|\\$[A-Za-z_][A-Za-z0-9_]*", "").trim();
        last = last.replaceAll("^[-_.]+|[-_.]+$", "").toLowerCase(Locale.ROOT);
        return last.matches("[a-z0-9][a-z0-9._-]*") ? last : null;
    }

    /** The registry path of an image before its last segment ({@code ghcr.io/org}), variables kept; null for a bare name. */
    static String imageRegistry(String image) {
        String s = image.trim();
        int slash = s.lastIndexOf('/');
        return slash > 0 ? s.substring(0, slash).toLowerCase(Locale.ROOT) : null;
    }

    /**
     * B3 / rule 25 extended: {@code networks.<n>.aliases} — the third Compose alias source,
     * beside {@code links} / {@code external_links} (rule 25) and {@code hostname:} (rule 37).
     * A network alias is a DNS name the service answers to on that network only, so it is
     * recorded for each service attached to that network (the alias' own service included),
     * not globally: skywalking-showcase runs a copy of the OpenTelemetry demo whose services
     * are named otel-demo-&lt;x&gt; and addressed as &lt;x&gt; on their own network, beside a
     * music application with its own frontend (2026-10-27). A service with no
     * {@code networks:} is on the file's {@code default} network.
     */
    private void extractComposeNetworkAliases(Map<?, ?> services, Map<String, String> projectEnv,
                                              String relative, EdgeLedger ledger) {
        Map<String, Set<String>> members = new LinkedHashMap<>();             // network -> services
        Map<String, Map<String, String>> aliasesByNetwork = new LinkedHashMap<>(); // network -> alias -> service
        for (Map.Entry<?, ?> entry : services.entrySet()) {
            String service = String.valueOf(entry.getKey()).trim();
            if (service.isEmpty() || !(entry.getValue() instanceof Map<?, ?> config)) continue;
            Object networks = config.get("networks");
            if (networks == null) {
                members.computeIfAbsent("default", k -> new java.util.LinkedHashSet<>()).add(service);
            } else if (networks instanceof List<?> list) {
                for (Object n : list) members.computeIfAbsent(String.valueOf(n).trim(), k -> new java.util.LinkedHashSet<>()).add(service);
            } else if (networks instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> n : map.entrySet()) {
                    String net = String.valueOf(n.getKey()).trim();
                    members.computeIfAbsent(net, k -> new java.util.LinkedHashSet<>()).add(service);
                    if (n.getValue() instanceof Map<?, ?> opts && opts.get("aliases") instanceof List<?> aliases) {
                        for (Object a : aliases) {
                            String alias = interpolate(String.valueOf(a).trim(), projectEnv, 0).toLowerCase(Locale.ROOT);
                            if (alias.isEmpty() || alias.contains("$") || alias.equalsIgnoreCase(service)) continue;
                            if (!alias.matches(DNS_LABEL + "|" + DNS_NAME)) continue;
                            aliasesByNetwork.computeIfAbsent(net, k -> new LinkedHashMap<>()).putIfAbsent(alias, service);
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, Map<String, String>> net : aliasesByNetwork.entrySet()) {
            for (Map.Entry<String, String> alias : net.getValue().entrySet()) {
                RuleTrace.hit(25, "network-alias " + net.getKey() + ":" + alias.getKey() + "=" + alias.getValue());
                for (String reader : members.getOrDefault(net.getKey(), Set.of())) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("service", reader);
                    fields.put("alias", alias.getKey());
                    fields.put("target", alias.getValue());
                    fields.put("network", net.getKey());
                    ledger.add("compose-alias", fields, relative, -1, "High (compose network alias)");
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
            // Convention ③: an env_file is injected wholesale, like an envFrom ConfigMap; the
            // service chose none of its keys, so a key is its own only if it reads it (rule 14).
            Map<String, String> wholesale = new LinkedHashMap<>(); // key -> the env_file it came from
            for (Path resolved : composeEnvFiles(config, root, composeDir, projectEnv)) {
                for (Map.Entry<String, String> e : readEnvFile(resolved).entrySet()) {
                    fileEnv.put(e.getKey(), interpolate(e.getValue(), projectEnv, 0));
                    wholesale.put(e.getKey(), SourceScanner.relative(root, resolved));
                }
            }
            env.putAll(fileEnv);
            Set<String> forwarded = new java.util.HashSet<>();
            Set<String> interpolated = new java.util.HashSet<>(); // keys whose value had a ${VAR} filled from .env (rule 24, traced below)

            Object environment = config.get("environment");
            if (environment instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String key = String.valueOf(e.getKey()).trim();
                    if (e.getValue() != null) {
                        String value = interpolate(String.valueOf(e.getValue()), projectEnv, 0);
                        if (!value.equals(String.valueOf(e.getValue()))) interpolated.add(key);
                        env.put(key, value);
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
                        String value = interpolate(s.substring(eq + 1).trim(), projectEnv, 0);
                        if (!value.equals(s.substring(eq + 1).trim())) interpolated.add(s.substring(0, eq).trim());
                        env.put(s.substring(0, eq).trim(), value);
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
                // Rule 7: the Compose environment is the env → host table. Rule 24: the value
                // came through a forwarded key, an env_file, or a ${VAR} filled from .env.
                String via = forwarded.contains(e.getKey()) ? "forward " : fileEnv.containsKey(e.getKey()) && !environmentDeclares(config, e.getKey()) ? "env_file "
                        : interpolated.contains(e.getKey()) ? "interp " : null;
                String shared = "env_file ".equals(via) ? wholesale.get(e.getKey()) : null;
                for (String[] hv : hostsOf(e.getKey(), e.getValue(), true)) {
                    RuleTrace.hit(via != null ? 24 : 7, (via != null ? via : "env ") + service + "." + e.getKey() + "=" + hv[0]);
                    recordWorkloadEnv(service, null, hv[0], e.getKey(), hv[1], relative, ledger, shared);
                }
            }
            // Convention ①: the ports a service publishes on the host (ports: "5432:5432"). A
            // process on the developer's machine reaching localhost:5432 reaches this service.
            for (String port : publishedPorts(config.get("ports"), projectEnv)) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("workload", service);
                fields.put("port", port);
                ledger.add("compose-port", fields, relative, -1, "High (compose ports)");
            }
            // Rule 53: the command line is wiring too (mysql -h mysql, curl http://nginx:8080).
            for (String key : List.of("entrypoint", "command")) {
                for (String[] hit : commandAddresses(config.get(key), projectEnv)) {
                    RuleTrace.hit(53, "compose " + service + "." + key + " " + hit[0] + "=" + hit[1]);
                    recordCommandAddress(service, hit, key, relative, ledger);
                }
            }
        }
    }

    /**
     * The host ports a Compose service publishes: {@code "8080:80"}, {@code "127.0.0.1:8080:80/tcp"},
     * {@code "${PORT:-8080}:80"}, a range's first port, and the long form {@code {published: 8080}}.
     * A bare container port ({@code "80"}) publishes on a random host port and is skipped.
     */
    static List<String> publishedPorts(Object ports, Map<String, String> projectEnv) {
        List<String> out = new ArrayList<>();
        if (!(ports instanceof List<?> list)) return out;
        for (Object p : list) {
            String published = null;
            if (p instanceof Map<?, ?> m && m.get("published") != null) {
                published = String.valueOf(m.get("published"));
            } else if (p != null && !(p instanceof Map<?, ?>)) {
                String s = interpolate(String.valueOf(p), projectEnv, 0).trim().replaceAll("/(tcp|udp)$", "");
                String[] parts = s.split(":");
                if (parts.length >= 2) published = parts[parts.length - 2];
            }
            if (published == null) continue;
            published = published.trim().replaceAll("-\\d+$", "");
            if (published.matches("\\d{2,5}")) out.add(published);
        }
        return out;
    }

    /** The env_files a Compose service names, resolved inside the repository. */
    private List<Path> composeEnvFiles(Map<?, ?> config, Path root, Path composeDir, Map<String, String> projectEnv) {
        Object envFile = config.get("env_file");
        List<?> envFiles = envFile instanceof List<?> l ? l : (envFile == null ? List.of() : List.of(envFile));
        List<Path> out = new ArrayList<>();
        for (Object f : envFiles) {
            String p = f instanceof Map<?, ?> m ? String.valueOf(m.get("path")) : String.valueOf(f);
            p = interpolate(p, projectEnv, 0);
            if (p.isBlank() || p.contains("$")) continue;
            Path resolved = composeDir.resolve(p).normalize();
            if (resolved.startsWith(root.normalize())) out.add(resolved);
        }
        return out;
    }

    private void recordCommandAddress(String workload, String[] hit, String key, String relative, EdgeLedger ledger) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workload", workload);
        fields.put("host", hit[1]);
        fields.put("name", key);
        fields.put("via", "command");
        if ("bare".equals(hit[3])) fields.put("bare", "true");
        String kind = kindHint(key, hit[2]);
        if (kind != null) fields.put("kind", kind);
        if (renderingChart != null) fields.put("helm", renderingChart);
        ledger.add("workload-env", fields, relative, -1, "High (container command line)");
    }

    /**
     * Rule 53: the addresses a container's command line names — a URL or {@code host:port}
     * anywhere ({@code ./bin/flink run -m jobmanager:8081}, {@code curl http://nginx:8080/test}),
     * and a bare host after {@code -h} or after a flag whose name says it holds one ({@code psql
     * -h postgres}, {@code --broker-addr=broker}) — graded like the environment: they are this
     * container's own wiring, so rule 35 now sees them too and stops drawing a depends_on that
     * only orders start-up (skywalking-showcase's monitor loads, 2026-10-27). Returns {flag or
     * token, host, raw value} per address; a value with an unfilled placeholder or a loopback
     * host is not one.
     */
    static List<String[]> commandAddresses(Object command, Map<String, String> env) {
        List<String> tokens = new java.util.ArrayList<>();
        List<Object> items = command instanceof List<?> l ? new java.util.ArrayList<>(l) : command == null ? List.of() : List.of(command);
        for (Object item : items) {
            String text = env == null ? String.valueOf(item) : interpolate(String.valueOf(item), env, 0);
            for (String t : text.split("[\\s;|&]+")) {
                t = t.replaceAll("^[\"'(]+|[\"')]+$", "");
                if (!t.isEmpty()) tokens.add(t);
            }
        }
        List<String[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            String flag = null, value = null;
            int eq = t.indexOf('=');
            if (t.startsWith("-") && eq > 0) {
                flag = t.substring(0, eq);
                value = t.substring(eq + 1);
            } else if (t.startsWith("-") && i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("-")) {
                flag = t;
                value = tokens.get(i + 1);
            }
            String host = null;
            String raw = null;
            // A flag whose name says it holds a host (--zk-host=x, --broker-addr x): the same key words as everywhere (②).
            if (flag != null && (flag.equals("-h") || AddressShape.keyKind(flag) != AddressShape.KeyKind.NONE)) {
                host = addressHost("_host", value);
                raw = value;
            }
            if (host == null && !t.startsWith("-")) {
                for (String part : splitAddresses(t)) {
                    if (part.contains("://") || part.matches("[A-Za-z0-9][A-Za-z0-9.-]*:\\d{2,5}(/.*)?")) {
                        String h = addressHost(null, part);
                        if (h != null && !AddressShape.isLoopback(h)) out.add(new String[]{t, h, part, ""});
                    }
                }
                continue;
            }
            // A bare word after a host-named flag is a host only if the deployment declares that
            // name (rule 45's test, applied by the merger): --character-set-server=utf8mb4 is not.
            if (host != null && !AddressShape.isLoopback(host) && !raw.contains("$")) {
                boolean bare = !raw.contains("://") && !raw.contains(":");
                out.add(new String[]{flag, host, raw, bare ? "bare" : ""});
            }
        }
        return out;
    }

    /** Whether a Compose service's own {@code environment} block names {@code key} (in either form); trace only. */
    private static boolean environmentDeclares(Map<?, ?> config, String key) {
        Object environment = config.get("environment");
        if (environment instanceof Map<?, ?> map) return map.containsKey(key);
        if (environment instanceof List<?> list) {
            for (Object item : list) {
                String s = String.valueOf(item).trim();
                if (s.equals(key) || s.startsWith(key + "=")) return true;
            }
        }
        return false;
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
                RuleTrace.hit(38, "yarp " + value);
                ledger.add("url", fields, relative, -1, "High (yarp reverse proxy)");
                return;
            }
            if (!isRelevantDotnetKey(prefix, value)) return;
            if (value.length() > MAX_VALUE_LENGTH) value = value.substring(0, MAX_VALUE_LENGTH) + "...";
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("key", prefix);
            fields.put("value", value);
            RuleTrace.hit(38, "key " + prefix);
            ledger.add("config", fields, relative, -1, "High");
        }
    }

    /**
     * Rule 61: an Ocelot gateway's route table ({@code ocelot*.json}): every
     * {@code Routes[]} (Ocelot 16+) or {@code ReRoutes[]} entry's
     * {@code DownstreamHostAndPorts[].Host} is an upstream of the gateway that owns the file,
     * as YARP's destinations and nginx's proxy_pass are (rules 6, 34, 38). The .NET gateways
     * of 4 of the 100 mining systems state their routes nowhere else (2026-11-03).
     */
    private void extractOcelotRoutes(Path file, String relative, EdgeLedger ledger) throws Exception {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        org.json.JSONObject root = new org.json.JSONObject(text);
        for (String key : List.of("Routes", "ReRoutes")) {
            org.json.JSONArray routes = root.optJSONArray(key);
            if (routes == null) continue;
            for (int i = 0; i < routes.length(); i++) {
                org.json.JSONObject route = routes.optJSONObject(i);
                org.json.JSONArray hosts = route == null ? null : route.optJSONArray("DownstreamHostAndPorts");
                if (hosts == null) continue;
                String scheme = route.optString("DownstreamScheme", "http");
                for (int j = 0; j < hosts.length(); j++) {
                    org.json.JSONObject hp = hosts.optJSONObject(j);
                    String host = hp == null ? "" : hp.optString("Host", "").trim();
                    if (host.isEmpty()) continue;
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("value", scheme + "://" + host + (hp.has("Port") ? ":" + hp.opt("Port") : ""));
                    fields.put("route", route.optString("UpstreamPathTemplate", ""));
                    RuleTrace.hit(61, "ocelot " + relative + " " + host);
                    ledger.add("url", fields, relative, -1, "High (ocelot gateway route)");
                }
            }
        }
    }

    /** {@code ReverseProxy:Clusters:<cluster>:Destinations:<name>:Address}. */
    private static final java.util.regex.Pattern YARP_DESTINATION = java.util.regex.Pattern.compile(
            "(?i)ReverseProxy:Clusters:([^:]+):Destinations:[^:]+:Address");

    private boolean isRelevantDotnetKey(String key, String value) {
        // An inbound statement (CORS origins, redirect URIs) or a comment key is no target (convention ②).
        if (AddressShape.isInboundKey(key) || AddressShape.isCommentKey(key)) return false;
        String k = key.toLowerCase(Locale.ROOT);
        if (k.startsWith("connectionstrings:")) return true;
        String last = k.substring(k.lastIndexOf(':') + 1);
        if (AddressShape.keyKind(key) != AddressShape.KeyKind.NONE) return true;
        // The Options pattern binds a section to a class (PostgresOptions:ConnectionString,
        // MongoOptions:ConnectionString): a connection string is one wherever it sits (rule 58).
        if (RuleTrace.hitIf(last.endsWith("connectionstring") || AddressShape.isConnectionString(value), 58, "options-key " + key)) return true;
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
                RuleTrace.hit(34, "url " + value);
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
                RuleTrace.hitIf(!sb.toString().equals(target), 46, "set-var " + sb);
                target = sb.toString();
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("value", target);
            RuleTrace.hit(6, "url " + target);
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
        recordWorkloadEnv(workload, configMap, host, envName, value, relative, ledger, null);
    }

    /**
     * @param shared the env_file the value came from when it was injected wholesale rather than
     *               chosen for this workload, else null: then the value is this workload's only
     *               if it reads the key (convention ③, {@code key-read} rows)
     */
    private void recordWorkloadEnv(String workload, String configMap, String host, String envName, String value,
                                   String relative, EdgeLedger ledger, String shared) {
        recordWorkloadEnv(workload, configMap, host, envName, value, relative, ledger, shared, null);
    }

    /** @param configMapKey the one ConfigMap key a {@code configMapKeyRef} selects, else null */
    private void recordWorkloadEnv(String workload, String configMap, String host, String envName, String value,
                                   String relative, EdgeLedger ledger, String shared, String configMapKey) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workload", workload);
        if (configMap != null && !configMap.isBlank()) fields.put("configmap", configMap.trim());
        if (host != null && !host.isBlank()) fields.put("host", host.trim());
        if (fields.size() < 2) return;
        if (envName != null && !envName.isBlank()) fields.put("name", envName.trim());
        if (shared != null) fields.put("shared", shared);
        if (configMapKey != null) fields.put("configmap_key", configMapKey);
        if (bareUnderAddressKey(envName, value)) fields.put("bare", "true");
        String kind = kindHint(envName, value);
        if (kind != null) fields.put("kind", kind);
        if (renderingChart != null) fields.put("helm", renderingChart);
        if (currentNamespace != null) fields.put("namespace", currentNamespace);
        String hostNs = value == null ? null : namespaceLabel(value);
        if (hostNs != null) fields.put("host_ns", hostNs);
        ledger.add("workload-env", fields, relative, -1, "High (k8s manifest)");
    }

    /** One DNS label, and a dotted name of several. */
    private static final String DNS_LABEL = "[a-z0-9]([a-z0-9-]*[a-z0-9])?";
    private static final String DNS_NAME = DNS_LABEL + "(\\." + DNS_LABEL + ")+";

    /** The in-cluster DNS suffixes a Kubernetes Service name may carry; the label before them is the namespace. */
    static final String[] CLUSTER_SUFFIXES = {".svc.cluster.local", ".cluster.local", ".svc"};

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
        String s = AddressShape.host(raw);         // scheme, userinfo, path, port off (convention ②)
        if (s.isEmpty() || s.contains("$") || s.contains("[") || s.contains("{")) return null;
        if (s.matches("[0-9.]+")) return null;     // an IP or a bare number is not a name
        boolean cluster = false;
        for (String suffix : CLUSTER_SUFFIXES) {
            if (s.endsWith(suffix) && s.length() > suffix.length()) {
                RuleTrace.hit(36, "cluster-suffix " + s);
                s = s.substring(0, s.length() - suffix.length());
                cluster = true;
                break;
            }
        }
        int lastDot = s.lastIndexOf('.');
        if (cluster) {
            if (lastDot > 0) s = s.substring(0, lastDot);            // <svc>.<ns>.svc.cluster.local -> <svc>
        } else if (!keepDomain && lastDot > 0 && s.indexOf('.') == lastDot && !AddressShape.isReservedHost(s)) {
            RuleTrace.hit(36, "ns-strip " + s);
            s = s.substring(0, lastDot);                              // k8s manifest: <svc>.<ns> -> <svc>
        } else if (lastDot > 0) {
            RuleTrace.hit(36, "keep-dotted " + s);                    // a domain, a reserved TLD or a dotted Compose name: kept whole
        }
        if (s.matches(DNS_LABEL)) return s;
        return s.matches(DNS_NAME) ? s : null;
    }

    /**
     * A URI scheme chain ends in the address: Pulsar's {@code metadata-store:zk:zookeeper:2181}
     * and {@code zk:zookeeper:2181} name zookeeper, not the scheme in front. When a
     * scheme-less authority has several colons and ends in a port, the host is the segment
     * before the port (B7, 2026-10-27).
     */
    public static String trailingHostPort(String s) {
        if (s == null || s.indexOf(':') == s.lastIndexOf(':') || s.contains("[")) return s;
        String[] parts = s.split(":");
        if (parts.length < 3 || !parts[parts.length - 1].matches("\\d{1,5}")
                || !parts[parts.length - 2].matches("[A-Za-z0-9][A-Za-z0-9.-]*")) return s;
        // Every segment in front of the host must be a scheme name (zk, metadata-store).
        for (int i = 0; i < parts.length - 2; i++) if (!parts[i].matches("[A-Za-z][A-Za-z0-9+.-]*")) return s;
        RuleTrace.hit(36, "scheme-chain " + s);
        return parts[parts.length - 2] + ":" + parts[parts.length - 1];
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

            // target -> the key that named it. links is the version 1 file format's way of
            // saying the same thing, and says slightly more: a link exists so that this
            // container can reach that one by name (without it a v1 file resolves no name),
            // whereas depends_on only orders start-up. "gateway" or "db:database" — the alias
            // after the colon is local to this service, and is what its config then calls
            // the target (mall-swarm's jdbc:mysql://db for `mysql:db`), so it is recorded as
            // a compose-alias row. external_links is the same declaration for a container
            // another Compose file runs (2026-10-26).
            Map<String, String> targets = new LinkedHashMap<>();
            for (String key : List.of("links", "external_links", "depends_on")) {
                Object declared = config.get(key);
                if (declared instanceof List<?> list) {
                    for (Object t : list) {
                        String[] parts = String.valueOf(t).trim().split(":", 2);
                        String target = parts[0].trim();
                        targets.putIfAbsent(target, key);
                        String alias = parts.length > 1 ? parts[1].trim() : "";
                        if (!key.equals("depends_on") && !target.isEmpty()) RuleTrace.hit(12, "links " + service + "->" + target);
                        if (!alias.isEmpty() && !target.isEmpty() && !alias.equalsIgnoreCase(target)) {
                            Map<String, String> fields = new LinkedHashMap<>();
                            fields.put("service", service);
                            fields.put("alias", alias);
                            fields.put("target", target);
                            RuleTrace.hit(25, "alias " + service + ":" + alias + "=" + target);
                            ledger.add("compose-alias", fields, relative, -1, "High (docker-compose links alias)");
                        }
                    }
                } else if (declared instanceof Map<?, ?> map) {
                    for (Object k : map.keySet()) targets.putIfAbsent(String.valueOf(k).trim(), key);
                }
            }

            for (Map.Entry<String, String> t : targets.entrySet()) {
                String target = t.getKey();
                if (target.isEmpty() || target.equals(service) || ServiceRootScanner.isTestName(target)) continue;
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("source_service", service);
                fields.put("target_service", target);
                fields.put("via", t.getValue());
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
        // An inbound statement (CORS origins, redirect URIs) or a comment key is no target (convention ②).
        if (AddressShape.isInboundKey(key) || AddressShape.isCommentKey(key)) return false;

        for (String prefix : OPENAPI_UI_PREFIXES) {
            if (RuleTrace.hitIf(k.startsWith(prefix), 43, "drop " + k)) return false;
        }

        if (k.startsWith("spring.datasource") || k.startsWith("spring.kafka")
                || k.startsWith("spring.rabbitmq") || k.startsWith("spring.data.mongodb")
                || k.startsWith("spring.redis") || k.startsWith("spring.data.redis")
                || k.startsWith("eureka.") || k.startsWith("spring.cloud.consul")
                // Spring Cloud Alibaba's registry / config centre (server-addr: host:port, no scheme)
                || k.startsWith("spring.cloud.nacos") || k.startsWith("spring.cloud.zookeeper")) {
            return true;
        }
        // The port a service listens on, and the config-server import whose value is a
        // placeholder with a localhost default: together they let a loopback address in a
        // client declaration be read back to the one module that owns the port (2026-10-23).
        if (k.equals("server.port") || k.startsWith("spring.config.import")
                || k.startsWith("spring.cloud.config.uri")) {
            return true;
        }
        // A key whose name says it holds an address — the same words as an environment
        // variable's, a flag's or a .NET key's: host, address, url, endpoint, an address list
        // (uris, bootstrap-servers, server-addr), a registry id (Zuul serviceId).
        if (AddressShape.keyKind(key) != AddressShape.KeyKind.NONE) return true;
        // A value that is plainly a URI is worth keeping whatever the key is called.
        return AddressShape.hasScheme(value);
    }
}
