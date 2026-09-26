package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One concrete slice, printed step by step, so the hop/node experiment can be explained
 * with a real example instead of in the abstract ("for every pair, at every hop limit,
 * cut a slice and measure it" tells a listener nothing about what actually happens).
 */
public class SliceWalkthroughTest {

    @Test
    void oneQuestionOneSlice() throws IOException {
        DependencyGraph g = GraphFile.read(Path.of("docs/diagrams/fig3a-boa-layered.dot"), "bank-of-anthos");

        System.out.println("\n整張圖：" + g.getNodes().size() + " 個節點、" + g.getEdges().size() + " 條邊");
        System.out.println("節點：" + g.getNodes().stream().map(n -> n.id).sorted().toList());

        for (String[] pair : new String[][]{
                {"frontend", "ledger-db"},
                {"istio-ingressgateway", "ledger-db"},
                {"frontend", "balancereader"}}) {
            List<String> path = SubgraphExtractor.shortestPath(g, pair[0], pair[1], 12);
            SubgraphExtractor.Result r = SubgraphExtractor.extract(g, List.of(pair), 9999, 4);
            System.out.println("\n──────── 問句點名：" + pair[0] + " 和 " + pair[1] + " ────────");
            System.out.println("  最短路徑    ：" + (path == null ? "走不到" : String.join(" → ", path)
                    + "（" + (path.size() - 1) + " 跳）"));
            System.out.println("  起點        ：" + r.seeds);
            System.out.println("  路徑上加進來：" + r.connectors);
            System.out.println("  一跳鄰居    ：" + r.neighbours);
            System.out.println("  切出來的子圖：" + r.graph.getNodes().size() + " 個節點 "
                    + r.graph.getNodes().stream().map(n -> n.id).sorted().toList());
        }
        System.out.println();
    }
}
