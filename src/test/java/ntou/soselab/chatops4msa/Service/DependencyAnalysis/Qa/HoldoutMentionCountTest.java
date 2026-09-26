package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How many nodes a question actually names, over the 102-sentence router hold-out.
 *
 * <p>Why this exists: {@code docs/threshold-design.md} §8.4 measures slice sizes over 242
 * seed sets that a program enumerated (single nodes, pairs, and sliding windows of 3, 4, 6
 * and 8 flow members). The 8-seed row is where a slice first reaches 21 nodes, and the
 * partial graph's node cap was argued against that row being atypical. "Atypical" was an
 * assumption, not a measurement — so this test measures the one thing we do have a sample
 * of: how many node ids the hold-out questions name.
 *
 * <p><b>What this is NOT.</b> The hold-out was written by the same author as the tool, so
 * this is the distribution of <i>the author's</i> questions, not of real users' questions.
 * Real thread questions from a deployed instance would be a genuine sample and we do not
 * have one yet (`docs/threshold-design.md` §6 lists collecting them as future work). The
 * number is reported with that caveat attached, in the document and here.
 */
public class HoldoutMentionCountTest {

    @Test
    void howManyNodesTheHoldoutQuestionsName() throws Exception {
        List<CalibrationSupport.HoldoutRow> rows = CalibrationSupport.loadHoldout();
        assertFalse(rows.isEmpty());

        Map<String, DependencyGraph> graphs = new TreeMap<>();
        Map<Integer, Integer> histogram = new TreeMap<>();
        Map<String, List<Integer>> byProject = new TreeMap<>();
        List<Integer> all = new ArrayList<>();

        for (CalibrationSupport.HoldoutRow row : rows) {
            DependencyGraph graph = graphs.computeIfAbsent(row.project, CalibrationSupport::projectGraph);
            int named = GraphGrounding.mentionedNodes(row.question, graph).size();
            histogram.merge(named, 1, Integer::sum);
            byProject.computeIfAbsent(row.project, k -> new ArrayList<>()).add(named);
            all.add(named);
        }

        StringBuilder out = new StringBuilder("nodes named per hold-out question (" + all.size() + " questions)\n");
        for (Map.Entry<Integer, Integer> e : histogram.entrySet()) {
            out.append(String.format(Locale.ROOT, "  %d node(s): %3d questions (%.0f%%)%n",
                    e.getKey(), e.getValue(), 100.0 * e.getValue() / all.size()));
        }
        out.append(String.format(Locale.ROOT, "  median %d · p90 %d · max %d%n",
                percentile(all, 50), percentile(all, 90), percentile(all, 100)));
        for (Map.Entry<String, List<Integer>> e : byProject.entrySet()) {
            out.append(String.format(Locale.ROOT, "  %-16s median %d · p90 %d · max %d%n",
                    e.getKey(), percentile(e.getValue(), 50), percentile(e.getValue(), 90),
                    percentile(e.getValue(), 100)));
        }
        System.out.println(out);

        // The claim the node cap's argument rests on: questions name a couple of nodes, not
        // eight. Pinned so that a future hold-out with wider questions makes this fail
        // rather than silently invalidating §8.4's reading.
        assertTrue(percentile(all, 90) <= 3,
                "p90 of nodes named per question should stay small; got " + percentile(all, 90));
    }

    private static int percentile(List<Integer> values, int p) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}
