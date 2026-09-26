package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Why relaxing the hop limit costs nothing, measured rather than argued.
 *
 * <p>{@code SubgraphLimitsExperimentTest} reports the same median/p90/max slice size for
 * every hop limit from 1 to 12, but it only measures the pairs a limit connects, so the
 * populations differ between rows. This probe holds the PAIR fixed and varies the limit,
 * which is the comparison the claim actually needs, and counts how often a connector node
 * is one the neighbour pass would have added anyway.
 */
public class HopCostProbeTest {

    @Test
    void sameQuestionDifferentHopLimit() throws IOException {
        Map<String, DependencyGraph> corpus = new LinkedHashMap<>();
        Path dir = Path.of("src/test/resources/graphs/microdepgraph");
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".graphml")).sorted().toList()) {
                corpus.put(f.getFileName().toString(), GraphFile.read(f, "corpus"));
            }
        }
        corpus.put("bank-of-anthos", GraphFile.read(Path.of("docs/diagrams/fig3a-boa-layered.dot"), "boa"));
        corpus.put("train-ticket", GraphFile.read(Path.of("docs/train-ticket-greenfield-graph.mmd"), "tt"));

        // distance -> how many pairs, how many grew when the path was allowed in
        Map<Integer, int[]> byDistance = new TreeMap<>();
        List<String> examples = new ArrayList<>();

        for (Map.Entry<String, DependencyGraph> entry : corpus.entrySet()) {
            DependencyGraph g = entry.getValue();
            List<String> ids = g.getNodes().stream().map(n -> n.id).toList();
            for (String a : ids) {
                for (String b : ids) {
                    if (a.equals(b)) continue;
                    List<String> path = SubgraphExtractor.shortestPath(g, a, b, 12);
                    if (path == null) continue;
                    int distance = path.size() - 1;

                    // The same question with the path excluded (limit 0 takes no path at
                    // all) and with it included.
                    int without = SubgraphExtractor.extract(g, List.of(a, b), 9999, 0).graph.getNodes().size();
                    int with = SubgraphExtractor.extract(g, List.of(a, b), 9999, 12).graph.getNodes().size();

                    int[] row = byDistance.computeIfAbsent(distance, k -> new int[3]);
                    row[0]++;                       // pairs at this distance
                    if (with > without) {
                        row[1]++;                   // pairs where the path added a node
                        row[2] += with - without;   // how many nodes it added in total
                        if (examples.size() < 5) {
                            examples.add(entry.getKey() + ": " + String.join(" -> ", path)
                                    + "  (" + without + " -> " + with + " nodes)");
                        }
                    }
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("\n================ 同一組問句，開關連接路徑的差別 ================\n");
        out.append(String.format("%-8s %-10s %-14s %-12s%n", "最短距離", "配對數", "尺寸變大的", "多出的節點數"));
        for (Map.Entry<Integer, int[]> e : byDistance.entrySet()) {
            int[] r = e.getValue();
            out.append(String.format("%-10d %-11d %-15s %-12d%n", e.getKey(), r[0],
                    r[1] + " (" + String.format("%.1f%%", r[0] == 0 ? 0 : 100.0 * r[1] / r[0]) + ")", r[2]));
        }
        out.append("\n路徑真的多帶進節點的例子：\n");
        if (examples.isEmpty()) out.append("  （一個都沒有）\n");
        examples.forEach(x -> out.append("  ").append(x).append('\n'));
        out.append("=============================================================\n");
        System.out.println(out);
    }
}
