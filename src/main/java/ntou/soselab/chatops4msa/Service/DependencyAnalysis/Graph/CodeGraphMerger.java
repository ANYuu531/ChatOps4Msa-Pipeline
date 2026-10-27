package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
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
 * Merges the structured code-extracted edges (EdgeLedger.toJson) onto a runtime
 * {@link DependencyGraph}, <b>deterministically</b> — no LLM.
 *
 * The runtime graph is the authoritative vocabulary: its node ids are the real
 * k8s workload names. This merger tries to resolve each code edge's source and
 * target onto that vocabulary using section-specific rules (a Feign client name,
 * an absolute URL's host, a Kafka topic, …) plus name normalisation. What it can
 * resolve is added to the graph; what it cannot is returned as {@link Unresolved}
 * so the caller may hand only that residue to an LLM ("prefer not to use the LLM,
 * only where necessary").
 *
 * Provenance: a resolved code edge is added with provenance=code and
 * runtimeObserved=false, so an edge the code declares but the mesh never observed
 * renders dashed. When it lands on an existing runtime edge, {@link DependencyGraph}
 * merges them — the arrow stays solid and simply gains "code" provenance
 * ("confirmed by both code and runtime").
 */
public class CodeGraphMerger {

    /** A code edge whose source and/or target could not be resolved deterministically. */
    public static class Unresolved {
        public final String section;
        public final String rawSource; // best hint for the source service (file/repo derived), may be null
        public final String rawTarget; // the raw target token from the ledger fields, may be null
        public final String file;
        public final int line;

        public Unresolved(String section, String rawSource, String rawTarget, String file, int line) {
            this.section = section;
            this.rawSource = rawSource;
            this.rawTarget = rawTarget;
            this.file = file;
            this.line = line;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("section", section)
                    .put("source_hint", rawSource == null ? "" : rawSource)
                    .put("target_raw", rawTarget == null ? "" : rawTarget)
                    .put("provenance", file + (line > 0 ? ":" + line : ""));
        }
    }

    /**
     * Sections whose edges are persistence MARKERS (an entity / repository / model
     * declaration), not edges with a target of their own. This is the language-neutral
     * "does this service persist?" interface: each stack contributes its own ORM's
     * markers through a .scm pattern that emits into one of these sections. Java/Spring
     * uses {@code jpa} (@Entity/@Repository); every other stack emits into
     * {@code persistence} (SQLAlchemy / Django / GORM / TypeORM / …). Because this set
     * already accepts {@code persistence}, adding an ORM is a .scm change only — no Java
     * edit. A marker promotes its owning service's datasource-declared db edge above a
     * bare declaration ("really used", not merely declared).
     */
    private static final Set<String> PERSISTENCE_SECTIONS = Set.of("jpa", "persistence");

    /**
     * Sections that are a lookup signal, not a dependency edge: the repository's
     * service inventory ({@code service-root}) and the env-var → host table
     * ({@code env-address}). They seed the greenfield vocabulary and resolve
     * indirected targets; they are never rendered as arrows.
     */
    private static final Set<String> META_SECTIONS = Set.of("service-root", "env-address", "workload-env",
            "k8s-workload", "compose-service", "k8s-service", "k8s-namespace", "module-deps", "name-constant", "import",
            "compose-alias", "url-metadata", "k8s-gateway", "compose-mount");

    /** Hosts that name the caller itself, never another workload. */
    private static final Set<String> LOOPBACK = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]", "host.docker.internal");

    /**
     * Config keys whose bare (non-URL) value is a host or a registry id, i.e. a target. The
     * address-list keys ({@code spring.elasticsearch.uris: es:9200}, Nacos's {@code
     * server-addr: nacos-registry:8848}, {@code spring.rabbitmq.addresses}) carry
     * {@code host:port} with no scheme, so they are named here rather than left to the
     * URL shape (2026-10-26, mall-swarm).
     */
    private static final String[] HOST_KEY_SUFFIXES = {
            ".host", ".hostname", "_host", "_hostname", ".address", ".serviceid", ".service-id",
            ".uris", ".cluster-nodes", ".server-addr", ".addresses", ".bootstrap-servers"};

    /** A bare fully-qualified host ({@code oss-cn-shenzhen.aliyuncs.com}, optionally {@code :port}): no scheme, no path. */
    private static final java.util.regex.Pattern BARE_FQDN = java.util.regex.Pattern.compile(
            "(?i)[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:\\d{2,5})?");

    /** Feign attributes that are bean wiring, not a target: a contextId is not a host. */
    private static final Set<String> FEIGN_NON_TARGET_ATTRS = Set.of(
            "contextid", "qualifier", "qualifiers", "fallback", "fallbackfactory",
            "configuration", "primary", "decode404", "path");

    private final DependencyGraph graph;
    private final Set<String> knownNodes = new LinkedHashSet<>();
    private final String defaultSource; // resolved from the repo name, used when the file path does not identify a service
    /** Services with persistence code (JPA/ORM markers) — used to tell a really-used DB from a declared one. */
    private final Set<String> persistenceServices = new LinkedHashSet<>();
    /** Common module-directory prefix (e.g. "spring-petclinic") learned from dirs that resolved to workloads. */
    private String modulePrefix;

    /**
     * Greenfield mode: no runtime graph supplied a vocabulary, so the service
     * inventory scanned from the repo ({@code service-root}) is the only source of
     * node names. In this mode the merger both seeds nodes from the inventory and
     * attributes sources by matching a file to its service directory.
     */
    private final boolean greenfield;
    /** service directory (relative path) -> service name, longest dir wins on lookup. */
    private final Map<String, String> serviceDirs = new LinkedHashMap<>();
    /** env var name -> resolved host, from k8s ConfigMap / .env ({@code env-address}). */
    private final Map<String, String> envAddress = new LinkedHashMap<>();
    /** ConfigMap name -> the address-shaped hosts it carries, for joining a workload's envFrom. */
    private final Map<String, Set<String>> configMapHosts = new LinkedHashMap<>();
    /**
     * Normalised service key -> node, for resolving a call whose host is a variable
     * but whose URL PATH names the callee by convention (Spring: {@code
     * /api/v1/orderservice/...} -> order-service). Keys are letters-only forms of the
     * known service names, with and without a {@code ts-} prefix / {@code service} suffix.
     */
    private final Map<String, String> serviceKeys = new LinkedHashMap<>();
    /** Whether the vocabulary came from a rendered Helm chart (rule 52: only when nothing more concrete exists). */
    private boolean helmVocabulary;
    /** Workload names the k8s manifests declare ({@code k8s-workload}); greenfield vocabulary. */
    private final Set<String> workloads = new LinkedHashSet<>();
    /** Module names that are (or align to) a deployable workload; pre-added as nodes in greenfield. */
    private final Set<String> deployableModules = new LinkedHashSet<>();
    /** Spring property key (lower-cased) -> address-shaped value, from every config file read. */
    private final Map<String, String> properties = new LinkedHashMap<>();
    /** {@code file:line} of every @FeignClient that declares a url — its name is then only a bean id. */
    private final Set<String> feignUrlSites = new LinkedHashSet<>();
    /** Config file (relative path) -> the client service it configures, by file stem. */
    private final Map<String, String> configFileService = new LinkedHashMap<>();
    /** Config files shared by every client of a config repository (application*.yml beside per-service files). */
    private final Set<String> sharedConfigFiles = new LinkedHashSet<>();
    /** Services that fetch their config from a config server (bootstrap: spring.cloud.config.uri). */
    private final Set<String> configClients = new LinkedHashSet<>();

    /**
     * One declared value of an env var, with the scope it holds in: {@code workload:<name>}
     * (a workload's own env block, or a Compose service's environment), {@code
     * configmap:<name>} (a ConfigMap's data), {@code module:<node>} (a Dockerfile's ENV),
     * or null (a global .env). The same name may be declared with a different value per
     * workload — four manifests each saying MONGO_CLIENT_HOST — so a read in module X is
     * resolved against X's own scope first (2026-10-23).
     */
    private record EnvValue(String host, String scope, String kind, boolean listed) {
        EnvValue(String host, String scope, String kind) {
            this(host, scope, kind, false);
        }
    }

    /**
     * The namespaces the manifests declare (plus the runtime graph's own). A host written
     * {@code <svc>.<ns>} is in-cluster only when {@code <ns>} is one of these; that label,
     * and the cluster DNS suffixes, are the only things ever stripped from a dotted name.
     * Everything else dotted is either a declared name kept whole ({@code catalog.api}, a
     * Compose service) or an external domain — never cut to its first label, which is how
     * {@code catalog.api} became {@code catalog} and {@code api.yas.local} became {@code api}
     * (2026-10-26, set C).
     */
    private final Set<String> namespaces = new LinkedHashSet<>();
    /** Repo-relative module directory → the Compose service built from it ({@code build:` context / dockerfile dir). */
    private final Map<String, String> composeBuildDirs = new LinkedHashMap<>();
    /** k8s image stem (and the stem with the project's registry prefix cut) → the vocabulary workloads running it. */
    private final Map<String, Set<String>> k8sImages = new LinkedHashMap<>();
    private final Map<String, Set<String>> k8sImageRegistries = new LinkedHashMap<>();
    /** A module whose image several workloads run → those workloads (its code edges are each one's). */
    private final Map<String, List<String>> moduleFanout = new LinkedHashMap<>();

    /**
     * The workloads that run the image a module is built into: an image stem (whole, or with
     * the project's registry prefix cut) equal to the module's directory, name or artifact.
     */
    private Set<String> imageWorkloads(String dir, String rawName, String artifact, Set<String> vocabulary) {
        String last = dir.contains("/") ? dir.substring(dir.lastIndexOf('/') + 1) : dir;
        for (String key : new String[]{hyphenated(last), hyphenated(rawName), artifact == null ? "" : hyphenated(artifact)}) {
            if (key.isEmpty()) continue;
            for (Map.Entry<String, Set<String>> e : k8sImages.entrySet()) {
                if (hyphenated(e.getKey()).equals(key)) return filterTo(e.getValue(), vocabulary);
            }
        }
        // Exact only: a suffix test on image names cut to their project stem would equate
        // train-ticket's undeployed ts-train-food-service with ts-food-service's image.
        return Set.of();
    }

    private static Set<String> filterTo(Set<String> names, Set<String> vocabulary) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) if (vocabulary.contains(n)) out.add(n);
        return out;
    }

    /** Compose image stem ({@code gateway-service}, and the stem with the project's registry prefix cut) → the services running it. */
    private final Map<String, Set<String>> composeImages = new LinkedHashMap<>();
    /** Image registry path → the image stems under it. */
    private final Map<String, Set<String>> composeImageRegistries = new LinkedHashMap<>();

    private static String commonPrefix(Set<String> names) {
        String p = null;
        for (String n : names) {
            if (p == null) {
                p = n;
                continue;
            }
            int i = 0;
            while (i < p.length() && i < n.length() && p.charAt(i) == n.charAt(i)) i++;
            p = p.substring(0, i);
        }
        return p == null ? "" : p;
    }

    /** Compose service names, as declared (dots included), for the artifactId equation. */
    private final Set<String> composeServiceNames = new LinkedHashSet<>();

    /** env var name -> every declared value with its scope. */
    private final Map<String, List<EnvValue>> envValues = new LinkedHashMap<>();
    /** Raw workload name (a versioned Deployment, a Compose service) -> the node it is. */
    private final Map<String, String> workloadAliases = new LinkedHashMap<>();
    /** Raw workload name -> the ConfigMaps injected into it (envFrom / configMapKeyRef). */
    private final Map<String, Set<String>> workloadConfigMaps = new LinkedHashMap<>();
    /** Raw workload name -> the literal hosts its own env block names (a service that states its targets by address). */
    private final Map<String, Set<String>> workloadHosts = new LinkedHashMap<>();
    /** Listening port -> the modules whose config declares it (server.port); a target only when unique. */
    private final Map<String, Set<String>> portOwners = new LinkedHashMap<>();
    /** Module node -> the dependency artifacts its build manifest declares. */
    private final Map<String, Set<String>> moduleArtifacts = new LinkedHashMap<>();
    /** Module node -> the manifest file the artifacts came from (provenance). */
    private final Map<String, String> moduleManifests = new LinkedHashMap<>();
    /** Module names (service-root spelling) that are libraries, not services. */
    private final Set<String> libraryModules = new LinkedHashSet<>();
    /** The library modules whose verdict came from a JVM build file (trace only: rule 41 rather than 21). */
    private final Set<String> jvmLibraryModules = new LinkedHashSet<>();
    /** Library module dir -> its importable top-level packages. */
    private final Map<String, Set<String>> libraryPackages = new LinkedHashMap<>();
    /** Imported dotted module ({@code pkg.sub}) -> the service nodes whose code imports it. */
    private final Map<String, Set<String>> importers = new LinkedHashMap<>();
    /** {@code Type.NAME} -> the string value of an enum constant / static final String. */
    private final Map<String, String> nameConstants = new LinkedHashMap<>();
    /** service-root module name (before workload alignment) -> node. */
    private final Map<String, String> moduleNameNodes = new LinkedHashMap<>();
    /** The source whose placeholders are being substituted, for scoped env lookups. */
    private String currentSource;
    /**
     * Compose link aliases: node → (alias → target service). {@code links: - mysql:db} lets
     * that one container reach mysql as "db", and its config then says {@code jdbc:mysql://db};
     * the alias is local to the declaring service, so it is kept per service and consulted for
     * the reading module first, then taken repo-wide only when every declaration agrees
     * (2026-10-26, mall-swarm: db→mysql, es→elasticsearch, rabbit→rabbitmq).
     */
    private final Map<String, Map<String, String>> composeAliases = new LinkedHashMap<>();
    /** Compose network aliases (B3): reader node → (alias → target), consulted only for that reader. */
    private final Map<String, Map<String, String>> networkAliases = new LinkedHashMap<>();
    /** Async rows (the kafka and rabbit sections), merged after everything else so the broker a service is configured with is known. */
    private final List<JSONObject> asyncRows = new ArrayList<>();

    private CodeGraphMerger(DependencyGraph graph, String repoName) {
        this.graph = graph;
        for (DependencyGraph.Node node : graph.getNodes()) knownNodes.add(node.id);
        this.greenfield = knownNodes.isEmpty();
        this.defaultSource = matchNode(repoShortName(repoName));
    }

    /**
     * Reads the two meta-sections before any edge is merged: the service inventory
     * (which, in greenfield, becomes the node vocabulary) and the env-var → host
     * table (which resolves indirected targets like {@code TRANSACTIONS_API_ADDR}).
     */
    private void indexMeta(JSONArray edges) {
        // Pass 0 (greenfield only): the workloads the manifests declare. These are the
        // names the cluster — and so the runtime graph — would use, so they outrank a
        // module directory's spelling: "catalog", not microservice-kubernetes-demo-catalog.
        // Grouped by manifest directory: only a directory that deploys this repo's own
        // modules is the application's deployment. A repo also ships manifests for its
        // monitoring stack (train-ticket: prometheus, grafana, jaeger, an EFK stack —
        // 60 workloads) and for optional extras (Bank of Anthos: a pgpool operator, a
        // Cloud SQL populate job); none of those is a node of the application's graph.
        // A Compose file's services are the same kind of vocabulary, for the repos whose
        // only deployment description it is (petclinic, ewolff's microservice-demo,
        // Tap-And-Eat). The manifests win when there are any: a k8s workload name is what
        // the cluster — and so the runtime graph — really uses, and mixing the two
        // spellings would split one service in two, which is the very fault this fixes.
        Map<String, Set<String>> workloadsByDir = new LinkedHashMap<>();
        Set<String> allWorkloads = new LinkedHashSet<>();
        // The k8s Services, read first: a Deployment named reviews-v1 beside a Service named
        // reviews is one version of the service "reviews" (the Istio bookinfo convention),
        // and the Service name is what every caller and the mesh use. So is a set of
        // Deployments that differ only by a trailing -v<digits>. Only that suffix is
        // stripped, and only on that evidence (2026-10-23).
        Set<String> k8sServices = new LinkedHashSet<>();
        Set<String> rawWorkloadNames = new LinkedHashSet<>();
        boolean k8sVocabulary = false; // trace only: whether the vocabulary came from manifests (rule 1) or Compose (rule 11)
        if (graph.getNamespace() != null && !graph.getNamespace().isBlank()) {
            namespaces.add(graph.getNamespace().trim().toLowerCase(Locale.ROOT));
        }
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String section = edge.optString("section", "");
            if ("k8s-namespace".equals(section)) {
                String ns = fields.optString("name", "").trim().toLowerCase(Locale.ROOT);
                if (!ns.isEmpty()) namespaces.add(ns);
            }
        }
        // Rule 54: the same Service name in two namespaces is two services (travels in
        // travel-portal, the portal; travels in travel-agency, the agency). Such a name — and
        // only such a name — is qualified name.namespace everywhere: as a node, as a reader,
        // and as a host, where a short name means the reader's own namespace (k8s DNS).
        Map<String, Set<String>> nsByName = new LinkedHashMap<>();
        Set<String> unnamespaced = new LinkedHashSet<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"k8s-service".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String name = fields.optString("name", "").trim().toLowerCase(Locale.ROOT);
            String ns = fields.optString("namespace", "").trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (ns.isEmpty()) unnamespaced.add(name);
            else nsByName.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(ns);
        }
        for (Map.Entry<String, Set<String>> e : nsByName.entrySet()) {
            if (e.getValue().size() >= 2 && !unnamespaced.contains(e.getKey())) {
                ambiguousNames.add(e.getKey());
                RuleTrace.hit(54, "qualify " + e.getKey() + " in " + e.getValue());
            }
        }
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String section = edge.optString("section", "");
            String name = clean(fields.optString("name", ""));
            if ("k8s-service".equals(section)) k8sServices.add(name);
            if ("k8s-workload".equals(section)) rawWorkloadNames.add(name);
            if ("compose-service".equals(section) && !name.isEmpty()) {
                composeServiceNames.add(name);
                // The module a Compose service is built from: the Dockerfile's directory when
                // one is named (a .NET solution builds every project from the repo root), else
                // the build context itself. The repo root names no module.
                String dockerfileDir = fields.optString("dockerfile_dir", "").replace('\\', '/');
                String context = fields.optString("context", "").replace('\\', '/');
                String built = !dockerfileDir.isBlank() ? dockerfileDir : context;
                if (!built.isBlank() && !built.equals(".")) composeBuildDirs.putIfAbsent(built, name);
                String image = fields.optString("image", "").trim();
                if (!image.isEmpty()) {
                    composeImages.computeIfAbsent(image, k -> new LinkedHashSet<>()).add(name);
                    String registry = fields.optString("image_registry", "");
                    composeImageRegistries.computeIfAbsent(registry, k -> new LinkedHashSet<>()).add(image);
                }
            }
        }
        // Several images under one registry path name the project the same way (yas-cart,
        // yas-storefront, yas-media under ghcr.io/nashtech-garage): that shared prefix is the
        // project's, not the module's, and the module is the rest — an exact equation once the
        // prefix is the one every image of that path carries (rule 44, 2026-10-27).
        for (Map.Entry<String, Set<String>> reg : composeImageRegistries.entrySet()) {
            if (reg.getKey().isEmpty() || reg.getValue().size() < 2) continue;
            String prefix = commonPrefix(reg.getValue());
            int cut = Math.max(prefix.lastIndexOf('-'), prefix.lastIndexOf('_'));
            prefix = cut >= 0 ? prefix.substring(0, cut + 1) : "";
            if (prefix.isEmpty()) continue;
            for (String image : reg.getValue()) {
                String stem = image.substring(prefix.length());
                if (stem.isEmpty()) continue;
                for (String svc : composeImages.getOrDefault(image, Set.of())) {
                    composeImages.computeIfAbsent(stem, k -> new LinkedHashSet<>()).add(svc);
                }
            }
        }
        if (greenfield) {
            // Rule 52's precedence: a rendered Helm chart is a template whose names hang on the
            // release and the values; it is the vocabulary only when the repo states its
            // deployment nowhere more concretely (no plain manifest, no Compose file). Its env
            // and routing rows are read either way (2026-10-27).
            for (String tier : List.of("k8s-workload", "compose-service", "k8s-workload:helm")) {
                String section = tier.startsWith("k8s-workload") ? "k8s-workload" : tier;
                boolean helmTier = tier.endsWith(":helm");
                for (int i = 0; i < edges.length(); i++) {
                    JSONObject edge = edges.optJSONObject(i);
                    if (edge == null || !section.equals(edge.optString("section", ""))) continue;
                    JSONObject fields = edge.optJSONObject("fields");
                    if (fields == null) continue;
                    if ("k8s-workload".equals(section) && fields.has("helm") != helmTier) continue;
                    String raw = clean(fields.optString("name", ""));
                    String name = "k8s-workload".equals(section) ? unversionedWorkload(raw, k8sServices, rawWorkloadNames) : raw;
                    String rowNs = fields.optString("namespace", "").trim().toLowerCase(Locale.ROOT);
                    if ("k8s-workload".equals(section) && ambiguousNames.contains(name) && !rowNs.isEmpty()) {
                        name = name + "." + rowNs;
                        workloadAliases.put(raw + "@" + rowNs, name);
                    }
                    // An ambiguous name with no namespace (travels-v2.yaml, applied nowhere) cannot be
                    // placed in either service: it is not a node of its own (rule 54).
                    if ("k8s-workload".equals(section) && ambiguousNames.contains(name) && rowNs.isEmpty()) {
                        RuleTrace.hit(54, "unplaceable " + raw);
                        continue;
                    }
                    if ("k8s-workload".equals(section) && !rowNs.isEmpty()) nodeNamespaces.put(name, rowNs);
                    if (!isPlausibleName(name)) continue;
                    String image = fields.optString("image", "").trim();
                    if ("k8s-workload".equals(section) && !image.isEmpty()) {
                        k8sImages.computeIfAbsent(image, k -> new LinkedHashSet<>()).add(name);
                        k8sImageRegistries.computeIfAbsent(fields.optString("image_registry", ""), k -> new LinkedHashSet<>()).add(image);
                    }
                    if (RuleTrace.hitIf(!name.equals(raw), 19, "version " + raw + "=" + name)) workloadAliases.put(raw, name);
                    allWorkloads.add(name);
                    workloadsByDir.computeIfAbsent(parentDir(edge.optString("file", "").replace('\\', '/')),
                            k -> new LinkedHashSet<>()).add(name);
                }
                if (!allWorkloads.isEmpty()) {
                    k8sVocabulary = "k8s-workload".equals(section);
                    helmVocabulary = helmTier;
                    RuleTrace.hitIf(helmTier, 52, "vocabulary " + allWorkloads.size() + " rendered workloads");
                    RuleTrace.hitIf(k8sVocabulary && !composeServiceNames.isEmpty(), 11, "k8s-over-compose " + composeServiceNames.size() + " compose names ignored");
                    break;
                }
            }
        }
        // The k8s images, with the project's registry prefix cut as for Compose (demo_travels_
        // under quay.io/kiali): an image name is the module's, the second alias source a
        // manifest gives besides the workload's own name (rule 44 extended, 2026-10-27).
        for (Map.Entry<String, Set<String>> reg : k8sImageRegistries.entrySet()) {
            if (reg.getKey().isEmpty() || reg.getValue().size() < 2) continue;
            String prefix = commonPrefix(reg.getValue());
            int cut = Math.max(prefix.lastIndexOf('-'), prefix.lastIndexOf('_'));
            prefix = cut >= 0 ? prefix.substring(0, cut + 1) : "";
            if (prefix.isEmpty()) continue;
            for (String image : new ArrayList<>(reg.getValue())) {
                String stem = image.substring(prefix.length());
                if (!stem.isEmpty()) k8sImages.computeIfAbsent(stem, k -> new LinkedHashSet<>()).addAll(k8sImages.getOrDefault(image, Set.of()));
            }
        }

        // Which ConfigMaps each workload receives, for scoping env lookups below.
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"workload-env".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String configMap = fields.optString("configmap", "");
            String workload = clean(fields.optString("workload", ""));
            if (!configMap.isBlank() && !workload.isEmpty()) {
                workloadConfigMaps.computeIfAbsent(workload, k -> new LinkedHashSet<>()).add(configMap);
            }
            String host = fields.optString("host", "");
            boolean undeclaredBare = "true".equals(fields.optString("bare", ""))
                    && composeServiceNames.stream().noneMatch(n -> n.equalsIgnoreCase(host.trim()))
                    && !rawWorkloadNames.contains(host.trim());
            if (!host.isBlank() && !workload.isEmpty() && !undeclaredBare) {
                workloadHosts.computeIfAbsent(workload, k -> new LinkedHashSet<>()).add(host.trim());
            }
        }

        // Pass 1: the service inventory, so knownNodes is complete before env-address
        // resolution below can prefer a host that is actually a known service. A module
        // directory aligns to the workload it deploys as (exact, or by -suffix) when the
        // manifests know one; otherwise its own name is the node.
        // A module whose build manifest says it is a library (a Python distribution with
        // no image) is never a node: its code runs inside the services that import it.
        Map<String, String> libraryDirs = new LinkedHashMap<>(); // dir -> packages
        Set<String> jvmLibraryDirs = new LinkedHashSet<>();        // the library dirs whose verdict came from a JVM build file (rule 41, not 21)
        Map<String, String> moduleArtifactIds = new LinkedHashMap<>(); // dir -> the module's own artifactId
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"module-deps".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String dir = fields.optString("dir", "").replace('\\', '/');
            if ("true".equals(fields.optString("library", ""))) {
                libraryDirs.put(dir, fields.optString("packages", ""));
                if ("jvm".equals(fields.optString("library_kind", ""))) jvmLibraryDirs.add(dir);
            }
            String artifact = fields.optString("artifact", "").trim();
            if (!artifact.isEmpty()) moduleArtifactIds.put(dir, artifact);
        }
        Map<String, String> moduleNodes = new LinkedHashMap<>(); // dir -> node, in inventory order
        Set<String> aligned = new LinkedHashSet<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"service-root".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String dir = fields.optString("dir", "").replace('\\', '/');
            String rawName = clean(fields.optString("name", ""));
            String name = rawName;
            if (dir.isBlank() || !isPlausibleName(name)) continue;
            // The deployment's own statement of which module it runs: a Compose service whose
            // build context (or Dockerfile) is this directory, or whose name is exactly this
            // module's Maven artifactId. That is a mapping the repo makes, not a similarity
            // guess, so it is applied without asking (yas: `backoffice: build: ./backoffice-bff`;
            // 2026-10-26). A deployment name overrules the module's own spelling and the
            // library heuristic alike.
            String deploymentName = composeBuildDirs.get(dir);
            if (deploymentName == null) {
                // A module nested inside a build context that holds no other module is what that
                // image is built from (robot-shop: `ratings: build: ratings`, the PHP module in
                // ratings/html). The deepest such context wins; two modules under one context
                // say nothing about which is the image (rule 44 extended, 2026-10-27).
                String best = null;
                for (Map.Entry<String, String> ctx : composeBuildDirs.entrySet()) {
                    String c = ctx.getKey();
                    if (c.isEmpty() || !dir.startsWith(c + "/")) continue;
                    if (best != null && best.length() >= c.length()) continue;
                    if (otherModuleUnder(c, dir, edges)) continue;
                    best = c;
                }
                if (RuleTrace.hitIf(best != null, 44, "nested " + rawName + "=" + (best == null ? "" : composeBuildDirs.get(best)))) {
                    deploymentName = composeBuildDirs.get(best);
                }
            }
            if (deploymentName == null) {
                // image: with no build: the image's name is this module's (its directory, or its
                // artifact) — exactly one Compose service runs that image (B2, 2026-10-27).
                String lastSegment = dir.contains("/") ? dir.substring(dir.lastIndexOf('/') + 1) : dir;
                for (String key : new String[]{lastSegment.toLowerCase(Locale.ROOT), rawName, moduleArtifactIds.getOrDefault(dir, "").toLowerCase(Locale.ROOT)}) {
                    Set<String> services = key.isEmpty() ? null : composeImages.get(key);
                    if (services != null && services.size() == 1) {
                        deploymentName = services.iterator().next();
                        RuleTrace.hit(44, "image " + rawName + "=" + deploymentName);
                        break;
                    }
                }
            }
            if (deploymentName == null) {
                String artifact = moduleArtifactIds.get(dir);
                if (artifact != null && !artifact.equalsIgnoreCase(rawName)) {
                    for (String composeName : composeServiceNames) {
                        if (RuleTrace.hitIf(composeName.equalsIgnoreCase(artifact), 44, "artifact " + rawName + "=" + composeName)) deploymentName = composeName;
                    }
                }
            }
            boolean deployed = false;
            // With k8s manifests present the Compose name only helps if it resolves to a
            // workload (exactly, or as the unique `-suffix` of one: TeaStore's compose
            // `webui` is the manifests' teastore-webui); otherwise the k8s spelling wins,
            // as rule 11 says, and the module aligns on its own name below.
            String deploymentWorkload = deploymentName == null ? null
                    : (allWorkloads.isEmpty() ? deploymentName : workloadForDeploymentName(deploymentName, allWorkloads));
            // …but when the manifests do not deploy this module at all (no workload for the
            // Compose name, none for the module's own name), there is no k8s spelling to lose
            // to: the Compose name is the only deployment name the repo gives it (robot-shop's
            // K8s/ holds a load generator only; ratings/html is the Compose service ratings).
            if (deploymentName != null && deploymentWorkload == null && greenfield
                    && matchWorkloadForModule(rawName, allWorkloads) == null) {
                RuleTrace.hit(44, "compose-only " + rawName + "=" + deploymentName);
                deploymentWorkload = deploymentName;
            }
            RuleTrace.hitIf(deploymentName != null && deploymentWorkload == null, 44, "no-workload " + rawName + "=" + deploymentName);
            if (deploymentWorkload != null && greenfield) {
                RuleTrace.hitIf(!deploymentWorkload.equals(rawName), 44, "map " + rawName + "=" + deploymentWorkload);
                name = deploymentWorkload;
                if (allWorkloads.contains(deploymentWorkload)) aligned.add(deploymentWorkload);
                deployed = true;
            } else if (greenfield && !allWorkloads.isEmpty()) {
                String workload = matchWorkloadForModule(name, allWorkloads);
                if (RuleTrace.hitIf(workload != null && !workload.equals(name), k8sVocabulary ? 1 : 11, "align " + name + "=" + workload) || workload != null) {
                    name = workload;
                    aligned.add(workload);
                    deployed = true;
                } else if (!k8sImages.isEmpty()) {
                    // No workload is named like the module: the image it is built into may be.
                    // One workload runs it → that workload; several (travels' portal image runs as
                    // voyages, viaggi and travels) → the module's code belongs to each of them.
                    Set<String> runs = imageWorkloads(dir, rawName, moduleArtifactIds.get(dir), allWorkloads);
                    if (runs.size() == 1) {
                        name = runs.iterator().next();
                        RuleTrace.hit(44, "k8s-image " + rawName + "=" + name);
                        aligned.add(name);
                        deployed = true;
                    } else if (runs.size() > 1) {
                        RuleTrace.hit(44, "k8s-image-fanout " + rawName + "=" + runs);
                        moduleFanout.put(name, new ArrayList<>(runs));
                        aligned.addAll(runs);
                    }
                }
            }
            moduleNodes.put(dir, name);
            moduleNameNodes.put(rawName, name);
            // A library the deployment names anyway is a service after all: the build
            // manifest's verdict yields to the manifest's.
            RuleTrace.hitIf(libraryDirs.containsKey(dir) && deployed, jvmLibraryDirs.contains(dir) ? 41 : 21, "deployed-overrides " + name);
            if (libraryDirs.containsKey(dir) && !deployed) {
                RuleTrace.hit(jvmLibraryDirs.contains(dir) ? 41 : 21, "lib " + name);
                if (jvmLibraryDirs.contains(dir)) jvmLibraryModules.add(name);
                libraryModules.add(name);
                Set<String> packages = new LinkedHashSet<>();
                for (String p : libraryDirs.get(dir).split(",")) if (!p.isBlank()) packages.add(p.trim());
                libraryPackages.put(dir, packages);
            }
        }
        // The application's manifests: the directories in which some module of this repo
        // is deployed. Their workloads (a database StatefulSet, the registry, the broker
        // of a variant) are the vocabulary; the other directories' are not.
        for (Map.Entry<String, Set<String>> e : workloadsByDir.entrySet()) {
            boolean deploysOwnModule = false;
            for (String w : e.getValue()) if (aligned.contains(w)) deploysOwnModule = true;
            if (deploysOwnModule) workloads.addAll(e.getValue());
            else for (String w : e.getValue()) RuleTrace.hit(10, "drop " + w + " (" + e.getKey() + ")");
        }
        if (greenfield) knownNodes.addAll(workloads);
        for (Map.Entry<String, String> e : moduleNodes.entrySet()) {
            String name = e.getValue();
            serviceDirs.put(e.getKey(), name);
            if (greenfield && !libraryModules.contains(name)) {
                knownNodes.add(name);
                // A module the manifests never deploy is a library or a tool; with no
                // manifests at all, every module is presumed deployable.
                if (workloads.isEmpty() || workloads.contains(name)) deployableModules.add(name);
                else RuleTrace.hit(k8sVocabulary ? 1 : 11, "not-preadded " + name);
            }
        }

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
            String host = clean(fields.optString("host", ""));
            if (name.isBlank() || host.isEmpty()) continue;
            String hostNs = fields.optString("host_ns", "").trim();
            if (!hostNs.isEmpty() && ambiguousNames.contains(host) && namespaces.contains(hostNs)) host = host + "." + hostNs;

            // A rendered chart that is not the vocabulary only wires names the concrete
            // deployment has: its own database (yas's k8s `postgresql` beside Compose's
            // `postgres`) is another variant's node, not this graph's (rule 52).
            if (RuleTrace.hitIf(fields.has("helm") && !helmVocabulary && matchNode(host) == null, 52, "variant-host " + name + "=" + host)) continue;
            String current = envAddress.get(name);
            if (current == null
                    || (matchNode(host) != null && matchNode(current) == null)) {
                envAddress.put(name, host);
            }
            String configMap = fields.optString("configmap", "");
            if (!configMap.isBlank()) configMapHosts.computeIfAbsent(configMap, k -> new LinkedHashSet<>()).add(host);

            String scope = null;
            String workload = fields.optString("workload", "");
            if (!configMap.isBlank()) {
                scope = "configmap:" + configMap;
            } else if ("dockerfile".equals(workload)) {
                RuleTrace.hit(18, "dockerfile-env " + name + "=" + host);
                scope = "module:" + edge.optString("file", "").replace('\\', '/');
            } else if (!workload.isBlank()) {
                scope = "workload:" + clean(workload);
            }
            String kind = kindField(fields);
            envValues.computeIfAbsent(name, k -> new ArrayList<>()).add(new EnvValue(host, scope, kind, "true".equals(fields.optString("list", ""))));
        }

        // The build manifests: what each module is built with, and who imports a library.
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String section = edge.optString("section", "");
            if ("module-deps".equals(section)) {
                String node = moduleNodes.get(fields.optString("dir", "").replace('\\', '/'));
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

        // In greenfield the inventory IS the graph's node set: add every service so
        // even a service nobody calls still appears (an accurate, if isolated, node).
        // The manifests' workloads are part of that inventory — a database StatefulSet
        // has no source directory and would otherwise only exist once something
        // resolves to it.
        // A module the manifests do not deploy (TeaStore's registryclient library, its
        // Docker base image) is not pre-added: it still attributes sources, and appears
        // only if an edge actually touches it.
        if (greenfield) {
            for (String name : workloads) RuleTrace.hit(k8sVocabulary ? 1 : 11, "node " + name);
            for (String name : workloads) graph.addNode(name, DependencyGraph.classifyKind(name));
            for (String name : deployableModules) {
                graph.addNode(name, DependencyGraph.classifyKind(name));
            }
        }

        // Build the path -> service lookup from the final node vocabulary, so a
        // path-encoded callee (see serviceKeys) can be resolved later.
        for (String node : knownNodes) registerServiceKeys(node);

        // Compose link aliases, keyed by the node the declaring service is. A `hostname:`
        // alias is declared by the target itself and holds for every reader: keyed "*".
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"compose-alias".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String rawService = fields.optString("service", "");
            String service = "*".equals(rawService) ? "*" : resolveComposeService(rawService);
            String alias = clean(fields.optString("alias", ""));
            String target = clean(fields.optString("target", ""));
            if (service == null || alias.isEmpty() || target.isEmpty()) continue;
            // A network alias holds on its network only: it is never taken repo-wide.
            if (fields.has("network")) {
                networkAliases.computeIfAbsent(service, k -> new LinkedHashMap<>()).putIfAbsent(alias, target);
                continue;
            }
            composeAliases.computeIfAbsent(service, k -> new LinkedHashMap<>()).putIfAbsent(alias, target);
        }

        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String section = edge.optString("section", "");
            if ("compose-mount".equals(section)) {
                mounts.computeIfAbsent(fields.optString("file", ""), k -> new LinkedHashSet<>()).add(fields.optString("service", ""));
            } else if ("k8s-gateway".equals(section)) {
                gatewayWorkloads.computeIfAbsent(fields.optString("name", ""), k -> new LinkedHashSet<>()).add(fields.optString("workload", ""));
            }
        }

        indexImports(edges);
        indexConfig(edges);
    }

    /** Repo file → the Compose services that mount it. */
    private final Map<String, Set<String>> mounts = new LinkedHashMap<>();
    /** Istio Gateway name → the ingress workload its selector names. */
    private final Map<String, Set<String>> gatewayWorkloads = new LinkedHashMap<>();

    /** Whether a service-root other than {@code dir} lies under the directory {@code context}. */
    private static boolean otherModuleUnder(String context, String dir, JSONArray edges) {
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"service-root".equals(edge.optString("section", ""))) continue;
            JSONObject f = edge.optJSONObject("fields");
            String d = f == null ? "" : f.optString("dir", "").replace('\\', '/');
            // The context directory's own module (its Dockerfile) is the image, not another module.
            if (!d.equals(dir) && !d.equals(context) && d.startsWith(context + "/")) return true;
        }
        return false;
    }

    /**
     * The service a Compose link alias stands for, as seen from {@code source}: the
     * declaration of the reading service itself, else the one target every declaring
     * service agrees on; null when the host is no alias or the declarations differ.
     */
    private String composeAlias(String host, String source) {
        if (host == null || (composeAliases.isEmpty() && networkAliases.isEmpty())) return null;
        String h = clean(host);
        if (h.isEmpty()) return null;
        if (source != null && networkAliases.containsKey(source)) {
            String target = networkAliases.get(source).get(h);
            if (RuleTrace.hitIf(target != null, 25, "network-resolve " + source + ":" + h + "=" + target)) return target;
        }
        if (source != null && composeAliases.containsKey(source)) {
            String target = composeAliases.get(source).get(h);
            if (RuleTrace.hitIf(target != null, 25, "resolve " + source + ":" + h + "=" + target)) return target;
        }
        String unanimous = null;
        for (Map<String, String> aliases : composeAliases.values()) {
            String target = aliases.get(h);
            if (target == null) continue;
            if (unanimous != null && !unanimous.equals(target)) return null;
            unanimous = target;
        }
        if (unanimous != null) RuleTrace.hit(composeAliases.getOrDefault("*", Map.of()).containsKey(h) ? 37 : 25, "resolve " + h + "=" + unanimous);
        return unanimous;
    }

    /** {@code host} with a Compose link alias resolved to the service it names, else {@code host} itself. */
    private String unalias(String host, String source) {
        String target = composeAlias(host, source);
        return target == null ? host : target;
    }

    /**
     * Hosts under a TLD reserved for documentation and testing (RFC 2606 / 6761: .test,
     * .example, .invalid, .localhost, example.com/net/org) or for mDNS (RFC 6762: .local)
     * never name a deployment: {@code https://vcr.local/} is a test cassette's placeholder,
     * {@code api.example.com} a sample. Not a dependency, not residue (2026-10-26).
     */
    static boolean isReservedHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".svc.cluster.local") || h.endsWith(".cluster.local")) return false; // in-cluster DNS, handled by clean()
        for (String tld : new String[]{".local", ".test", ".example", ".invalid", ".localhost"}) {
            if (h.endsWith(tld)) return true;
        }
        for (String domain : new String[]{"example.com", "example.net", "example.org"}) {
            if (h.equals(domain) || h.endsWith("." + domain)) return true;
        }
        return false;
    }

    /** The node kind a connection scheme states: {@code jdbc:} / {@code mongodb://} are a database whatever the host is called. */
    static String kindFromScheme(String rawTarget) {
        if (rawTarget == null) return null;
        String s = rawTarget.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("jdbc:") || s.startsWith("r2dbc:")) return DependencyGraph.KIND_DB;
        int i = s.indexOf("://");
        if (i <= 0) return null;
        switch (s.substring(0, i)) {
            case "mysql", "postgres", "postgresql", "mongodb", "mongodb+srv", "redis", "rediss", "cassandra",
                 "mariadb", "sqlserver", "oracle", "couchdb", "neo4j", "bolt", "memcached" -> {
                return DependencyGraph.KIND_DB;
            }
            case "amqp", "amqps", "kafka", "nats", "mqtt", "mqtts", "stomp" -> {
                return DependencyGraph.KIND_QUEUE;
            }
            default -> {
                return null;
            }
        }
    }

    private static final java.util.regex.Pattern VERSION_SUFFIX = java.util.regex.Pattern.compile("^(.+)-v\\d+$");

    /**
     * The service a versioned Deployment is a version of: {@code reviews-v1} → {@code reviews}
     * when a k8s Service named {@code reviews} is declared, or when another workload differs
     * from it only by the version. Any other name is returned as is — a trailing {@code -v2}
     * alone is not evidence, and nothing else is ever stripped.
     */
    static String unversionedWorkload(String raw, Set<String> k8sServices, Set<String> allWorkloads) {
        java.util.regex.Matcher m = VERSION_SUFFIX.matcher(raw);
        if (!m.matches()) return raw;
        String base = m.group(1);
        if (k8sServices.contains(base)) return base;
        for (String other : allWorkloads) {
            if (other.equals(raw)) continue;
            java.util.regex.Matcher o = VERSION_SUFFIX.matcher(other);
            if (o.matches() && o.group(1).equals(base)) return base;
        }
        return raw;
    }

    /**
     * Who imports a library's modules. A Python distribution with no image of its own
     * ({@code setup.py}, no Dockerfile) runs inside the services that import it, so a call
     * in {@code pkg/sub.py} belongs to every module whose code says {@code from pkg.sub
     * import …} (or {@code import pkg.sub}) — and to none if nobody does. The library
     * itself is never a node (2026-10-23).
     */
    private void indexImports(JSONArray edges) {
        if (libraryPackages.isEmpty()) return;
        Set<String> packages = new LinkedHashSet<>();
        for (Set<String> p : libraryPackages.values()) packages.addAll(p);
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
            String importer = resolveSource(file);
            if (importer == null || libraryModules.contains(importer)) continue;
            importers.computeIfAbsent(module, k -> new LinkedHashSet<>()).add(importer);
            if (!name.isEmpty()) importers.computeIfAbsent(module + "." + name, k -> new LinkedHashSet<>()).add(importer);
        }
    }

    /** Library file -> the dotted modules it imports from its own library (for transitive attribution). */
    private final Map<String, Set<String>> libraryImports = new LinkedHashMap<>();

    /** The dotted module a library file is ({@code lib/pkg/sub.py} → {@code pkg.sub}), or null outside a library. */
    private String moduleOfLibraryFile(String file) {
        String dir = libraryDirOf(file);
        if (dir == null) return null;
        String rel = file.replace('\\', '/').substring(dir.length() + 1);
        if (!rel.endsWith(".py")) return null;
        rel = rel.substring(0, rel.length() - 3);
        if (rel.endsWith("/__init__")) rel = rel.substring(0, rel.length() - "/__init__".length());
        return rel.replace('/', '.');
    }

    /** The library directory a file sits in, or null when the file is not library code. */
    private String libraryDirOf(String file) {
        if (libraryPackages.isEmpty() || file == null) return null;
        String path = file.replace('\\', '/');
        for (String dir : libraryPackages.keySet()) {
            if (path.startsWith(dir + "/")) return dir;
        }
        return null;
    }

    /** The services whose code imports the dotted module a library file is ({@code pkg/sub.py} → {@code pkg.sub}). */
    private Set<String> importersOf(String file) {
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

    /**
     * The host an env var resolves to for a read in {@code source}: the value declared in
     * that module's own scope — its workload's env block, a ConfigMap its workload receives,
     * its Dockerfile — and only when no scoped value exists, the repo-wide table, which is
     * trusted only when every declaration agrees (or exactly one names a known service: a
     * monolith variant's collapsed addresses lose to the microservice wiring). Several
     * different scoped values are ambiguous and resolve to nothing rather than to a guess.
     */
    private String resolveEnv(String name, String source) {
        EnvValue v = resolveEnvValue(name, source);
        return v == null ? null : v.host();
    }

    /**
     * Rule 55: every host of a list-valued variable when the reader's own scope declares the
     * variable as one list (travels' PORTAL_SERVICES); otherwise the single value of
     * {@link #resolveEnvValue}, or nothing.
     */
    private List<EnvValue> resolveEnvValues(String name, String source) {
        List<EnvValue> values = envValues.get(name);
        if (values != null && source != null) {
            List<EnvValue> scoped = new ArrayList<>();
            for (EnvValue v : values) if (v.scope() != null && scopeIs(v.scope(), source)) scoped.add(v);
            if (scoped.size() > 1 && scoped.stream().allMatch(EnvValue::listed)
                    && scoped.stream().map(EnvValue::scope).distinct().count() == 1) {
                RuleTrace.hit(55, "read-list " + name + " @" + source + " " + scoped.size());
                return scoped;
            }
        }
        EnvValue one = resolveEnvValue(name, source);
        return one == null ? List.of() : List.of(one);
    }

    /** As {@link #resolveEnv}, with the kind the declaring value's shape stated (or null). */
    private EnvValue resolveEnvValue(String name, String source) {
        List<EnvValue> values = envValues.get(name);
        if (values == null) {
            for (Map.Entry<String, List<EnvValue>> e : envValues.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    values = e.getValue();
                    break;
                }
            }
        }
        if (values == null || values.isEmpty()) return null;

        Set<String> scoped = new LinkedHashSet<>();
        Set<String> all = new LinkedHashSet<>();
        for (EnvValue v : values) {
            all.add(v.host());
            if (source != null && v.scope() != null && scopeIs(v.scope(), source)) scoped.add(v.host());
        }
        String pick = uniqueHost(scoped);
        if (RuleTrace.hitIf(pick == null && !scoped.isEmpty(), 20, "ambiguous " + name + " @" + source)) return null;
        RuleTrace.hitIf(pick != null, 20, "scoped " + name + "=" + pick + " @" + source);
        if (pick == null) pick = uniqueHost(all);
        RuleTrace.hitIf(scoped.isEmpty() && all.size() > 1, 20, (pick == null ? "global-ambiguous " : "global ") + name + " @" + source);
        if (pick == null) return null;
        for (EnvValue v : values) {
            if (v.host().equals(pick) && v.kind() != null) return v;
        }
        return new EnvValue(pick, null, null);
    }

    /** The {@code kind} a ledger row states for its host ({@code db} / {@code queue}), as a graph kind, or null. */
    private static String kindField(JSONObject fields) {
        String kind = fields.optString("kind", "");
        if ("db".equals(kind)) return DependencyGraph.KIND_DB;
        if ("queue".equals(kind)) return DependencyGraph.KIND_QUEUE;
        return null;
    }

    /** One host, or the one host among several that is a known node; null when ambiguous. */
    private String uniqueHost(Set<String> hosts) {
        if (hosts.isEmpty()) return null;
        if (hosts.size() == 1) return hosts.iterator().next();
        String known = null;
        for (String h : hosts) {
            if (matchNode(h) != null) {
                if (known != null) return null;
                known = h;
            }
        }
        return known;
    }

    /** Whether a declared env value's scope is the module {@code source}. */
    private boolean scopeIs(String scope, String source) {
        int colon = scope.indexOf(':');
        String kind = scope.substring(0, colon);
        String id = scope.substring(colon + 1);
        switch (kind) {
            case "workload" -> {
                String node = workloadAliases.getOrDefault(id, id);
                return source.equals(node) || source.equals(matchNodeLoose(node));
            }
            case "configmap" -> {
                for (Map.Entry<String, Set<String>> e : workloadConfigMaps.entrySet()) {
                    if (!e.getValue().contains(id)) continue;
                    String node = workloadAliases.getOrDefault(e.getKey(), e.getKey());
                    if (source.equals(node) || source.equals(matchNodeLoose(node))) return true;
                }
                return false;
            }
            case "module" -> {
                return source.equals(resolveSource(id));
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * A module directory's workload: the same name, or a workload the directory's
     * name ends with ({@code microservice-kubernetes-demo-catalog} → {@code catalog},
     * {@code tools-descartes-teastore-webui} → {@code teastore-webui}). The longest
     * workload wins so {@code ts-station-food-service} is not taken for {@code food-service}.
     */
    private static String matchWorkloadForModule(String module, Set<String> workloads) {
        String best = null;
        for (String w : workloads) {
            if (w.equalsIgnoreCase(module) || hyphenated(w).equals(hyphenated(module))) return w;
            if (w.length() >= 3 && (module.endsWith("-" + w) || module.endsWith("_" + w))
                    && (best == null || w.length() > best.length())) {
                best = w;
            }
        }
        return best;
    }

    /**
     * The workload a Compose service name stands for when manifests exist: the same name
     * (or the same name in module spelling), else the one workload that ends with
     * {@code -<name>}; null when none or several do.
     */
    private static String workloadForDeploymentName(String composeName, Set<String> workloads) {
        for (String w : workloads) {
            if (w.equalsIgnoreCase(composeName) || hyphenated(w).equals(hyphenated(composeName))) return w;
        }
        String unique = null;
        for (String w : workloads) {
            if (w.length() > composeName.length() + 1 && w.toLowerCase(Locale.ROOT).endsWith("-" + composeName.toLowerCase(Locale.ROOT))) {
                if (unique != null) return null;
                unique = w;
            }
        }
        return unique;
    }

    /**
     * A name with every run of non-alphanumerics as one hyphen: the module-directory
     * spelling (rule 2: {@code Basket.API} → {@code basket-api}), so a Compose service
     * {@code basket.api} and the directory it is built from compare equal — the same
     * letters at the same word boundaries, not a similarity guess (2026-10-26).
     */
    private static String hyphenated(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
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
    private void indexConfig(JSONArray edges) {
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
            if (!"config".equals(section)) continue;

            String key = fields.optString("key", "").toLowerCase(Locale.ROOT);
            String value = fields.optString("value", "");
            if (!key.isEmpty() && !value.isBlank() && !value.contains("${")
                    && (looksLikeUrl(value) || isPlausibleName(clean(value)))) {
                properties.putIfAbsent(key, value.trim());
            }
            if (key.startsWith("spring.cloud.config.uri") || key.startsWith("spring.cloud.config.import")
                    || (key.startsWith("spring.config.import") && value.contains("configserver"))) {
                String client = resolveSource(file);
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
                    String owner = resolveSource(file);
                    if (owner != null) portOwners.computeIfAbsent(port, k -> new LinkedHashSet<>()).add(owner);
                }
            }

            // A config FILE named after a known service configures that service. Only a
            // config file: a code file also yields config rows (getenv, @Value), and
            // CatalogClient.java is not catalog's configuration.
            String stem = isConfigFile(file) ? fileStem(file) : null;
            if (stem != null && !stem.startsWith("application") && !stem.startsWith("bootstrap")) {
                String node = matchNode(stem);
                for (String s = stem; node == null && s.contains("-"); ) {
                    s = s.substring(0, s.lastIndexOf('-'));
                    node = matchNode(s); // account-service-dev → account-service; other-prod → other
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

    private static boolean isConfigFile(String file) {
        String f = file == null ? "" : file.toLowerCase(Locale.ROOT);
        return f.endsWith(".yml") || f.endsWith(".yaml") || f.endsWith(".properties");
    }

    /** {@code config/shared/account-service.yml} → {@code account-service}; null when no name. */
    private static String fileStem(String file) {
        if (file == null || file.isBlank()) return null;
        String base = file.substring(file.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        String stem = (dot > 0 ? base.substring(0, dot) : base).toLowerCase(Locale.ROOT);
        return stem.isEmpty() ? null : stem;
    }

    private static String parentDir(String file) {
        int slash = file.lastIndexOf('/');
        return slash < 0 ? "" : file.substring(0, slash);
    }

    /** Registers the letters-only key variants of a service node for path resolution. */
    private void registerServiceKeys(String node) {
        String base = node.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (base.length() < 3) return;
        serviceKeys.putIfAbsent(base, node);                                  // tsorderservice
        String noPrefix = base.startsWith("ts") ? base.substring(2) : base;   // orderservice
        if (noPrefix.length() >= 3) serviceKeys.putIfAbsent(noPrefix, node);
        for (String b : new String[]{base, noPrefix}) {
            if (b.endsWith("service") && b.length() > 9) {                     // order / tsorder (len>9 avoids bare "service")
                serviceKeys.putIfAbsent(b.substring(0, b.length() - 7), node);
            }
        }
    }

    /**
     * The service a URL path names by convention, or null. Scans path segments in
     * order (the service token is conventionally first, e.g. {@code
     * /api/v1/orderservice/order}) and returns the first that matches a known
     * service key. Used only as a fallback when the host cannot be resolved.
     */
    private String resolveServiceFromPath(String path) {
        if (path == null || path.isEmpty() || serviceKeys.isEmpty()) return null;
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            String key = seg.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (key.length() < 3) continue;
            String node = serviceKeys.get(key);
            if (node != null) return node;
        }
        return null;
    }

    /**
     * First pass: which services have persistence code. A persistence-section edge is
     * a marker (@Entity/@Table/@Repository in Java; a mapped model / __tablename__ /
     * models.Model in Python; etc.) found in a service's source, so the service that
     * owns the file "really uses" a database — enough to promote its datasource-declared
     * db edge above a bare declaration. Language-neutral: see {@link #PERSISTENCE_SECTIONS}.
     */
    private void collectPersistenceServices(JSONArray edges) {
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !PERSISTENCE_SECTIONS.contains(edge.optString("section", ""))) continue;
            String service = resolveSource(edge.optString("file", ""));
            if (service != null) persistenceServices.add(service);
        }
    }

    /**
     * @param graph        the runtime graph to enrich (mutated in place)
     * @param codeEdgesJson STAGE_CODE_EDGES, i.e. {@code {repo, failed, edges:[...]}}
     * @param repoName     the analysed repo ("owner/repo"), a fallback source hint
     * @return the code edges that could not be resolved deterministically
     */
    public static List<Unresolved> merge(DependencyGraph graph, String codeEdgesJson, String repoName) {
        List<Unresolved> unresolved = new ArrayList<>();
        if (graph == null || codeEdgesJson == null || codeEdgesJson.isBlank()) return unresolved;

        JSONArray edges;
        try {
            JSONObject root = new JSONObject(codeEdgesJson);
            if (root.optBoolean("failed", false)) return unresolved;
            edges = root.optJSONArray("edges");
            if (edges == null) return unresolved;
        } catch (Exception e) {
            return unresolved;
        }

        CodeGraphMerger merger = new CodeGraphMerger(graph, repoName);
        merger.indexMeta(edges);
        merger.learnModulePrefix(edges);
        merger.collectPersistenceServices(edges);
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge != null) merger.mergeOne(edge, unresolved);
        }
        merger.mergeWorkloadWiring(edges);
        merger.mergeImplicitRegistry(edges);
        merger.mergeAsync(unresolved);
        merger.retypeEdgesByTargetKind();
        return unresolved;
    }

    /**
     * Messaging code → the broker. A {@code @RabbitListener(queues = "mall.order.cancel")},
     * a {@code @KafkaListener(topics = …)} or a {@code kafkaTemplate.send(topic, …)} names a
     * destination, not a host; the host is the broker the service is configured with
     * ({@code spring.rabbitmq.host}, {@code KAFKA_ADDR}, a Compose link). So the edge is
     * {@code service → broker}, for consumer and producer alike — the convention every
     * reference edge set follows — and the destination name is never a node. The broker is
     * the queue-kind node of the right family this service already connects to, else the one
     * such node the deployment has; with none, or several, the row is residue (2026-10-26:
     * mall-swarm drew {@code mall.order.cancel → mall-portal}).
     */
    private void mergeAsync(List<Unresolved> unresolved) {
        for (JSONObject edge : asyncRows) {
            String section = edge.optString("section", "");
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) fields = new JSONObject();
            String file = edge.optString("file", "");
            int line = edge.optInt("line", 0);
            String source = edge.optString("resolved_source", "");
            if (source.isBlank()) source = null;
            String family = section.startsWith("kafka") ? "kafka" : "rabbit";
            String broker = source == null ? null : brokerFor(source, family);
            if (broker == null) {
                RuleTrace.hit(30, "residue " + source + " " + section + " " + brokerTarget(section, fields));
                unresolved.add(new Unresolved(section, source, brokerTarget(section, fields), file, line));
                continue;
            }
            RuleTrace.hit(30, "edge " + source + "->" + broker);
            graph.addNode(broker, DependencyGraph.KIND_QUEUE);
            addCodeEdge(source, broker, "async", file, line);
        }
    }

    private String brokerFor(String source, String family) {
        for (DependencyGraph.Edge e : graph.getEdges()) {
            if (!e.source.equals(source)) continue;
            DependencyGraph.Node n = graph.findNode(e.target);
            if (n != null && DependencyGraph.KIND_QUEUE.equals(n.kind) && brokerFamily(n.id, family)) return n.id;
        }
        String unique = null;
        for (DependencyGraph.Node n : graph.getNodes()) {
            if (!DependencyGraph.KIND_QUEUE.equals(n.kind) || !brokerFamily(n.id, family)) continue;
            if (unique != null) return null;
            unique = n.id;
        }
        if (unique == null) {
            for (String node : knownNodes) {
                if (!DependencyGraph.KIND_QUEUE.equals(DependencyGraph.classifyKind(node)) || !brokerFamily(node, family)) continue;
                if (unique != null) return null;
                unique = node;
            }
        }
        return unique;
    }

    private static boolean brokerFamily(String node, String family) {
        String n = node.toLowerCase(Locale.ROOT);
        return "kafka".equals(family) ? n.contains("kafka") : (n.contains("rabbit") || n.contains("amqp"));
    }

    /** Artifact names (lower-cased) that are a Eureka server / client starter. */
    private static boolean isEurekaServerArtifact(String a) {
        return a.contains("eureka") && a.contains("server");
    }

    private static boolean isEurekaClientArtifact(String a) {
        return a.contains("eureka") && !a.contains("server");
    }

    /**
     * Spring Cloud's implicit registry default. A module built with the Eureka client
     * starter registers at {@code eureka.client.serviceUrl.defaultZone}; when no config
     * file of the module (its own, its file in a config repository, or a shared file it
     * receives) sets that key, the client still registers — at the framework's default,
     * which in every deployment of these systems is the one Eureka server the repo ships.
     * The edge is drawn only when exactly one module declares the server starter, and
     * graded {@code inferred}: the manifest states the client, nothing states the host
     * (2026-10-23, ecommerce: nine clients, no eureka.client.* anywhere).
     */
    private void mergeImplicitRegistry(JSONArray edges) {
        String server = null;
        for (Map.Entry<String, Set<String>> e : moduleArtifacts.entrySet()) {
            for (String a : e.getValue()) {
                if (isEurekaServerArtifact(a)) {
                    if (server != null && !server.equals(e.getKey())) return; // several servers: ambiguous
                    server = e.getKey();
                }
            }
        }
        // Only between modules the deployment vocabulary knows: a registry module the
        // manifests do not name would become a second spelling of the real one.
        if (server == null || !deployableModules.contains(server)) return;

        Set<String> zoneDeclared = new LinkedHashSet<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"config".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String key = fields.optString("key", "").toLowerCase(Locale.ROOT).replace("-", "");
            if (!key.startsWith("eureka.client.serviceurl")) continue;
            String file = edge.optString("file", "").replace('\\', '/');
            if (sharedConfigFiles.contains(file)) {
                zoneDeclared.addAll(configClients);
                continue;
            }
            String owner = configFileService.get(file);
            if (owner == null) owner = resolveSource(file);
            if (owner != null) zoneDeclared.add(owner);
        }
        for (Map.Entry<String, Set<String>> e : moduleArtifacts.entrySet()) {
            String client = e.getKey();
            if (client.equals(server) || zoneDeclared.contains(client)) continue;
            if (!deployableModules.contains(client)) continue;
            boolean isClient = false;
            for (String a : e.getValue()) if (isEurekaClientArtifact(a)) isClient = true;
            if (!isClient) continue;
            String provenance = moduleManifests.getOrDefault(client, client);
            RuleTrace.hit(16, "edge " + client + "->" + server);
            graph.addNode(server, DependencyGraph.classifyKind(server));
            addCodeEdge(client, server, "sync-http", provenance, -1, DependencyGraph.CONF_INFERRED);
        }
    }

    /**
     * Config-key namespaces whose values are consumed only by a client library: the
     * auto-configuration that binds {@code spring.rabbitmq.*} ships with the AMQP starter,
     * {@code security.oauth2.resource.*} with an OAuth2 artifact, and so on. A shared config
     * file hands these keys to every config client, but a client built without the library
     * never reads them; piggymetrics's registry, gateway and monitoring got RabbitMQ and
     * auth-service edges that way (2026-10-23). Each entry: key prefix → artifact regex.
     */
    private static final String[][] KEY_LIBRARIES = {
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
    private boolean consumesKey(String client, String key) {
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
     * ({@code *_API_ADDR}) is injected into workloads that never call most of them, so
     * service-kind hosts stay with the code layer, which sees the actual reads. With
     * persistence code the edge is {@code documented}; without it, {@code inferred} — a
     * datasource a service is handed but never touches is a declaration, not a use.
     */
    private void mergeWorkloadWiring(JSONArray edges) {
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"workload-env".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String rowNs = fields.optString("namespace", "").trim().toLowerCase(Locale.ROOT);
            readerNamespace = rowNs.isEmpty() ? null : rowNs;
            String rawWorkload = clean(fields.optString("workload", ""));
            String plainWorkload = cleanHost(fields.optString("workload", ""), namespaces);
            if (readerNamespace != null && workloadAliases.containsKey(plainWorkload + "@" + readerNamespace)) {
                rawWorkload = workloadAliases.get(plainWorkload + "@" + readerNamespace);
            }
            String source = matchNodeLoose(workloadAliases.getOrDefault(rawWorkload, rawWorkload));
            // A Compose service the vocabulary does not know (the manifests are a stale
            // partial set and name it differently, or not at all) is still a service the
            // deployment declares — the same resolution depends_on uses for its endpoints
            // (yas: identity, which yaslocal.yaml calls keycloak; 2026-10-26).
            if (source == null && isComposeFile(edge.optString("file", ""))) source = resolveComposeService(rawWorkload);
            if (source == null) continue;

            // Where the address came from decides what a service-kind target means, so the
            // two sources are kept apart rather than merged into one set.
            Map<String, Boolean> hosts = new LinkedHashMap<>(); // host -> written on this workload
            String configMap = fields.optString("configmap", "");
            for (String h : configMapHosts.getOrDefault(configMap, Set.of())) hosts.putIfAbsent(h, false);
            String literal = fields.optString("host", "");
            String hostNs = fields.optString("host_ns", "").trim();
            if (!literal.isBlank() && !hostNs.isEmpty() && ambiguousNames.contains(literal.trim()) && namespaces.contains(hostNs)) {
                literal = literal.trim() + "." + hostNs;
            }
            // A bare word from a command-line flag is a host only when the deployment declares it (rule 53 / 45).
            if (!literal.isBlank() && "true".equals(fields.optString("bare", "")) && !declaredName(literal)) {
                RuleTrace.hit(53, "bare-undeclared " + rawWorkload + " " + literal);
                literal = "";
            }
            if (!literal.isBlank()) hosts.put(literal, true);

            String file = edge.optString("file", "");
            // What the value's shape said the target is (a connection string, an amqp:// URL):
            // outranks the host's name, which in .NET carries no hint (catalogdb, messagebroker).
            String statedKind = kindField(fields);
            for (Map.Entry<String, Boolean> entry : hosts.entrySet()) {
                String host = unalias(entry.getKey(), source);
                if (RuleTrace.hitIf(isLoopback(host), 8, "loopback " + host) || RuleTrace.hitIf(isReservedHost(host), 33, "drop " + host)) continue;
                // A domain in the workload's own env (LLM_BASE_URL=https://local-llm.com) is an
                // external endpoint it is handed: drawn as such, dotted, never cut to a first
                // label that would read as an in-cluster service (2026-10-26).
                if (external(host)) {
                    if (!entry.getValue()) continue;
                    if (RuleTrace.hitIf(fields.has("helm") && !helmVocabulary, 52, "variant-host " + source + "->" + host)) continue;
                    RuleTrace.hit(36, "external-env " + source + "->" + host);
                    graph.addNode(host, DependencyGraph.KIND_EXTERNAL);
                    addCodeEdge(source, host, "external", file, -1, DependencyGraph.CONF_INFERRED);
                    continue;
                }
                // Loose: a manifest often injects the short service name (REGISTRY_HOST:
                // registry) while the workload is deployed as teastore-registry. Matching
                // strictly here invented a second node for the same component.
                String node = matchNodeLoose(host);
                if (node == null) {
                    if (RuleTrace.hitIf(fields.has("helm") && !helmVocabulary, 52, "variant-host " + source + "->" + host)) continue;
                    String label = cleanLabel(host);
                    if (label == null || !isPlausibleName(label)) continue;
                    node = label;
                }
                if (node.equals(source)) continue;
                String kind = statedKind != null && entry.getValue() ? statedKind : DependencyGraph.classifyKind(node);
                RuleTrace.hitIf(statedKind != null && entry.getValue() && !statedKind.equals(DependencyGraph.classifyKind(node)), 39, "kind " + node + "=" + statedKind);
                if (!DependencyGraph.KIND_DB.equals(kind) && !DependencyGraph.KIND_QUEUE.equals(kind)) {
                    // A service address written into this workload's own env is that
                    // workload saying which service it needs — the most explicit form a
                    // declaration takes in a manifest. Drawing nothing puts it level with
                    // "no provenance at all", which is what the inferred grade exists to
                    // avoid; drawing it dotted says "declared, no use observed".
                    // A shared ConfigMap is still excluded: it is handed to workloads that
                    // call none of it, and there the code layer is the only honest source.
                    if (RuleTrace.hitIf(!entry.getValue(), 14, "skip-configmap " + source + "->" + node)) continue;
                    RuleTrace.hit(14, "edge " + source + "->" + node);
                    graph.addNode(node, kind);
                    addCodeEdge(source, node, edgeType("", kind), file, -1, DependencyGraph.CONF_INFERRED);
                    continue;
                }
                graph.addNode(node, kind);
                String confidence = DependencyGraph.KIND_DB.equals(kind) && !persistenceServices.contains(source)
                        ? DependencyGraph.CONF_INFERRED
                        : DependencyGraph.CONF_DOCUMENTED;
                addCodeEdge(source, node, edgeType("", kind), file, -1, confidence);
            }
        }
        readerNamespace = null;
    }

    /**
     * Learns the common module-directory prefix from the dirs that DID resolve to a
     * workload via the {@code -suffix} rule (e.g. {@code spring-petclinic-customers-service}
     * → workload {@code customers-service} implies prefix {@code spring-petclinic}). It
     * is then stripped from a module dir that has no workload (an undeployed service),
     * so its source node reads {@code genai-service}, not {@code spring-petclinic-genai-service}.
     */
    private void learnModulePrefix(JSONArray edges) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            String seg = firstPathSegment(edge.optString("file", ""));
            if (seg == null) continue;
            String c = clean(seg);
            for (String node : knownNodes) {
                if (node.length() >= 3 && c.endsWith("-" + node)) {
                    String prefix = c.substring(0, c.length() - node.length() - 1);
                    if (!prefix.isBlank()) counts.merge(prefix, 1, Integer::sum);
                    break;
                }
            }
        }
        modulePrefix = counts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse(null);
    }

    /**
     * The services with persistence (JPA/ORM) code, resolved onto the graph
     * vocabulary. Exposed so a db edge of ANY provenance — code or doc — can be
     * promoted to "really used" when its source service is shown to actually
     * persist. This matters when the datasource is externalised (petclinic keeps it
     * in the config-server), so the db edges are doc-derived yet the persistence
     * proof lives in the service source.
     */
    public static Set<String> persistenceServices(DependencyGraph graph, String codeEdgesJson, String repoName) {
        if (graph == null || codeEdgesJson == null || codeEdgesJson.isBlank()) return Set.of();
        JSONArray edges;
        try {
            JSONObject root = new JSONObject(codeEdgesJson);
            if (root.optBoolean("failed", false)) return Set.of();
            edges = root.optJSONArray("edges");
            if (edges == null) return Set.of();
        } catch (Exception e) {
            return Set.of();
        }
        CodeGraphMerger merger = new CodeGraphMerger(graph, repoName);
        merger.indexMeta(edges);
        merger.learnModulePrefix(edges);
        merger.collectPersistenceServices(edges);
        return merger.persistenceServices;
    }

    /**
     * Promotes a declared-only ({@code inferred}) db edge to {@code documented} when
     * its source service has persistence code — the deterministic proof that the
     * database is really used, not merely declared. Applied after all merges so it
     * reaches db edges of any provenance (code or doc). Runtime-observed and
     * already-documented edges are left untouched.
     */
    public static void promoteReallyUsedDbs(DependencyGraph graph, Set<String> persistenceServices) {
        if (graph == null || persistenceServices == null || persistenceServices.isEmpty()) return;
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            if ("db".equals(edge.type) && !edge.runtimeObserved
                    && DependencyGraph.CONF_INFERRED.equals(edge.confidence)
                    && persistenceServices.contains(edge.source)) {
                edge.confidence = DependencyGraph.CONF_DOCUMENTED;
            }
        }
    }

    private void mergeOne(JSONObject edge, List<Unresolved> unresolved) {
        mergeOne(edge, unresolved, null);
    }

    /**
     * @param sourceOverride the calling service when the caller already knows it (a
     *                       shared config file fanned out to each config client); null
     *                       to attribute the edge from its file path.
     */
    private void mergeOne(JSONObject edge, List<Unresolved> unresolved, String sourceOverride) {
        readerNamespace = null;
        String section = edge.optString("section", "");
        JSONObject fields = edge.optJSONObject("fields");
        if (fields == null) fields = new JSONObject();
        String file = edge.optString("file", "");
        int line = edge.optInt("line", 0);

        // Inbound endpoints this service exposes are not an outgoing dependency;
        // persistence markers (jpa/persistence) are a SIGNAL (handled in
        // collectPersistenceServices), not an edge with a target of their own;
        // the meta-sections (service-root / env-address) are lookup tables consumed
        // in indexMeta, never edges.
        if ("http-server".equals(section) || PERSISTENCE_SECTIONS.contains(section)
                || META_SECTIONS.contains(section) || "url-constant".equals(section)) return;
        // A URL in a constant nobody references is declared, not used (see
        // TreeSitterExtractor.markUnreferencedUrlConstants): no edge, no residue.
        if ("url".equals(section) && "true".equals(fields.optString("unreferenced", ""))) return;

        // docker-compose depends_on names BOTH endpoints explicitly (source_service ->
        // target_service), so the source comes from the field, not the file's module.
        // This is what draws service -> config-server / discovery-server even when the
        // client-side URLs live in an externalised config repo the scan never sees.
        if ("compose-dependency".equals(section)) {
            String rawSource = clean(fields.optString("source_service", ""));
            String src = resolveComposeService(fields.optString("source_service", ""));
            String tgt = resolveComposeService(fields.optString("target_service", ""));
            if (src != null && tgt != null && !src.equals(tgt)) {
                // depends_on is startup order, not a call. Across ten systems and the
                // MicroDepGraph set, every depends_on edge from a service whose own
                // environment names NO address was a real dependency (90 of 90: the
                // Compose-v1 era states its wiring only this way), and every wrong one
                // came from a service whose environment states its targets by address
                // (the OpenTelemetry demo: 5 of 5, health-check ordering). So when the
                // service names addresses, a depends_on entry not backed by one of them
                // is not drawn; a backed one is documented (two declarations agree); and
                // with no addresses at all the entry is the deployment's only wiring
                // statement — drawn, graded inferred, as a declaration without a use
                // site is everywhere else in this merger (2026-10-26).
                Set<String> hosts = workloadHosts.getOrDefault(rawSource, Set.of());
                String confidence = DependencyGraph.CONF_INFERRED;
                if (!hosts.isEmpty()) {
                    boolean addressed = false;
                    for (String h : hosts) {
                        String node = matchNodeLoose(unalias(h, src));
                        if (node == null) node = cleanLabel(h);
                        if (tgt.equals(node)) {
                            addressed = true;
                            break;
                        }
                    }
                    if (RuleTrace.hitIf(!addressed, 35, "drop " + src + "->" + tgt)) return;
                    confidence = DependencyGraph.CONF_DOCUMENTED;
                }
                RuleTrace.hit(35, (hosts.isEmpty() ? "inferred " : "documented ") + src + "->" + tgt);
                // The target is a node too: Robot Shop's cart -> redis edge pointed at a
                // node that did not exist, because only the source was ever added.
                RuleTrace.hitIf(graph.findNode(tgt) == null, 7, "node " + tgt);
                graph.addNode(tgt, DependencyGraph.classifyKind(tgt));
                // The provenance names the key, so a reader can tell a startup-order
                // declaration from an address the same file hands the service.
                addCodeEdge(src, tgt, edgeType("", DependencyGraph.classifyKind(tgt)), file + "#depends_on", line, confidence);
            }
            return;
        }

        // A Feign client's contextId / qualifier / path are bean wiring; and when it
        // declares a url, its name is only a bean id (piggymetrics: name="rates-client",
        // url="${rates.url}") — the url row carries the target.
        if ("feign".equals(section)) {
            String attr = fields.optString("attr", "").toLowerCase(Locale.ROOT);
            if (RuleTrace.hitIf(FEIGN_NON_TARGET_ATTRS.contains(attr), 8, "feign-attr " + attr + " " + file + ":" + line)) return;
            if (RuleTrace.hitIf((attr.equals("name") || attr.equals("value") || attr.isEmpty())
                    && feignUrlSites.contains(file.replace('\\', '/') + ":" + line), 8, "feign-bean-id " + fields.optString("value", ""))) return;
        }

        // A shared config file of a config repository configures every client; the
        // edges it states belong to each of them, not to the server that serves it.
        if (sourceOverride == null && "config".equals(section) && sharedConfigFiles.contains(file.replace('\\', '/'))) {
            String key = fields.optString("key", "");
            for (String client : configClients) {
                if (RuleTrace.hitIf(consumesKey(client, key), 3, "fanout " + client + " " + key)) mergeOne(edge, unresolved, client);
            }
            return;
        }

        // A declaration no live code uses draws no edge (the Python analogue of the
        // unreferenced-constant rule): an env read whose variable nothing references, or a
        // call inside a function nothing ever calls, is declared, not used.
        if (("config".equals(section) || "http-client".equals(section))
                && "true".equals(fields.optString("unreferenced", ""))) return;
        // A getenv whose value a known call site consumes: the call-site row carries the
        // edge (and the provenance the reference edge sets expect — where the call is made).
        if ("config".equals(section) && "true".equals(fields.optString("superseded", ""))) return;

        String source = sourceOverride != null ? sourceOverride : resolveSource(file);
        if ("config".equals(section) && sourceOverride == null) {
            String owner = configFileService.get(file.replace('\\', '/'));
            if (owner != null) source = owner;
        }
        // A module several workloads run (one image, several Deployments): each runs this code.
        if (sourceOverride == null && source != null && moduleFanout.containsKey(source)) {
            for (String w : moduleFanout.get(source)) mergeOne(edge, unresolved, w);
            return;
        }
        // A short host in this reader's code means its own namespace (rule 54).
        readerNamespace = source == null ? null : nodeNamespaces.get(source);

        // Code in a library module runs inside the services that import it: the call is
        // theirs, attributed by the import of this very file; with no importer it is
        // nobody's, and the library is never a node.
        if (sourceOverride == null && source != null && libraryModules.contains(source)) {
            Set<String> importers = importersOf(file);
            RuleTrace.hitIf(importers.isEmpty(), jvmLibraryModules.contains(source) ? 41 : 21, (jvmLibraryModules.contains(source) ? "no-edge " : "no-importer ") + file);
            for (String importer : importers) RuleTrace.hit(21, "attribute " + file + "=" + importer);
            for (String importer : importers) mergeOne(edge, unresolved, importer);
            return;
        }

        // An enum constant / static final String whose value names a module, used as an
        // argument at a call site: TeaStore's loadBalanceRESTOperation(Service.PERSISTENCE, …)
        // dispatches to the service named "tools.descartes.teastore.persistence" through its
        // own registry. The value equals the module directory, so the callee is knowable
        // statically; only a value that is a module or workload counts, and only from a
        // deployable module (a shared client library's references are not its own calls).
        if ("name-ref".equals(section)) {
            if (source == null || !deployableModules.contains(source)) return;
            String type = fields.optString("type", "");
            String value = nameConstants.get((type.isBlank() ? "" : type + ".") + fields.optString("name", ""));
            if (value == null) return;
            String node = resolveModuleName(value);
            if (node != null && !node.equals(source)) {
                RuleTrace.hit(17, "edge " + source + "->" + node);
                addCodeEdge(source, node, edgeType(section, DependencyGraph.classifyKind(node)), file, line);
            }
            return;
        }

        // Rule 56: ingress routing — the gateway workload to each destination it routes to.
        if ("ingress-route".equals(section)) {
            Set<String> gws = gatewayWorkloads.getOrDefault(fields.optString("gateway", ""), Set.of());
            String rowNs = fields.optString("namespace", "").trim().toLowerCase(Locale.ROOT);
            readerNamespace = rowNs.isEmpty() ? null : rowNs;
            String node = matchNodeLoose(fields.optString("host", ""));
            readerNamespace = null;
            if (node == null) return;
            for (String gw : gws) {
                if (gw.isBlank() || gw.equals(node)) continue;
                RuleTrace.hit(56, "edge " + gw + "->" + node);
                graph.addNode(gw, DependencyGraph.KIND_GATEWAY);
                addCodeEdge(gw, node, "sync-http", file, line);
            }
            return;
        }

        // Rule 49: a registry name (dialer.Dial("srv-rate"), a literal reaching a dial through
        // a parameter) is resolved by Consul / etcd at run time. Nothing in the repository
        // maps it to a deployment, so it is never drawn: it is listed as residue, which is
        // where the "Resolve names" question asks the operator (2026-10-27).
        if ("registry-ref".equals(section)) {
            String name = fields.optString("name", "").trim();
            if (!name.isEmpty() && source != null) {
                RuleTrace.hit(49, "listed " + source + " => " + name);
                unresolved.add(new Unresolved(section, source, name, file, line));
            }
            return;
        }

        // A code-side config edge is a service reading an env var. When that env var
        // resolves (via the k8s ConfigMap / .env table) to a host that is a known
        // service, the read IS the dependency — this is how an env-indirected target
        // (TRANSACTIONS_API_ADDR -> ledgerwriter) becomes a concrete edge with no
        // runtime data. A bare env read that names no host is a signal, not an edge.
        if ("config".equals(section) && fields.has("env")) {
            String env = fields.optString("env", "");
            List<EnvValue> listed = env.isBlank() || source == null ? List.of() : resolveEnvValues(env, source);
            if (listed.size() > 1) {
                for (EnvValue v : listed) {
                    String h = unalias(v.host(), source);
                    if (isLoopback(h) || isReservedHost(h)) continue;
                    String node = matchNode(h);
                    if (node == null || node.equals(source)) continue;
                    String kind = v.kind() != null ? v.kind() : DependencyGraph.classifyKind(node);
                    graph.addNode(node, kind);
                    addCodeEdge(source, node, edgeType("", kind), file, line);
                }
                return;
            }
            EnvValue value = env.isBlank() ? null : resolveEnvValue(env, source);
            String host = value == null ? null : value.host();
            String statedKind = value == null ? null : value.kind();
            // A .NET IConfiguration read whose key the environment does not override falls
            // back to appsettings.json: GrpcSettings:DiscountUrl → https://localhost:5052 in
            // the checked-in file (loopback: nothing), or a real address in another repo.
            if (host == null && fields.has("dotnetkey")) {
                String prop = propertyLookup(fields.optString("dotnetkey", ""));
                if (RuleTrace.hitIf(prop != null, 40, "appsettings " + fields.optString("dotnetkey", ""))) {
                    host = stripToHost(prop);
                    if (host.isEmpty()) host = null;
                    statedKind = kindFromScheme(prop);
                    if (statedKind == null && fields.optString("dotnetkey", "").toLowerCase(Locale.ROOT).startsWith("connectionstrings:")) {
                        statedKind = DependencyGraph.KIND_DB;
                    }
                }
            }
            // The read's own default, when the environment does not set the variable: at a
            // use site (a client option {host: redisHost}, rule 47) any host-shaped default;
            // at a bare read only an absolute URL (getenv('CATALOGUE_URL') ?: 'http://catalogue:8080'),
            // the code's own statement of the address, like a ${key:default} (rule 48).
            String fallback = fields.optString("envdefault", "").trim();
            if (host == null && !fallback.isEmpty()) {
                boolean useSite = "true".equals(fields.optString("usesite", ""));
                if (useSite || looksLikeUrl(fallback)) {
                    String h = stripToHost(fallback);
                    if (h != null && !h.isEmpty()) {
                        RuleTrace.hit(48, (useSite ? "usesite " : "default ") + env + "=" + h);
                        host = h;
                        if (statedKind == null) statedKind = kindFromScheme(fallback);
                    }
                }
            }
            if (host != null && source != null) {
                if (RuleTrace.hitIf(isLoopback(host), 8, "loopback " + host) || RuleTrace.hitIf(isReservedHost(host), 33, "drop " + host)) return;
                host = unalias(host, source);
                // Only a known in-cluster node: a bare env read of a domain is not a call
                // (AICS hands every service SYSTEM_ADDRESS, its own public address, for the
                // links it puts in mail). An external host needs a call site with the URL.
                String node = matchNode(host);
                if (node != null && !node.equals(source)) {
                    String kind = statedKind != null ? statedKind : DependencyGraph.classifyKind(node);
                    RuleTrace.hitIf(statedKind != null && !statedKind.equals(DependencyGraph.classifyKind(node)), 39, "kind " + node + "=" + statedKind);
                    graph.addNode(node, kind);
                    addCodeEdge(source, node, edgeType("", kind), file, line);
                }
            }
            return;
        }

        // An HTTP call whose URL came from an env var (requests.get(f"{PREFIX}/x") with
        // PREFIX = os.getenv("X_API_PREFIX")): the call site is the use, the env table
        // (this module's scope first) or the getenv default names the host.
        if (("http-client".equals(section) || "grpc".equals(section)) && fields.has("env") && !fields.has("url")) {
            String env = fields.optString("env", "");
            String host = env.isBlank() || source == null ? null : resolveEnv(env, source);
            if (host == null) {
                String fallback = fields.optString("envdefault", "");
                if (!fallback.isBlank()) host = stripToHost(fallback);
            }
            if (host == null || host.isEmpty() || source == null) return;
            if (RuleTrace.hitIf(isLoopback(host), 8, "loopback " + host) || RuleTrace.hitIf(isReservedHost(host), 33, "drop " + host)) return;
            host = unalias(host, source);
            if (external(host)) {
                graph.addNode(host, DependencyGraph.KIND_EXTERNAL);
                addCodeEdge(source, host, "external", file, line);
                return;
            }
            String node = matchNode(host);
            if (node == null) {
                String label = cleanLabel(host);
                if (label == null || !isPlausibleName(label)) return;
                node = label;
            }
            if (node.equals(source)) return;
            String kind = DependencyGraph.classifyKind(node);
            graph.addNode(node, kind);
            addCodeEdge(source, node, edgeType(section, kind), file, line);
            return;
        }

        // A Java format string whose host placeholder is a getenv with a default:
        // String.format("http://%s/shipping/", getenv("CART_ENDPOINT", "cart")). The env
        // table (Dockerfile ENV, manifest env) fills %s, else the call's own default.
        if ("url".equals(section) && fields.has("env") && fields.optString("value", "").matches(".*%[sd].*")) {
            String env = fields.optString("env", "");
            String host = env.isBlank() || source == null ? null : resolveEnv(env, source);
            if (host == null) {
                String fallback = fields.optString("envdefault", "");
                if (!fallback.isBlank()) host = stripToHost(fallback);
            }
            if (host == null || host.isEmpty()) return;
            RuleTrace.hit(18, "format " + env + "=" + host);
            fields.put("value", fields.optString("value", "").replaceFirst("%[sd]", java.util.regex.Matcher.quoteReplacement(host)));
        }

        // @Value("${catalog.service.host:catalog}"): the property may resolve through
        // the config files, and failing that its default names the host. Only a value
        // that is an address or a known node is taken — a port or a flag is not.
        if ("config".equals(section) && fields.has("property")) {
            currentSource = source;
            String value = substitutePlaceholders(fields.optString("property", ""));
            if (value == null || source == null) return;
            String node = matchNode(value);
            RuleTrace.hitIf(node == null && looksLikeUrl(value) && isLoopback(stripToHost(value)), 8, "loopback " + stripToHost(value));
            if (node == null && looksLikeUrl(value) && !isLoopback(stripToHost(value))) {
                String host = unalias(stripToHost(value), source);
                if (RuleTrace.hitIf(isReservedHost(host), 33, "drop " + host)) return;
                if (external(host)) {
                    graph.addNode(host, DependencyGraph.KIND_EXTERNAL);
                    addCodeEdge(source, host, "external", file, line);
                    return;
                }
                node = matchNode(host);
            }
            if (node != null && !node.equals(source)) {
                addCodeEdge(source, node, edgeType("", DependencyGraph.classifyKind(node)), file, line);
            }
            return;
        }

        // --- asynchronous: the edge is service → broker, resolved last (see mergeAsync) ---
        if (section.startsWith("kafka") || section.startsWith("rabbit")) {
            if (source == null) {
                unresolved.add(new Unresolved(section, null, brokerTarget(section, fields), file, line));
                return;
            }
            JSONObject deferred = new JSONObject(edge.toString());
            deferred.put("resolved_source", source);
            asyncRows.add(deferred);
            return;
        }

        // --- synchronous: resolve the callee onto the graph vocabulary ---
        // Fallback for a call whose host is a variable but whose URL path names the
        // service by convention (/api/v1/orderservice/... -> order-service). Used only
        // when the host cannot be resolved below, so a real literal host always wins.
        String rawTarget = syncTarget(section, fields);
        // What the connection scheme says the target is: jdbc:mysql://db is a database
        // even though "db" carries no database hint (2026-10-26, mall-swarm).
        String schemeKind = kindFromScheme(rawTarget);

        // The host may be a placeholder — http://${BALANCES_API_ADDR}/balances, or a
        // Feign url="${rates.url}". The env -> host table (k8s ConfigMap / .env) or the
        // property table (application*.yml) fills it in, and a ${key:default} falls
        // back to its default: this is what draws ledgerwriter -> balancereader, and
        // statistics-service -> api.exchangeratesapi.io, with no runtime data.
        nameInferred = false;
        currentSource = source;
        String substituted = substitutePlaceholders(rawTarget);
        if (substituted != null) rawTarget = substituted;
        boolean inferredByName = nameInferred;
        if (schemeKind == null) schemeKind = kindFromScheme(rawTarget);
        // Spring's config import syntax wraps the address: [optional:]configserver:URL.
        RuleTrace.hitIf(rawTarget != null && SPRING_IMPORT_PREFIX.matcher(rawTarget.trim()).find(), 15, "configserver-prefix " + rawTarget);
        if (rawTarget != null) rawTarget = SPRING_IMPORT_PREFIX.matcher(rawTarget.trim()).replaceFirst("");

        // Fallback for a call whose host is a variable but whose URL path names the
        // service by convention (/api/v1/orderservice/… → order-service; a format string
        // "http://%s:%s/catalog/" → catalog). Used only when the host cannot be resolved
        // below, so a real literal host always wins.
        String pathNode = null;
        if (source != null) {
            pathNode = resolveServiceFromPath(fields.optString("path", ""));
            if (pathNode == null) pathNode = resolveServiceFromPath(pathOf(rawTarget));
        }

        if (rawTarget == null || source == null) {
            if (pathNode != null && !pathNode.equals(source)) {
                addCodeEdge(source, pathNode, "sync-http", file, line);
                return;
            }
            // A config key whose value names no address (a username, a profile, a
            // context path) is a signal the ledger keeps, not a dependency that failed
            // to resolve; it must not be handed to the LLM as residue.
            if (RuleTrace.hitIf(rawTarget == null && "config".equals(section), 8, "config-signal " + fields.optString("key", fields.optString("env", "")))) return;
            unresolved.add(new Unresolved(section, source, rawTarget, file, line));
            return;
        }

        // A browser call with a relative URL goes back to the page's own origin — the
        // service that serves the page — not to another service. spring-cloud-microservice's
        // AngularJS front end does $http.get('users'); read as a host, that drew
        // simple-ui -> users, a node that does not exist (2026-10-06).
        if (isRelativeBrowserCall(section, rawTarget, file)) return;

        String fullHost = stripToHost(rawTarget);
        // A documentation / test TLD is nobody's address: drawn as nothing, left as no residue.
        if (RuleTrace.hitIf(isReservedHost(fullHost), 33, "drop " + fullHost)) return;
        // A Compose link alias is what this service calls the target; the service is the node.
        String aliasTarget = composeAlias(fullHost, source);
        if (aliasTarget != null) {
            fullHost = aliasTarget;
            rawTarget = aliasTarget;
        }
        // localhost / 127.0.0.1 is the caller itself (a dev profile, a health check),
        // never another workload — piggymetrics and TeaStore both grew a "localhost" node.
        if (isLoopback(fullHost)) {
            // …unless the port says which module it is: a dev default of
            // http://localhost:9296 in every client, and one module whose server.port is
            // 9296. Read back to that module, graded inferred (the host was never named);
            // with no or several owners of the port, nothing is drawn (2026-10-23).
            String owner = portOwner(portOf(rawTarget));
            if (RuleTrace.hitIf(owner != null && !owner.equals(source), 15, "edge " + source + "->" + owner + " :" + portOf(rawTarget))) {
                graph.addNode(owner, DependencyGraph.classifyKind(owner));
                addCodeEdge(source, owner, edgeType(section, DependencyGraph.classifyKind(owner)), file, line,
                        DependencyGraph.CONF_INFERRED);
            } else {
                RuleTrace.hit(8, "loopback " + fullHost + (portOf(rawTarget) == null ? "" : ":" + portOf(rawTarget)));
            }
            return;
        }
        // A format-string host whose variable is itself named after the service:
        // http://{user}:8080/check/{id} with USER = os.getenv('USER_HOST', 'user').
        java.util.regex.Matcher formatted = FORMAT_HOST.matcher(fullHost);
        if (formatted.matches()) {
            String node = matchNode(formatted.group(1));
            if (node != null && !node.equals(source)) {
                addCodeEdge(source, node, edgeType(section, DependencyGraph.classifyKind(node)), file, line);
                return;
            }
        }
        if (fullHost.isEmpty()) {
            if (pathNode != null && !pathNode.equals(source)) {
                addCodeEdge(source, pathNode, "sync-http", file, line);
                return;
            }
            unresolved.add(new Unresolved(section, source, rawTarget, file, line));
            return;
        }

        // A real external host: keep the whole domain as the node.
        if (external(fullHost)) {
            graph.addNode(fullHost, DependencyGraph.KIND_EXTERNAL);
            addCodeEdge(source, fullHost, "external", file, line);
            return;
        }
        // A .NET connection-string key says the target is a database whatever its name.
        if (schemeKind == null && fields.optString("key", "").toLowerCase(Locale.ROOT).startsWith("connectionstrings")) {
            RuleTrace.hit(39, "connectionstrings " + fields.optString("key", ""));
            schemeKind = DependencyGraph.KIND_DB;
        }

        // In-cluster target. Prefer an existing workload; otherwise, since a
        // dependency the mesh never observed (a DB, an un-exercised service) is
        // still a real dependency, introduce the node from its own name.
        String node = matchNode(rawTarget);
        if (node == null) {
            String label = cleanLabel(fullHost);
            if (!isPlausibleName(label)) {
                if (pathNode != null && !pathNode.equals(source)) {
                    addCodeEdge(source, pathNode, "sync-http", file, line);
                    return;
                }
                unresolved.add(new Unresolved(section, source, rawTarget, file, line));
                return;
            }
            node = label;
        }
        String kind = schemeKind != null ? schemeKind : DependencyGraph.classifyKind(node);
        RuleTrace.hitIf(schemeKind != null && !schemeKind.equals(DependencyGraph.classifyKind(node)), 26, "kind " + node + "=" + schemeKind);
        graph.addNode(node, kind);
        // A db the service really uses (has persistence code) is documented; a db known
        // only from a datasource URL, with no entity/repository code, is a bare
        // declaration — kept but marked weakest so the graph does not overstate it.
        // A target found only through its variable's name is a guess of the same rank.
        String confidence = (DependencyGraph.KIND_DB.equals(kind) && !persistenceServices.contains(source))
                || inferredByName
                ? DependencyGraph.CONF_INFERRED
                : DependencyGraph.CONF_DOCUMENTED;
        addCodeEdge(source, node, edgeType(section, kind), file, line, confidence);
    }

    /** Maps a code section + resolved target kind to a graph edge type. */
    private static String edgeType(String section, String targetKind) {
        if (DependencyGraph.KIND_DB.equals(targetKind)) return "db";
        if (DependencyGraph.KIND_QUEUE.equals(targetKind)) return "async";
        return "grpc".equals(section) ? "grpc" : "sync-http";
    }

    private void addCodeEdge(String from, String to, String type, String file, int line) {
        addCodeEdge(from, to, type, file, line, DependencyGraph.CONF_DOCUMENTED);
    }

    private void addCodeEdge(String from, String to, String type, String file, int line, String confidence) {
        if (from == null || to == null || from.equals(to)) return;
        graph.addNode(from, DependencyGraph.KIND_SERVICE);
        graph.addEdge(from, to, type,
                DependencyGraph.PROV_CODE,
                confidence,
                false, 0,
                "code: " + file + (line > 0 ? ":" + line : ""));
    }

    // ---------- target extraction ----------

    /** Keys whose bare single-word value is a host only when that word is a deployed node ({@code elasticsearch.url=elasticsearch}). */
    private static final String[] ADDRESS_KEY_SUFFIXES = {".url", ".uri", "_url", "_uri", ".endpoint", "_endpoint"};

    /** The callee token for a synchronous section, or null if none is usable. */
    private String syncTarget(String section, JSONObject fields) {
        switch (section) {
            case "feign" -> {
                // @FeignClient(name="catalogue", url="...") — a bare name or a URL.
                String v = firstNonBlank(fields, "value", "name", "url", "attr");
                return v;
            }
            case "http-client" -> {
                // Only an absolute URL carries a host; a bare path targets self-ish.
                return firstNonBlank(fields, "url");
            }
            case "url" -> {
                return firstNonBlank(fields, "value");
            }
            case "grpc" -> {
                // gRPC target syntax: dns:///host:port, dns://authority/host:port, passthrough:///…
                String t = firstNonBlank(fields, "url", "value", "target", "host", "name");
                return t == null ? null : t.replaceFirst("(?i)^(dns|passthrough|xds):(//[^/]*)?/", "");
            }
            case "config" -> {
                // A config value that is a URL is a dependency target; so is a bare host
                // under a host-shaped key (spring.data.mongodb.host: account-mongodb) or a
                // registry id under a route (zuul.routes.x.serviceId: account-service).
                String v = firstNonBlank(fields, "value");
                if (v == null) return null;
                // An address list names several hosts; the first is the edge this rule
                // draws (the others are the same cluster in every system seen so far).
                if (v.contains(",") && !looksLikeUrl(v.substring(0, v.indexOf(',')).trim() + "x")) {
                    RuleTrace.hit(27, "first-of-list " + fields.optString("key", ""));
                    v = v.substring(0, v.indexOf(',')).trim();
                } else if (v.contains(",") && !v.contains("://")) {
                    RuleTrace.hit(27, "first-of-list " + fields.optString("key", ""));
                    v = v.substring(0, v.indexOf(',')).trim();
                }
                if (looksLikeUrl(v)) return v;
                // A YAML list flattens to key[0], key[1]: the suffix is the key's, not the index's.
                String key = fields.optString("key", "").toLowerCase(Locale.ROOT).replaceAll("\\[\\d+]$", "");
                if (RuleTrace.hitIf(key.equals("host") || key.equals("hostname"), 5, "key " + key + "=" + v)) return v; // a bare `host=` parameter
                for (String suffix : HOST_KEY_SUFFIXES) {
                    if (RuleTrace.hitIf(key.endsWith(suffix), LIST_KEY_SUFFIXES.contains(suffix) ? 27 : 5, (LIST_KEY_SUFFIXES.contains(suffix) ? "list " : "key ") + key + "=" + v)) return v;
                }
                // An endpoint key whose value is a bare fully-qualified host (aliyun.oss.endpoint:
                // oss-cn-shenzhen.aliyuncs.com) names an external service without a scheme; a
                // bare single word under such a key stays a signal, not a target (2026-10-26).
                if (RuleTrace.hitIf((key.endsWith(".endpoint") || key.endsWith("_endpoint")) && BARE_FQDN.matcher(v.trim()).matches(), 27, "fqdn " + key + "=" + v)) return v;
                // A .NET ConnectionStrings:X value: a key=value; string names its host, and a
                // bare host:port (ConnectionStrings:Redis=distributedcache:6379) is one too.
                if (key.startsWith("connectionstrings")) {
                    java.util.regex.Matcher cs = CONNECTION_STRING_HOST.matcher(v);
                    if (v.indexOf('=') > 0 && v.indexOf(';') > 0) return RuleTrace.hitIf(cs.find(), 32, "connstr " + key) ? cs.group(1).trim().replaceFirst("(?i)^tcp:", "") : null;
                    return RuleTrace.hitIf(v.matches("[^\\s/=;]+(:\\d{2,5})?"), 38, "connectionstrings-host " + key + "=" + v) ? v : null;
                }
                // A bare single word under a *.url / *.uri / *.endpoint key is the target only
                // when it is a node the deployment declares (elasticsearch.url=elasticsearch,
                // the Compose service elasticsearch) — any other word is a signal, not a guess
                // at a host (2026-10-26, yas).
                for (String suffix : ADDRESS_KEY_SUFFIXES) {
                    if (key.endsWith(suffix) && v.trim().matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(:\\d{2,5})?")) {
                        if (RuleTrace.hitIf(declaredName(v.trim().replaceAll(":\\d+$", "")), 45, "accept " + key + "=" + v)) return v.trim();
                        RuleTrace.hit(45, "drop " + key + "=" + v);
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** The address-list keys among {@link #HOST_KEY_SUFFIXES} (rule 27); the others are rule 5's bare-host keys. */
    private static final Set<String> LIST_KEY_SUFFIXES = Set.of(".uris", ".cluster-nodes", ".server-addr", ".addresses", ".bootstrap-servers");

    /** The host key of a {@code key=value;} connection string (Npgsql, SqlClient, ODBC), as the extractor reads it. */
    private static final java.util.regex.Pattern CONNECTION_STRING_HOST = java.util.regex.Pattern.compile(
            "(?i)(?:^|;)\\s*(?:host|server|data source|hostname|address|addr|network address)\\s*=\\s*([^;,]+)");

    /** A Python/Java format placeholder standing in for the whole host: {user}, {cart_host}. */
    private static final java.util.regex.Pattern FORMAT_HOST =
            java.util.regex.Pattern.compile("\\{([a-z][a-z0-9_]*)}");

    private static final java.util.regex.Pattern PLACEHOLDER =
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
    private String substitutePlaceholders(String rawTarget) {
        if (rawTarget == null) return null;
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
                value = nodeNamedByVariable(name.trim());
                if (RuleTrace.hitIf(value != null, 4, "by-name " + name + "=" + value)) nameInferred = true;
            }
            if (value == null) return null;
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
        }
        if (!any) return null;
        m.appendTail(out);
        return out.toString();
    }

    private String lookupPlaceholder(String name) {
        String value = envLookup(name);
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
    private boolean nameInferred;

    private static final java.util.regex.Pattern VARIABLE_HOST_SUFFIX = java.util.regex.Pattern.compile(
            "(?i)(_service)?(_host|_hostname|_addr|_address|_url|_uri|_endpoint|_server)$");

    private String nodeNamedByVariable(String name) {
        String base = VARIABLE_HOST_SUFFIX.matcher(name.trim()).replaceFirst("");
        if (base.isEmpty() || base.equals(name.trim())) return null; // no host-shaped suffix: not a host variable
        String label = base.toLowerCase(Locale.ROOT).replace('_', '-').replace('.', '-');
        String node = matchNode(label);
        if (node == null) node = serviceKeys.get(label.replaceAll("[^a-z0-9]", ""));
        return node;
    }

    private String envLookup(String name) {
        return resolveEnv(name, currentSource);
    }

    private static final java.util.regex.Pattern SPRING_IMPORT_PREFIX =
            java.util.regex.Pattern.compile("^(?i)(?:optional:)?configserver:");

    /** The explicit port of a URL-ish target ({@code http://localhost:9296/x} → {@code 9296}), or null. */
    private static String portOf(String rawTarget) {
        if (rawTarget == null) return null;
        String s = rawTarget.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("jdbc:")) s = s.substring(5);
        s = s.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.lastIndexOf(':');
        if (colon < 0) return null;
        String port = s.substring(colon + 1).trim();
        return port.matches("\\d{2,5}") ? port : null;
    }

    /** The one module that listens on the port, or null when none or several do. */
    private String portOwner(String port) {
        if (port == null) return null;
        Set<String> owners = portOwners.get(port);
        return owners != null && owners.size() == 1 ? owners.iterator().next() : null;
    }

    /**
     * The node a constant's string value names: a known node outright, or a module
     * directory's name in its node-safe spelling ({@code tools.descartes.teastore.persistence}
     * → {@code tools-descartes-teastore-persistence} → the workload it aligned to).
     */
    private String resolveModuleName(String value) {
        String node = matchNode(value);
        if (node != null) return node;
        String normalised = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (normalised.isEmpty()) return null;
        node = moduleNameNodes.get(normalised);
        if (node != null) return node;
        return matchNode(normalised);
    }

    /** A property by its Spring key, relaxed over case and {@code -}/{@code _} spelling. */
    private String propertyLookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String value = properties.get(key);
        if (value != null) return value;
        String relaxed = key.replaceAll("[-_]", "");
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (e.getKey().replaceAll("[-_]", "").equals(relaxed)) return e.getValue();
        }
        return null;
    }

    /** The path part of a URL-ish target ({@code http://%s:%s/catalog/} → {@code /catalog/}), or "". */
    private static String pathOf(String rawTarget) {
        if (rawTarget == null) return "";
        String s = rawTarget.trim();
        int scheme = s.indexOf("://");
        if (scheme < 0) return "";
        int slash = s.indexOf('/', scheme + 3);
        return slash < 0 ? "" : s.substring(slash);
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        // The platform's own addresses are no more a dependency than loopback is: the
        // cloud metadata server (metadata.google.internal, 169.254.169.254) and anything
        // under the reserved .internal domain (2026-10-26).
        return LOOPBACK.contains(h) || h.endsWith(".internal") || h.startsWith("169.254.");
    }

    /** The broker destination (topic / exchange / queue) for an async section. */
    private static String brokerTarget(String section, JSONObject fields) {
        return switch (section) {
            case "kafka-produce", "kafka-consume" -> firstNonBlank(fields, "topic");
            case "rabbit-produce" -> firstNonBlank(fields, "exchange", "routing", "value");
            case "rabbit-consume" -> firstNonBlank(fields, "queue");
            default -> null;
        };
    }

    // ---------- name normalisation ----------

    /**
     * Resolves a docker-compose service name onto the graph vocabulary: an existing
     * workload if known, otherwise the cleaned name introduced as a new node — a service
     * declared in compose but not observed at runtime (e.g. {@code tracing-server}) is a
     * real dependency, drawn dashed. Null only for a blank or implausible name.
     */
    private String resolveComposeService(String raw) {
        String node = matchNodeLoose(raw);
        if (node != null) return node;
        String label = cleanLabel(raw);
        return (label != null && isPlausibleName(label) && !SOURCE_STOP.contains(label)) ? label : null;
    }

    /** Suffixes a Compose/Helm service name adds over the workload it runs (order-service-container). */
    private static final String[] DEPLOY_NAME_SUFFIXES = {"-container", "-svc", "-pod", "-deployment"};

    /**
     * {@link #matchNode}, then the spellings a deployment file gives the same workload: a
     * {@code -container} suffix stripped, or the bare last word of a prefixed workload
     * name when it is unambiguous — TeaStore's compose file says {@code persistence} and
     * {@code db} for the workloads its manifests call {@code teastore-persistence} and
     * {@code teastore-db}. A short name that fits several nodes resolves to none.
     */
    /** Whether a file is a Compose document, by the spec's and the legacy names. */
    private static boolean isComposeFile(String file) {
        String base = file == null ? "" : file.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return (base.startsWith("docker-compose") || base.startsWith("compose.")) && (base.endsWith(".yml") || base.endsWith(".yaml"));
    }

    /**
     * Whether a bare word names something the deployment declares: a node of the graph
     * vocabulary, or a Compose service the manifests' vocabulary left out (rule 11 keeps
     * the k8s spelling when both exist; the Compose name is still a declaration, not a
     * guess). Used only to accept a bare word under an address key as a target.
     */
    private boolean declaredName(String word) {
        if (word == null || word.isBlank()) return false;
        if (matchNodeLoose(word) != null) return true;
        String c = clean(word);
        for (String name : composeServiceNames) if (name.equalsIgnoreCase(c)) return true;
        return false;
    }

    /**
     * An edge drawn before its target's kind was known ({@code depends_on: messagebroker},
     * typed as a call) is retyped once a later row's shape says the target is a database or
     * a broker: the arrow to a queue node is an async edge, to a database a db edge.
     */
    private void retypeEdgesByTargetKind() {
        for (DependencyGraph.Edge e : graph.getEdges()) {
            if (e.runtimeObserved || !"sync-http".equals(e.type)) continue;
            DependencyGraph.Node target = graph.findNode(e.target);
            if (target == null) continue;
            if (DependencyGraph.KIND_DB.equals(target.kind) || DependencyGraph.KIND_QUEUE.equals(target.kind)) RuleTrace.hit(39, "retype " + e.source + "->" + e.target);
            if (DependencyGraph.KIND_DB.equals(target.kind)) e.type = "db";
            else if (DependencyGraph.KIND_QUEUE.equals(target.kind)) e.type = "async";
        }
    }

    private String matchNodeLoose(String candidate) {
        String node = matchNode(candidate);
        if (node != null) return node;
        String c = clean(candidate);
        if (c.isEmpty()) return null;
        for (String suffix : DEPLOY_NAME_SUFFIXES) {
            if (c.endsWith(suffix) && c.length() > suffix.length()) {
                node = matchNode(c.substring(0, c.length() - suffix.length()));
                if (RuleTrace.hitIf(node != null, 7, "strip-suffix " + c + "=" + node)) return node;
            }
        }
        String unique = null;
        for (String known : knownNodes) {
            if (known.length() > c.length() + 1 && known.endsWith("-" + c)) {
                if (unique != null) return null; // ambiguous
                unique = known;
            }
        }
        RuleTrace.hitIf(unique != null, 7, "unique-suffix " + c + "=" + unique);
        return unique;
    }

    private static final java.util.regex.Pattern FRONTEND_FILE =
            java.util.regex.Pattern.compile("(?i).*\\.(js|jsx|ts|tsx|vue|html|htm)$");

    /**
     * An HTTP call in browser-side code whose URL has no scheme and no host — a
     * relative path ({@code 'users'}, {@code '/api/orders'}, {@code './data'}). A
     * placeholder ({@code ${API}}, {@code process.env.X}) is not relative: it names an
     * address the rest of the merge may still resolve.
     */
    public static boolean isRelativeBrowserCall(String section, String rawTarget, String file) {
        if (!"http-client".equals(section) || rawTarget == null || file == null) return false;
        if (!FRONTEND_FILE.matcher(file).matches()) return false;
        String t = rawTarget.trim();
        if (t.isEmpty() || t.contains("://") || t.startsWith("//")) return false;
        if (t.contains("$") || t.contains("{") || t.contains("process.env")) return false;
        return t.matches("[./]*[A-Za-z0-9_\\-./?=&%#]*");
    }

    /** Resolve a host/name to a known workload id, or null. Deterministic only. */
    private String matchNode(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        String c = clean(candidate);
        if (c.isEmpty()) return null;

        for (String node : knownNodes) {
            if (node.equalsIgnoreCase(c)) return node;
        }
        // The same name in module-directory spelling (Basket.API ↔ basket.api ↔ basket-api).
        if (c.indexOf('.') >= 0 || c.indexOf('-') >= 0) {
            String h = hyphenated(c);
            for (String node : knownNodes) {
                if (RuleTrace.hitIf(node.indexOf('.') >= 0 && hyphenated(node).equals(h), 36, "dotted-name " + c + "=" + node)) return node;
            }
        }
        // Feign/gRPC client identifiers: "CatalogueClient" / "catalogue-client" -> "catalogue".
        String stripped = c.replaceAll("(?i)[-_]?client$", "");
        if (!stripped.equals(c)) {
            for (String node : knownNodes) {
                if (node.equalsIgnoreCase(stripped)) return node;
            }
        }
        return null;
    }

    /** The in-cluster DNS suffixes a Service name may carry; the label before them is the namespace. */
    private static final String[] CLUSTER_SUFFIXES = {".svc.cluster.local", ".cluster.local", ".svc"};

    /**
     * Lower-cased host name with scheme/port/path/userinfo stripped, and with exactly the
     * DNS suffixes that are suffixes removed: a cluster suffix together with its namespace
     * label ({@code <svc>.<ns>.svc.cluster.local} → {@code <svc>}), or a trailing label that
     * is a namespace the manifests declare ({@code <svc>.<ns>} → {@code <svc>}). Any other
     * dotted name is kept whole: a Compose service may be called {@code catalog.api}, and a
     * domain cut to its first label reads as a service that does not exist (2026-10-26).
     */
    private String clean(String raw) {
        String s = cleanHost(raw, namespaces);
        if (ambiguousNames.isEmpty() || !ambiguousNames.contains(s)) return s;
        // An ambiguous name (rule 54): its own namespace label says which, else the reader's.
        String ns = ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor.namespaceLabel(raw);
        if (ns != null && namespaces.contains(ns)) return s + "." + ns;
        if (readerNamespace != null) return s + "." + readerNamespace;
        return s;
    }

    /** The namespace of the reader whose hosts are being resolved (rule 54), or null. */
    private String readerNamespace;
    /** Service names declared in two or more namespaces (rule 54). */
    private final Set<String> ambiguousNames = new LinkedHashSet<>();
    /** Workload node → the namespace its manifest puts it in. */
    private final Map<String, String> nodeNamespaces = new LinkedHashMap<>();

    static String cleanHost(String raw, Set<String> namespaces) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("jdbc:")) s = s.substring(5);   // jdbc:mysql://host -> mysql://host
        s = s.replaceFirst("^[a-z][a-z0-9+.-]*://", ""); // scheme
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);       // path
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);            // userinfo
        s = ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor.trailingHostPort(s);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);       // port
        // ${...} placeholders and quotes are not a usable host.
        s = s.replace("\"", "").replace("'", "").trim();
        if (s.contains("${") || s.contains("{{")) return "";
        boolean cluster = false;
        for (String suffix : CLUSTER_SUFFIXES) {
            if (s.endsWith(suffix) && s.length() > suffix.length()) {
                RuleTrace.hit(36, "cluster-suffix " + s);
                s = s.substring(0, s.length() - suffix.length());
                cluster = true;
                break;
            }
        }
        int dot = s.lastIndexOf('.');
        if (RuleTrace.hitIf(dot > 0 && !cluster && namespaces.contains(s.substring(dot + 1)), 36, "ns-strip " + s) || (dot > 0 && cluster)) s = s.substring(0, dot);
        return s;
    }

    /**
     * Whether a dotted host is an external domain as seen from this graph: not a declared
     * node (a Compose service {@code catalog.api}), not under a declared namespace, not an
     * in-cluster DNS name or an IP.
     */
    private boolean external(String host) {
        if (host == null || !host.contains(".")) return false;
        if (matchNode(host) != null) return false;
        int dot = host.lastIndexOf('.');
        if (namespaces.contains(host.substring(dot + 1).toLowerCase(Locale.ROOT))) return false;
        return isExternal(host, graph.getNamespace());
    }

    /**
     * The full host of a target, dots preserved (scheme/port/path/userinfo/quotes
     * removed): {@code http://catalogue/x} -> {@code catalogue},
     * {@code https://api.github.com/v3} -> {@code api.github.com}. A ${...}/{{...}}
     * placeholder is not a usable host and yields "".
     */
    private static String stripToHost(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("jdbc:")) s = s.substring(5);
        s = s.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        s = ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor.trailingHostPort(s);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        s = s.replace("\"", "").replace("'", "").trim();
        if (s.contains("${") || s.contains("{{")) return "";
        return s;
    }

    /** A real external host: a dotted domain that is not an in-cluster k8s name or a raw IP. */
    private static boolean isExternal(String host, String namespace) {
        if (host == null || !host.contains(".")) return false;
        if (host.endsWith(".svc.cluster.local") || host.endsWith(".cluster.local")
                || host.endsWith(".svc") || host.endsWith(".local")) return false;
        if (namespace != null && !namespace.isBlank()
                && host.endsWith("." + namespace.toLowerCase(Locale.ROOT))) return false;
        if (host.matches("[0-9.]+")) return false; // a bare IP, not a business name
        return true;
    }

    private static boolean looksLikeUrl(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://") || s.contains("://");
    }

    /** A host reduced to its DNS name (cluster suffixes off), or null when nothing usable remains. */
    private String cleanLabel(String raw) {
        if (raw == null) return null;
        String s = clean(raw);
        return s.isEmpty() ? null : s;
    }

    /**
     * A name safe to introduce as a node id: DNS labels, optionally dotted (a Compose
     * service may be called {@code catalog.api}), not a bare number.
     */
    private static boolean isPlausibleName(String s) {
        if (s == null || s.length() < 2 || s.length() > 63) return false;
        if (!s.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")) return false;
        return !s.matches("[0-9.]+");
    }

    // ---------- source attribution ----------

    /** Directory names that are never a service (so their files don't invent a source). */
    private static final Set<String> SOURCE_STOP = Set.of(
            "src", "lib", "libs", "pkg", "internal", "cmd", "app", "apps", "main",
            "test", "tests", "vendor", "target", "build", "dist", "node_modules",
            "common", "shared", "util", "utils", "core", "api", "web");

    /**
     * The calling service for a code edge. A dependency needs a real source: prefer
     * an existing workload (from the file's module directory, or the repo name);
     * only introduce a new source node when the directory clearly names a service
     * the runtime graph simply never saw traffic for.
     */
    private String resolveSource(String file) {
        // Layout-aware first: the service is the deepest scanned service directory
        // (service-root) that contains this file. This is what makes a nested repo
        // (src/<group>/<service>/…) attribute to <service> instead of collapsing to
        // the top path segment "src". Falls through to the flat-layout rule below.
        String svc = serviceForFile(file);
        if (svc != null) {
            String matched = matchNodeForSource(svc);
            if (matched != null) return matched;
            if (greenfield && isPlausibleName(svc) && !SOURCE_STOP.contains(svc)) return svc;
        }
        // A file in no module that exactly one Compose service mounts is that service's config.
        if (svc == null && file != null) {
            Set<String> by = mounts.get(file.replace('\\', '/'));
            if (by != null && by.size() == 1) {
                String mounted = resolveComposeService(by.iterator().next());
                if (RuleTrace.hitIf(mounted != null, 56, "mount " + file + "=" + mounted)) return mounted;
            }
        }

        String seg = firstPathSegment(file);
        if (seg != null) {
            String matched = matchNodeForSource(seg);
            if (matched != null) return matched;
            // A directory is named in module spelling (rule 2: dots become hyphens), not as a host.
            String label = cleanLabel(seg);
            if (label != null && label.indexOf('.') >= 0 && matchNode(label) == null) label = hyphenated(label);
            // An undeployed service's module dir has no workload to align to; strip the
            // learned repo prefix so it reads "genai-service", not the full module name.
            if (label != null && modulePrefix != null && label.startsWith(modulePrefix + "-")) {
                String stripped = label.substring(modulePrefix.length() + 1);
                if (isPlausibleName(stripped) && !SOURCE_STOP.contains(stripped)) label = stripped;
            }
            if (label != null && !SOURCE_STOP.contains(label) && isPlausibleName(label)) return label;
        }
        return defaultSource; // repo name matched a real workload, or null
    }

    /**
     * The service name of the deepest {@code service-root} directory that contains
     * this file, or null if no scanned service directory does. "Deepest" so a file
     * under {@code src/ledger/ledgerwriter/…} attributes to {@code ledgerwriter},
     * not to an ancestor module that also carries a manifest.
     */
    private String serviceForFile(String file) {
        if (file == null || file.isBlank() || serviceDirs.isEmpty()) return null;
        String path = file.replace('\\', '/');
        String best = null;
        int bestLen = -1;
        for (Map.Entry<String, String> entry : serviceDirs.entrySet()) {
            String dir = entry.getKey();
            if ((path.equals(dir) || path.startsWith(dir + "/")) && dir.length() > bestLen) {
                best = entry.getValue();
                bestLen = dir.length();
            }
        }
        return best;
    }

    private static String firstPathSegment(String file) {
        if (file == null || file.isBlank()) return null;
        String path = file.replace('\\', '/');
        int slash = path.indexOf('/');
        return slash <= 0 ? null : path.substring(0, slash);
    }

    /**
     * Like {@link #matchNode}, but also accepts a module directory whose name ends
     * with a known workload — e.g. {@code spring-petclinic-customers-service}
     * resolves to the workload {@code customers-service}.
     */
    private String matchNodeForSource(String seg) {
        String matched = matchNode(seg);
        if (matched != null) return matched;
        String c = clean(seg);
        for (String node : knownNodes) {
            if (node.length() >= 3 && (c.endsWith("-" + node) || c.endsWith("_" + node))) return node;
        }
        return null;
    }

    private static String repoShortName(String repoName) {
        if (repoName == null || repoName.isBlank()) return null;
        String s = repoName.trim();
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        return s.replaceAll("\\.git$", "");
    }

    private static String firstNonBlank(JSONObject fields, String... keys) {
        for (String key : keys) {
            String v = fields.optString(key, "");
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
