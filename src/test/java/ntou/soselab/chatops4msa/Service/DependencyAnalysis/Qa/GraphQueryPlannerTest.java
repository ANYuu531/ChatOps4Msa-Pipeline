package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The planner's prompt must hand the model the catalogue and this graph's ids, nothing else. */
public class GraphQueryPlannerTest {

    @Test
    void systemPromptCarriesEveryOperatorAndEveryNodeId() {
        DependencyGraph g = new DependencyGraph("ns");
        g.addNode("frontend", DependencyGraph.KIND_SERVICE);
        g.addNode("accounts-db", DependencyGraph.KIND_DB);
        String prompt = new GraphQueryPlanner(null).systemPrompt(g);

        for (String op : GraphQuery.OPS.keySet()) assertTrue(prompt.contains("- " + op + " — "), op);
        assertTrue(prompt.contains("frontend [service], accounts-db [db]"));
        assertTrue(prompt.contains("At most 3 queries"));
        assertFalse(prompt.contains("<OPERATORS>"));
        assertFalse(prompt.contains("<NODES>"));
        assertFalse(prompt.contains("<MAX>"));
    }

    @Test
    void anEmptyGraphOrQuestionPlansNothingWithoutCallingTheModel() {
        GraphQueryPlanner planner = new GraphQueryPlanner(null); // a call would NPE
        assertTrue(planner.plan(new DependencyGraph("ns"), "who calls frontend?").isEmpty());
        DependencyGraph g = new DependencyGraph("ns");
        g.addNode("frontend", DependencyGraph.KIND_SERVICE);
        assertTrue(planner.plan(g, "  ").isEmpty());
        assertTrue(planner.plan(null, "x").isEmpty());
    }
}
