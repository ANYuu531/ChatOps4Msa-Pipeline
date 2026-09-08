package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The deterministic half of the report Q&amp;A: the facts the graph can state about
 * whatever the question names, written out by code so the model never has to read
 * them off a picture or recall them from prose.
 *
 * The same rule that took the report's infrastructure section away from the model
 * applies here. "Who calls ledgerwriter", "was that edge observed", "how many
 * connections", "what has to be running before frontend starts" — every one of these
 * is a lookup or a traversal on {@link DependencyGraph}, and a lookup done by the model
 * is a lookup that can come back wrong. So the grounding computes them and hands the
 * model text it only has to phrase; the prompt tells it this block outranks the
 * retrieved passages when the two disagree.
 *
 * What is produced for a question:
 * <ul>
 *   <li>a global summary (counts, tiers, an implied start-up order) — always;</li>
 *   <li>a fact sheet per node the question names: its edges in both directions with
 *       provenance / confidence / observed / count / evidence, plus the transitive
 *       closure each way;</li>
 *   <li>for the first two named nodes, how they relate: a direct edge or the shortest
 *       directed path.</li>
 * </ul>
 */
public final class GraphGrounding {

    /** Fact sheets for more nodes than this would drown the question they came from. */
    static final int MAX_FACT_SHEETS = 4;
    /** A transitive listing is cut here; the count is still stated. */
    static final int MAX_TRANSITIVE = 40;

    private GraphGrounding() {
    }

    /** Everything the graph can say about the question, as one Markdown block. */
    public static String ground(DependencyGraph graph, String question) {
        return ground(graph, question, List.of());
    }

    /**
     * @param extraNodeIds nodes resolved by another route (the query planner's
     *                     arguments), whose fact sheets are wanted even though the
     *                     question did not spell their id — e.g. the model mapped
     *                     "the login service" onto {@code userservice}
     */
    public static String ground(DependencyGraph graph, String question, Collection<String> extraNodeIds) {
        if (graph == null) return "No dependency graph is available for this report.\n";
        StringBuilder sb = new StringBuilder();
        sb.append(summary(graph));

        List<DependencyGraph.Node> named = new ArrayList<>(mentionedNodes(question, graph));
        if (extraNodeIds != null) {
            for (String id : extraNodeIds) {
                for (DependencyGraph.Node n : graph.getNodes()) {
                    if (n.id.equals(id) && !named.contains(n)) named.add(n);
                }
            }
        }
        if (named.isEmpty()) {
            sb.append("\nThe question names no node of this graph. Node ids, for reference: ")
                    .append(String.join(", ", ids(graph.getNodes()))).append("\n");
            return sb.toString();
        }
        for (int i = 0; i < named.size() && i < MAX_FACT_SHEETS; i++) {
            sb.append('\n').append(factSheet(graph, named.get(i)));
        }
        if (named.size() >= 2) {
            sb.append('\n').append(relation(graph, named.get(0), named.get(1)));
        }
        return sb.toString();
    }

    // ---------- mention detection ----------

