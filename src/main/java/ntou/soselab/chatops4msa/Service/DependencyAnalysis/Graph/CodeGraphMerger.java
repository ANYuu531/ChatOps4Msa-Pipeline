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
     * ({@code env-address}). They seed the names.greenfield vocabulary and resolve
     * indirected targets; they are never rendered as arrows.
     */
    private static final Set<String> META_SECTIONS = Set.of("service-root", "env-address", "workload-env", "compose-port", "key-read", "parsed-dir",
            "dockerfile-from", "image-build",
            "k8s-workload", "compose-service", "k8s-service", "k8s-namespace", "module-deps", "name-constant", "import",
            "compose-alias", "url-metadata", "k8s-gateway", "compose-mount", "registry-name");

    /** Feign attributes that are bean wiring, not a target: a contextId is not a host. */
    private static final Set<String> FEIGN_NON_TARGET_ATTRS = Vocabulary.FEIGN_NON_TARGET_ATTRS;

    private final DependencyGraph graph;
    /** Services with persistence code (JPA/ORM markers) — used to tell a really-used DB from a declared one. */
    private final Set<String> persistenceServices = new LinkedHashSet<>();
    /** Compose depends_on entries no address backs: {source, target, file, line}, drawn last as startup order. */
    private final List<String[]> startupOrder = new ArrayList<>();
    /** Async rows (the kafka and rabbit sections), merged after everything else so the broker a service is configured with is known. */
    private final List<JSONObject> asyncRows = new ArrayList<>();

    /**
     * Reads the two meta-sections before any edge is merged: the service inventory
     * (which, in names.greenfield, becomes the node vocabulary) and the env-var → host
     * table (which resolves indirected targets like {@code TRANSACTIONS_API_ADDR}).
     */
    /**
     * Reads the meta-sections before any edge is merged, one pass per table: the declared
     * names (convention ①: names.namespaces, Services, names.workloads, Compose services, the module
     * inventory aligned to them), the env-var → host table with its scopes (convention ③),
     * the build manifests, the Compose aliases and names.mounts, and the config lookups.
     */
    private void indexMeta(JSONArray edges) {
        names.indexRegistryNames(edges);
        names.indexNamespaces(edges);
        names.indexDeclaredNames(edges);
        owner.indexImages(edges);
        names.chooseVocabulary(edges);
        owner.indexWorkloadEnv(edges);
        names.indexInventory(edges);
        owner.indexEnvAddresses(edges);
        owner.indexBuildManifests(edges);
        seedGreenfieldNodes();
        names.indexComposeAliases(edges);
        names.indexServiceSelectors(edges, names.rawWorkloadNames, names.k8sServices, new LinkedHashSet<>(names.moduleNodes.values()));
        names.indexMountsAndGateways(edges);
        owner.indexImports(edges);
        owner.indexConfig(edges);
    }

    /**
     * Greenfield: the vocabulary becomes the node set; then the path → service keys.
     */
    private void seedGreenfieldNodes() {
        // In names.greenfield the inventory IS the graph's node set: add every service so
        // even a service nobody calls still appears (an accurate, if isolated, node).
        // The manifests' names.workloads are part of that inventory — a database StatefulSet
        // has no source directory and would otherwise only exist once something
        // resolves to it.
        // A module the manifests do not deploy (TeaStore's registryclient library, its
        // Docker base image) is not pre-added: it still attributes sources, and appears
        // only if an edge actually touches it.
        if (names.greenfield) {
            for (String name : names.workloads) RuleTrace.hit(names.k8sVocabulary ? 1 : 11, "node " + name);
            for (String name : names.workloads) graph.addNode(name, DependencyGraph.classifyKind(name));
            for (String name : names.deployableModules) {
                graph.addNode(name, DependencyGraph.classifyKind(name));
            }
        }

        // Build the path -> service lookup from the final node vocabulary, so a
        // path-encoded callee (see names.serviceKeys) can be resolved later.
        for (String node : names.knownNodes) names.registerServiceKeys(node);
    }

    /** The node kind a connection scheme states: {@code jdbc:} / {@code mongodb://} are a database whatever the host is called. */
    static String kindFromScheme(String rawTarget) {
        String k = AddressShape.kind(null, rawTarget);
        return "db".equals(k) ? DependencyGraph.KIND_DB : "queue".equals(k) ? DependencyGraph.KIND_QUEUE : null;
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
            String service = names.resolveSource(edge.optString("file", ""));
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
        merger.names.learnModulePrefix(edges);
        merger.collectPersistenceServices(edges);
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge != null) merger.mergeOne(edge, unresolved);
        }
        merger.mergeWorkloadWiring(edges);
        merger.mergeImplicitRegistry(edges);
        merger.mergeAsync(unresolved);
        merger.retypeEdgesByTargetKind();
        merger.drawStartupOrder();
        return unresolved;
    }

    /**
     * The Compose depends_on entries nothing else backed, drawn after every call edge is in:
     * where a call edge (or a runtime-observed one) already joins the pair, the declaration
     * only adds its provenance to it — the order is implied by the call; otherwise the pair
     * gets a {@link DependencyGraph#TYPE_STARTUP} edge, a relation of its own that a deploy
     * order can read and no scorer counts as a call.
     */
    private void drawStartupOrder() {
        for (String[] s : startupOrder) {
            String src = s[0], tgt = s[1];
            String ref = "code: " + s[2] + "#depends_on" + ("-1".equals(s[3]) ? "" : ":" + s[3]);
            DependencyGraph.Edge existing = null;
            for (DependencyGraph.Edge e : graph.getEdges()) {
                if (e.source.equals(src) && e.target.equals(tgt)) {
                    existing = e;
                    break;
                }
            }
            if (existing != null) {
                graph.addEdge(src, tgt, existing.type, DependencyGraph.PROV_CODE, DependencyGraph.CONF_INFERRED, false, 0, ref);
                continue;
            }
            RuleTrace.hitIf(graph.findNode(tgt) == null, 7, "node " + tgt);
            graph.addNode(src, DependencyGraph.KIND_SERVICE);
            graph.addNode(tgt, DependencyGraph.classifyKind(tgt));
            graph.addEdge(src, tgt, DependencyGraph.TYPE_STARTUP, DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, ref);
        }
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
            for (String node : names.knownNodes) {
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
        for (Map.Entry<String, Set<String>> e : owner.moduleArtifacts.entrySet()) {
            for (String a : e.getValue()) {
                if (isEurekaServerArtifact(a)) {
                    if (server != null && !server.equals(e.getKey())) return; // several servers: ambiguous
                    server = e.getKey();
                }
            }
        }
        // Only between modules the deployment vocabulary knows: a registry module the
        // manifests do not name would become a second spelling of the real one.
        if (server == null || !names.deployableModules.contains(server)) return;

        Set<String> zoneDeclared = new LinkedHashSet<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"config".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String key = fields.optString("key", "").toLowerCase(Locale.ROOT).replace("-", "");
            if (!key.startsWith("eureka.client.serviceurl")) continue;
            String file = edge.optString("file", "").replace('\\', '/');
            if (owner.sharedConfigFiles.contains(file)) {
                zoneDeclared.addAll(owner.configClients);
                continue;
            }
            String declarer = owner.configFileService.get(file);
            if (declarer == null) declarer = names.resolveSource(file);
            if (declarer != null) zoneDeclared.add(declarer);
        }
        for (Map.Entry<String, Set<String>> e : owner.moduleArtifacts.entrySet()) {
            String client = e.getKey();
            if (client.equals(server) || zoneDeclared.contains(client)) continue;
            if (!names.deployableModules.contains(client)) continue;
            boolean isClient = false;
            for (String a : e.getValue()) if (isEurekaClientArtifact(a)) isClient = true;
            if (!isClient) continue;
            String provenance = owner.moduleManifests.getOrDefault(client, client);
            RuleTrace.hit(16, "edge " + client + "->" + server);
            graph.addNode(server, DependencyGraph.classifyKind(server));
            addCodeEdge(client, server, "sync-http", provenance, -1, DependencyGraph.CONF_INFERRED);
        }
    }

    private void mergeWorkloadWiring(JSONArray edges) {
        owner.allEdges = edges;
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"workload-env".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String rowNs = fields.optString("namespace", "").trim().toLowerCase(Locale.ROOT);
            names.readerNamespace = rowNs.isEmpty() ? null : rowNs;
            String rawWorkload = names.clean(fields.optString("workload", ""));
            String plainWorkload = cleanHost(fields.optString("workload", ""), names.namespaces);
            if (names.readerNamespace != null && names.workloadAliases.containsKey(plainWorkload + "@" + names.readerNamespace)) {
                rawWorkload = names.workloadAliases.get(plainWorkload + "@" + names.readerNamespace);
            }
            String source = names.matchNodeLoose(names.workloadAliases.getOrDefault(rawWorkload, rawWorkload));
            // A Compose service the vocabulary does not know (the manifests are a stale
            // partial set and name it differently, or not at all) is still a service the
            // deployment declares — the same resolution depends_on uses for its endpoints
            // (yas: identity, which yaslocal.yaml calls keycloak; 2026-10-26).
            if (source == null && isComposeFile(edge.optString("file", ""))) source = names.resolveComposeService(rawWorkload);
            if (source == null) continue;

            // Where the address came from decides what a service-kind target means, so the
            // two sources are kept apart rather than merged into one set.
            Map<String, Boolean> hosts = new LinkedHashMap<>(); // host -> this workload's own value
            String configMap = fields.optString("configmap", "");
            if (!configMap.isBlank()) {
                Map<String, String> entries = owner.configMapEntries.getOrDefault(configMap, Map.of());
                String selected = fields.optString("configmap_key", "");
                if (!selected.isBlank()) {
                    // configMapKeyRef: the workload picked this key.
                    if (entries.containsKey(selected)) hosts.put(entries.get(selected), true);
                } else {
                    // envFrom: the whole ConfigMap, no key chosen. A key the workload never reads is
                    // not its dependency; one it reads stays wholesale (false): only a data store or
                    // broker is drawn from it, since a framework binds those without a line of code.
                    for (Map.Entry<String, String> kv : entries.entrySet()) {
                        boolean read = owner.readsKey(source, kv.getKey());
                        RuleTrace.hit(14, (read ? "configmap-read " : "configmap-unread ") + source + " " + kv.getKey() + "=" + kv.getValue());
                        if (read) hosts.putIfAbsent(kv.getValue(), false);
                    }
                }
            }
            // An env_file is wholesale too (convention ③).
            String shared = fields.optString("shared", "");
            if (!shared.isBlank()) {
                String key = fields.optString("name", "");
                boolean read = owner.readsKey(source, key);
                RuleTrace.hit(14, (read ? "env_file-read " : "env_file-unread ") + source + " " + key + " (" + shared + ")");
                if (!read) continue;
            }
            String literal = fields.optString("host", "");
            String hostNs = fields.optString("host_ns", "").trim();
            if (!literal.isBlank() && !hostNs.isEmpty() && names.ambiguousNames.contains(literal.trim()) && names.namespaces.contains(hostNs)) {
                literal = literal.trim() + "." + hostNs;
            }
            // A bare word from a command-line flag is a host only when the deployment declares it (rule 53 / 45).
            if (!literal.isBlank() && "true".equals(fields.optString("bare", "")) && !names.declaredName(literal)) {
                RuleTrace.hit(53, "bare-undeclared " + rawWorkload + " " + literal);
                literal = "";
            }
            if (!literal.isBlank()) hosts.put(literal, shared.isBlank());

            String file = edge.optString("file", "");
            // What the value's shape said the target is (a connection string, an amqp:// URL):
            // outranks the host's name, which in .NET carries no hint (catalogdb, messagebroker).
            String statedKind = Ownership.kindField(fields);
            for (Map.Entry<String, Boolean> entry : hosts.entrySet()) {
                // Convention ①. The environment is read inside the container, where localhost is
                // the workload itself (devSide false). A domain in the workload's own env
                // (LLM_BASE_URL=https://local-llm.com) is an external endpoint it is handed:
                // drawn as such, dotted, never cut to a first label (2026-10-26).
                DeployedNames.Target t = names.resolveTarget(entry.getKey(), source, false, entry.getValue(), true);
                if (t == null || t.dropped()) continue;
                if (t.external()) {
                    if (RuleTrace.hitIf(fields.has("helm") && !names.helmVocabulary, 52, "variant-host " + source + "->" + t.node())) continue;
                    RuleTrace.hit(36, "external-env " + source + "->" + t.node());
                    graph.addNode(t.node(), DependencyGraph.KIND_EXTERNAL);
                    addCodeEdge(source, t.node(), "external", file, -1, DependencyGraph.CONF_INFERRED);
                    continue;
                }
                if (RuleTrace.hitIf(t.fresh() && fields.has("helm") && !names.helmVocabulary, 52, "variant-host " + source + "->" + t.node())) continue;
                String node = t.node();
                if (node.equals(source)) continue;
                String kind = statedKind != null && entry.getValue() ? statedKind : DependencyGraph.classifyKind(node);
                RuleTrace.hitIf(statedKind != null && entry.getValue() && !statedKind.equals(DependencyGraph.classifyKind(node)), 39, "kind " + node + "=" + statedKind);
                if (!DependencyGraph.KIND_DB.equals(kind) && !DependencyGraph.KIND_QUEUE.equals(kind)) {
                    // A service address written into this workload's own env is that
                    // workload saying which service it needs — the most explicit form a
                    // declaration takes in a manifest. Drawing nothing puts it level with
                    // "no provenance at all", which is what the inferred grade exists to
                    // avoid; drawing it dotted says "declared, no use observed".
                    // A shared ConfigMap is still excluded: it is handed to names.workloads that
                    // call none of it, and there the code layer is the only honest source.
                    // Tried on 2026-11-03: leaving the own env block to the code layer too
                    // (yas hands storefront ten addresses its code never uses). It cost 27
                    // real edges — the code layer cannot yet follow a Spring
                    // @ConfigurationProperties binding to its accessor, TeaStore's registry
                    // reads, a load generator's env — so the manifest keeps drawing these,
                    // inferred, until convention ④ follows those bindings.
                    if (RuleTrace.hitIf(!entry.getValue(), 14, "skip-configmap " + source + "->" + node)) continue;
                    if (RuleTrace.hitIf(owner.codeReadable(source), 14, "code-decides " + source + "->" + node)) continue;
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
        names.readerNamespace = null;
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
        merger.names.learnModulePrefix(edges);
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
    /**
     * One ledger row → zero or more graph edges. The gates and handlers run in the order the
     * conventions are asked: ④ is the row a use at all, ③ whose row it is, ② what its value
     * is, ① where that lands in this deployment.
     *
     * @param sourceOverride the calling service when the caller already knows it (a
     *                        shared config file fanned out to each config client); null
     *                        to attribute the edge from its file path.
     */
    private void mergeOne(JSONObject edge, List<Unresolved> unresolved, String sourceOverride) {
        names.readerNamespace = null;
        String section = edge.optString("section", "");
        JSONObject fields = edge.optJSONObject("fields");
        if (fields == null) fields = new JSONObject();
        String file = edge.optString("file", "");
        int line = edge.optInt("line", 0);

        // ④ Rows that are no edge, and a URL only the one container mounting its file may own (rule 60).
        if (isNotAnEdge(section, fields)) return;
        if (mountedCandidateRejected(section, fields, file)) return;
        // ③ depends_on names both endpoints itself: startup order, or a call when an address backs it.
        if ("compose-dependency".equals(section)) {
            mergeComposeDependency(fields, file, line);
            return;
        }
        if (isFeignWiring(section, fields, file, line)) return;
        if (fanOutSharedConfig(edge, section, fields, file, unresolved, sourceOverride)) return;
        if (isDeclaredNotUsed(section, fields)) return;
        if (sourceOverride == null && fanOutBaseImage(edge, file, unresolved)) return;

        // ③ Whose row this is: the file's module, a config file's client, every workload running the module.
        String source = sourceOverride != null ? sourceOverride : names.resolveSource(file);
        if ("config".equals(section) && sourceOverride == null) {
            String configOwner = owner.configFileService.get(file.replace('\\', '/'));
            if (configOwner != null) source = configOwner;
        }
        // A module several names.workloads run (one image, several Deployments): each runs this code.
        if (sourceOverride == null && source != null && names.moduleFanout.containsKey(source)) {
            for (String w : names.moduleFanout.get(source)) mergeOne(edge, unresolved, w);
            return;
        }
        // A short host in this reader's code means its own namespace (rule 54).
        names.readerNamespace = source == null ? null : names.nodeNamespaces.get(source);
        if (sourceOverride == null && source != null && names.libraryModules.contains(source)) {
            fanOutLibrary(edge, file, source, unresolved);
            return;
        }

        // ② what the row's value is, ① where it lands — one handler per row shape.
        if ("name-ref".equals(section)) {
            mergeNameRef(section, fields, source, file, line);
            return;
        }
        if ("ingress-route".equals(section)) {
            mergeIngressRoute(fields, file, line);
            return;
        }
        if ("registry-ref".equals(section)) {
            mergeRegistryRef(section, fields, source, file, line, unresolved);
            return;
        }
        if ("config".equals(section) && fields.has("env")) {
            mergeEnvRead(fields, source, file, line);
            return;
        }
        if (("http-client".equals(section) || "grpc".equals(section)) && fields.has("env") && !fields.has("url")) {
            mergeEnvCall(section, fields, source, file, line);
            return;
        }
        if ("url".equals(section) && fields.has("env") && fields.optString("value", "").matches(".*%[sd].*")
                && !fillFormatHost(fields, source)) return;
        if ("config".equals(section) && fields.has("property")) {
            mergeProperty(fields, source, file, line);
            return;
        }
        if (section.startsWith("kafka") || section.startsWith("rabbit")) {
            deferAsync(edge, section, fields, source, file, line, unresolved);
            return;
        }
        mergeSync(section, fields, source, file, line, unresolved);
    }

    /**
     * Convention ④: rows that are a signal or an inbound surface, never an outgoing edge.
     */
    private boolean isNotAnEdge(String section, JSONObject fields) {
        // Inbound endpoints this service exposes are not an outgoing dependency;
        // persistence markers (jpa/persistence) are a SIGNAL (handled in
        // collectPersistenceServices), not an edge with a target of their own;
        // the meta-sections (service-root / env-address) are lookup tables consumed
        // in indexMeta, never edges.
        if ("http-server".equals(section) || PERSISTENCE_SECTIONS.contains(section)
                || META_SECTIONS.contains(section) || "url-constant".equals(section)) return true;
        // A URL in a constant nobody references is declared, not used (see
        // TreeSitterExtractor.markUnreferencedUrlConstants): no edge, no residue.
        if ("url".equals(section) && "true".equals(fields.optString("unreferenced", ""))) return true;
        return false;
    }

    /**
     * Rule 60: true when a URL from a file of no known kind has no owner or no declared target.
     */
    private boolean mountedCandidateRejected(String section, JSONObject fields, String file) {
        // Rule 60: an address in a YAML file of no known kind counts only as the config of
        // the one Compose service that names.mounts the file, and only toward a declared name.
        // Convention ③ decides the owner — the one container that names.mounts the file, or the module
        // the file sits in — and ① the target: a declared name, or localhost:PORT read back to
        // the one service on PORT (the module's own config is read on the developer's machine).
        // No new node and no external domain come from a file of no known kind.
        if (!"url".equals(section) || !"candidate".equals(fields.optString("mounted", ""))) return false;
        Set<String> by = names.mounts.get(file.replace('\\', '/'));
        boolean inModule = names.serviceForFile(file) != null;
        String value = fields.optString("value", "");
        String host = mountedHost(value);
        // The owner must itself be a component the deployment declares: a tool directory's
        // JSON (train-ticket's json2shiviz/) names services, and runs nowhere.
        String owner = inModule ? names.resolveSource(file) : by != null && by.size() == 1 ? names.resolveComposeService(by.iterator().next()) : null;
        boolean owned = owner != null && (names.declaredWorkloads.contains(owner) || names.deployableModules.contains(owner)
                || names.declaredWorkloads.stream().anyMatch(w -> owner.equals(names.matchNodeLoose(w))));
        boolean target = host != null && (names.declaredName(names.unalias(host, null))
                || (isLoopback(host) && names.portOwner(portOf(value)) != null));
        if (!owned || !target) return true;
        RuleTrace.hit(60, (inModule ? "module-file " : "mounted ") + file + " " + host);
        return false;
    }

    /**
     * Rule 35: {@code depends_on} and {@code links} — a call edge, a startup-order edge, or nothing.
     */
    private void mergeComposeDependency(JSONObject fields, String file, int line) {
        // docker-compose depends_on names BOTH endpoints explicitly (source_service ->
        // target_service), so the source comes from the field, not the file's module.
        // This is what draws service -> config-server / discovery-server even when the
        // client-side URLs live in an externalised config repo the scan never sees.
        String rawSource = names.clean(fields.optString("source_service", ""));
        String src = names.resolveComposeService(fields.optString("source_service", ""));
        String tgt = names.resolveComposeService(fields.optString("target_service", ""));
        if (src != null && tgt != null && !src.equals(tgt)) {
            // depends_on is startup order, not a call: "start that first" says nothing
            // about who talks to whom. It is a call edge when the service's own addresses
            // name the same target (two declarations agree: documented), or when a
            // container running this repo's own code states no address anywhere — the
            // author's one statement of what their code needs (hotelReservation's Go
            // services, whose addresses sit in a config.json no module owns; the Compose
            // v1 era): inferred, a declaration without a use site. From a third-party
            // image it is only ordering: that container's wiring lives in the image,
            // unreadable, so nothing lets the tool claim a call (skywalking-showcase's
            // node-exporter waits for oap and never connects to it; a curl container
            // waits for the exporter it never calls). Those, and a depends_on the
            // service's own addresses contradict, are kept as a relation of their own
            // (TYPE_STARTUP, drawn last so it never shadows a call the code states).
            // links says more: in the version 1 file format a container reaches another
            // by name only through a link, so a link from a service that states no
            // address is its one wiring statement whatever runs in it (2026-11-03).
            boolean link = !"depends_on".equals(fields.optString("via", "depends_on"));
            Set<String> hosts = owner.workloadHosts.getOrDefault(rawSource, Set.of());
            boolean addressed = false;
            for (String h : hosts) {
                String node = names.matchNodeLoose(names.unalias(h, src));
                if (node == null) node = names.cleanLabel(h);
                if (tgt.equals(node)) {
                    addressed = true;
                    break;
                }
            }
            String confidence = DependencyGraph.CONF_DOCUMENTED;
            if (!addressed) {
                if (!link && (!hosts.isEmpty() || !owner.ownContainer(rawSource))) {
                    RuleTrace.hit(35, "startup " + src + "->" + tgt);
                    // Both endpoints are declared services: nodes now, so a later row's
                    // address (config.json's mongodb-geo:27017) finds the name, as it
                    // did when depends_on drew the edge itself; the relation itself last.
                    RuleTrace.hitIf(graph.findNode(tgt) == null, 7, "node " + tgt);
                    graph.addNode(src, DependencyGraph.KIND_SERVICE);
                    graph.addNode(tgt, DependencyGraph.classifyKind(tgt));
                    startupOrder.add(new String[] {src, tgt, file, String.valueOf(line)});
                    return;
                }
                if (RuleTrace.hitIf(!hosts.isEmpty(), 35, "drop " + src + "->" + tgt)) return;
                confidence = DependencyGraph.CONF_INFERRED;
            }
            RuleTrace.hit(35, (addressed ? "documented " : "inferred ") + src + "->" + tgt);
            // The target is a node too: Robot Shop's cart -> redis edge pointed at a
            // node that did not exist, because only the source was ever added.
            RuleTrace.hitIf(graph.findNode(tgt) == null, 7, "node " + tgt);
            graph.addNode(tgt, DependencyGraph.classifyKind(tgt));
            // The provenance names the key, so a reader can tell a startup-order
            // declaration from an address the same file hands the service.
            addCodeEdge(src, tgt, edgeType("", DependencyGraph.classifyKind(tgt)), file + "#depends_on", line, confidence);
        }
    }

    /**
     * Rule 8: a Feign attribute that is bean wiring, or a name beside a url, is no target.
     */
    private boolean isFeignWiring(String section, JSONObject fields, String file, int line) {
        // A Feign client's contextId / qualifier / path are bean wiring; and when it
        // declares a url, its name is only a bean id (piggymetrics: name="rates-client",
        // url="${rates.url}") — the url row carries the target.
        if (!"feign".equals(section)) return false;
        String attr = fields.optString("attr", "").toLowerCase(Locale.ROOT);
        if (RuleTrace.hitIf(FEIGN_NON_TARGET_ATTRS.contains(attr), 8, "feign-attr " + attr + " " + file + ":" + line)) return true;
        if (RuleTrace.hitIf((attr.equals("name") || attr.equals("value") || attr.isEmpty())
                && owner.feignUrlSites.contains(file.replace('\\', '/') + ":" + line), 8, "feign-bean-id " + fields.optString("value", ""))) return true;
        return false;
    }

    /**
     * Rule 3: a shared file of a config repository is merged once per config client that consumes the key.
     */
    private boolean fanOutSharedConfig(JSONObject edge, String section, JSONObject fields, String file,
                                       List<Unresolved> unresolved, String sourceOverride) {
        if (!(sourceOverride == null && "config".equals(section) && owner.sharedConfigFiles.contains(file.replace('\\', '/')))) return false;
        String key = fields.optString("key", "");
        for (String client : owner.configClients) {
            if (RuleTrace.hitIf(owner.consumesKey(client, key), 3, "fanout " + client + " " + key)) mergeOne(edge, unresolved, client);
        }
        return true;
    }

    /**
     * Convention ④: a declaration no live code uses, or a read a call site already carries.
     */
    private boolean isDeclaredNotUsed(String section, JSONObject fields) {
        // A declaration no live code uses draws no edge (the Python analogue of the
        // unreferenced-constant rule): an env read whose variable nothing references, or a
        // call inside a function nothing ever calls, is declared, not used.
        if (("config".equals(section) || "http-client".equals(section))
                && "true".equals(fields.optString("unreferenced", ""))) return true;
        // A getenv whose value a known call site consumes: the call-site row carries the
        // edge (and the provenance the reference edge sets expect — where the call is made).
        if ("config".equals(section) && "true".equals(fields.optString("superseded", ""))) return true;
        return false;
    }

    /**
     * Rule 53: a base image's rows are merged once per deployed service built FROM it.
     */
    private boolean fanOutBaseImage(JSONObject edge, String file, List<Unresolved> unresolved) {
        // A base image's directory (its command line, its start scripts) runs inside every
        // image built FROM it: TeaStore's start.sh writes the registry URL from REGISTRY_HOST,
        // and each of the five services inherits that line as its own (2026-11-04).
        Set<String> children = owner.baseImageChildrenOf(file);
        if (children == null) return false;
        for (String child : children) {
            RuleTrace.hit(53, "base-image " + child + " <- " + file);
            mergeOne(edge, unresolved, child);
        }
        return true;
    }

    /**
     * Rules 21 / 41: a library's rows are merged once per service that imports the file.
     */
    private void fanOutLibrary(JSONObject edge, String file, String source, List<Unresolved> unresolved) {
        // Code in a library module runs inside the services that import it: the call is
        // theirs, attributed by the import of this very file; with no importer it is
        // nobody's, and the library is never a node.
        Set<String> importers = owner.importersOf(file);
        RuleTrace.hitIf(importers.isEmpty(), names.jvmLibraryModules.contains(source) ? 41 : 21, (names.jvmLibraryModules.contains(source) ? "no-edge " : "no-importer ") + file);
        for (String importer : importers) RuleTrace.hit(21, "attribute " + file + "=" + importer);
        for (String importer : importers) mergeOne(edge, unresolved, importer);
    }

    /**
     * Rule 17: a constant whose value names a module, used at a call site.
     */
    private void mergeNameRef(String section, JSONObject fields, String source, String file, int line) {
        // An enum constant / static final String whose value names a module, used as an
        // argument at a call site: TeaStore's loadBalanceRESTOperation(Service.PERSISTENCE, …)
        // dispatches to the service named "tools.descartes.teastore.persistence" through its
        // own registry. The value equals the module directory, so the callee is knowable
        // statically; only a value that is a module or workload counts, and only from a
        // deployable module (a shared client library's references are not its own calls).
        if (source == null || !names.deployableModules.contains(source)) return;
        String type = fields.optString("type", "");
        String value = owner.nameConstants.get((type.isBlank() ? "" : type + ".") + fields.optString("name", ""));
        if (value == null) return;
        String node = names.resolveModuleName(value);
        if (node != null && !node.equals(source)) {
            RuleTrace.hit(17, "edge " + source + "->" + node);
            addCodeEdge(source, node, edgeType(section, DependencyGraph.classifyKind(node)), file, line);
        }
    }

    /**
     * Rule 56: ingress routing, gateway workload → each destination.
     */
    private void mergeIngressRoute(JSONObject fields, String file, int line) {
        // Rule 56: ingress routing — the gateway workload to each destination it routes to.
        Set<String> gws = names.gatewayWorkloads.getOrDefault(fields.optString("gateway", ""), Set.of());
        String rowNs = fields.optString("namespace", "").trim().toLowerCase(Locale.ROOT);
        names.readerNamespace = rowNs.isEmpty() ? null : rowNs;
        String node = names.matchNodeLoose(fields.optString("host", ""));
        names.readerNamespace = null;
        if (node == null) return;
        for (String gw : gws) {
            if (gw.isBlank() || gw.equals(node)) continue;
            RuleTrace.hit(56, "edge " + gw + "->" + node);
            graph.addNode(gw, DependencyGraph.KIND_GATEWAY);
            addCodeEdge(gw, node, "sync-http", file, line);
        }
    }

    /**
     * Rule 49: a registry name dialled at run time → the one service that registers it, else residue.
     */
    private void mergeRegistryRef(String section, JSONObject fields, String source, String file, int line,
                                      List<Unresolved> unresolved) {
        // Rule 49: a registry name (dialer.Dial("srv-rate"), a literal reaching a dial through
        // a parameter) is resolved by Consul / etcd at run time — to whichever service
        // registered itself under that name. The registering side is code too
        // (Registry.Register(name, …) with const name = "srv-search"): when exactly one
        // service registers the name, the dial is a call to it. With no registrant, or
        // several, nothing in the repository settles it: listed as residue, where the
        // "Resolve names" question asks the operator (2026-10-27; 2026-10-28).
        String name = fields.optString("name", "").trim();
        if (!name.isEmpty() && source != null) {
            Set<String> owners = new LinkedHashSet<>();
            for (String f : names.registryNameFiles.getOrDefault(name, Set.of())) {
                String owner = names.resolveSource(f);
                if (owner != null) owners.add(owner);
            }
        String owner = owners.size() == 1 ? owners.iterator().next() : null;
            if (owner != null && !owner.equals(source) && (names.knownNodes.contains(owner) || names.declaredWorkloads.contains(owner))) {
                RuleTrace.hit(49, "registered " + source + " => " + name + " = " + owner);
                graph.addNode(owner, DependencyGraph.classifyKind(owner));
                addCodeEdge(source, owner, edgeType(section, DependencyGraph.classifyKind(owner)), file, line);
            } else {
                RuleTrace.hit(49, "listed " + source + " => " + name);
                unresolved.add(new Unresolved(section, source, name, file, line));
            }
        }
    }

    /**
     * An env read in code: the env table in this module's scope (③), an appsettings fallback
     * (rule 40) or the read's own default (rule 48) names the host; a declared name only (①).
     */
    private void mergeEnvRead(JSONObject fields, String source, String file, int line) {
        // A code-side config edge is a service reading an env var. When that env var
        // resolves (via the k8s ConfigMap / .env table) to a host that is a known
        // service, the read IS the dependency — this is how an env-indirected target
        // (TRANSACTIONS_API_ADDR -> ledgerwriter) becomes a concrete edge with no
        // runtime data. A bare env read that names no host is a signal, not an edge.
        String env = fields.optString("env", "");
        List<EnvScope.Value> listed = env.isBlank() || source == null ? List.of() : owner.resolveEnvValues(env, source);
        if (listed.size() > 1) {
            for (EnvScope.Value v : listed) {
                DeployedNames.Target t = names.resolveTarget(v.host(), source, false, false, false);
                if (t == null || t.dropped() || t.node().equals(source)) continue;
                String node = t.node();
                String kind = v.kind() != null ? v.kind() : DependencyGraph.classifyKind(node);
                graph.addNode(node, kind);
                addCodeEdge(source, node, edgeType("", kind), file, line);
            }
            return;
        }
        EnvScope.Value value = env.isBlank() ? null : owner.resolveEnvValue(env, source);
        String host = value == null ? null : value.host();
        String raw = host;          // the address as written, port and all
        boolean devSide = false;    // read on the developer's machine, not in the container
        String statedKind = value == null ? null : value.kind();
        // A .NET IConfiguration read whose key the environment does not override falls
        // back to appsettings.json: GrpcSettings:DiscountUrl → https://localhost:5052 in
        // the checked-in file (loopback: nothing), or a real address in another repo.
        if (host == null && fields.has("dotnetkey")) {
            String prop = owner.propertyLookup(fields.optString("dotnetkey", ""));
            if (RuleTrace.hitIf(prop != null, 40, "appsettings " + fields.optString("dotnetkey", ""))) {
                host = stripToHost(prop);
                if (host.isEmpty()) host = null;
                raw = prop;
                devSide = true;
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
                    raw = fallback;
                    devSide = true;
                    if (statedKind == null) statedKind = kindFromScheme(fallback);
                }
            }
        }
        if (host != null && source != null) {
            // Only a declared name: a bare env read of a domain is not a call (AICS hands
            // every service SYSTEM_ADDRESS, its own public address, for the links it puts
            // in mail). An external host needs a call site with the URL.
            DeployedNames.Target t = names.resolveTarget(raw, source, devSide, false, false);
            if (t != null && !t.dropped() && !t.node().equals(source)) {
                String node = t.node();
                String kind = statedKind != null ? statedKind : DependencyGraph.classifyKind(node);
                RuleTrace.hitIf(statedKind != null && !statedKind.equals(DependencyGraph.classifyKind(node)), 39, "kind " + node + "=" + statedKind);
                graph.addNode(node, kind);
                addCodeEdge(source, node, edgeType("", kind), file, line, t.byPort() ? DependencyGraph.CONF_INFERRED : DependencyGraph.CONF_DOCUMENTED);
            }
        }
    }

    /**
     * A call whose URL came from an env var: the call site is the use (④), the table names the host.
     */
    private void mergeEnvCall(String section, JSONObject fields, String source, String file, int line) {
        // An HTTP call whose URL came from an env var (requests.get(f"{PREFIX}/x") with
        // PREFIX = os.getenv("X_API_PREFIX")): the call site is the use, the env table
        // (this module's scope first) or the getenv default names the host.
        String env = fields.optString("env", "");
        String host = env.isBlank() || source == null ? null : owner.resolveEnv(env, source);
        String raw = host;
        boolean devSide = false;
        if (host == null) {
            String fallback = fields.optString("envdefault", "");
            if (!fallback.isBlank()) {
                host = stripToHost(fallback);
                raw = fallback;
                devSide = true;
            }
        }
        if (host == null || host.isEmpty() || source == null) return;
        DeployedNames.Target t = names.resolveTarget(raw, source, devSide, true, true);
        if (t == null || t.dropped()) return;
        if (t.external()) {
            graph.addNode(t.node(), DependencyGraph.KIND_EXTERNAL);
            addCodeEdge(source, t.node(), "external", file, line);
            return;
        }
        String node = t.node();
        if (node.equals(source)) return;
        String kind = DependencyGraph.classifyKind(node);
        graph.addNode(node, kind);
        addCodeEdge(source, node, edgeType(section, kind), file, line, t.byPort() ? DependencyGraph.CONF_INFERRED : DependencyGraph.CONF_DOCUMENTED);
    }

    /**
     * Rule 18: fills a format string's host placeholder from the env table; false when nothing fills it.
     */
    private boolean fillFormatHost(JSONObject fields, String source) {
        // A Java format string whose host placeholder is a getenv with a default:
        // String.format("http://%s/shipping/", getenv("CART_ENDPOINT", "cart")). The env
        // table (Dockerfile ENV, manifest env) fills %s, else the call's own default.
        String env = fields.optString("env", "");
        String host = env.isBlank() || source == null ? null : owner.resolveEnv(env, source);
        if (host == null) {
            String fallback = fields.optString("envdefault", "");
            if (!fallback.isBlank()) host = stripToHost(fallback);
        }
        if (host == null || host.isEmpty()) return false;
        RuleTrace.hit(18, "format " + env + "=" + host);
        fields.put("value", fields.optString("value", "").replaceFirst("%[sd]", java.util.regex.Matcher.quoteReplacement(host)));
        return true;
    }

    /**
     * A {@code @Value} property: resolved through the config files, else its default (rule 4).
     */
    private void mergeProperty(JSONObject fields, String source, String file, int line) {
        // @Value("${catalog.service.host:catalog}"): the property may resolve through
        // the config files, and failing that its default names the host. Only a value
        // that is an address or a known node is taken — a port or a flag is not.
        owner.currentSource = source;
        String value = owner.substitutePlaceholders(fields.optString("property", ""));
        if (value == null || source == null) return;
        // A URL may name an external host; a bare value only a declared name. The property is
        // the module's own config, read where the module runs in development too.
        DeployedNames.Target t = names.resolveTarget(value, source, true, looksLikeUrl(value), false);
        if (t == null || t.dropped()) return;
        if (t.external()) {
            graph.addNode(t.node(), DependencyGraph.KIND_EXTERNAL);
            addCodeEdge(source, t.node(), "external", file, line);
        } else if (!t.node().equals(source)) {
            addCodeEdge(source, t.node(), edgeType("", DependencyGraph.classifyKind(t.node())), file, line,
                    t.byPort() ? DependencyGraph.CONF_INFERRED : DependencyGraph.CONF_DOCUMENTED);
        }
    }

    /**
     * Messaging rows wait for {@link #mergeAsync}: the broker is known only once every config row is in.
     */
    private void deferAsync(JSONObject edge, String section, JSONObject fields, String source, String file, int line,
                                List<Unresolved> unresolved) {
        // --- asynchronous: the edge is service → broker, resolved last (see mergeAsync) ---
        if (source == null) {
            unresolved.add(new Unresolved(section, null, brokerTarget(section, fields), file, line));
            return;
        }
        JSONObject deferred = new JSONObject(edge.toString());
        deferred.put("resolved_source", source);
        asyncRows.add(deferred);
    }

    /**
     * A synchronous row: the callee token (②, {@link #syncTarget}), placeholders filled from the
     * tables (③), then the name table (①, {@link #resolveTarget}); residue when nothing settles it.
     */
    private void mergeSync(String section, JSONObject fields, String source, String file, int line,
                               List<Unresolved> unresolved) {
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
        owner.nameInferred = false;
        owner.currentSource = source;
        String substituted = owner.substitutePlaceholders(rawTarget);
        if (substituted != null) rawTarget = substituted;
        boolean inferredByName = owner.nameInferred;
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
            pathNode = names.resolveServiceFromPath(fields.optString("path", ""));
            if (pathNode == null) pathNode = names.resolveServiceFromPath(pathOf(rawTarget));
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
        // A format-string host whose variable is itself named after the service:
        // http://{user}:8080/check/{id} with USER = os.getenv('USER_HOST', 'user').
        java.util.regex.Matcher formatted = FORMAT_HOST.matcher(fullHost);
        if (formatted.matches()) {
            String node = names.matchNode(formatted.group(1));
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
        // A .NET connection-string key says the target is a database whatever its name.
        if (schemeKind == null && fields.optString("key", "").toLowerCase(Locale.ROOT).startsWith("connectionstrings")) {
            RuleTrace.hit(39, "connectionstrings " + fields.optString("key", ""));
            schemeKind = DependencyGraph.KIND_DB;
        }

        // Convention ①: reserved → nobody; alias → its service; localhost:PORT → the one
        // service on PORT (a module's code and config are read on the developer's machine too:
        // a dev default of http://localhost:9296 in every client, rule 15); a domain → external;
        // a declared name; else, since a dependency the mesh never observed (a DB, an
        // un-exercised service) is still a real dependency, a new node from its own name.
        DeployedNames.Target t = names.resolveTarget(rawTarget, source, true, true, true);
        if (t != null && t.dropped()) return;
        if (t == null) {
            if (pathNode != null && !pathNode.equals(source)) {
                addCodeEdge(source, pathNode, "sync-http", file, line);
                return;
            }
            unresolved.add(new Unresolved(section, source, rawTarget, file, line));
            return;
        }
        if (t.external()) {
            graph.addNode(t.node(), DependencyGraph.KIND_EXTERNAL);
            addCodeEdge(source, t.node(), "external", file, line);
            return;
        }
        String node = t.node();
        if (node.equals(source)) return;
        String kind = schemeKind != null ? schemeKind : DependencyGraph.classifyKind(node);
        RuleTrace.hitIf(schemeKind != null && !schemeKind.equals(DependencyGraph.classifyKind(node)), 26, "kind " + node + "=" + schemeKind);
        graph.addNode(node, kind);
        // A db the service really uses (has persistence code) is documented; a db known
        // only from a datasource URL, with no entity/repository code, is a bare
        // declaration — kept but marked weakest so the graph does not overstate it.
        // A target found only through its variable's name, or only through its port, is a
        // guess of the same rank.
        String confidence = (DependencyGraph.KIND_DB.equals(kind) && !persistenceServices.contains(source))
                || inferredByName || t.byPort()
                ? DependencyGraph.CONF_INFERRED
                : DependencyGraph.CONF_DOCUMENTED;
        addCodeEdge(source, node, edgeType(section, kind), file, line, confidence);
    }


    /** Maps a code section + resolved target kind to a graph edge type. */
    static String edgeType(String section, String targetKind) {
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
                // What the key's name says the value is: the same words as an environment
                // variable's, a flag's or a .NET key's (convention ②, AddressShape.keyKind).
                String key = fields.optString("key", "").replaceAll("\\[\\d+]$", "");
                AddressShape.KeyKind kk = AddressShape.keyKind(key);
                // .NET joins configuration sections with ':' (RabbitMqOptions:HostName), Spring with '.' (rule 58).
                RuleTrace.hitIf(key.indexOf(':') > 0 && kk != AddressShape.KeyKind.NONE && kk != AddressShape.KeyKind.CONNECTION, 58, "options-key " + key + "=" + v);
                switch (kk) {
                    case HOST -> {
                        // The value must be a host too: prefer-ip-address: true is a flag.
                        if (RuleTrace.hitIf(!AddressShape.isHostValue(v), 5, "not-a-host " + key + "=" + v)) return null;
                        RuleTrace.hit(5, "key " + key + "=" + v);
                        return v;
                    }
                    case LIST -> {
                        if (RuleTrace.hitIf(!AddressShape.isHostValue(v), 27, "not-a-list " + key + "=" + v)) return null;
                        RuleTrace.hit(27, "list " + key + "=" + v);
                        return v;
                    }
                    case CONNECTION -> {
                        // A connection string names its host; a bare host:port is one too
                        // (ConnectionStrings:Redis=distributedcache:6379).
                        if (AddressShape.isConnectionString(v)) {
                            RuleTrace.hit(key.toLowerCase(Locale.ROOT).startsWith("connectionstrings") ? 32 : 58, "connstr " + key);
                            return AddressShape.host(v);
                        }
                        return RuleTrace.hitIf(v.matches("[^\\s/=;]+(:\\d{2,5})?"), 38, "connectionstrings-host " + key + "=" + v) ? v : null;
                    }
                    case ADDRESS -> {
                        // A bare fully-qualified host under an endpoint key (aliyun.oss.endpoint:
                        // oss-cn-shenzhen.aliyuncs.com) names an external service; under a URL or
                        // URI key a value with no scheme is a relative reference (RFC 3986 §4.2),
                        // href: contact.php. A bare single word is the target only when the
                        // deployment declares it (elasticsearch.url=elasticsearch).
                        boolean endpointKey = key.toLowerCase(Locale.ROOT).matches(".*endpoints?$");
                        if (RuleTrace.hitIf(endpointKey && BARE_FQDN.matcher(v.trim()).matches(), 27, "fqdn " + key + "=" + v)) return v;
                        if (v.trim().matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(:\\d{2,5})?")) {
                            if (RuleTrace.hitIf(names.declaredName(v.trim().replaceAll(":\\d+$", "")), 45, "accept " + key + "=" + v)) return v.trim();
                            RuleTrace.hit(45, "drop " + key + "=" + v);
                        }
                        return null;
                    }
                    default -> {
                        // The Options pattern: a connection string is one wherever it sits (rule 58).
                        if (RuleTrace.hitIf(AddressShape.isConnectionString(v), 58, "options-connstr " + key)) return AddressShape.host(v);
                        return null;
                    }
                }
            }
            default -> {
                return null;
            }
        }
    }

    static boolean endsWithHostKey(String key) {
        return AddressShape.keyKind(key) != AddressShape.KeyKind.NONE;
    }

    /** A Python/Java format placeholder standing in for the whole host: {user}, {cart_host}. */
    private static final java.util.regex.Pattern FORMAT_HOST =
            java.util.regex.Pattern.compile("\\{([a-z][a-z0-9_]*)}");

    private static final java.util.regex.Pattern SPRING_IMPORT_PREFIX =
            java.util.regex.Pattern.compile("^(?i)(?:optional:)?configserver:");

    /** The explicit port of a URL-ish target ({@code http://localhost:9296/x} → {@code 9296}), or null. */
    static String portOf(String rawTarget) {
        return AddressShape.port(rawTarget);
    }

    /** The path part of a URL-ish target ({@code http://%s:%s/catalog/} → {@code /catalog/}), or "". */
    static String pathOf(String rawTarget) {
        if (rawTarget == null) return "";
        String s = rawTarget.trim();
        int scheme = s.indexOf("://");
        if (scheme < 0) return "";
        int slash = s.indexOf('/', scheme + 3);
        return slash < 0 ? "" : s.substring(slash);
    }

    static boolean isLoopback(String host) {
        if (host == null) return false;
        // The platform's own addresses are no more a dependency than loopback is: the
        // cloud metadata server (metadata.google.internal, 169.254.169.254) and anything
        // under the reserved .internal domain (2026-10-26).
        return AddressShape.isLoopback(host);
    }

    /** The broker destination (topic / exchange / queue) for an async section. */
    static String brokerTarget(String section, JSONObject fields) {
        return switch (section) {
            case "kafka-produce", "kafka-consume" -> firstNonBlank(fields, "topic");
            case "rabbit-produce" -> firstNonBlank(fields, "exchange", "routing", "value");
            case "rabbit-consume" -> firstNonBlank(fields, "queue");
            default -> null;
        };
    }

    // ---------- name normalisation ----------

    /**
     * Whether a bare word names something the deployment declares: a node of the graph
     * vocabulary, or a Compose service the manifests' vocabulary left out (rule 11 keeps
     * the k8s spelling when both exist; the Compose name is still a declaration, not a
     * guess). Used only to accept a bare word under an address key as a target.
     */
    /** The host of a mounted config value: {@code http://prometheus:9090} or {@code app:8080}. */
    static String mountedHost(String v) {
        String h = AddressShape.host(v);
        return h.matches("[a-z0-9][a-z0-9.-]*") ? h : null;
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

    /**
     * The full host of a target, dots preserved (scheme/port/path/userinfo/quotes
     * removed): {@code http://catalogue/x} -> {@code catalogue},
     * {@code https://api.github.com/v3} -> {@code api.github.com}. A ${...}/{{...}}
     * placeholder is not a usable host and yields "".
     */
    static String stripToHost(String raw) {
        return AddressShape.host(raw);
    }

    static boolean looksLikeUrl(String v) {
        return AddressShape.hasScheme(v);
    }

    static String firstNonBlank(JSONObject fields, String... keys) {
        for (String key : keys) {
            String v = fields.optString(key, "");
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
    private final DeployedNames names;
    private final Ownership owner;

    private CodeGraphMerger(DependencyGraph graph, String repoName) {
        this.graph = graph;
        this.names = new DeployedNames(graph, repoName);
        this.owner = new Ownership(names);
    }

}
