package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

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

        Unresolved(String section, String rawSource, String rawTarget, String file, int line) {
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
                    .put("evidence", file + (line > 0 ? ":" + line : ""));
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
            "k8s-workload", "compose-service");

    /** Hosts that name the caller itself, never another workload. */
    private static final Set<String> LOOPBACK = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]", "host.docker.internal");

    /** Config keys whose bare (non-URL) value is a host or a registry id, i.e. a target. */
    private static final String[] HOST_KEY_SUFFIXES = {
            ".host", ".hostname", "_host", "_hostname", ".address", ".serviceid", ".service-id"};

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
        if (greenfield) {
            for (String section : List.of("k8s-workload", "compose-service")) {
                for (int i = 0; i < edges.length(); i++) {
                    JSONObject edge = edges.optJSONObject(i);
                    if (edge == null || !section.equals(edge.optString("section", ""))) continue;
                    JSONObject fields = edge.optJSONObject("fields");
                    if (fields == null) continue;
                    String name = clean(fields.optString("name", ""));
                    if (!isPlausibleName(name)) continue;
                    allWorkloads.add(name);
                    workloadsByDir.computeIfAbsent(parentDir(edge.optString("file", "").replace('\\', '/')),
                            k -> new LinkedHashSet<>()).add(name);
                }
                if (!allWorkloads.isEmpty()) break;
            }
        }

        // Pass 1: the service inventory, so knownNodes is complete before env-address
        // resolution below can prefer a host that is actually a known service. A module
        // directory aligns to the workload it deploys as (exact, or by -suffix) when the
        // manifests know one; otherwise its own name is the node.
        Map<String, String> moduleNodes = new LinkedHashMap<>(); // dir -> node, in inventory order
        Set<String> aligned = new LinkedHashSet<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"service-root".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String dir = fields.optString("dir", "");
            String name = clean(fields.optString("name", ""));
            if (dir.isBlank() || !isPlausibleName(name)) continue;
            if (greenfield && !allWorkloads.isEmpty()) {
                String workload = matchWorkloadForModule(name, allWorkloads);
                if (workload != null) {
                    name = workload;
                    aligned.add(workload);
                }
            }
            moduleNodes.put(dir.replace('\\', '/'), name);
        }
        // The application's manifests: the directories in which some module of this repo
        // is deployed. Their workloads (a database StatefulSet, the registry, the broker
        // of a variant) are the vocabulary; the other directories' are not.
        for (Map.Entry<String, Set<String>> e : workloadsByDir.entrySet()) {
            for (String w : e.getValue()) {
                if (aligned.contains(w)) {
                    workloads.addAll(e.getValue());
                    break;
                }
            }
        }
        if (greenfield) knownNodes.addAll(workloads);
        for (Map.Entry<String, String> e : moduleNodes.entrySet()) {
            String name = e.getValue();
            serviceDirs.put(e.getKey(), name);
            if (greenfield) {
                knownNodes.add(name);
                // A module the manifests never deploy is a library or a tool; with no
                // manifests at all, every module is presumed deployable.
                if (workloads.isEmpty() || workloads.contains(name)) deployableModules.add(name);
            }
        }

        // Pass 2: the env -> host table. A repo may declare the same env var in more
        // than one manifest (e.g. a monolith variant that points every API at one
        // host); prefer the value whose host is a real service over one that is not,
        // so the microservice wiring wins over the monolith's collapsed addresses.
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"env-address".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String name = fields.optString("name", "");
            String host = clean(fields.optString("host", ""));
            if (name.isBlank() || host.isEmpty()) continue;

            String current = envAddress.get(name);
            if (current == null
                    || (matchNode(host) != null && matchNode(current) == null)) {
                envAddress.put(name, host);
            }
            String configMap = fields.optString("configmap", "");
            if (!configMap.isBlank()) configMapHosts.computeIfAbsent(configMap, k -> new LinkedHashSet<>()).add(host);
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
            for (String name : workloads) graph.addNode(name, DependencyGraph.classifyKind(name));
            for (String name : deployableModules) {
                graph.addNode(name, DependencyGraph.classifyKind(name));
            }
        }

        // Build the path -> service lookup from the final node vocabulary, so a
        // path-encoded callee (see serviceKeys) can be resolved later.
        for (String node : knownNodes) registerServiceKeys(node);

        indexConfig(edges);
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
            if (w.equalsIgnoreCase(module)) return w;
            if (w.length() >= 3 && (module.endsWith("-" + w) || module.endsWith("_" + w))
                    && (best == null || w.length() > best.length())) {
                best = w;
            }
        }
        return best;
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
                if (client != null) configClients.add(client);
            }

            // A config FILE named after a known service configures that service. Only a
            // config file: a code file also yields config rows (getenv, @Value), and
            // CatalogClient.java is not catalog's configuration.
            String stem = isConfigFile(file) ? fileStem(file) : null;
            if (stem != null && !stem.startsWith("application") && !stem.startsWith("bootstrap")) {
                String node = matchNode(stem);
                for (String s = stem; node == null && s.contains("-"); s = s.substring(0, s.lastIndexOf('-'))) {
                    node = matchNode(s); // account-service-dev → account-service
                }
                if (node != null) {
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
        return unresolved;
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
            String source = matchNodeLoose(fields.optString("workload", ""));
            if (source == null) continue;

            // Where the address came from decides what a service-kind target means, so the
            // two sources are kept apart rather than merged into one set.
            Map<String, Boolean> hosts = new LinkedHashMap<>(); // host -> written on this workload
            String configMap = fields.optString("configmap", "");
            for (String h : configMapHosts.getOrDefault(configMap, Set.of())) hosts.putIfAbsent(h, false);
            String literal = fields.optString("host", "");
            if (!literal.isBlank()) hosts.put(literal, true);

            String file = edge.optString("file", "");
            for (Map.Entry<String, Boolean> entry : hosts.entrySet()) {
                String host = entry.getKey();
                if (isLoopback(host)) continue;
                // Loose: a manifest often injects the short service name (REGISTRY_HOST:
                // registry) while the workload is deployed as teastore-registry. Matching
                // strictly here invented a second node for the same component.
                String node = matchNodeLoose(host);
                if (node == null) {
                    String label = cleanLabel(host);
                    if (label == null || !isPlausibleName(label)) continue;
                    node = label;
                }
                if (node.equals(source)) continue;
                String kind = DependencyGraph.classifyKind(node);
                if (!DependencyGraph.KIND_DB.equals(kind) && !DependencyGraph.KIND_QUEUE.equals(kind)) {
                    // A service address written into this workload's own env is that
                    // workload saying which service it needs — the most explicit form a
                    // declaration takes in a manifest. Drawing nothing puts it level with
                    // "no evidence at all", which is what the inferred grade exists to
                    // avoid; drawing it dotted says "declared, no use observed".
                    // A shared ConfigMap is still excluded: it is handed to workloads that
                    // call none of it, and there the code layer is the only honest source.
                    if (!entry.getValue()) continue;
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
            String src = resolveComposeService(fields.optString("source_service", ""));
            String tgt = resolveComposeService(fields.optString("target_service", ""));
            if (src != null && tgt != null && !src.equals(tgt)) {
                // The target is a node too: Robot Shop's cart -> redis edge pointed at a
                // node that did not exist, because only the source was ever added.
                graph.addNode(tgt, DependencyGraph.classifyKind(tgt));
                addCodeEdge(src, tgt, edgeType("", DependencyGraph.classifyKind(tgt)), file, line);
            }
            return;
        }

        // A Feign client's contextId / qualifier / path are bean wiring; and when it
        // declares a url, its name is only a bean id (piggymetrics: name="rates-client",
        // url="${rates.url}") — the url row carries the target.
        if ("feign".equals(section)) {
            String attr = fields.optString("attr", "").toLowerCase(Locale.ROOT);
            if (FEIGN_NON_TARGET_ATTRS.contains(attr)) return;
            if ((attr.equals("name") || attr.equals("value") || attr.isEmpty())
                    && feignUrlSites.contains(file.replace('\\', '/') + ":" + line)) return;
        }

        // A shared config file of a config repository configures every client; the
        // edges it states belong to each of them, not to the server that serves it.
        if (sourceOverride == null && "config".equals(section) && sharedConfigFiles.contains(file.replace('\\', '/'))) {
            for (String client : configClients) mergeOne(edge, unresolved, client);
            return;
        }

        String source = sourceOverride != null ? sourceOverride : resolveSource(file);
        if ("config".equals(section) && sourceOverride == null) {
            String owner = configFileService.get(file.replace('\\', '/'));
            if (owner != null) source = owner;
        }

        // A code-side config edge is a service reading an env var. When that env var
        // resolves (via the k8s ConfigMap / .env table) to a host that is a known
        // service, the read IS the dependency — this is how an env-indirected target
        // (TRANSACTIONS_API_ADDR -> ledgerwriter) becomes a concrete edge with no
        // runtime data. A bare env read that names no host is a signal, not an edge.
        if ("config".equals(section) && fields.has("env")) {
            String env = fields.optString("env", "");
            String host = env.isBlank() ? null : envAddress.get(env);
            if (host != null && source != null) {
                String node = matchNode(host);
                if (node != null && !node.equals(source)) {
                    addCodeEdge(source, node, edgeType("", DependencyGraph.classifyKind(node)), file, line);
                }
            }
            return;
        }

        // @Value("${catalog.service.host:catalog}"): the property may resolve through
        // the config files, and failing that its default names the host. Only a value
        // that is an address or a known node is taken — a port or a flag is not.
        if ("config".equals(section) && fields.has("property")) {
            String value = substitutePlaceholders(fields.optString("property", ""));
            if (value == null || source == null) return;
            String node = matchNode(value);
            if (node == null && looksLikeUrl(value) && !isLoopback(stripToHost(value))) {
                String host = stripToHost(value);
                if (isExternal(host, graph.getNamespace())) {
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

        // --- asynchronous: the other endpoint is a broker destination (its own node) ---
        if (section.startsWith("kafka") || section.startsWith("rabbit")) {
            String broker = cleanToken(brokerTarget(section, fields));
            if (broker == null || source == null) {
                unresolved.add(new Unresolved(section, source, brokerTarget(section, fields), file, line));
                return;
            }
            graph.addNode(broker, DependencyGraph.KIND_QUEUE);
            boolean consume = section.endsWith("consume");
            // produce: service -> destination; consume: destination -> service.
            addCodeEdge(consume ? broker : source, consume ? source : broker, "async", file, line);
            return;
        }

        // --- synchronous: resolve the callee onto the graph vocabulary ---
        // Fallback for a call whose host is a variable but whose URL path names the
        // service by convention (/api/v1/orderservice/... -> order-service). Used only
        // when the host cannot be resolved below, so a real literal host always wins.
        String rawTarget = syncTarget(section, fields);

        // The host may be a placeholder — http://${BALANCES_API_ADDR}/balances, or a
        // Feign url="${rates.url}". The env -> host table (k8s ConfigMap / .env) or the
        // property table (application*.yml) fills it in, and a ${key:default} falls
        // back to its default: this is what draws ledgerwriter -> balancereader, and
        // statistics-service -> api.exchangeratesapi.io, with no runtime data.
        nameInferred = false;
        String substituted = substitutePlaceholders(rawTarget);
        if (substituted != null) rawTarget = substituted;
        boolean inferredByName = nameInferred;

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
            if (rawTarget == null && "config".equals(section)) return;
            unresolved.add(new Unresolved(section, source, rawTarget, file, line));
            return;
        }

        String fullHost = stripToHost(rawTarget);
        // localhost / 127.0.0.1 is the caller itself (a dev profile, a health check),
        // never another workload — piggymetrics and TeaStore both grew a "localhost" node.
        if (isLoopback(fullHost)) return;
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
        if (isExternal(fullHost, graph.getNamespace())) {
            graph.addNode(fullHost, DependencyGraph.KIND_EXTERNAL);
            addCodeEdge(source, fullHost, "external", file, line);
            return;
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
        String kind = DependencyGraph.classifyKind(node);
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

    /** The callee token for a synchronous section, or null if none is usable. */
    private static String syncTarget(String section, JSONObject fields) {
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
                return firstNonBlank(fields, "value", "target", "host", "name");
            }
            case "config" -> {
                // A config value that is a URL is a dependency target; so is a bare host
                // under a host-shaped key (spring.data.mongodb.host: account-mongodb) or a
                // registry id under a route (zuul.routes.x.serviceId: account-service).
                String v = firstNonBlank(fields, "value");
                if (v == null) return null;
                if (looksLikeUrl(v)) return v;
                String key = fields.optString("key", "").toLowerCase(Locale.ROOT);
                for (String suffix : HOST_KEY_SUFFIXES) {
                    if (key.endsWith(suffix)) return v;
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

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
            // The placeholder's own default outranks a guess from its name: train-ticket's
            // lb://${ADMIN_ORDER_SERVICE_HOST:ts-admin-order-service} states the host.
            if (value == null && fallback != null && !fallback.isBlank()) value = fallback.trim();
            if (value == null && name != null) {
                value = nodeNamedByVariable(name.trim());
                if (value != null) nameInferred = true;
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
        String host = envAddress.get(name);
        if (host != null) return host;
        for (Map.Entry<String, String> e : envAddress.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
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
        return host != null && LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
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
    private String matchNodeLoose(String candidate) {
        String node = matchNode(candidate);
        if (node != null) return node;
        String c = clean(candidate);
        if (c.isEmpty()) return null;
        for (String suffix : DEPLOY_NAME_SUFFIXES) {
            if (c.endsWith(suffix) && c.length() > suffix.length()) {
                node = matchNode(c.substring(0, c.length() - suffix.length()));
                if (node != null) return node;
            }
        }
        String unique = null;
        for (String known : knownNodes) {
            if (known.length() > c.length() + 1 && known.endsWith("-" + c)) {
                if (unique != null) return null; // ambiguous
                unique = known;
            }
        }
        return unique;
    }

    /** Resolve a host/name to a known workload id, or null. Deterministic only. */
    private String matchNode(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        String c = clean(candidate);
        if (c.isEmpty()) return null;

        for (String node : knownNodes) {
            if (node.equalsIgnoreCase(c)) return node;
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

    /** Lower-cased first DNS label with scheme/port/path and cluster suffixes stripped. */
    private static String clean(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("jdbc:")) s = s.substring(5);   // jdbc:mysql://host -> mysql://host
        s = s.replaceFirst("^[a-z][a-z0-9+.-]*://", ""); // scheme
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);       // path
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);            // userinfo
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);       // port
        // ${...} placeholders and quotes are not a usable host.
        s = s.replace("\"", "").replace("'", "").trim();
        if (s.contains("${") || s.contains("{{")) return "";
        // Kubernetes DNS: <svc>.<ns>.svc.cluster.local -> <svc>.
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s;
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

    /** A broker destination (topic/exchange/queue) name — dots kept, placeholders rejected. */
    private static String cleanToken(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace("\"", "").replace("'", "").trim();
        if (s.isEmpty() || s.contains("${") || s.contains("{{")) return null;
        return s;
    }

    /** A host reduced to its single DNS label, or null when nothing usable remains. */
    private static String cleanLabel(String raw) {
        if (raw == null) return null;
        String s = clean(raw);
        return s.isEmpty() ? null : s;
    }

    /** A name safe to introduce as a node id: k8s-service-ish, not a bare number. */
    private static boolean isPlausibleName(String s) {
        if (s == null || s.length() < 2 || s.length() > 63) return false;
        if (!s.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?")) return false;
        return !s.matches("[0-9]+");
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

        String seg = firstPathSegment(file);
        if (seg != null) {
            String matched = matchNodeForSource(seg);
            if (matched != null) return matched;
            String label = cleanLabel(seg);
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