    /**
     * The graph nodes the question names, in order of first mention.
     *
     * A node is matched by its id and by loose spellings of it: separators as spaces
     * or dropped ({@code accounts-db}, {@code accounts db}, {@code accountsdb}), and a
     * core with the conventional {@code ts-} prefix / {@code -service} suffix removed
     * ({@code ts-order-service} → {@code order}). Matches are bounded by ASCII
     * alphanumerics only, so {@code 請問frontend依賴誰} still names {@code frontend}
     * while {@code frontends} does not.
     */
    public static List<DependencyGraph.Node> mentionedNodes(String question, DependencyGraph graph) {
        List<DependencyGraph.Node> out = new ArrayList<>();
        if (question == null || question.isBlank() || graph == null) return out;
        String q = question.toLowerCase(Locale.ROOT);

        Map<DependencyGraph.Node, Integer> firstAt = new LinkedHashMap<>();
        for (DependencyGraph.Node node : graph.getNodes()) {
            int best = -1;
            for (String alias : aliases(node.id)) {
                java.util.regex.Matcher m = boundary(alias).matcher(q);
                if (m.find() && (best < 0 || m.start() < best)) best = m.start();
            }
            if (best >= 0) firstAt.put(node, best);
        }
        // Role words: "前端" / "the gateway" name a node by what it is, not by its id.
        for (Map.Entry<Pattern, Pattern> synonym : SYNONYMS.entrySet()) {
            java.util.regex.Matcher m = synonym.getKey().matcher(q);
            if (!m.find()) continue;
            for (DependencyGraph.Node node : graph.getNodes()) {
                if (firstAt.containsKey(node)) continue;
                boolean byId = synonym.getValue().matcher(node.id.toLowerCase(Locale.ROOT)).find();
                boolean byKind = synonym == GATEWAY_ENTRY && DependencyGraph.KIND_GATEWAY.equals(node.kind);
                if (byId || byKind) firstAt.put(node, m.start());
            }
        }

        List<Map.Entry<DependencyGraph.Node, Integer>> entries = new ArrayList<>(firstAt.entrySet());
        entries.sort((a, b) -> {
            int byPos = Integer.compare(a.getValue(), b.getValue());
            // Same start: the longer id is the more specific mention (accounts-db over db).
            return byPos != 0 ? byPos : Integer.compare(b.getKey().id.length(), a.getKey().id.length());
        });
        for (Map.Entry<DependencyGraph.Node, Integer> e : entries) out.add(e.getKey());
        return out;
    }

    /**
     * Role words → the ids they usually denote. Kept to the two roles every system has
     * and every reader names in their own language; anything more specific is the
     * model planner's job, since it sees the id list.
     */
    private static final Map<Pattern, Pattern> SYNONYMS = new LinkedHashMap<>();
    private static final Map.Entry<Pattern, Pattern> GATEWAY_ENTRY;

    static {
        SYNONYMS.put(Pattern.compile("前端|front[- ]?end(?!s)|the ui\\b|網頁", Pattern.CASE_INSENSITIVE),
                Pattern.compile("^(frontend|front-end|web|ui|www)$|frontend"));
        Pattern gatewayWord = Pattern.compile("閘道|入口|gateway|ingress|api ?gw\\b", Pattern.CASE_INSENSITIVE);
        SYNONYMS.put(gatewayWord, Pattern.compile("gateway|ingress"));
        Map.Entry<Pattern, Pattern> gw = null;
        for (Map.Entry<Pattern, Pattern> e : SYNONYMS.entrySet()) if (e.getKey() == gatewayWord) gw = e;
        GATEWAY_ENTRY = gw;
    }

    /**
     * Replaces each named node with the placeholder the router's examples use
     * ({@code X}, {@code Y}, {@code Z}), so "frontend 依賴誰" is compared as "X 依賴誰".
     *
     * Calibration showed why: a real id in the question adds a direction of its own to
     * the vector and pulled every node-bearing question 0.1–0.2 below the placeholder
     * examples, while the id-free intents scored fine. The ids are already known from
     * {@link #mentionedNodes}, so masking costs one more embedding of a short string.
     */
    public static String maskMentions(String question, List<DependencyGraph.Node> mentioned) {
        if (question == null || mentioned == null || mentioned.isEmpty()) return question;
        String[] placeholders = {" X ", " Y ", " Z "};
        String out = question;
        for (int i = 0; i < mentioned.size() && i < placeholders.length; i++) {
            DependencyGraph.Node node = mentioned.get(i);
            for (String alias : aliases(node.id)) out = boundary(alias).matcher(out).replaceAll(placeholders[i]);
            for (Map.Entry<Pattern, Pattern> synonym : SYNONYMS.entrySet()) {
                boolean byId = synonym.getValue().matcher(node.id.toLowerCase(Locale.ROOT)).find();
                boolean byKind = synonym == GATEWAY_ENTRY && DependencyGraph.KIND_GATEWAY.equals(node.kind);
                if (byId || byKind) out = synonym.getKey().matcher(out).replaceAll(placeholders[i]);
            }
        }
        // Spaced like the examples ("X 依賴誰？"), with no space left before punctuation.
        return out.replaceAll("\\s+", " ").replaceAll("\\s+([?？!！,，.。:：;；])", "$1").trim();
    }

