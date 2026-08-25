package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The canonical dependency-graph model — the single source of truth every
 * renderer (emitter) consumes.
 *
 * The dependency evidence is scattered across the pipeline in many shapes
 * (structured {@code EdgeLedger} from code, raw Istio Prometheus JSON from
 * runtime, LLM prose from the docs). This model is the one structured form they
 * all normalise into, so that a renderer never has to know where an edge came
 * from — it only serialises this graph into some output format (Mermaid, DOT,
 * Cytoscape JSON, …).
 *
 * Phase 1 populates it from runtime edges only (see {@link RuntimeGraphBuilder}),
 * but the model already carries the provenance / confidence fields the later
 * phases need, so merging in code and doc edges is additive.
 *
 * The JSON shape produced by {@link #toJson()} is the "Canonical Graph JSON" the
 * visualization plan defines, so it can be handed straight to a future front-end
 * or persisted without another translation step.
 */
public class DependencyGraph {

    /** How a node participates in the mesh. Drives the visual encoding (shape/colour). */
    public static final String KIND_SERVICE = "service";
    public static final String KIND_DB = "db";
    public static final String KIND_QUEUE = "queue";
    public static final String KIND_EXTERNAL = "external";
    public static final String KIND_GATEWAY = "gateway";

    /** Where the evidence for an edge came from. An edge can have several. */
    public static final String PROV_RUNTIME = "runtime";
    public static final String PROV_CODE = "code";
    public static final String PROV_DOC = "doc";

    /** How strongly the edge is believed. observed (seen on the wire) > documented > inferred. */
    public static final String CONF_OBSERVED = "observed";
    public static final String CONF_DOCUMENTED = "documented";
    public static final String CONF_INFERRED = "inferred";

    /** Substrings that identify a datastore workload by convention (visual hint only). */
    private static final String[] DB_HINTS = {
            "-db", "database", "mongo", "mysql", "postgres", "postgresql",
            "redis", "cassandra", "mariadb", "couch", "elasticsearch", "hsqldb"};
    /** Substrings that identify a message broker (visual hint only). */
    private static final String[] QUEUE_HINTS = {"rabbitmq", "kafka", "nats", "activemq", "rabbit", "amqp"};

    /**
     * Classifies a workload/host name into a node kind by naming convention. Shared
     * by every builder so runtime and code nodes are typed the same way. Heuristic
     * and visual only — it never affects which edges exist.
     */
    public static String classifyKind(String name) {
        if (name == null || name.isBlank()) return KIND_SERVICE;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("ingressgateway") || n.contains("egressgateway")) return KIND_GATEWAY;
        for (String hint : QUEUE_HINTS) if (n.contains(hint)) return KIND_QUEUE;
        for (String hint : DB_HINTS) if (n.contains(hint)) return KIND_DB;
        return KIND_SERVICE;
    }

    public static class Node {
        public final String id;
        public String kind;
        public String namespace;

        /**
         * Whether this workload is actually running in the cluster, per the K8s
         * Deployment inventory. {@code null} = not determined or not applicable
         * (e.g. an external host or a checkpoint captured before k8s state existed);
         * {@code TRUE} = a matching Deployment was found; {@code FALSE} = a service
         * the graph references but the cluster does not run (drawn greyed/dashed).
         * Set by {@link K8sGraphBuilder}.
         */
        public Boolean deployed;
        /** Deployment creationTimestamp (ISO-8601), when the workload is deployed. */
        public String deployedAt;
        /** The container image of the deployed workload, when known. */
        public String image;
        /** Replica health as "ready/desired" (e.g. "3/3"), when known. */
        public String replicas;
        /**
         * The tier this node is drawn in: 0 = ingress/entry, rising with call depth,
         * with data stores and external hosts in the last tiers. {@code null} until
         * {@link GraphLayerAssigner} has run (and on an old checkpoint), in which case
         * the emitters fall back to their previous free layout.
         */
        public Integer layer;

        Node(String id, String kind, String namespace) {
            this.id = id;
            this.kind = kind;
            this.namespace = namespace;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("kind", kind);
            if (namespace != null && !namespace.isBlank()) json.put("namespace", namespace);
            if (deployed != null) json.put("deployed", deployed);
            if (deployedAt != null && !deployedAt.isBlank()) json.put("deployedAt", deployedAt);
            if (image != null && !image.isBlank()) json.put("image", image);
            if (replicas != null && !replicas.isBlank()) json.put("replicas", replicas);
            if (layer != null) json.put("layer", layer);
            return json;
        }
    }

    public static class Edge {
        public final String source;
        public final String target;
        public String type;
        public final Set<String> provenance = new LinkedHashSet<>();
        public String confidence;
        public boolean runtimeObserved;
        /** Runtime request count; 0 when unknown (e.g. a code-only edge). */
        public long count;
        public final List<String> evidence = new ArrayList<>();

        Edge(String source, String target) {
            this.source = source;
            this.target = target;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("source", source);
            json.put("target", target);
            json.put("type", type);
            json.put("provenance", new JSONArray(provenance));
            json.put("confidence", confidence);
            json.put("runtimeObserved", runtimeObserved);
            if (count > 0) json.put("count", count);
            if (!evidence.isEmpty()) json.put("evidence", new JSONArray(evidence));
            return json;
        }
    }

    private final String namespace;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();

    public DependencyGraph(String namespace) {
        this.namespace = namespace == null ? "" : namespace;
    }

    public String getNamespace() {
        return namespace;
    }

    /**
     * Ensures a node exists, upgrading its kind if a more specific one is offered.
     * A node named as an edge endpoint defaults to {@link #KIND_SERVICE}; a later,
     * more specific classification (db/queue/external/gateway) wins.
     */
    public Node addNode(String id, String kind) {
        Node node = nodes.get(id);
        if (node == null) {
            node = new Node(id, kind == null ? KIND_SERVICE : kind, namespace);
            nodes.put(id, node);
        } else if (kind != null && !KIND_SERVICE.equals(kind) && KIND_SERVICE.equals(node.kind)) {
            node.kind = kind;
        }
        return node;
    }

    /**
     * Adds an edge, merging into an existing (source,target) instead of duplicating
     * it: provenance and evidence are unioned, the request count is kept as the max,
     * and runtimeObserved is OR-ed. This is what lets Phase 2 union code/doc edges
     * onto the same runtime edge without creating parallel arrows.
     */
    public Edge addEdge(String source, String target, String type,
                        String provenance, String confidence,
                        boolean runtimeObserved, long count, String evidence) {
        String key = source + "\u0000" + target;
        Edge edge = edges.get(key);
        if (edge == null) {
            edge = new Edge(source, target);
            edge.type = type;
            edge.confidence = confidence;
            edges.put(key, edge);
        } else {
            // A stronger confidence wins; observed beats documented beats inferred.
            if (rank(confidence) > rank(edge.confidence)) edge.confidence = confidence;
        }
        if (provenance != null) edge.provenance.add(provenance);
        edge.runtimeObserved |= runtimeObserved;
        if (count > edge.count) edge.count = count;
        if (evidence != null && !evidence.isBlank() && !edge.evidence.contains(evidence)) {
            edge.evidence.add(evidence);
        }
        return edge;
    }

    private static int rank(String confidence) {
        if (CONF_OBSERVED.equals(confidence)) return 3;
        if (CONF_DOCUMENTED.equals(confidence)) return 2;
        if (CONF_INFERRED.equals(confidence)) return 1;
        return 0;
    }

    /** Removes a node and every edge incident to it. A no-op when the id is absent. */
    public void removeNode(String id) {
        if (id == null || nodes.remove(id) == null) return;
        edges.values().removeIf(e -> e.source.equals(id) || e.target.equals(id));
    }

    /**
     * Merges {@code fromId} into {@code toId}: every edge touching {@code fromId} is
     * redirected onto {@code toId} (its provenance, evidence, confidence, observed flag
     * and count folded onto any existing edge exactly as {@link #addEdge} would), then
     * {@code fromId} and its now-stale edges are dropped. A self-edge produced by the
     * redirect is discarded. A no-op when {@code fromId} is absent or equals {@code toId}.
     *
     * This is how {@code GraphNormalizer} collapses a documentation/code alias
     * (e.g. {@code api-gateway-controller}) onto the real workload it names, so the alias
     * does not linger as a duplicate phantom node while its evidence is preserved on the
     * real edge.
     */
    public void renameNode(String fromId, String toId) {
        if (fromId == null || toId == null || fromId.equals(toId) || !nodes.containsKey(fromId)) return;
        addNode(toId, KIND_SERVICE);
        List<Edge> incident = new ArrayList<>();
        for (Edge e : edges.values()) {
            if (e.source.equals(fromId) || e.target.equals(fromId)) incident.add(e);
        }
        for (Edge e : incident) {
            String s = e.source.equals(fromId) ? toId : e.source;
            String t = e.target.equals(fromId) ? toId : e.target;
            if (s.equals(t)) continue;
            Edge merged = addEdge(s, t, e.type, null, e.confidence, e.runtimeObserved, e.count, null);
            merged.provenance.addAll(e.provenance);
            for (String ev : e.evidence) if (!merged.evidence.contains(ev)) merged.evidence.add(ev);
        }
        removeNode(fromId);
    }

    public Collection<Node> getNodes() {
        return nodes.values();
    }

    public Collection<Edge> getEdges() {
        return edges.values();
    }

    public boolean isEmpty() {
        return edges.isEmpty();
    }

    /** The Canonical Graph JSON — the shape the later phases (DOT, Cytoscape) reuse. */
    public JSONObject toJson() {
        JSONArray nodesJson = new JSONArray();
        for (Node node : nodes.values()) nodesJson.put(node.toJson());
        JSONArray edgesJson = new JSONArray();
        for (Edge edge : edges.values()) edgesJson.put(edge.toJson());
        return new JSONObject()
                .put("namespace", namespace)
                .put("nodes", nodesJson)
                .put("edges", edgesJson);
    }
}
