package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why a partial graph stops at {@link SubgraphExtractor#MAX_PATH_HOPS} hops and
 * {@link SubgraphExtractor#MAX_NODES} nodes, measured on the graphs DepWeaver produced
 * for Bank of Anthos and train-ticket rather than argued from taste.
 *
 * <p>The two limits answer different questions, so they are measured separately:
 * <ul>
 *   <li><b>hops</b> decides whether two services a question names are drawn as one flow.
 *       Measured as a trade-off over the documented flows of {@code qa/flows.tsv}: how
 *       many ordered pairs <em>inside</em> a flow are within h hops (wanted), against how
 *       many pairs from two <em>different</em> flows are also within h hops (a picture
 *       that invents a flow). Selected by Youden's J — coverage minus false connection —
 *       with the smaller h on a tie.</li>
 *   <li><b>nodes</b> decides how much of that flow is drawn. Measured as the natural size
 *       of a slice (the size with no cap) over every realistic seed set, against the
 *       readability ceiling reported by Ghoniem, Fekete and Castagliola (Information
 *       Visualization 4(2), 2005): "when graphs are bigger than twenty vertices, the
 *       matrix-based visualization outperforms node-link diagrams on most tasks". Rule:
 *       the smallest cap that leaves at least 90% of slices untruncated, and never above
 *       20 — a partial graph is a node-link picture in a chat message.</li>
 * </ul>
 * Both rules are written here before the numbers are read, the way the router sweep does
 * it. Offline and deterministic: no API key, no cluster, no archive.
 */
public class SubgraphLimitsExperimentTest {

    static final Path OUT_DIR = Path.of("target/qa-calibration");
    static final int[] HOPS = {1, 2, 3, 4, 5, 6, 8, 12};
    static final int[] CAPS = {6, 8, 10, 12, 15, 20, 25, 30, 40};
    /** The readability ceiling for a node-link picture (Ghoniem et al. 2005). */
    static final int READABILITY_CEILING = 20;
    static final int UNCAPPED = Integer.MAX_VALUE;

    record Flow(String project, String name, List<String> members, List<String> seeds, String source) {
    }

    record HopRow(int hops, int sameFlowPairs, int sameFlowWithin, int crossFlowPairs, int crossFlowWithin) {
        double coverage() {
            return sameFlowPairs == 0 ? 0 : (double) sameFlowWithin / sameFlowPairs;
        }

        double falseConnection() {
            return crossFlowPairs == 0 ? 0 : (double) crossFlowWithin / crossFlowPairs;
        }

        double j() {
            return coverage() - falseConnection();
        }
    }

    /** Youden's J over the hop rows, smaller h on a tie: written before the results. */
    static HopRow selectHops(List<HopRow> rows) {
        HopRow best = null;
        for (HopRow r : rows) {
            if (best == null || r.j() > best.j() + 1e-9 || (Math.abs(r.j() - best.j()) <= 1e-9 && r.hops < best.hops)) best = r;
        }
        return best;
    }

    /**
     * The hop cap chosen on an outside corpus: the <em>largest</em> hop limit whose p90
     * slice still fits the readability ceiling. Written before the numbers are read.
     *
     * <p>Rationale for taking the largest rather than the smallest: every hop allowed
     * connects more of the pairs a question may name, and the only thing a longer
     * connector costs is nodes in the picture — which is exactly what the 20-node ceiling
     * bounds. So the two limits are one decision: allow as many hops as the picture can
     * still carry.
     */
    static int selectHopCap(Map<Integer, Integer> p90SizeByHop) {
        int chosen = 1;
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(p90SizeByHop).entrySet()) {
            if (e.getValue() <= READABILITY_CEILING) chosen = Math.max(chosen, e.getKey());
            else break;
        }
        return chosen;
    }

    /** Smallest cap leaving >= 90% of slices whole, never above the readability ceiling. */
    static int selectCap(Map<Integer, Double> untruncatedShareByCap) {
        int chosen = READABILITY_CEILING;
        for (Map.Entry<Integer, Double> e : new TreeMap<>(untruncatedShareByCap).entrySet()) {
            if (e.getKey() > READABILITY_CEILING) break;
            if (e.getValue() >= 0.90) {
                chosen = e.getKey();
                break;
            }
        }
        return chosen;
    }

    // ---------- the experiment ----------

    @Test
    void measureTheTwoPartialGraphLimitsOnRealGraphs() throws Exception {
        Map<String, DependencyGraph> graphs = new LinkedHashMap<>();
        graphs.put("bank-of-anthos", GraphFile.read(Path.of("docs/diagrams/fig3a-boa-layered.dot"), "bank-of-anthos"));
        graphs.put("train-ticket", GraphFile.read(Path.of("docs/train-ticket-greenfield-graph.mmd"), "train-ticket"));

        List<Flow> flows = readFlows();
        StringBuilder md = new StringBuilder("# Partial-graph limits: hops and nodes\n\n");
        md.append("Graphs are the ones DepWeaver produced: `docs/diagrams/fig3a-boa-layered.dot` (runtime) and ")
                .append("`docs/train-ticket-greenfield-graph.mmd` (static). Flows and their members come from ")
                .append("`src/test/resources/qa/flows.tsv`.\n\n");

        // --- the graphs themselves: how far apart are two services at all? ---
        md.append("## 1. Distance between services in the whole graph\n\n");
        md.append("| project | nodes | edges | connected ordered pairs | ≤1 | ≤2 | ≤3 | ≤4 | ≤5 | ≤6 | longest |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        StringBuilder csv = new StringBuilder("section,project,key,metric,value\n");
        for (Map.Entry<String, DependencyGraph> e : graphs.entrySet()) {
            DependencyGraph g = e.getValue();
            List<Integer> distances = new ArrayList<>();
            List<String> ids = g.getNodes().stream().map(n -> n.id).toList();
            for (String a : ids) {
                for (String b : ids) {
                    if (a.equals(b)) continue;
                    List<String> path = SubgraphExtractor.shortestPath(g, a, b, UNCAPPED);
                    if (path != null) distances.add(path.size() - 1);
                }
            }
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d |", e.getKey(), g.getNodes().size(), g.getEdges().size(), distances.size()));
            for (int h = 1; h <= 6; h++) {
                md.append(String.format(Locale.ROOT, " %.0f%% |", 100.0 * within(distances, h) / Math.max(1, distances.size())));
                csv.append(String.format(Locale.ROOT, "whole-graph,%s,<=%d,share,%.4f%n", e.getKey(), h,
                        (double) within(distances, h) / Math.max(1, distances.size())));
            }
            int longest = distances.stream().mapToInt(Integer::intValue).max().orElse(0);
            md.append(' ').append(longest).append(" |\n");
            csv.append(String.format(Locale.ROOT, "whole-graph,%s,longest,hops,%d%n", e.getKey(), longest));
        }

        // --- hops: connect a flow, do not invent one ---
        List<HopRow> hopRows = new ArrayList<>();
        for (int h : HOPS) {
            int samePairs = 0, sameWithin = 0, crossPairs = 0, crossWithin = 0;
            for (Flow f : flows) {
                DependencyGraph g = graphs.get(f.project());
                if (g == null) continue;
                List<String> members = present(g, f.members());
                for (String a : members) {
                    for (String b : members) {
                        if (a.equals(b)) continue;
                        samePairs++;
                        if (SubgraphExtractor.shortestPath(g, a, b, h) != null) sameWithin++;
                    }
                }
                for (Flow other : flows) {
                    if (other == f || !other.project().equals(f.project())) continue;
                    Set<String> onlyA = new LinkedHashSet<>(members);
                    onlyA.removeAll(other.members());
                    Set<String> onlyB = new LinkedHashSet<>(present(g, other.members()));
                    onlyB.removeAll(f.members());
                    for (String a : onlyA) {
                        for (String b : onlyB) {
                            crossPairs++;
                            if (SubgraphExtractor.shortestPath(g, a, b, h) != null) crossWithin++;
                        }
                    }
                }
            }
            hopRows.add(new HopRow(h, samePairs, sameWithin, crossPairs, crossWithin));
        }
        md.append("\n## 2. Hop limit: pairs inside one flow vs pairs across two flows\n\n");
        md.append("| hops | same-flow pairs within | coverage | cross-flow pairs within | false connection | J = coverage − false |\n|---|---|---|---|---|---|\n");
        for (HopRow r : hopRows) {
            md.append(String.format(Locale.ROOT, "| %d | %d/%d | %.3f | %d/%d | %.3f | %.3f |%n",
                    r.hops(), r.sameFlowWithin(), r.sameFlowPairs(), r.coverage(),
                    r.crossFlowWithin(), r.crossFlowPairs(), r.falseConnection(), r.j()));
            csv.append(String.format(Locale.ROOT, "hops,all,%d,coverage,%.4f%n", r.hops(), r.coverage()));
            csv.append(String.format(Locale.ROOT, "hops,all,%d,false_connection,%.4f%n", r.hops(), r.falseConnection()));
            csv.append(String.format(Locale.ROOT, "hops,all,%d,j,%.4f%n", r.hops(), r.j()));
        }
        HopRow chosenHops = selectHops(hopRows);
        md.append("\n**Selected by the rule (max J, smaller h on a tie): ").append(chosenHops.hops())
                .append(" hops** — in production ").append(SubgraphExtractor.MAX_PATH_HOPS).append(".\n");

        // --- nodes: how big is a slice before any cap? ---
        List<int[]> sizes = new ArrayList<>();                 // natural size, flow members in the slice
        Map<Integer, List<Integer>> sizeBySeedCount = new TreeMap<>();
        StringBuilder perSeed = new StringBuilder("project,flow,seeds,seed_count,natural_size,flow_members_in_slice,flow_members\n");
        for (Flow f : flows) {
            DependencyGraph g = graphs.get(f.project());
            if (g == null) continue;
            for (List<String> seeds : seedSets(present(g, f.members()), f.seeds())) {
                SubgraphExtractor.Result natural = SubgraphExtractor.extract(g, seeds, UNCAPPED, SubgraphExtractor.MAX_PATH_HOPS);
                int size = natural.graph.getNodes().size();
                int members = (int) natural.graph.getNodes().stream().filter(n -> f.members().contains(n.id)).count();
                sizes.add(new int[]{size, members});
                sizeBySeedCount.computeIfAbsent(seeds.size(), k -> new ArrayList<>()).add(size);
                perSeed.append(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%d%n", f.project(), f.name(),
                        String.join("+", seeds), seeds.size(), size, members, f.members().size()));
            }
        }
        List<Integer> naturalSizes = sizes.stream().map(s -> s[0]).sorted().toList();
        md.append("\n## 3. Node limit: the natural size of a slice (no cap, ")
                .append(SubgraphExtractor.MAX_PATH_HOPS).append(" hops)\n\n");
        md.append(String.format(Locale.ROOT, "%d seed sets over %d flows — median %d, p90 %d, max %d nodes.%n%n",
                naturalSizes.size(), flows.size(), percentile(naturalSizes, 50), percentile(naturalSizes, 90),
                naturalSizes.get(naturalSizes.size() - 1)));
        md.append("| seeds named | slices | median | p90 | max |\n|---|---|---|---|---|\n");
        for (Map.Entry<Integer, List<Integer>> e : sizeBySeedCount.entrySet()) {
            List<Integer> s = e.getValue().stream().sorted().toList();
            md.append(String.format(Locale.ROOT, "| %d | %d | %d | %d | %d |%n", e.getKey(), s.size(),
                    percentile(s, 50), percentile(s, 90), s.get(s.size() - 1)));
            csv.append(String.format(Locale.ROOT, "natural-size,seeds=%d,p50,nodes,%d%n", e.getKey(), percentile(s, 50)));
            csv.append(String.format(Locale.ROOT, "natural-size,seeds=%d,p90,nodes,%d%n", e.getKey(), percentile(s, 90)));
            csv.append(String.format(Locale.ROOT, "natural-size,seeds=%d,max,nodes,%d%n", e.getKey(), s.get(s.size() - 1)));
        }

        md.append("\n| cap | slices left whole | truncated | avg nodes dropped | avg flow members drawn |\n|---|---|---|---|---|\n");
        Map<Integer, Double> untruncated = new LinkedHashMap<>();
        for (int cap : CAPS) {
            int whole = 0, droppedTotal = 0, membersTotal = 0, n = 0;
            for (Flow f : flows) {
                DependencyGraph g = graphs.get(f.project());
                if (g == null) continue;
                for (List<String> seeds : seedSets(present(g, f.members()), f.seeds())) {
                    SubgraphExtractor.Result capped = SubgraphExtractor.extract(g, seeds, cap, SubgraphExtractor.MAX_PATH_HOPS);
                    SubgraphExtractor.Result natural = SubgraphExtractor.extract(g, seeds, UNCAPPED, SubgraphExtractor.MAX_PATH_HOPS);
                    int dropped = natural.graph.getNodes().size() - capped.graph.getNodes().size();
                    if (dropped == 0) whole++;
                    droppedTotal += dropped;
                    membersTotal += (int) capped.graph.getNodes().stream().filter(x -> f.members().contains(x.id)).count();
                    n++;
                }
            }
            untruncated.put(cap, (double) whole / n);
            md.append(String.format(Locale.ROOT, "| %d | %.0f%% | %d/%d | %.1f | %.1f |%n",
                    cap, 100.0 * whole / n, n - whole, n, (double) droppedTotal / n, (double) membersTotal / n));
            csv.append(String.format(Locale.ROOT, "nodes,all,%d,untruncated,%.4f%n", cap, (double) whole / n));
            csv.append(String.format(Locale.ROOT, "nodes,all,%d,avg_dropped,%.4f%n", cap, (double) droppedTotal / n));
            csv.append(String.format(Locale.ROOT, "nodes,all,%d,avg_members_drawn,%.4f%n", cap, (double) membersTotal / n));
        }
        int chosenCap = selectCap(untruncated);
        md.append("\n**Selected by the rule (smallest cap leaving ≥90% of slices whole, never above ")
                .append(READABILITY_CEILING).append("): ").append(chosenCap).append(" nodes** — in production ")
                .append(SubgraphExtractor.MAX_NODES).append(".\n");

        Files.createDirectories(OUT_DIR);
        Files.writeString(OUT_DIR.resolve("subgraph-limits.md"), md.toString());
        Files.writeString(OUT_DIR.resolve("subgraph-limits.csv"), csv.toString());
        Files.writeString(OUT_DIR.resolve("subgraph-limits-per-seed.csv"), perSeed.toString());
        System.out.println(md);
    }

    /**
     * The hop limit measured on 22 real dependency graphs, not on our two.
     *
     * <p>Our own two graphs have diameter 3, so every cap from 3 upwards cuts exactly the
     * same slice and the number cannot be justified on them. This runs the same question
     * over the MicroDepGraph corpus — 20 open-source microservice systems, 5 to 25
     * services each (see {@code graphs/microdepgraph/SOURCE.md}) — plus ours, and asks
     * two things of every hop limit: how many of the pairs a question could name does it
     * connect, and how big does the picture get.
     */
    @Test
    void measureTheHopLimitOnAnOutsideCorpusOfRealGraphs() throws Exception {
        Map<String, DependencyGraph> corpus = new LinkedHashMap<>();
        Path dir = Path.of("src/test/resources/graphs/microdepgraph");
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".graphml")).sorted().forEach(files::add);
        }
        assertTrue(files.size() >= 20, "the vendored corpus is missing: " + files.size() + " graphml files");
        for (Path f : files) {
            String name = f.getFileName().toString().replace(".graphml", "");
            corpus.put(name, GraphFile.read(f, name));
        }
        corpus.put("bank-of-anthos (ours)", GraphFile.read(Path.of("docs/diagrams/fig3a-boa-layered.dot"), "bank-of-anthos"));
        corpus.put("train-ticket (ours)", GraphFile.read(Path.of("docs/train-ticket-greenfield-graph.mmd"), "train-ticket"));

        StringBuilder md = new StringBuilder("# Hop limit on an outside corpus\n\n");
        md.append("MicroDepGraph (Rahman, Panichella & Taibi, SattoSE 2019; LGPL-3.0, vendored under ")
                .append("`src/test/resources/graphs/microdepgraph/`) plus the two graphs DepWeaver produced. ")
                .append("Edges there are declared dependencies (Docker Compose + internal API calls), infrastructure included.\n\n");
        StringBuilder csv = new StringBuilder("section,graph,key,metric,value\n");

        md.append("## 1. How deep are real dependency graphs?\n\n");
        md.append("| graph | nodes | edges | reachable ordered pairs | ≤1 | ≤2 | ≤3 | ≤4 | ≤5 | ≤6 | diameter |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        Map<Integer, Integer> pooledWithin = new TreeMap<>();
        int pooledPairs = 0;
        List<Integer> diameters = new ArrayList<>();
        for (Map.Entry<String, DependencyGraph> e : corpus.entrySet()) {
            DependencyGraph g = e.getValue();
            List<String> ids = g.getNodes().stream().map(n -> n.id).toList();
            List<Integer> distances = new ArrayList<>();
            for (String a : ids) {
                for (String b : ids) {
                    if (a.equals(b)) continue;
                    List<String> path = SubgraphExtractor.shortestPath(g, a, b, UNCAPPED);
                    if (path != null) distances.add(path.size() - 1);
                }
            }
            int diameter = distances.stream().mapToInt(Integer::intValue).max().orElse(0);
            diameters.add(diameter);
            pooledPairs += distances.size();
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d |", e.getKey(), g.getNodes().size(), g.getEdges().size(), distances.size()));
            for (int h = 1; h <= 6; h++) {
                int within = within(distances, h);
                pooledWithin.merge(h, within, Integer::sum);
                md.append(String.format(Locale.ROOT, " %.0f%% |", distances.isEmpty() ? 0 : 100.0 * within / distances.size()));
            }
            md.append(' ').append(diameter).append(" |\n");
            csv.append(String.format(Locale.ROOT, "corpus-depth,%s,diameter,hops,%d%n", e.getKey(), diameter));
        }
        md.append(String.format(Locale.ROOT, "%n%d graphs, %d reachable ordered pairs pooled; diameters: median %d, max %d.%n%n",
                corpus.size(), pooledPairs, percentile(diameters.stream().sorted().toList(), 50),
                diameters.stream().mapToInt(Integer::intValue).max().orElse(0)));
        md.append("| hop limit | pairs it connects (pooled) |\n|---|---|\n");
        for (Map.Entry<Integer, Integer> e : pooledWithin.entrySet()) {
            double share = (double) e.getValue() / Math.max(1, pooledPairs);
            md.append(String.format(Locale.ROOT, "| %d | %.1f%% |%n", e.getKey(), 100 * share));
            csv.append(String.format(Locale.ROOT, "corpus-reach,pooled,%d,share,%.4f%n", e.getKey(), share));
        }

        // 2. What a hop limit costs in picture size: every two-service question, every limit.
        md.append("\n## 2. What each hop limit costs in nodes (two-seed questions, no node cap)\n\n");
        md.append("| hop limit | pairs connected | median slice | p90 | max | over the 20-node ceiling |\n|---|---|---|---|---|---|\n");
        Map<Integer, Integer> p90ByHop = new TreeMap<>();
        for (int h : HOPS) {
            List<Integer> sizes = new ArrayList<>();
            int connected = 0, pairs = 0, over = 0;
            for (DependencyGraph g : corpus.values()) {
                List<String> ids = g.getNodes().stream().map(n -> n.id).toList();
                for (String a : ids) {
                    for (String b : ids) {
                        if (a.equals(b)) continue;
                        pairs++;
                        if (SubgraphExtractor.shortestPath(g, a, b, h) == null) continue;
                        connected++;
                        int size = SubgraphExtractor.extract(g, List.of(a, b), UNCAPPED, h).graph.getNodes().size();
                        sizes.add(size);
                        if (size > READABILITY_CEILING) over++;
                    }
                }
            }
            List<Integer> sorted = sizes.stream().sorted().toList();
            int p90 = percentile(sorted, 90);
            p90ByHop.put(h, p90);
            md.append(String.format(Locale.ROOT, "| %d | %d/%d | %d | %d | %d | %.1f%% |%n", h, connected, pairs,
                    percentile(sorted, 50), p90, sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1),
                    sizes.isEmpty() ? 0 : 100.0 * over / sizes.size()));
            csv.append(String.format(Locale.ROOT, "corpus-slice,pooled,%d,p50,%d%n", h, percentile(sorted, 50)));
            csv.append(String.format(Locale.ROOT, "corpus-slice,pooled,%d,p90,%d%n", h, p90));
            csv.append(String.format(Locale.ROOT, "corpus-slice,pooled,%d,over_ceiling,%.4f%n", h,
                    sizes.isEmpty() ? 0 : (double) over / sizes.size()));
        }
        int chosen = selectHopCap(p90ByHop);
        md.append("\n**Selected by the rule (largest hop limit whose p90 slice still fits ")
                .append(READABILITY_CEILING).append(" nodes): ").append(chosen).append(" hops** — in production ")
                .append(SubgraphExtractor.MAX_PATH_HOPS).append(".\n");

        Files.createDirectories(OUT_DIR);
        Files.writeString(OUT_DIR.resolve("subgraph-hops-external.md"), md.toString());
        Files.writeString(OUT_DIR.resolve("subgraph-hops-external.csv"), csv.toString());
        System.out.println(md);
    }

    // ---------- offline: the rules, the parser, and that production is unchanged ----------

    @Test
    void theHopRuleTakesTheBestTradeOffAndTheSmallerHopOnATie() {
        List<HopRow> rows = List.of(
                new HopRow(1, 100, 40, 100, 2),      // J = 0.38
                new HopRow(2, 100, 70, 100, 10),     // J = 0.60
                new HopRow(3, 100, 90, 100, 30),     // J = 0.60, later: loses the tie
                new HopRow(4, 100, 95, 100, 60));    // J = 0.35
        assertEquals(2, selectHops(rows).hops());
        assertEquals(4, selectHops(List.of(new HopRow(4, 10, 9, 10, 1))).hops());
    }

    @Test
    void theHopCapRuleTakesTheLastLimitThePictureCanCarry() {
        assertEquals(4, selectHopCap(Map.of(1, 6, 2, 9, 3, 14, 4, 19, 5, 24, 6, 30)));
        assertEquals(1, selectHopCap(Map.of(1, 8, 2, 21)), "a limit whose p90 overflows is not taken");
        assertEquals(12, selectHopCap(Map.of(1, 4, 2, 6, 3, 8, 4, 9, 12, 12)), "nothing overflows: the deepest is fine");
    }

    @Test
    void graphmlFromTheOutsideCorpusIsReadBackWhole() throws Exception {
        DependencyGraph ftgo = GraphFile.read(Path.of("src/test/resources/graphs/microdepgraph/FTGO.graphml"), "ftgo");
        assertEquals(15, ftgo.getNodes().size());
        assertEquals(28, ftgo.getEdges().size());
        assertTrue(ftgo.getEdges().stream().anyMatch(e -> e.source.equals("ftgo-order-service") && e.target.equals("kafka")));
        assertTrue(ftgo.getEdges().stream().noneMatch(e -> e.runtimeObserved), "the dataset declares, it does not observe");
    }

    @Test
    void theCapRuleStopsAtTheReadabilityCeiling() {
        Map<Integer, Double> plenty = Map.of(6, 0.5, 8, 0.8, 10, 0.95, 20, 1.0, 30, 1.0);
        assertEquals(10, selectCap(plenty));
        Map<Integer, Double> never = Map.of(6, 0.1, 8, 0.2, 10, 0.3, 20, 0.6, 30, 0.95);
        assertEquals(READABILITY_CEILING, selectCap(never), "a cap above the ceiling is not a picture");
    }

    @Test
    void theExperimentDrivesTheSameCodeProductionDoes() throws Exception {
        DependencyGraph g = GraphFile.read(Path.of("docs/train-ticket-greenfield-graph.mmd"), "train-ticket");
        List<String> seeds = List.of("ts-preserve-service", "ts-order-service");
        SubgraphExtractor.Result production = SubgraphExtractor.extract(g, seeds);
        SubgraphExtractor.Result replay = SubgraphExtractor.extract(g, seeds, SubgraphExtractor.MAX_NODES, SubgraphExtractor.MAX_PATH_HOPS);
        assertEquals(production.graph.getNodes().stream().map(n -> n.id).toList(),
                replay.graph.getNodes().stream().map(n -> n.id).toList());
        assertEquals(production.graph.getEdges().size(), replay.graph.getEdges().size());
    }

    @Test
    void theGraphFilesAreReadBackAsTheyWereDrawn() throws Exception {
        DependencyGraph boa = GraphFile.read(Path.of("docs/diagrams/fig3a-boa-layered.dot"), "bank-of-anthos");
        assertEquals(10, boa.getNodes().size());
        assertEquals(12, boa.getEdges().size());
        assertTrue(boa.getNodes().stream().anyMatch(n -> n.id.equals("ledger-db") && DependencyGraph.KIND_DB.equals(n.kind)));
        assertTrue(boa.getEdges().stream().anyMatch(e -> e.source.equals("frontend") && e.target.equals("ledgerwriter")));

        DependencyGraph tt = GraphFile.read(Path.of("docs/train-ticket-greenfield-graph.mmd"), "train-ticket");
        // 52 edge lines, one of them to the undeclared extraction artifact "null" — dropped
        // here exactly as the calibration node sets drop it.
        assertEquals(51, tt.getEdges().size());
        assertTrue(tt.getNodes().stream().noneMatch(n -> n.id.equals("null")));
        assertTrue(tt.getNodes().stream().anyMatch(n -> n.id.equals("github.com") && DependencyGraph.KIND_EXTERNAL.equals(n.kind)));
        assertTrue(tt.getEdges().stream().anyMatch(e -> e.source.equals("ts-preserve-service") && e.target.equals("ts-seat-service")));
        assertTrue(tt.getEdges().stream().noneMatch(e -> e.runtimeObserved), "the static graph has no observed edge");
    }

    @Test
    void everyFlowMemberAndSeedExistsInItsProjectGraph() throws Exception {
        Map<String, DependencyGraph> graphs = Map.of(
                "bank-of-anthos", GraphFile.read(Path.of("docs/diagrams/fig3a-boa-layered.dot"), "bank-of-anthos"),
                "train-ticket", GraphFile.read(Path.of("docs/train-ticket-greenfield-graph.mmd"), "train-ticket"));
        for (Flow f : readFlows()) {
            DependencyGraph g = graphs.get(f.project());
            assertTrue(g != null, "unknown project " + f.project());
            Set<String> ids = new LinkedHashSet<>(g.getNodes().stream().map(n -> n.id).toList());
            for (String m : f.members()) assertTrue(ids.contains(m), f.name() + ": no node " + m);
            for (String s : f.seeds()) assertTrue(f.members().contains(s), f.name() + ": seed " + s + " is not a member");
        }
    }

    // ---------- helpers ----------

    static List<Flow> readFlows() throws Exception {
        List<Flow> flows = new ArrayList<>();
        try (InputStream in = SubgraphLimitsExperimentTest.class.getResourceAsStream("/qa/flows.tsv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t");
                if (c.length < 4) continue;
                flows.add(new Flow(c[0].trim(), c[1].trim(), List.of(c[2].split("\\|")), List.of(c[3].split("\\|")),
                        c.length > 4 ? c[4].trim() : ""));
            }
        }
        return flows;
    }

    /** The flow's members that the graph actually has, in the order given. */
    static List<String> present(DependencyGraph g, List<String> ids) {
        Set<String> have = new LinkedHashSet<>(g.getNodes().stream().map(n -> n.id).toList());
        return ids.stream().filter(have::contains).toList();
    }

    /**
     * Seed sets a question would produce: the flow's own named seeds, every member alone,
     * every pair of the first six members, and — because a plan may name up to
     * {@code GraphQuery}'s eight seeds — the sliding windows of 3, 4, 6 and 8 members,
     * which is where a slice grows towards the cap.
     */
    static List<List<String>> seedSets(List<String> members, List<String> named) {
        List<List<String>> out = new ArrayList<>();
        if (!named.isEmpty()) out.add(named);
        for (String m : members) out.add(List.of(m));
        List<String> head = members.subList(0, Math.min(6, members.size()));
        for (int i = 0; i < head.size(); i++) {
            for (int j = i + 1; j < head.size(); j++) out.add(List.of(head.get(i), head.get(j)));
        }
        for (int size : new int[]{3, 4, 6, 8}) {
            for (int start = 0; start + size <= members.size(); start++) out.add(members.subList(start, start + size));
        }
        return out;
    }

    static int within(List<Integer> distances, int h) {
        return (int) distances.stream().filter(d -> d <= h).count();
    }

    static int percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    static {
        Arrays.sort(HOPS);
        Arrays.sort(CAPS);
    }
}