    static Set<String> aliases(String id) {
        Set<String> out = new LinkedHashSet<>();
        String lower = id.toLowerCase(Locale.ROOT);
        addSpellings(out, lower);
        String core = lower;
        if (core.startsWith("ts-")) core = core.substring(3);
        core = core.replaceAll("-(service|svc|srv)$", "");
        if (!core.equals(lower) && core.length() >= 4) addSpellings(out, core);
        return out;
    }

    private static void addSpellings(Set<String> out, String name) {
        if (name.isBlank()) return;
        out.add(name);
        String spaced = name.replaceAll("[-_.]+", " ").trim();
        if (!spaced.equals(name)) out.add(spaced);
        String joined = name.replaceAll("[-_.]+", "");
        if (!joined.equals(name) && joined.length() >= 4) out.add(joined);
    }

    private static Pattern boundary(String alias) {
        return Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(alias) + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    }

    // ---------- fact sheets ----------

    /** One node: what it is, what it calls, what calls it, and both transitive closures. */
    public static String factSheet(DependencyGraph graph, DependencyGraph.Node node) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Node: ").append(node.id).append('\n');
        sb.append("- Kind: ").append(kindWord(node.kind)).append('\n');
        sb.append("- Deployed: ").append(deployedWord(graph, node)).append('\n');
        if (node.image != null && !node.image.isBlank()) sb.append("- Image: ").append(node.image).append('\n');
        if (node.replicas != null && !node.replicas.isBlank()) sb.append("- Replicas (ready/desired): ").append(node.replicas).append('\n');
        if (node.deployedAt != null && !node.deployedAt.isBlank()) sb.append("- Created: ").append(node.deployedAt).append('\n');
        if (node.layer != null) sb.append("- Tier: ").append(node.layer).append(" (0 = entry; deeper tiers are called later)\n");

        List<DependencyGraph.Edge> out = new ArrayList<>();
        List<DependencyGraph.Edge> in = new ArrayList<>();
        for (DependencyGraph.Edge e : graph.getEdges()) {
            if (e.source.equals(node.id)) out.add(e);
            if (e.target.equals(node.id)) in.add(e);
        }
        sb.append("- Depends on (outgoing edges): ").append(out.isEmpty() ? "none" : out.size()).append('\n');
        for (DependencyGraph.Edge e : out) sb.append("  - ").append(edgeLine(graph, e)).append('\n');
        sb.append("- Depended on by (incoming edges): ").append(in.isEmpty() ? "none" : in.size()).append('\n');
        for (DependencyGraph.Edge e : in) sb.append("  - ").append(edgeLine(graph, e)).append('\n');

