package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.AliasResolution;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link GreenfieldProbeTest} over a whole corpus in one JVM, for rule mining
 * (docs/generalization/mining/). For every checkout it writes the usual
 * {@code <name>.mmd}/{@code .summary.md}/{@code .rules.json}, plus {@code <name>.mining.json}:
 * every ledger entry with its file:line, every edge with all of its provenance refs, every
 * unresolved code edge and every alias question — the summary truncates the last two.
 * The residue miner reads that file to find the address-shaped mentions no rule consumed.
 *
 * <p>Run with {@code mvn -o test -Dtest=MiningProbeTest -Dprobe.list=/path/list.tsv
 * -Dprobe.outdir=/path/out [-Dprobe.timeout=240]}; each list line is
 * {@code name<TAB>/path/to/checkout}. A repo that throws or exceeds the timeout is recorded
 * in {@code failures.tsv} and the run goes on.
 */
public class MiningProbeTest {

    @Test
    void probeCorpus() throws Exception {
        String list = System.getProperty("probe.list");
        String outDir = System.getProperty("probe.outdir");
        Assumptions.assumeTrue(list != null && !list.isBlank() && outDir != null && !outDir.isBlank(),
                "set -Dprobe.list=list.tsv -Dprobe.outdir=dir to run this driver");
        long timeout = Long.parseLong(System.getProperty("probe.timeout", "240"));
        Path out = Path.of(outDir);
        Files.createDirectories(out);
        StringBuilder failures = new StringBuilder("name\treason\n");

        for (String row : Files.readAllLines(Path.of(list))) {
            if (row.isBlank() || row.startsWith("#")) continue;
            String[] cols = row.split("\t");
            String name = cols[0];
            Path root = Path.of(cols[1]);
            ExecutorService one = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "probe-" + name);
                t.setDaemon(true);
                return t;
            });
            long t0 = System.nanoTime();
            try {
                Future<GreenfieldProbeTest.Probe> f =
                        one.submit(() -> GreenfieldProbeTest.run(root, new AliasResolution.Answers()));
                GreenfieldProbeTest.Probe p = f.get(timeout, TimeUnit.SECONDS);
                Files.writeString(out.resolve(name + ".mmd"), p.mermaid);
                Files.writeString(out.resolve(name + ".summary.md"), p.summary);
                Files.writeString(out.resolve(name + ".rules.json"), RuleTrace.toJson().toString(2) + "\n");
                Files.writeString(out.resolve(name + ".mining.json"), miningJson(p).toString(1) + "\n");
                System.out.printf("[mining] %s ok in %.1fs: %d nodes / %d edges%n", name,
                        (System.nanoTime() - t0) / 1e9, p.graph.getNodes().size(), p.graph.getEdges().size());
            } catch (TimeoutException e) {
                failures.append(name).append("\ttimeout ").append(timeout).append("s\n");
                System.out.println("[mining] " + name + " TIMEOUT");
            } catch (Exception | Error e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                failures.append(name).append('\t').append(c.getClass().getSimpleName()).append(": ")
                        .append(String.valueOf(c.getMessage()).replaceAll("\\s+", " ")).append('\n');
                System.out.println("[mining] " + name + " FAILED " + c);
            } finally {
                one.shutdownNow();
            }
        }
        Files.writeString(out.resolve("failures.tsv"), failures.toString());
    }

    static JSONObject miningJson(GreenfieldProbeTest.Probe p) {
        JSONArray ledger = new JSONArray();
        for (EdgeLedger.Edge e : p.ledger.getEdges()) {
            JSONObject fields = new JSONObject();
            e.fields.forEach(fields::put);
            ledger.put(new JSONObject().put("section", e.section).put("fields", fields)
                    .put("file", e.file).put("line", e.line).put("confidence", e.confidence));
        }
        JSONArray nodes = new JSONArray();
        for (DependencyGraph.Node n : p.graph.getNodes()) {
            nodes.put(new JSONObject().put("id", n.id).put("kind", n.kind));
        }
        JSONArray edges = new JSONArray();
        for (DependencyGraph.Edge e : p.graph.getEdges()) {
            edges.put(new JSONObject().put("source", e.source).put("target", e.target).put("type", e.type)
                    .put("confidence", e.confidence).put("refs", new JSONArray(e.provenanceRefs)));
        }
        JSONArray unresolved = new JSONArray();
        for (CodeGraphMerger.Unresolved u : p.unresolved) {
            unresolved.put(new JSONObject().put("section", u.section).put("source", u.rawSource)
                    .put("target", u.rawTarget).put("file", u.file).put("line", u.line));
        }
        JSONArray questions = new JSONArray();
        List<AliasResolution.Question> qs = p.questions.list();
        for (AliasResolution.Question q : qs) {
            questions.put(new JSONObject().put("name", q.name).put("origin", q.origin).put("seenIn", q.seenIn)
                    .put("candidates", new JSONArray(q.candidates)));
        }
        return new JSONObject().put("repo", p.repoName).put("ledger", ledger).put("nodes", nodes)
                .put("edges", edges).put("unresolved", unresolved).put("questions", questions);
    }
}
