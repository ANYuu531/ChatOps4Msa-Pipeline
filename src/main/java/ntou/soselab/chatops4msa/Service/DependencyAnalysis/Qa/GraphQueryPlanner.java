package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.NLPService.LLMService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a question into {@link GraphQuery} operations — the one place in the Q&amp;A
 * where the model is asked to reason about the question rather than to phrase an
 * answer.
 *
 * The model sees the operator catalogue and the node ids, and returns a JSON plan.
 * That is all it returns: the plan is validated by {@link GraphQuery#parse} and run by
 * {@link GraphQueryEngine}, so the worst a bad plan can do is select the wrong
 * lookup, never state a wrong fact. On any failure (no key, network, junk output) the
 * plan is empty and the answer falls back to the mention-based grounding, which
 * already covers the questions that name a node.
 */
@Component
public class GraphQueryPlanner {

    private static final String PROMPT_FILE = "prompts/graph_query_plan.txt";
    /** More than this and the context is queries about everything, not about the question. */
    static final int MAX_QUERIES = 3;

    private final LLMService llmService;
    private final String template;

    public GraphQueryPlanner(LLMService llmService) {
        this.llmService = llmService;
        this.template = loadTemplate();
    }

    /** The validated plan for the question; empty when none applies or the call fails. */
    public List<GraphQuery> plan(DependencyGraph graph, String question) {
        return plan(graph, question, "");
    }

    /**
     * @param hints report passages retrieved for the question. A business flow
     *              ("checkout") is not in the graph, but the documentation notes often
     *              say which services a flow goes through; with them the seeds of a
     *              {@code subgraph} rest on the report's evidence rather than on how
     *              the service names sound.
     */
    public List<GraphQuery> plan(DependencyGraph graph, String question, String hints) {
        return plan(graph, question, hints, "");
    }

    /**
     * @param previous the thread's previous question and the queries it ran, or empty.
     *                 The planner does not see the conversation, and "add contacts too"
     *                 is meaningless without the seeds it adds to: the first real run
     *                 re-guessed the seeds instead, and added a service nobody asked for.
     */
    public List<GraphQuery> plan(DependencyGraph graph, String question, String hints, String previous) {
        if (graph == null || graph.getNodes().isEmpty() || question == null || question.isBlank()) return List.of();
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt(graph, hints, previous)));
            messages.put(new JSONObject().put("role", "user").put("content", question));
            String response = llmService.callAPIFromOutside(messages);
            List<GraphQuery> queries = GraphQuery.parse(response, graph);
            if (queries.size() > MAX_QUERIES) queries = new ArrayList<>(queries.subList(0, MAX_QUERIES));
            return queries; // the caller logs the plan together with its source (rules|llm)
        } catch (Exception e) {
            System.out.println("[WARNING] graph query planning skipped: " + e.getMessage());
            return List.of();
        }
    }

    /** The template with the catalogue and this graph's node ids filled in. */
    String systemPrompt(DependencyGraph graph) {
        return systemPrompt(graph, "");
    }

    String systemPrompt(DependencyGraph graph, String hints) {
        return systemPrompt(graph, hints, "");
    }

    String systemPrompt(DependencyGraph graph, String hints, String previous) {
        StringBuilder ops = new StringBuilder();
        for (Map.Entry<String, Integer> op : GraphQuery.OPS.entrySet()) {
            int arity = op.getValue();
            ops.append("- ").append(op.getKey()).append(" — ").append(describe(op.getKey()))
                    .append(arity == GraphQuery.VARIADIC ? " (1 to " + GraphQuery.MAX_SEEDS + " args)"
                            : arity == 0 ? " (no args)" : " (" + arity + " arg" + (arity > 1 ? "s" : "") + ")")
                    .append('\n');
        }
        List<String> ids = new ArrayList<>();
        for (DependencyGraph.Node n : graph.getNodes()) ids.add(n.id + " [" + n.kind + "]");
        String excerpt = hints == null || hints.isBlank() ? "(none retrieved)" : truncate(hints, HINT_CHARS);
        return template
                .replace("<OPERATORS>", ops.toString())
                .replace("<NODES>", String.join(", ", ids))
                .replace("<MAX>", String.valueOf(MAX_QUERIES))
                .replace("<HINTS>", excerpt)
                .replace("<PREVIOUS>", previous == null || previous.isBlank() ? "(none: this is the first question)" : truncate(previous, HINT_CHARS));
    }

    /** Enough for the passages that name a flow's services; the planner call stays small. */
    static final int HINT_CHARS = 4000;

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n…";
    }

    private static String describe(String op) {
        switch (op) {
            case "dependencies-of": return "what a node directly calls / uses";
            case "dependents-of": return "who directly calls a node";
            case "impact-of": return "everything transitively affected if a node fails or changes";
            case "startup-needs": return "everything that must be running before a node works";
            case "path": return "shortest call path between two nodes";
            case "edges-of-type": return "all edges of one type: sync-http, db, async, external";
            case "db-users": return "which services use which database, with evidence level";
            case "observed-edges": return "edges Istio actually observed at runtime";
            case "unobserved-edges": return "edges declared in code/docs but never observed";
            case "uncovered": return "the coverage figure and the business edges traffic never exercised";
            case "mentioned-only": return "edges with no usage evidence (only mentioned in docs)";
            case "undeployed": return "workloads referenced but not running in the cluster";
            case "deploy-order": return "start-up / deployment order implied by the graph";
            case "externals": return "external hosts and who calls them";
            case "async": return "message-broker relationships";
            case "subgraph": return "draw the part of the graph around the given nodes (a flow, a feature, a named group); code adds the paths between them and their one-hop neighbours";
            default: return "";
        }
    }

    private static String loadTemplate() {
        try {
            return new String(FileCopyUtils.copyToByteArray(new ClassPathResource(PROMPT_FILE).getInputStream()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WARNING] graph query prompt missing (" + PROMPT_FILE + ")");
            return "Choose up to <MAX> graph queries answering the question. Operators:\n<OPERATORS>\nNodes: <NODES>\n"
                    + "Reply with a JSON array of {\"op\": ..., \"args\": [...]} only.";
        }
    }
}
