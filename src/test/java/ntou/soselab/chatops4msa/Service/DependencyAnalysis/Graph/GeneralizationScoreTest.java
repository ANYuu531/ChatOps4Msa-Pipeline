package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scores every static (greenfield) graph under {@code docs/generalization/} against the
 * hand-written ground truth beside it ({@code truth/<name>.tsv}) and writes
 * {@code docs/generalization/scores.md}. The numbers in the generalization report are
 * produced here, not counted by hand: rerun the probe, rerun this, read the table.
 *
 * <p>An edge is a (source, target) pair; direction is as the tool draws it. Truth rows
 * of class {@code variant} (a dependency only a deployment variant has) count neither
 * for nor against. Everything else the tool draws that is not in the truth is a false
 * positive, and every non-variant truth row the tool lacks is a miss.
 */
public class GeneralizationScoreTest {

    private static final Path DIR = Path.of("docs/generalization");

    private record Truth(String source, String target, String cls, String evidence) {
        String key() {
            return source + " -> " + target;
        }
    }

    @Test
    void scoreEveryGraphAgainstItsTruth() throws IOException {
        Path truthDir = DIR.resolve("truth");
        assertTrue(Files.isDirectory(truthDir), "run from the repository root");
        StringBuilder md = new StringBuilder();
        md.append("# 靜態 greenfield 圖 vs ground truth（程式計分）\n\n")
          .append("由 `GeneralizationScoreTest` 產生：讀 `truth/<name>.tsv` 與 `<name>.mmd`，")
          .append("`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。\n\n")
          .append("| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R |\n")
          .append("|---|---|---|---|---|---|---|---|---|---|\n");
        StringBuilder details = new StringBuilder();

        List<Path> truths = new ArrayList<>();
        try (var s = Files.list(truthDir)) {
            s.filter(p -> p.toString().endsWith(".tsv")).sorted().forEach(truths::add);
        }
        assertFalse(truths.isEmpty());

        for (Path truthFile : truths) {
            String name = truthFile.getFileName().toString().replace(".tsv", "");
            // The probe's edge list is the exact record of what the tool drew (the Mermaid
            // file is the picture of it; the emitter's arrow styles are not all readable).
            Path summaryFile = DIR.resolve(name + ".summary.md");
            if (!Files.exists(summaryFile)) continue;
            Map<String, Truth> truth = readTruth(truthFile);

            Set<String> drawn = new LinkedHashSet<>();
            Map<String, String> confidence = new TreeMap<>();
            java.util.regex.Pattern edgeLine = java.util.regex.Pattern.compile(
                    "^- (\\S+) -> (\\S+)\\s+\\(([\\w-]+), (\\w+)\\)");
            for (String line : Files.readAllLines(summaryFile)) {
                java.util.regex.Matcher m = edgeLine.matcher(line);
                if (!m.find()) continue;
                drawn.add(m.group(1) + " -> " + m.group(2));
                confidence.put(m.group(1) + " -> " + m.group(2), m.group(4));
            }

            Set<String> scorable = new LinkedHashSet<>();
            for (Truth t : truth.values()) if (!t.cls.equals("variant")) scorable.add(t.key());

            List<String> right = new ArrayList<>();
            List<String> wrong = new ArrayList<>();
            List<String> missed = new ArrayList<>();
            for (String d : drawn) {
                Truth t = truth.get(d);
                if (t == null) wrong.add(d);
                else if (!t.cls.equals("variant")) right.add(d);
            }
            for (String s : scorable) if (!drawn.contains(s)) missed.add(s);

            int drawnScorable = right.size() + wrong.size();
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %s | %s | %s | %s | %s |\n",
                    name, drawnScorable, right.size(), wrong.size(), missed.size(),
                    ratio(right.size(), drawnScorable), ratio(right.size(), scorable.size()),
                    perClass(truth, drawn, wrong, "business"), perClass(truth, drawn, wrong, "data"),
                    perClass(truth, drawn, wrong, "control")));

            details.append("\n## ").append(name).append('\n');
            if (!wrong.isEmpty()) {
                details.append("\n錯（工具畫了、truth 沒有）：\n");
                for (String w : wrong) details.append("- ").append(w).append("  (").append(confidence.get(w)).append(")\n");
            }
            long inferred = right.stream().filter(r -> "inferred".equals(confidence.get(r))).count();
            if (inferred > 0) details.append("\n對的裡面有 ").append(inferred).append(" 條標 inferred（名字推的，不是表查到的）。\n");
            if (!missed.isEmpty()) {
                details.append("\n漏（truth 有、工具沒畫）：\n");
                for (String m : missed) details.append("- ").append(m).append("  ·  ").append(truth.get(m).evidence).append('\n');
            }
            if (wrong.isEmpty() && missed.isEmpty()) details.append("\n全對。\n");
        }
        md.append(details);
        Files.writeString(DIR.resolve("scores.md"), md.toString());
        System.out.println(md);
    }

    /**
     * P/R restricted to one truth class. A false positive has no class of its own, so it
     * is charged to the class its target's kind implies: a db/queue target to {@code data},
     * a service target to {@code business}. Control-plane precision is not separable and
     * is shown as recall only.
     */
    private static String perClass(Map<String, Truth> truth, Set<String> drawn, List<String> wrong, String cls) {
        int truthCount = 0;
        int hit = 0;
        for (Truth t : truth.values()) {
            if (!t.cls.equals(cls)) continue;
            truthCount++;
            if (drawn.contains(t.key())) hit++;
        }
        if (truthCount == 0) return "—";
        if (cls.equals("control")) return "— / " + ratio(hit, truthCount);
        int fpOfClass = 0;
        for (String w : wrong) {
            String kind = DependencyGraph.classifyKind(w.substring(w.indexOf(" -> ") + 4));
            boolean data = DependencyGraph.KIND_DB.equals(kind) || DependencyGraph.KIND_QUEUE.equals(kind);
            if (cls.equals("data") == data) fpOfClass++;
        }
        return ratio(hit, hit + fpOfClass) + " / " + ratio(hit, truthCount);
    }

    private static String ratio(int a, int b) {
        return b == 0 ? "—" : String.format(Locale.ROOT, "%.2f", (double) a / b);
    }

    private static Map<String, Truth> readTruth(Path file) throws IOException {
        Map<String, Truth> out = new TreeMap<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] cols = line.split("\t");
            assertTrue(cols.length >= 3, "truth row needs source, target, class: " + line);
            Truth t = new Truth(cols[0].trim(), cols[1].trim(), cols[2].trim(), cols.length > 3 ? cols[3].trim() : "");
            out.put(t.key(), t);
        }
        return out;
    }
}
