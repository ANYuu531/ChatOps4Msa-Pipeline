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
    private static final Set<String> META_SECTIONS = Set.of("service-root", "env-address");

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
    /**
     * Normalised service key -> node, for resolving a call whose host is a variable
     * but whose URL PATH names the callee by convention (Spring: {@code
     * /api/v1/orderservice/...} -> order-service). Keys are letters-only forms of the
     * known service names, with and without a {@code ts-} prefix / {@code service} suffix.
     */
    private final Map<String, String> serviceKeys = new LinkedHashMap<>();

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
        // Pass 1: the service inventory, so knownNodes is complete before env-address
        // resolution below can prefer a host that is actually a known service.
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"service-root".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String dir = fields.optString("dir", "");
            String name = clean(fields.optString("name", ""));
            if (!dir.isBlank() && isPlausibleName(name)) {
                serviceDirs.put(dir.replace('\\', '/'), name);
                if (greenfield) knownNodes.add(name);
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
        }

        // In greenfield the inventory IS the graph's node set: add every service so
        // even a service nobody calls still appears (an accurate, if isolated, node).
        if (greenfield) {
            for (String name : serviceDirs.values()) {
                graph.addNode(name, DependencyGraph.classifyKind(name));
            }
        }

        // Build the path -> service lookup from the final node vocabulary, so a
        // path-encoded callee (see serviceKeys) can be resolved later.
        for (String node : knownNodes) registerServiceKeys(node);
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
        return unresolved;
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
                || META_SECTIONS.contains(section)) return;

        // docker-compose depends_on names BOTH endpoints explicitly (source_service ->
        // target_service), so the source comes from the field, not the file's module.
        // This is what draws service -> config-server / discovery-server even when the
        // client-side URLs live in an externalised config repo the scan never sees.
        if ("compose-dependency".equals(section)) {
            String src = resolveComposeService(fields.optString("source_service", ""));
            String tgt = resolveComposeService(fields.optString("target_service", ""));
            if (src != null && tgt != null && !src.equals(tgt)) {
                addCodeEdge(src, tgt, edgeType("", DependencyGraph.classifyKind(tgt)), file, line);
            }
            return;
        }

        String source = resolveSource(file);

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
        String pathNode = (source == null) ? null : resolveServiceFromPath(fields.optString("path", ""));

        String rawTarget = syncTarget(section, fields);
        if (rawTarget == null || source == null) {
            if (pathNode != null && !pathNode.equals(source)) {
                addCodeEdge(source, pathNode, "sync-http", file, line);
                return;
            }
            unresolved.add(new Unresolved(section, source, rawTarget, file, line));
            return;
        }

        // The host may be an env placeholder — http://${BALANCES_API_ADDR}/balances.
        // stripToHost cannot resolve it, but the env -> host table can: this is what
        // draws ledgerwriter -> balancereader with no runtime data.
        String envHost = hostFromEnvPlaceholder(rawTarget);
        if (envHost != null) {
            String node = matchNode(envHost);
            if (node != null && !node.equals(source)) {
                addCodeEdge(source, node, edgeType(section, DependencyGraph.classifyKind(node)), file, line);
                return;
            }
        }

        String fullHost = stripToHost(rawTarget);
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
        String confidence = DependencyGraph.KIND_DB.equals(kind) && !persistenceServices.contains(source)
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
                // Only a config value that looks like a URL is a dependency target.
                String v = firstNonBlank(fields, "value");
                return (v != null && looksLikeUrl(v)) ? v : null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The host an env-var placeholder resolves to via the {@code env-address} table,
     * or null. Matches {@code ${NAME}} and {@code {{NAME}}} against the table
     * case-insensitively, so a URL whose host is externalised to config
     * ({@code http://${BALANCES_API_ADDR}/…}) still yields a concrete target.
     */
    private String hostFromEnvPlaceholder(String rawTarget) {
        if (rawTarget == null || envAddress.isEmpty()) return null;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\$\\{([^}]+)}|\\{\\{([^}]+)}}").matcher(rawTarget);
        if (!m.find()) return null;
        String name = m.group(1) != null ? m.group(1) : m.group(2);
        if (name == null) return null;
        name = name.trim();
        // Property paths like ${app.balances.url} are not an env key; take the last
        // segment too, so ${BALANCES_API_ADDR} and ${env.BALANCES_API_ADDR} both hit.
        String host = envLookup(name);
        if (host == null && name.contains(".")) host = envLookup(name.substring(name.lastIndexOf('.') + 1));
        return host;
    }

    private String envLookup(String name) {
        String host = envAddress.get(name);
        if (host != null) return host;
        for (Map.Entry<String, String> e : envAddress.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
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
        String node = matchNode(raw);
        if (node != null) return node;
        String label = cleanLabel(raw);
        return (label != null && isPlausibleName(label) && !SOURCE_STOP.contains(label)) ? label : null;
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
