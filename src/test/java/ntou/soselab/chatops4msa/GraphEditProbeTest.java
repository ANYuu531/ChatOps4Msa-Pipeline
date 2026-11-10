package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphEdit;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.AliasResolution;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.EvidenceFinder;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.GraphEditDialogue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The graph-edit dialogue on a real checkout, without Discord or a model: the static
 * graph is built offline ({@link GreenfieldProbeTest#run}), then each scripted request
 * is played turn by turn against it and the transcript printed.
 *
 * <pre>
 * mvn -o -q test -Dtest=GraphEditProbeTest -Dprobe.edit.repo=~/Documents/depweaver-eval/repos/holdout-c/yas \
 *     -Dprobe.edit.script="add-edge|kafka-connect|postgres|1|ok|yes;add-edge|storefront-nextjs|storefront|1|ok|yes" \
 *     -Dprobe.edit.out=docs/experiments/2026-11-06-graph-edit/yas.md
 * </pre>
 * A request is {@code op|arg|arg|answer|answer…}; requests are separated by {@code ;}.
 */
@EnabledIfSystemProperty(named = "probe.edit.repo", matches = ".+")
public class GraphEditProbeTest {

    @Test
    void playScript() throws Exception {
        Path root = Path.of(System.getProperty("probe.edit.repo").replaceFirst("^~", System.getProperty("user.home")));
        GreenfieldProbeTest.Probe probe = GreenfieldProbeTest.run(root, new AliasResolution.Answers());
        String codeEdges = probe.ledger.toJson().toString();
        DependencyGraph graph = probe.graph;

        List<CodeGraphMerger.Unresolved> residue = CodeGraphMerger.merge(new DependencyGraph(""), codeEdges, probe.repoName);
        EvidenceFinder.Context ctx = new EvidenceFinder.Context(graph,
                CodeGraphMerger.fileOwners(new DependencyGraph(""), codeEdges, probe.repoName),
                EvidenceFinder.filesWithRows(codeEdges), residue);

        GraphEditDialogue.Env env = new GraphEditDialogue.Env() {
            public DependencyGraph graph() {
                return graph;
            }

            public List<EvidenceFinder.Evidence> search(String source, String target) {
                return EvidenceFinder.find(root, ctx, source, target);
            }

            public String readLine(String file, int line) {
                return EvidenceFinder.lineOf(root.resolve(file), line);
            }

            public String user() {
                return "probe";
            }

            public String now() {
                return "2026-11-06";
            }
        };

        StringBuilder out = new StringBuilder("# graph-edit probe · " + probe.repoName + "\n\n");
        out.append("graph: ").append(graph.getNodes().size()).append(" nodes / ").append(graph.getEdges().size()).append(" edges\n");
        String script = System.getProperty("probe.edit.script", "");
        for (String request : script.split(";")) {
            if (request.isBlank()) continue;
            String[] parts = request.split("\\|", -1);
            String op = parts[0].trim();
            List<String> args = op.equals(GraphEdit.ADD_NODE) ? List.of(parts[1].trim()) : List.of(parts[1].trim(), parts[2].trim());
            int firstAnswer = op.equals(GraphEdit.ADD_NODE) ? 2 : 3;
            String question = op.equals(GraphEdit.ADD_NODE) ? "幫我在圖上新增節點 " + args.get(0)
                    : "圖上少了 " + args.get(0) + " 連到 " + args.get(1) + " 的線，幫我加上";
            out.append("\n## ").append(question).append("\n\n");
            boolean before = op.equals(GraphEdit.ADD_EDGE) && hasEdge(graph, args.get(0), args.get(1));
            out.append("_edge on the graph before: ").append(before).append("_\n\n");

            GraphEditDialogue.Turn turn = GraphEditDialogue.start(op, args, question, env);
            out.append("**bot:**\n```\n").append(turn.reply).append("\n```\n");
            for (int i = firstAnswer; i < parts.length && turn.next != null; i++) {
                out.append("**user:** ").append(parts[i]).append("\n\n");
                turn = GraphEditDialogue.answer(turn.next, parts[i], env);
                out.append("**bot:**").append(turn.understood ? "" : " _(not understood)_").append("\n```\n").append(turn.reply).append("\n```\n");
                if (turn.applied != null) {
                    turn.applied.apply(graph);
                    out.append("_applied: ").append(turn.applied.toJson()).append("_\n\n");
                }
            }
        }
        String text = out.toString();
        System.out.println(text);
        String outPath = System.getProperty("probe.edit.out");
        if (outPath != null && !outPath.isBlank()) {
            Path target = Path.of(outPath);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, text);
        }
    }

    private static boolean hasEdge(DependencyGraph graph, String s, String t) {
        for (DependencyGraph.Edge e : graph.getEdges()) if (e.source.equals(s) && e.target.equals(t)) return true;
        return false;
    }
}
