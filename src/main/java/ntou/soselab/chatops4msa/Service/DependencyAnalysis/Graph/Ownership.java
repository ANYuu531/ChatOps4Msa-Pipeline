package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.AddressShape;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.EnvScope;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.BARE_FQDN;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.DEPLOY_NAME_SUFFIXES;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.cleanHost;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.commonPrefix;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.fileStem;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.firstPathSegment;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.hyphenated;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.isComposeFile;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.isConfigFile;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.isExternal;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.isPlausibleName;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.matchWorkloadForModule;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.parentDir;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.repoShortName;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.unversionedWorkload;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.workloadForDeploymentName;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Convention ③ — whose value is it: the env → host table with its scopes, which ConfigMap
 * or env_file each workload receives and which keys it reads, what each module is built
 * with and who imports a library, the FROM chain, the config repository's layout, and the
 * placeholder tables. Answers through the name table ({@link DeployedNames}) it is given.
 */
final class Ownership {

    private final DeployedNames names;

    Ownership(DeployedNames names) {
        this.names = names;
        this.envScope = new EnvScope(this::scopeIs, names::matchNode);
    }

    /**
     * What runs this repo's own code: the directories a grammar parsed, the images Dockerfiles
     * are built FROM and the directories build scripts and Compose build them from (the FROM
     * chain, rule 53), and the Compose services with a {@code build:}.
     */
    void indexImages(JSONArray edges) {
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String section = edge.optString("section", "");
            if ("parsed-dir".equals(section)) parsedDirs.add(fields.optString("dir", "").replace('\\', '/'));
            if ("dockerfile-from".equals(section)) dockerfileFrom.put(fields.optString("dir", "").replace('\\', '/'), fields.optString("image", ""));
            if ("image-build".equals(section)) builtImages.putIfAbsent(fields.optString("image", ""), fields.optString("dir", "").replace('\\', '/'));
            if ("compose-service".equals(section) && fields.has("image_built")) {
                String ctx = fields.optString("dockerfile_dir", fields.optString("context", "")).replace('\\', '/');
                if (!ctx.isBlank()) builtImages.putIfAbsent(fields.optString("image_built", ""), ctx);
            }
            if ("compose-service".equals(section) && "true".equals(fields.optString("built", ""))) {
                String name = names.clean(fields.optString("name", ""));
                if (!name.isEmpty()) composeBuilt.add(name);
            }
        }
    }

    /** env var name -> resolved host, from k8s ConfigMap / .env ({@code env-address}). */
    final Map<String, String> envAddress = new LinkedHashMap<>();
    /** ConfigMap name -> the address-shaped hosts it carries, for joining a workload's envFrom. */
    final Map<String, Set<String>> configMapHosts = new LinkedHashMap<>();
    /** ConfigMap name -> key -> the host its value names (convention ③: a key is read, a ConfigMap is not). */
    final Map<String, Map<String, String>> configMapEntries = new LinkedHashMap<>();
    /** Service node -> the keys of shared env sources its code or own config names ({@code key-read}). */
    Map<String, Set<String>> keysRead;
    JSONArray allEdges = new JSONArray();
    /** Spring property key (lower-cased) -> address-shaped value, from every config file read. */
    final Map<String, String> properties = new LinkedHashMap<>();
    /** {@code file:line} of every @FeignClient that declares a url — its name is then only a bean id. */
    final Set<String> feignUrlSites = new LinkedHashSet<>();
    /** Config file (relative path) -> the client service it configures, by file stem. */
    final Map<String, String> configFileService = new LinkedHashMap<>();
    /** Config files shared by every client of a config repository (application*.yml beside per-service files). */
    final Set<String> sharedConfigFiles = new LinkedHashSet<>();
    /** Services that fetch their config from a config server (bootstrap: spring.cloud.config.uri). */
    final Set<String> configClients = new LinkedHashSet<>();

    /**
     * One declared value of an env var, with the scope it holds in: {@code workload:<name>}
     * (a workload's own env block, or a Compose service's environment), {@code
     * configmap:<name>} (a ConfigMap's data), {@code module:<node>} (a Dockerfile's ENV),
     * or null (a global .env). The same name may be declared with a different value per
     * workload — four manifests each saying MONGO_CLIENT_HOST — so a read in module X is
     * resolved against X's own scope first (2026-10-23).
     */
    final EnvScope envScope;

    /** Compose services with a {@code build:} — this repo's own code runs in them. */
    final Set<String> composeBuilt = new LinkedHashSet<>();
    /** FROM chain: image stem → the repo directory it is built from (build scripts, Compose build:+image:). */
    final Map<String, String> builtImages = new LinkedHashMap<>();
    /** FROM chain: a Dockerfile's directory → the image stem it is built on. */
    final Map<String, String> dockerfileFrom = new LinkedHashMap<>();
    /** FROM chain, resolved: a base image's directory → the services whose images are built on it. */
    final Map<String, Set<String>> baseImageChildren = new LinkedHashMap<>();
    /** Raw workload name -> the ConfigMaps injected into it (envFrom / configMapKeyRef). */
    final Map<String, Set<String>> workloadConfigMaps = new LinkedHashMap<>();
    /** Raw workload name -> the literal hosts its own env block names (a service that states its targets by address). */
    final Map<String, Set<String>> workloadHosts = new LinkedHashMap<>();
    /** Module node -> the dependency artifacts its build manifest declares. */
    final Map<String, Set<String>> moduleArtifacts = new LinkedHashMap<>();
    /** Module node -> the manifest file the artifacts came from (provenance). */
    final Map<String, String> moduleManifests = new LinkedHashMap<>();
    /** Directories a grammar parsed files in (parsed-dir rows): the only proof a module's code can be read. */
    final Set<String> parsedDirs = new LinkedHashSet<>();
    /** Imported dotted module ({@code pkg.sub}) -> the service nodes whose code imports it. */
    final Map<String, Set<String>> importers = new LinkedHashMap<>();
    /** {@code Type.NAME} -> the string value of an enum constant / static final String. */
    final Map<String, String> nameConstants = new LinkedHashMap<>();
    /** The source whose placeholders are being substituted, for scoped env lookups. */
    String currentSource;
    /**
     * Which ConfigMaps each workload receives and which hosts its own env block names (convention ③).
     */
    void indexWorkloadEnv(JSONArray edges) {
        // Which ConfigMaps each workload receives, for scoping env lookups below.
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"workload-env".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String configMap = fields.optString("configmap", "");
            String workload = names.clean(fields.optString("workload", ""));
            if (!configMap.isBlank() && !workload.isEmpty()) {
                workloadConfigMaps.computeIfAbsent(workload, k -> new LinkedHashSet<>()).add(configMap);
            }
            String host = fields.optString("host", "");
            boolean undeclaredBare = "true".equals(fields.optString("bare", ""))
                    && names.composeServiceNames.stream().noneMatch(n -> n.equalsIgnoreCase(host.trim()))
                    && !names.rawWorkloadNames.contains(host.trim());
            if (!host.isBlank() && !workload.isEmpty() && !undeclaredBare) {
                workloadHosts.computeIfAbsent(workload, k -> new LinkedHashSet<>()).add(host.trim());
            }
        }
    }

    /**
     * The env → host table, every declared value kept with its scope (convention ③, rule 20).
     */
    void indexEnvAddresses(JSONArray edges) {
        // Pass 2: the env -> host table, every declared value kept with its scope (a
        // workload's own env block, a ConfigMap, a Dockerfile's module, or global). A repo
        // may declare the same env var in more than one manifest — a monolith variant that
        // points every API at one host, four services each naming their own database — and
        // only the value in the reading module's scope is what that module gets; see
        // resolveEnv. The legacy single-valued table is kept for the ConfigMap join.
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"env-address".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String name = fields.optString("name", "");
            String host = names.clean(fields.optString("host", ""));
            if (name.isBlank() || host.isEmpty()) continue;
            // A bare word under a URL-named key is a host only if the deployment declares it (rule 45).
            if (RuleTrace.hitIf("true".equals(fields.optString("bare", "")) && !names.declaredName(host), 45, "drop-env " + name + "=" + host)) continue;
            String hostNs = fields.optString("host_ns", "").trim();
            if (!hostNs.isEmpty() && names.ambiguousNames.contains(host) && names.namespaces.contains(hostNs)) host = host + "." + hostNs;

            // A rendered chart that is not the vocabulary only wires names the concrete
            // deployment has: its own database (yas's k8s `postgresql` beside Compose's
            // `postgres`) is another variant's node, not this graph's (rule 52).
            if (RuleTrace.hitIf(fields.has("helm") && !names.helmVocabulary && names.matchNode(host) == null, 52, "variant-host " + name + "=" + host)) continue;
            String current = envAddress.get(name);
            if (current == null
                    || (names.matchNode(host) != null && names.matchNode(current) == null)) {
                envAddress.put(name, host);
            }
            String configMap = fields.optString("configmap", "");
            if (!configMap.isBlank()) {
                configMapHosts.computeIfAbsent(configMap, k -> new LinkedHashSet<>()).add(host);
                configMapEntries.computeIfAbsent(configMap, k -> new LinkedHashMap<>()).putIfAbsent(name, host);
            }

            String scope = null;
            String workload = fields.optString("workload", "");
            if (!configMap.isBlank()) {
                scope = "configmap:" + configMap;
            } else if ("dockerfile".equals(workload)) {
                RuleTrace.hit(18, "dockerfile-env " + name + "=" + host);
                scope = "module:" + edge.optString("file", "").replace('\\', '/');
            } else if (!workload.isBlank()) {
                scope = "workload:" + names.clean(workload);
            }
            String kind = kindField(fields);
            envScope.declare(name, host, scope, kind, "true".equals(fields.optString("list", "")));
        }
    }

    /**
     * What each module is built with, and the string constants that name modules (rule 17).
     */
    void indexBuildManifests(JSONArray edges) {
        // The build manifests: what each module is built with, and who imports a library.
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String section = edge.optString("section", "");
            if ("module-deps".equals(section)) {
                String node = names.moduleNodes.get(fields.optString("dir", "").replace('\\', '/'));
                if (node == null) continue;
                Set<String> artifacts = moduleArtifacts.computeIfAbsent(node, k -> new LinkedHashSet<>());
                for (String a : fields.optString("artifacts", "").split(",")) {
                    if (!a.isBlank()) artifacts.add(a.trim().toLowerCase(Locale.ROOT));
                }
                String manifest = fields.optString("manifest", "");
                if (!manifest.isBlank()) moduleManifests.put(node, fields.optString("dir", "") + "/" + manifest);
            } else if ("name-constant".equals(section)) {
                String type = fields.optString("type", "");
                String name = fields.optString("name", "");
                String value = fields.optString("value", "");
                if (!name.isBlank() && !value.isBlank()) {
                    nameConstants.putIfAbsent((type.isBlank() ? "" : type + ".") + name, value.trim());
                }
            }
        }
    }

    /**
     * Whether a Compose service runs this repo's own code: it has a {@code build:}, or a
     * module directory aligns to it (by name, or by its image's name — rule 44).
     */
    boolean ownContainer(String composeService) {
        return composeBuilt.contains(composeService) || names.serviceDirs.containsValue(composeService);
    }

    boolean baseImagesResolved = false;

    /**
     * The services whose images are built FROM the base image whose directory holds
     * {@code file}, or null when the file is not in such a directory. A base image is a
     * directory some build script or Compose service builds into an image that another
     * Dockerfile of this repo names in FROM; the child is the service its own Dockerfile's
     * directory aligns to. Resolved once, after the service inventory.
     */
    Set<String> baseImageChildrenOf(String file) {
        if (!baseImagesResolved) {
            baseImagesResolved = true;
            for (Map.Entry<String, String> from : dockerfileFrom.entrySet()) {
                String baseDir = builtImages.get(from.getValue());
                if (baseDir == null || baseDir.equals(from.getKey())) continue;
                String child = names.serviceForFile(from.getKey() + "/Dockerfile");
                if (child == null) child = names.innerServiceDir(from.getKey() + "/Dockerfile");
                // Only a deployed child inherits: TeaStore's all-in-one image is built on the
                // base too, but no manifest runs it, so it is no node (2026-11-04).
                if (child == null || !(names.deployableModules.contains(child) || names.declaredWorkloads.contains(child))) continue;
                baseImageChildren.computeIfAbsent(baseDir, k -> new LinkedHashSet<>()).add(child);
            }
        }
        if (baseImageChildren.isEmpty() || file == null) return null;
        String path = file.replace('\\', '/');
        for (Map.Entry<String, Set<String>> e : baseImageChildren.entrySet()) {
            String dir = e.getKey();
            if (dir.isEmpty() ? !path.contains("/") : (path.startsWith(dir + "/"))) return e.getValue();
        }
        return null;
    }

    /**
     * Whether the tool can read the code that runs in a service: a module directory aligns
     * to it and its build marker names a language with a grammar. A Rust or Ruby module (the
     * OpenTelemetry demo's shipping and email) is the repo's own code the tool still cannot
     * read, so for it the manifest's word is all there is — like a third-party image.
     */
    boolean codeReadable(String service) {
        for (Map.Entry<String, String> e : names.serviceDirs.entrySet()) {
            if (!e.getValue().equals(service)) continue;
            String dir = e.getKey().replace('\\', '/');
            for (String parsed : parsedDirs) {
                if (parsed.equals(dir) || parsed.startsWith(dir + "/")) return true;
            }
        }
        return false;
    }

    /**
     * Who imports a library's modules. A Python distribution with no image of its own
     * ({@code setup.py}, no Dockerfile) runs inside the services that import it, so a call
     * in {@code pkg/sub.py} belongs to every module whose code says {@code from pkg.sub
     * import …} (or {@code import pkg.sub}) — and to none if nobody does. The library
     * itself is never a node (2026-10-23).
     */
    void indexImports(JSONArray edges) {
        if (names.libraryPackages.isEmpty()) return;
        Set<String> packages = new LinkedHashSet<>();
        for (Set<String> p : names.libraryPackages.values()) packages.addAll(p);
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"import".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String file = edge.optString("file", "").replace('\\', '/');
            String module = fields.optString("module", "").trim();
            String relative = fields.optString("relative", "").trim();
            String name = fields.optString("name", "").trim();
            // `from . import x` / `from .sub import y` inside a library: the module is
            // named relative to the importing file's own package.
            if (module.isEmpty() && !relative.isEmpty()) {
                String ownModule = moduleOfLibraryFile(file);
                if (ownModule == null) continue;
                int dots = 0;
                while (dots < relative.length() && relative.charAt(dots) == '.') dots++;
                String pkg = ownModule;
                for (int d = 0; d < dots && pkg.contains("."); d++) pkg = pkg.substring(0, pkg.lastIndexOf('.'));
                if (dots > 0 && !pkg.contains(".") && ownModule.indexOf('.') < 0) pkg = "";
                String rest = relative.substring(dots);
                module = pkg.isEmpty() ? rest : (rest.isEmpty() ? pkg : pkg + "." + rest);
                if (module.isEmpty()) continue;
            }
            if (module.isEmpty()) continue;
            String top = module.contains(".") ? module.substring(0, module.indexOf('.')) : module;
            if (!packages.contains(top)) continue;
            String ownModule = moduleOfLibraryFile(file);
            if (ownModule != null) {
                // Library-internal import: whoever imports this file transitively imports that one.
                libraryImports.computeIfAbsent(ownModule, k -> new LinkedHashSet<>()).add(module);
                if (!name.isEmpty()) libraryImports.get(ownModule).add(module + "." + name);
                continue;
            }
            String importer = names.resolveSource(file);
            if (importer == null || names.libraryModules.contains(importer)) continue;
            importers.computeIfAbsent(module, k -> new LinkedHashSet<>()).add(importer);
            if (!name.isEmpty()) importers.computeIfAbsent(module + "." + name, k -> new LinkedHashSet<>()).add(importer);
        }
    }

    /** Library file -> the dotted modules it imports from its own library (for transitive attribution). */
    final Map<String, Set<String>> libraryImports = new LinkedHashMap<>();

    /** The dotted module a library file is ({@code lib/pkg/sub.py} → {@code pkg.sub}), or null outside a library. */
    String moduleOfLibraryFile(String file) {
        String dir = libraryDirOf(file);
        if (dir == null) return null;
        String rel = file.replace('\\', '/').substring(dir.length() + 1);
        if (!rel.endsWith(".py")) return null;
        rel = rel.substring(0, rel.length() - 3);
        if (rel.endsWith("/__init__")) rel = rel.substring(0, rel.length() - "/__init__".length());
        return rel.replace('/', '.');
    }

    /** The library directory a file sits in, or null when the file is not library code. */
    String libraryDirOf(String file) {
        if (names.libraryPackages.isEmpty() || file == null) return null;
        String path = file.replace('\\', '/');
        for (String dir : names.libraryPackages.keySet()) {
            if (path.startsWith(dir + "/")) return dir;
        }
        return null;
    }

    /** The services whose code imports the dotted module a library file is ({@code pkg/sub.py} → {@code pkg.sub}). */
    Set<String> importersOf(String file) {
        String module = moduleOfLibraryFile(file);
        if (module == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        // The module itself, then every library module that imports it, transitively: a
        // service importing summarizer also runs what summarizer imports.
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(module));
        Set<String> seen = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String m = queue.poll();
            if (!seen.add(m)) continue;
            for (Map.Entry<String, Set<String>> e : importers.entrySet()) {
                // `from pkg.sub import f` and `import pkg.sub` both name the module; a package
                // import (`import pkg`) is not taken for every file under it.
                if (e.getKey().equals(m) || e.getKey().startsWith(m + ".")) out.addAll(e.getValue());
            }
            for (Map.Entry<String, Set<String>> e : libraryImports.entrySet()) {
                for (String imported : e.getValue()) {
                    if (imported.equals(m) || imported.startsWith(m + ".")) queue.add(e.getKey());
                }
            }
        }
        return out;
    }

    /** The host an env var resolves to for a read in {@code source} (convention ③, {@link EnvScope}). */
    String resolveEnv(String name, String source) {
        return envScope.resolve(name, source);
    }

    /** Rule 55: every host of a list-valued variable in the reader's scope, else the single value. */
    List<EnvScope.Value> resolveEnvValues(String name, String source) {
        return envScope.resolveAll(name, source);
    }

    /** As {@link #resolveEnv}, with the kind the declaring value's shape stated (or null). */
    EnvScope.Value resolveEnvValue(String name, String source) {
        return envScope.resolveValue(name, source);
    }

    /** The {@code kind} a ledger row states for its host ({@code db} / {@code queue}), as a graph kind, or null. */
    static String kindField(JSONObject fields) {
        String kind = fields.optString("kind", "");
        if ("db".equals(kind)) return DependencyGraph.KIND_DB;
        if ("queue".equals(kind)) return DependencyGraph.KIND_QUEUE;
        return null;
    }

    /** Whether a declared env value's scope is the module {@code source}. */
    boolean scopeIs(String scope, String source) {
        int colon = scope.indexOf(':');
        String kind = scope.substring(0, colon);
        String id = scope.substring(colon + 1);
        switch (kind) {
            case "workload" -> {
                String node = names.workloadAliases.getOrDefault(id, id);
                return source.equals(node) || source.equals(names.matchNodeLoose(node));
            }
            case "configmap" -> {
                for (Map.Entry<String, Set<String>> e : workloadConfigMaps.entrySet()) {
                    if (!e.getValue().contains(id)) continue;
                    String node = names.workloadAliases.getOrDefault(e.getKey(), e.getKey());
                    if (source.equals(node) || source.equals(names.matchNodeLoose(node))) return true;
                }
                return false;
            }
            case "module" -> {
                return source.equals(names.resolveSource(id));
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * The lookup tables the config rows carry, read before any edge is merged:
     * <ul>
     *   <li>the property table ({@code rates.url → https://api.exchangeratesapi.io}), so a
     *       {@code ${property}} placeholder in a client declaration resolves like an env var;</li>
     *   <li>the Feign clients that declare a {@code url}: their {@code name} is a bean id
     *       (piggymetrics's "rates-client"), not a host, and must not become a node;</li>
     *   <li>a Spring Cloud Config repository's layout: a file named after a client
     *       service ({@code shared/account-service.yml}) configures THAT service, whatever
     *       directory it sits in, and the {@code application.yml} beside such files is
     *       shared by every config client. Attributed by path, every one of piggymetrics's
     *       datasources and its Eureka registration read as dependencies of the config
     *       server (2026-09-22).</li>
     * </ul>
     */
    void indexConfig(JSONArray edges) {
        Set<String> configRepoDirs = new LinkedHashSet<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            String section = edge.optString("section", "");
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String file = edge.optString("file", "").replace('\\', '/');

            if ("feign".equals(section)) {
                if ("url".equalsIgnoreCase(fields.optString("attr", "")) && !fields.optString("value", "").isBlank()) {
                    feignUrlSites.add(file + ":" + edge.optInt("line", 0));
                }
                continue;
            }
            // A port a Compose service publishes on the host: who answers localhost:PORT on the
            // developer's machine (convention ①, beside server.port below).
            if ("compose-port".equals(section)) {
                String owner = names.resolveComposeService(fields.optString("workload", ""));
                if (owner != null) names.publishedPorts.computeIfAbsent(fields.optString("port", ""), k -> new LinkedHashSet<>()).add(owner);
                continue;
            }
            if (!"config".equals(section)) continue;

            String key = fields.optString("key", "").toLowerCase(Locale.ROOT);
            String value = fields.optString("value", "");
            if (!key.isEmpty() && !value.isBlank() && !value.contains("${")
                    && (CodeGraphMerger.looksLikeUrl(value) || isPlausibleName(names.clean(value)))) {
                properties.putIfAbsent(key, value.trim());
            }
            if (key.startsWith("spring.cloud.config.uri") || key.startsWith("spring.cloud.config.import")
                    || (key.startsWith("spring.config.import") && value.contains("configserver"))) {
                String client = names.resolveSource(file);
                if (RuleTrace.hitIf(client != null, 3, "config-client " + client)) configClients.add(client);
            }
            // server.port: the module that listens on a port. A loopback address with that
            // port in another module's client declaration (`http://localhost:9296`, the
            // dev default of a config import) is read back to this module — when exactly
            // one module owns the port (2026-10-23).
            if (key.equals("server.port")) {
                String port = value.trim();
                java.util.regex.Matcher m = PLACEHOLDER.matcher(port);
                if (m.matches()) port = m.group(2) == null ? "" : m.group(2).trim();
                if (port.matches("\\d{2,5}")) {
                    String owner = names.resolveSource(file);
                    if (owner != null) names.portOwners.computeIfAbsent(port, k -> new LinkedHashSet<>()).add(owner);
                }
            }

            // A config FILE named after a known service configures that service. Only a
            // config file: a code file also yields config rows (getenv, @Value), and
            // CatalogClient.java is not catalog's configuration.
            String stem = isConfigFile(file) ? fileStem(file) : null;
            if (stem != null && !stem.startsWith("application") && !stem.startsWith("bootstrap")) {
                String node = names.matchNode(stem);
                for (String s = stem; node == null && s.contains("-"); ) {
                    s = s.substring(0, s.lastIndexOf('-'));
                    node = names.matchNode(s); // account-service-dev → account-service; other-prod → other
                }
                if (RuleTrace.hitIf(node != null, 3, "config-file " + file + "=" + node)) {
                    configFileService.put(file, node);
                    configRepoDirs.add(parentDir(file));
                }
            }
        }
        // The application*.yml beside per-service files is the shared part of the repo.
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"config".equals(edge.optString("section", ""))) continue;
            String file = edge.optString("file", "").replace('\\', '/');
            String stem = fileStem(file);
            if (stem != null && stem.startsWith("application") && configRepoDirs.contains(parentDir(file))) {
                RuleTrace.hit(3, "shared-file " + file);
                sharedConfigFiles.add(file);
            }
        }
    }

    /**
     * Config-key names.namespaces whose values are consumed only by a client library: the
     * auto-configuration that binds {@code spring.rabbitmq.*} ships with the AMQP starter,
     * {@code security.oauth2.resource.*} with an OAuth2 artifact, and so on. A shared config
     * file hands these keys to every config client, but a client built without the library
     * never reads them; piggymetrics's registry, gateway and monitoring got RabbitMQ and
     * auth-service edges that way (2026-10-23). Each entry: key prefix → artifact regex.
     */
    static final String[][] KEY_LIBRARIES = {
            {"spring.rabbitmq.", "amqp|rabbit"},
            {"spring.kafka.", "kafka"},
            {"spring.data.mongodb.", "mongo"},
            {"spring.redis.", "redis"},
            {"spring.data.redis.", "redis"},
            {"security.oauth2.", "oauth2"},
            {"spring.security.oauth2.", "oauth2"},
            {"eureka.client.", "eureka"},
            {"spring.cloud.consul.", "consul"},
            {"spring.zipkin.", "zipkin|sleuth"},
    };

    /** Whether a shared config key can take effect in {@code client}, given what it is built with. */
    boolean consumesKey(String client, String key) {
        Set<String> artifacts = moduleArtifacts.get(client);
        if (artifacts == null || artifacts.isEmpty()) return true; // unknown build: cannot tell, keep
        String k = key.toLowerCase(Locale.ROOT);
        for (String[] entry : KEY_LIBRARIES) {
            if (!k.startsWith(entry[0])) continue;
            java.util.regex.Pattern lib = java.util.regex.Pattern.compile(entry[1]);
            for (String a : artifacts) if (RuleTrace.hitIf(lib.matcher(a).find(), 23, "keep " + client + " " + entry[0])) return true;
            RuleTrace.hit(23, "drop " + client + " " + entry[0]);
            return false;
        }
        return true;
    }

    /**
     * Data-store edges from the deployment wiring: a workload whose manifest injects a
     * ConfigMap ({@code envFrom}) or a literal address that resolves to a data store
     * gets a {@code db} edge to it.
     *
     * <p>Why this exists: Bank of Anthos's ledger services read {@code ledger-db} through
     * {@code SPRING_DATASOURCE_URL}, which Spring Boot binds without a line of code, so
     * the config-read rule above never fires — the env→host table knew the host and the
     * JPA markers proved the service persists, and still no edge was drawn. The static
     * graph shipped with no data layer at all; a doc-derived edge happened to cover four
     * of the five services and transactionhistory was simply missing (2026-09-21).
     *
     * <p>Only data-store and broker hosts are taken. A ConfigMap of service addresses
     * ({@code *_API_ADDR}) is injected into names.workloads that never call most of them, so
     * service-kind hosts stay with the code layer, which sees the actual reads. With
     * persistence code the edge is {@code documented}; without it, {@code inferred} — a
     * datasource a service is handed but never touches is a declaration, not a use.
     */
    /** Whether {@code source}'s code or own config names {@code key}, or its framework binds it ({@code key-read} rows). */
    boolean readsKey(String source, String key) {
        if (keysRead == null) {
            keysRead = new LinkedHashMap<>();
            for (int i = 0; i < allEdges.length(); i++) {
                JSONObject edge = allEdges.optJSONObject(i);
                if (edge == null || !"key-read".equals(edge.optString("section", ""))) continue;
                JSONObject fields = edge.optJSONObject("fields");
                String reader = names.resolveSource(edge.optString("file", ""));
                if (fields == null || reader == null) continue;
                keysRead.computeIfAbsent(reader, k -> new java.util.HashSet<>()).add(fields.optString("name", ""));
            }
        }
        return keysRead.getOrDefault(source, Set.of()).contains(key);
    }

    static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}|\\{\\{([^}]+)}}");

    /**
     * The target with every {@code ${NAME}}, {@code ${NAME:default}} and {@code {{NAME}}}
     * placeholder filled in, or null when the input has none or one cannot be filled.
     * A name is looked up in the env → host table (k8s ConfigMap / .env), then in the
     * property table (application*.yml, a config repository), then falls back to the
     * placeholder's own default; each lookup is case-insensitive and also tries the last
     * dotted segment, so {@code ${env.BALANCES_API_ADDR}} still hits. The whole string
     * is returned, so {@code http://${X}/path} keeps its path for the rules that follow.
     */
    /** {@code :${X}} — a port placeholder after a host. */
    static final java.util.regex.Pattern PORT_PLACEHOLDER = java.util.regex.Pattern.compile(":\\$\\{([A-Za-z0-9_.\\-]+)\\}");

    String substitutePlaceholders(String rawTarget) {
        if (rawTarget == null) return null;
        // A port placeholder the table does not hold (http://${REGISTRY_HOST}:${REGISTRY_PORT}/…)
        // does not hide the host: the env table keeps addresses, not numbers, so the port
        // part is dropped and the host still resolves (TeaStore's start.sh, 2026-11-04).
        rawTarget = PORT_PLACEHOLDER.matcher(rawTarget).replaceAll(r -> {
            List<String> words = AddressShape.words(r.group(1));
            return !words.isEmpty() && "port".equals(words.get(words.size() - 1)) && lookupPlaceholder(r.group(1)) == null
                    ? "" : java.util.regex.Matcher.quoteReplacement(r.group(0));
        });
        java.util.regex.Matcher m = PLACEHOLDER.matcher(rawTarget);
        StringBuilder out = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            any = true;
            String name = (m.group(1) != null ? m.group(1) : m.group(3));
            String fallback = m.group(2);
            String value = name == null ? null : lookupPlaceholder(name.trim());
            RuleTrace.hitIf(value != null, 4, "table " + name + "=" + value);
            // The placeholder's own default outranks a guess from its name: train-ticket's
            // lb://${ADMIN_ORDER_SERVICE_HOST:ts-admin-order-service} states the host.
            if (RuleTrace.hitIf(value == null && fallback != null && !fallback.isBlank(), 4, "default " + name + "=" + fallback)) value = fallback.trim();
            if (value == null && name != null) {
                value = names.nodeNamedByVariable(name.trim());
                if (RuleTrace.hitIf(value != null, 4, "by-name " + name + "=" + value)) nameInferred = true;
            }
            if (value == null) return null;
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
        }
        if (!any) return null;
        m.appendTail(out);
        return out.toString();
    }

    String lookupPlaceholder(String name) {
        String value = envLookup(name);
        // Spring's relaxed binding: the property yas.services.product is set by the
        // environment variable YAS_SERVICES_PRODUCT, and the environment outranks the
        // module's own file (2026-11-04).
        if (value == null && name.contains(".")) value = envLookup(name.toUpperCase(Locale.ROOT).replaceAll("[.\\-]", "_"));
        if (value == null) value = propertyLookup(name);
        if (value == null && name.contains(".")) {
            String last = name.substring(name.lastIndexOf('.') + 1);
            value = envLookup(last);
            if (value == null) value = propertyLookup(last);
        }
        return value;
    }

    /**
     * Set by {@link #substitutePlaceholders} when a placeholder resolved by its name
     * alone — the last resort after the tables and the placeholder's own default:
     * ${CATALOGUE_HOST} in Robot Shop's nginx template names catalogue, and no table
     * said so, so the edge this produces is marked inferred, not documented.
     */
    boolean nameInferred;

    String envLookup(String name) {
        return resolveEnv(name, currentSource);
    }

    /** A property by its Spring key, relaxed over case and {@code -}/{@code _} spelling. */
    String propertyLookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String value = properties.get(key);
        if (value != null) return value;
        String relaxed = key.replaceAll("[-_]", "");
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (e.getKey().replaceAll("[-_]", "").equals(relaxed)) return e.getValue();
        }
        return null;
    }

}
