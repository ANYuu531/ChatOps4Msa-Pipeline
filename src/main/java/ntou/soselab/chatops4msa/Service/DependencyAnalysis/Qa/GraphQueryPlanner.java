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
        if (graph == null || graph.getNodes().isEmpty() || question == null || question.isBlank()) return List.of();
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt(graph)));
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
        StringBuilder ops = new StringBuilder();
        for (Map.Entry<String, Integer> op : GraphQuery.OPS.entrySet()) {
            ops.append("- ").append(op.getKey()).append(" — ").append(describe(op.getKey()))
                    .append(op.getValue() == 0 ? " (no args)" : " (" + op.getValue() + " arg" + (op.getValue() > 1 ? "s" : "") + ")")
                    .append('\n');
        }
        List<String> ids = new ArrayList<>();
        for (DependencyGraph.Node n : graph.getNodes()) ids.add(n.id + " [" + n.kind + "]");
        return template
                .replace("<OPERATORS>", ops.toString())
                .replace("<NODES>", String.join(", ", ids))
                .replace("<MAX>", String.valueOf(MAX_QUERIES));
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
