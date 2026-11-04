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
 * Docker Compose files: the services with what they build and run, their environment and
 * env_files (interpolated the way Compose does), published ports, network and link aliases,
 * {@code depends_on} / {@code links}.
 */
final class ComposeReader {

    private final DescriptorRows rows;

    ComposeReader(DescriptorRows rows) {
        this.rows = rows;
    }

    /** Compose keys that are not services, in either file format. */
    static final Set<String> COMPOSE_TOP_LEVEL_KEYS =
            Set.of("version", "services", "networks", "volumes", "configs", "secrets", "name", "include");

    /**
     * The service map of a Compose file, in both formats: {@code services:} in version 2
     * and later, and — in the version 1 file format, which the Spring Cloud samples of that
     * era use — the top level itself, where every key is a service. Version 1 is not a
     * curiosity to skip: it is where those projects state their wiring, and the dataset we
     * score against derives its edges from exactly these files (2026-09-25).
     */
    static Map<?, ?> composeServices(Object loaded) {
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
    void extractComposeServices(Object loaded, Path root, Path composeDir, Map<String, String> projectEnv,
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
                if (!alias.isEmpty() && !alias.contains("$") && !alias.equalsIgnoreCase(service) && alias.matches(DescriptorRows.DNS_LABEL + "|" + DescriptorRows.DNS_NAME)) {
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
    void extractComposeNetworkAliases(Map<?, ?> services, Map<String, String> projectEnv,
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
                            if (!alias.matches(DescriptorRows.DNS_LABEL + "|" + DescriptorRows.DNS_NAME)) continue;
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
    static String repoRelativeDir(Path root, Path composeDir, String composePath, boolean parentOfFile) {
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
    void extractComposeEnvironment(Object loaded, Path root, Path composeDir, Map<String, String> projectEnv,
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
                rows.recordEnvAddress(e.getKey(), e.getValue(), null, service, relative, ledger,
                        forwarded.contains(e.getKey()) ? ".env" : null, true);
                // Rule 7: the Compose environment is the env → host table. Rule 24: the value
                // came through a forwarded key, an env_file, or a ${VAR} filled from .env.
                String via = forwarded.contains(e.getKey()) ? "forward " : fileEnv.containsKey(e.getKey()) && !environmentDeclares(config, e.getKey()) ? "env_file "
                        : interpolated.contains(e.getKey()) ? "interp " : null;
                String shared = "env_file ".equals(via) ? wholesale.get(e.getKey()) : null;
                for (String[] hv : DescriptorRows.hostsOf(e.getKey(), e.getValue(), true)) {
                    RuleTrace.hit(via != null ? 24 : 7, (via != null ? via : "env ") + service + "." + e.getKey() + "=" + hv[0]);
                    rows.recordWorkloadEnv(service, null, hv[0], e.getKey(), hv[1], relative, ledger, shared);
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
                    rows.recordCommandAddress(service, hit, key, relative, ledger);
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
    List<Path> composeEnvFiles(Map<?, ?> config, Path root, Path composeDir, Map<String, String> projectEnv) {
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
                host = DescriptorRows.addressHost("_host", value);
                raw = value;
            }
            if (host == null && !t.startsWith("-")) {
                for (String part : DescriptorRows.splitAddresses(t)) {
                    if (part.contains("://") || part.matches("[A-Za-z0-9][A-Za-z0-9.-]*:\\d{2,5}(/.*)?")) {
                        String h = DescriptorRows.addressHost(null, part);
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
    static boolean environmentDeclares(Map<?, ?> config, String key) {
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
    static final java.util.regex.Pattern COMPOSE_VAR = java.util.regex.Pattern.compile(
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
    static Map<String, String> projectEnv(Path dir) {
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
                    value = close > 0 ? value.substring(1, close) : DescriptorRows.unquote(value);
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
    static final java.util.regex.Pattern TEST_FILE_SEGMENT = java.util.regex.Pattern.compile(
            "(?i)(^|[.\\-_])(e2e|tests?)([.\\-_]|$)");

    static boolean isTestCompose(String lowerName) {
        String stem = lowerName.replaceAll("\\.ya?ml$", "");
        return TEST_FILE_SEGMENT.matcher(stem).find();
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
    void extractComposeDependsOn(Object loaded, String relative, EdgeLedger ledger) {
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

}
