package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule trace records which numbered rule changed an outcome, and on what. The
 * fixture is rule 15's (a loopback address read back to the one module owning its port,
 * from {@link GreenfieldGeneralizationTest}); the trace must name the edge it drew, count
 * the same edge once however often it is reached, and start empty after {@code clear()}.
 */
public class RuleTraceTest {

    private static final String LOOPBACK_PORT = """
            {"repo":"r","failed":false,"edges":[
              {"section":"service-root","fields":{"dir":"gateway","name":"gateway"},"file":"gateway/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"cloud-config","name":"cloud-config"},"file":"cloud-config/pom.xml","line":-1},
              {"section":"service-root","fields":{"dir":"a","name":"a"},"file":"a/pom.xml","line":-1},
              {"section":"config","fields":{"key":"server.port","value":"9296"},"file":"cloud-config/src/main/resources/application.yml","line":-1},
              {"section":"config","fields":{"key":"server.port","value":"8080"},"file":"a/src/main/resources/application.yml","line":-1},
              {"section":"config","fields":{"key":"spring.config.import","value":"${SPRING_CONFIG_IMPORT:optional:configserver:http://localhost:9296}"},"file":"gateway/src/main/resources/application.yml","line":-1},
              {"section":"config","fields":{"key":"spring.cloud.config.uri","value":"http://localhost:9296"},"file":"gateway/src/main/resources/bootstrap.yml","line":-1},
              {"section":"url","fields":{"value":"http://localhost:8080/health"},"file":"gateway/src/main/java/Health.java","line":9}
            ]}
            """;

    @Test
    void rule15RecordsTheEdgeItDrewAndRule8TheLoopbackItDropped() {
        RuleTrace.clear();
        assertTrue(RuleTrace.isEmpty());
        assertEquals(0, RuleTrace.count(15));

        DependencyGraph g = new DependencyGraph("");
        CodeGraphMerger.merge(g, LOOPBACK_PORT, "owner/repo");

        // Rule 15 fired on two things: the configserver: prefix it stripped and the one edge
        // it draws; the same edge reached from two config rows (the config import and the
        // bootstrap uri) is one thing affected, so three hits count as two.
        assertEquals(2, RuleTrace.count(15), RuleTrace.examples(15, 5).toString());
        assertEquals(3, RuleTrace.hits(15));
        assertTrue(RuleTrace.examples(15, 5).stream().anyMatch(t -> t.startsWith("edge gateway->cloud-config")), RuleTrace.examples(15, 5).toString());
        assertTrue(RuleTrace.examples(15, 5).stream().anyMatch(t -> t.startsWith("configserver-prefix")), RuleTrace.examples(15, 5).toString());
        // Rule 4 filled the placeholder from its default; rule 8 dropped localhost:8080 (no port owner).
        assertTrue(RuleTrace.count(4) >= 1, RuleTrace.examples(4, 5).toString());
        assertTrue(RuleTrace.examples(8, 10).stream().anyMatch(t -> t.startsWith("loopback localhost")), RuleTrace.examples(8, 10).toString());

        // The Markdown section lists every rule 1..46, fired or not, and the JSON carries counts and examples.
        String md = RuleTrace.toMarkdown();
        assertTrue(md.startsWith("## 規則觸發\n"));
        assertTrue(md.contains("\n- rule 15: 2 (") && md.contains("edge gateway->cloud-config"), md);
        assertTrue(md.contains("\n- rule 46: 0\n"), md);
        assertEquals(2, RuleTrace.toJson().getJSONObject("rules").getJSONObject("15").getInt("count"));
        assertEquals(RuleTrace.RULE_COUNT, RuleTrace.toJson().getJSONObject("rules").length());

        RuleTrace.clear();
        assertTrue(RuleTrace.isEmpty());
        assertFalse(RuleTrace.toMarkdown().contains("- rule 15: 2"));
    }
}
