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
 * Application configuration files: Spring {@code application*.yml} / {@code .properties},
 * .NET {@code appsettings*.json} and Ocelot routes, a reverse proxy's routing table (nginx,
 * Apache, Envoy), and any YAML / JSON of no known kind as candidate rows (rule 60).
 */
final class AppConfigReader {

    private final DescriptorRows rows;

    AppConfigReader(DescriptorRows rows) {
        this.rows = rows;
    }

    /**
     * Every document of a Spring-style YAML file (rule 57). Spring Boot keeps one profile per
     * {@code ---} document in one application.yml ({@code spring.profiles: docker} /
     * {@code spring.config.activate.on-profile: docker}); a single-document load threw on the
     * second {@code ---} and the whole file was dropped as unreadable, with its datasource
     * and discovery addresses (12 of the 100 mining systems, 92 files; 2026-11-03). A
     * document whose profile names a test run is skipped, as test directories are (rule 42),
     * and so is one for a profile the deployment never activates ({@link ActiveProfiles};
     * when the repository does not say, every document is read).
     */
    static List<Object> springDocuments(Path file, String relative, ActiveProfiles profiles) throws java.io.IOException {
        List<Object> docs = new ArrayList<>();
        for (Object doc : new Yaml().loadAll(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            if (doc == null) continue;
            String profile = documentProfile(doc);
            if (RuleTrace.hitIf(profile != null && TEST_PROFILE.matcher(profile).find(), 57, "test-profile " + relative + " " + profile)) continue;
            if (RuleTrace.hitIf(profiles.inactive(profile), 57, "inactive-profile " + relative + " " + profile)) continue;
            docs.add(doc);
        }
        RuleTrace.hitIf(docs.size() > 1, 57, "documents " + relative + " " + docs.size());
        return docs;
    }

    /** JSON a tool reads, not the component: package manifests, compiler and linter settings, launch profiles, API schemas. */
    static final java.util.regex.Pattern TOOLING_JSON = java.util.regex.Pattern.compile(
            "(?i)^(package(-lock)?|composer|tsconfig.*|jsconfig.*|angular|nx|lerna|project|workspace|turbo|vercel|netlify|firebase"
            + "|launchsettings|manifest|swagger|openapi|renovate|\\.?eslintrc.*|\\.?prettierrc.*|\\.?babelrc|babel\\.config|jest\\.config"
            + "|global\\.json|nuget|libman|bundleconfig|app\\.json|cdk|tslint|typedoc)(\\.json)?$|\\.(schema|lock|map)\\.json$|-lock\\.json$");

    /** A config file for a test run: config.test.json, settings.tests.json. */
    static final java.util.regex.Pattern TEST_CONFIG_NAME = java.util.regex.Pattern.compile("(?i)[._-](test|tests|spec|e2e|it)[._-]");

    static final java.util.regex.Pattern TEMPLATE_CONFIG = java.util.regex.Pattern.compile("(?i)[-_.](template|sample|example)s?\\.(ya?ml|properties)$");

    static final java.util.regex.Pattern TEST_PROFILE = java.util.regex.Pattern.compile("(?i)(^|[,\\s])(test|tests|it|integration-test)($|[,\\s])");

    /**
     * Rule 60: the addresses in a YAML file of no known kind — a Prometheus scrape config, a
     * Grafana datasource, an OpenTelemetry Collector or Tempo config, an Alertmanager target —
     * as candidate {@code url} rows. Such a file is the configuration of whichever container
     * mounts it (prometheus.yml into prometheus), so the merger keeps a row only when exactly
     * one Compose service mounts the file and the host is a name the deployment declares; any
     * other row is dropped, not residue. The most frequent residue shape of the 100 mining
     * systems (scrape targets in 14, datasources in 8, collector exporters in 5; 2026-11-03).
     */
    static void mountedConfigAddresses(String key, Object node, String relative, EdgeLedger ledger) {
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
            if (v.length() > DescriptorRows.MAX_VALUE_LENGTH || !AddressShape.isAddressShaped(v) || AddressShape.host(v).isEmpty()) return;
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
    static List<String[]> siblingHostPorts(Map<?, ?> map) {
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
    static String documentProfile(Object doc) {
        if (!(doc instanceof Map<?, ?> root) || !(root.get("spring") instanceof Map<?, ?> spring)) return null;
        Object p = spring.get("profiles");
        if (p instanceof String s) return s;
        if (spring.get("config") instanceof Map<?, ?> config && config.get("activate") instanceof Map<?, ?> activate
                && activate.get("on-profile") != null) {
            return String.valueOf(activate.get("on-profile"));
        }
        return null;
    }

    /**
     * Top-level keys that mark a YAML document as Spring configuration. A YAML file not
     * named application.yml is read as config only when it looks like one, so a GitHub
     * workflow, a Helm values file or a Compose file is not flattened into the ledger.
     */
    static final java.util.Set<String> SPRING_TOP_KEYS = java.util.Set.of(
            "spring", "server", "eureka", "zuul", "ribbon", "hystrix", "feign", "management",
            "security", "logging", "resilience4j");

    static boolean looksLikeSpringConfig(Object loaded) {
        if (!(loaded instanceof Map<?, ?> map) || map.isEmpty()) return false;
        int hits = 0;
        for (Object key : map.keySet()) {
            if (SPRING_TOP_KEYS.contains(String.valueOf(key).toLowerCase(Locale.ROOT))) hits++;
        }
        // "spring" alone is unambiguous; otherwise two Spring-shaped keys are required.
        return map.containsKey("spring") || map.containsKey("eureka") || hits >= 2;
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
    void extractDotnetAppSettings(Path file, String relative, EdgeLedger ledger) throws Exception {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        org.json.JSONObject root = new org.json.JSONObject(text);
        walkDotnetSettings("", root, relative, ledger);
    }

    void walkDotnetSettings(String prefix, Object node, String relative, EdgeLedger ledger) {
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
            if (value.length() > DescriptorRows.MAX_VALUE_LENGTH) value = value.substring(0, DescriptorRows.MAX_VALUE_LENGTH) + "...";
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
    void extractOcelotRoutes(Path file, String relative, EdgeLedger ledger) throws Exception {
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
    static final java.util.regex.Pattern YARP_DESTINATION = java.util.regex.Pattern.compile(
            "(?i)ReverseProxy:Clusters:([^:]+):Destinations:[^:]+:Address");

    boolean isRelevantDotnetKey(String key, String value) {
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
    boolean extractEnvoyClusters(Object loaded, String relative, EdgeLedger ledger) {
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

    static void collectSocketAddresses(Object node, List<Map<?, ?>> out, int depth) {
        if (depth > 8) return;
        if (node instanceof Map<?, ?> map) {
            if (map.get("socket_address") instanceof Map<?, ?> sa) out.add(sa);
            for (Object v : map.values()) collectSocketAddresses(v, out, depth + 1);
        } else if (node instanceof List<?> list) {
            for (Object v : list) collectSocketAddresses(v, out, depth + 1);
        }
    }

    /** {@code ProxyPass /order http://order:8080/} (Apache) and {@code proxy_pass http://cart:8080/;} (nginx). */
    static final java.util.regex.Pattern PROXY_DIRECTIVE = java.util.regex.Pattern.compile(
            "(?i)^\\s*(?:ProxyPass|ProxyPassReverse|proxy_pass)\\s+(?:\\S+\\s+)?([a-z]+://\\S+?)[;\\s]*$");

    /**
     * Emits a {@code url} row for every upstream a reverse-proxy config routes to. The
     * proxy is the front door of many demo systems (ewolff's Apache, Robot Shop's nginx);
     * its config is the only place its edges exist, and it is not a language any grammar
     * covers. A commented line is skipped; a {@code ${VAR}} host is kept for the merger's
     * placeholder tables to fill.
     */
    void extractReverseProxy(Path file, String relative, EdgeLedger ledger) throws Exception {
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
                String value = DescriptorRows.unquote(set.group(2));
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
    static final java.util.regex.Pattern NGINX_SET = java.util.regex.Pattern.compile(
            "^set\\s+\\$([A-Za-z_][A-Za-z0-9_]*)\\s+(\"[^\"]*\"|'[^']*'|[^\\s;]+)\\s*;\\s*$");
    /** A bare nginx variable reference inside a directive argument ({@code $docker_search_host}; not {@code ${…}}). */
    static final java.util.regex.Pattern NGINX_VAR = java.util.regex.Pattern.compile(
            "\\$([A-Za-z_][A-Za-z0-9_]*)");

    void readKeyValueFile(Path file, String relative, EdgeLedger ledger) throws Exception {
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals < 0) continue;
            record(line.substring(0, equals).trim(), line.substring(equals + 1).trim(), relative, ledger);
        }
    }

    void flatten(String prefix, Object node, String relative, EdgeLedger ledger) {
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

    void record(String key, String value, String relative, EdgeLedger ledger) {
        if (key.isEmpty() || value.isEmpty() || !isRelevant(key, value)) return;
        if (value.length() > DescriptorRows.MAX_VALUE_LENGTH) value = value.substring(0, DescriptorRows.MAX_VALUE_LENGTH) + "...";

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
    static final String[] OPENAPI_UI_PREFIXES = {"springdoc.", "springfox.", "swagger.", "knife4j."};

    /** Keys that name another service or a piece of infrastructure. */
    boolean isRelevant(String key, String value) {
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