        Map<String, Integer> needs = closure(graph, node.id, true);
        Map<String, Integer> impacted = closure(graph, node.id, false);
        sb.append("- Everything it transitively depends on (must be reachable for it to work fully): ")
                .append(listClosure(needs)).append('\n');
        sb.append("- Everything that transitively depends on it (impacted if it fails or changes): ")
                .append(listClosure(impacted)).append('\n');
        return sb.toString();
    }

    /** How two named nodes relate: a direct edge either way, else the shortest directed path. */
    public static String relation(DependencyGraph graph, DependencyGraph.Node a, DependencyGraph.Node b) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Relation: ").append(a.id).append(" and ").append(b.id).append('\n');
        boolean direct = false;
        for (DependencyGraph.Edge e : graph.getEdges()) {
            if ((e.source.equals(a.id) && e.target.equals(b.id)) || (e.source.equals(b.id) && e.target.equals(a.id))) {
                sb.append("- Direct edge: ").append(edgeLine(graph, e)).append('\n');
                direct = true;
            }
        }
        if (direct) return sb.toString();
        List<String> ab = shortestPath(graph, a.id, b.id);
        List<String> ba = shortestPath(graph, b.id, a.id);
        if (ab == null && ba == null) {
            sb.append("- No direct edge and no directed path between them in either direction.\n");
        } else {
            sb.append("- No direct edge between them.\n");
            if (ab != null) sb.append("- Shortest path ").append(String.join(" -> ", ab)).append('\n');
            if (ba != null) sb.append("- Shortest path ").append(String.join(" -> ", ba)).append('\n');
        }
        return sb.toString();
    }

    // ---------- global summary ----------

    /** Counts, tiers, isolated nodes and the start-up order the tiers imply. */
    public static String summary(DependencyGraph graph) {
        StringBuilder sb = new StringBuilder();
        String ns = graph.getNamespace();
        sb.append("## Graph summary").append(ns == null || ns.isBlank() ? " (greenfield: static, no cluster)" : " — namespace " + ns).append('\n');

        Map<String, Integer> kinds = new TreeMap<>();
        for (DependencyGraph.Node n : graph.getNodes()) kinds.merge(kindWord(n.kind), 1, Integer::sum);
        sb.append("- Nodes: ").append(graph.getNodes().size());
        if (!kinds.isEmpty()) {
            sb.append(" (");
            boolean first = true;
            for (Map.Entry<String, Integer> k : kinds.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(k.getValue()).append(' ').append(k.getKey());
                first = false;
            }
            sb.append(')');
        }
        sb.append('\n');

        int observed = 0, documented = 0, inferred = 0;
        for (DependencyGraph.Edge e : graph.getEdges()) {
            if (e.runtimeObserved) observed++;
            else if (DependencyGraph.CONF_DOCUMENTED.equals(e.confidence)) documented++;
            else inferred++;
        }
        sb.append("- Edges: ").append(graph.getEdges().size())
                .append(" (runtime-observed: ").append(observed)
                .append("; declared in code/docs with usage evidence but not observed: ").append(documented)
                .append("; mentioned only, no usage evidence: ").append(inferred).append(")\n");

        List<String> undeployed = new ArrayList<>();
        for (DependencyGraph.Node n : graph.getNodes()) if (Boolean.FALSE.equals(n.deployed)) undeployed.add(n.id);
        if (!undeployed.isEmpty()) {
            sb.append("- Referenced but NOT deployed in the cluster: ").append(String.join(", ", undeployed)).append('\n');
        }

        Set<String> touched = new LinkedHashSet<>();
        for (DependencyGraph.Edge e : graph.getEdges()) {
            touched.add(e.source);
            touched.add(e.target);
        }
        List<String> isolated = new ArrayList<>();
        for (DependencyGraph.Node n : graph.getNodes()) if (!touched.contains(n.id)) isolated.add(n.id);
        if (!isolated.isEmpty()) {
            sb.append("- Nodes with no edges at all (the extraction found nothing for them): ")
                    .append(String.join(", ", isolated)).append('\n');
        }

        TreeMap<Integer, List<String>> tiers = new TreeMap<>();
        for (DependencyGraph.Node n : graph.getNodes()) {
            if (n.layer == null || !touched.contains(n.id)) continue;
            tiers.computeIfAbsent(n.layer, k -> new ArrayList<>()).add(n.id);
        }
        if (!tiers.isEmpty()) {
            sb.append("- Tiers (0 = entry; each tier calls the ones below it):\n");
            for (Map.Entry<Integer, List<String>> t : tiers.entrySet()) {
                sb.append("  - tier ").append(t.getKey()).append(": ").append(String.join(", ", t.getValue())).append('\n');
            }
            sb.append("- Implied start-up / deployment order (deepest tier first, so every service finds what it calls already running): ");
            boolean first = true;
            for (Map.Entry<Integer, List<String>> t : tiers.descendingMap().entrySet()) {
                if (!first) sb.append(" → ");
                sb.append('[').append(String.join(", ", t.getValue())).append(']');
                first = false;
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------- helpers ----------

    static String edgeLine(DependencyGraph graph, DependencyGraph.Edge e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.source).append(" -> ").append(e.target)
                .append(" [").append(e.type == null ? "sync-http" : e.type).append("]")
                .append("; confidence=").append(e.confidence == null ? "unknown" : e.confidence)
                .append("; provenance=").append(e.provenance.isEmpty() ? "unknown" : String.join("+", e.provenance))
                .append("; runtime observed: ");
        if (e.runtimeObserved) {
            sb.append("YES");
            if (e.count > 0) {
                sb.append(" (").append(e.count).append(isDbTarget(graph, e) ? " TCP connections — a connection count, not requests)" : " requests)");
            }
        } else {
            sb.append("no");
        }
        if (!e.evidence.isEmpty()) {
            sb.append("; evidence: ").append(e.evidence.get(0));
            if (e.evidence.size() > 1) sb.append(" (+").append(e.evidence.size() - 1).append(" more)");
        }
        return sb.toString();
    }

    private static boolean isDbTarget(DependencyGraph graph, DependencyGraph.Edge e) {
        for (DependencyGraph.Node n : graph.getNodes()) {
            if (n.id.equals(e.target)) return DependencyGraph.KIND_DB.equals(n.kind);
        }
        return "db".equals(e.type);
    }

    /** BFS over outgoing ({@code forward}) or incoming edges; node id → depth, excluding the start. */
    static Map<String, Integer> closure(DependencyGraph graph, String start, boolean forward) {
        Map<String, List<String>> adj = new HashMap<>();
        for (DependencyGraph.Edge e : graph.getEdges()) {
            String from = forward ? e.source : e.target;
            String to = forward ? e.target : e.source;
            adj.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        }
        Map<String, Integer> depth = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        depth.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String next : adj.getOrDefault(cur, List.of())) {
                if (depth.containsKey(next)) continue;
                depth.put(next, depth.get(cur) + 1);
                queue.add(next);
            }
        }
        depth.remove(start);
        return depth;
    }

    private static String listClosure(Map<String, Integer> closure) {
        if (closure.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder().append(closure.size()).append(" — ");
        int shown = 0;
        for (Map.Entry<String, Integer> e : closure.entrySet()) {
            if (shown++ >= MAX_TRANSITIVE) {
                sb.append(", … (").append(closure.size() - MAX_TRANSITIVE).append(" more)");
                break;
            }
            if (shown > 1) sb.append(", ");
            sb.append(e.getKey()).append(" (depth ").append(e.getValue()).append(')');
        }
        return sb.toString();
    }

    /** Shortest directed path from → to as node ids, or {@code null} when unreachable. */
    static List<String> shortestPath(DependencyGraph graph, String from, String to) {
        Map<String, List<String>> adj = new HashMap<>();
        for (DependencyGraph.Edge e : graph.getEdges()) adj.computeIfAbsent(e.source, k -> new ArrayList<>()).add(e.target);
        Map<String, String> parent = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        parent.put(from, null);
        queue.add(from);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(to) && !cur.equals(from)) break;
            for (String next : adj.getOrDefault(cur, List.of())) {
                if (parent.containsKey(next)) continue;
                parent.put(next, cur);
                queue.add(next);
            }
        }
        if (!parent.containsKey(to) || from.equals(to)) return null;
        List<String> path = new ArrayList<>();
        for (String cur = to; cur != null; cur = parent.get(cur)) path.add(cur);
        Collections.reverse(path);
        return path;
    }

    private static List<String> ids(Iterable<DependencyGraph.Node> nodes) {
        List<String> out = new ArrayList<>();
        for (DependencyGraph.Node n : nodes) out.add(n.id);
        return out;
    }

    private static String kindWord(String kind) {
        if (DependencyGraph.KIND_DB.equals(kind)) return "database";
        if (DependencyGraph.KIND_QUEUE.equals(kind)) return "message broker";
        if (DependencyGraph.KIND_EXTERNAL.equals(kind)) return "external host";
        if (DependencyGraph.KIND_GATEWAY.equals(kind)) return "gateway";
        return "service";
    }

    private static String deployedWord(DependencyGraph graph, DependencyGraph.Node node) {
        if (Boolean.TRUE.equals(node.deployed)) return "yes (a matching Deployment is running)";
        if (Boolean.FALSE.equals(node.deployed)) return "NO — referenced in code/docs but not running in the cluster";
        // Unknown means two different things, and the reader should not be left to guess which.
        if (GraphQueryEngine.isGreenfield(graph)) return "unknown — greenfield (static) run, no cluster was queried";
        return "not determined (externally managed, or a StatefulSet rather than a Deployment)";
    }
}
