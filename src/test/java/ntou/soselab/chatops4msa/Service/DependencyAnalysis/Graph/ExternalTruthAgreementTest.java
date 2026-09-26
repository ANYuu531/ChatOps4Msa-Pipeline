package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scores the tool's static graphs against a ground truth <b>nobody here wrote</b>: the
 * dependency graphs of the MicroDepGraph dataset (Rahman, Panichella &amp; Taibi, SattoSE
 * 2019), vendored under {@code src/test/resources/graphs/microdepgraph/}.
 *
 * <p>Why this test exists: the generalization report's numbers are scored against a truth
 * file this project's author wrote by hand ({@code docs/generalization/truth/*.tsv}). That
 * is a construct-validity problem — the same person decided what the answer was and built
 * the thing being measured. Seven of the projects the dataset covers can be scored without
 * that dependence: the dataset's authors published their graphs, and the tool is run on the
 * same repositories at a commit from before the dataset was captured (2021-02-26), so the
 * two describe the same code.
 *
 * <p>The two are not measuring quite the same thing and the report must say so. MicroDepGraph
 * derives edges from Compose {@code depends_on}/{@code links} and internal API calls, and
 * keeps infrastructure (eureka, rabbitmq, zipkin) as ordinary nodes; it has no notion of an
 * external host or of evidence grade. So this test reports <b>agreement</b>, not precision:
 * how much of the dataset's edge set the tool draws (it should draw nearly all of it), and
 * every edge only one side has, listed individually so each can be traced to a cause.
 *
 * <p>Inputs are the probe's own output ({@code docs/generalization/external/<name>.summary.md},
 * produced by {@code GreenfieldProbeTest} — see {@code docs/generalization-external.md} for
 * the commands); the table goes to {@code docs/generalization/external-agreement.md}.
 */
public class ExternalTruthAgreementTest {

    private static final Path DIR = Path.of("docs/generalization/external");
    private static final Path CORPUS = Path.of("src/test/resources/graphs/microdepgraph");

    /**
     * @param name     the probe output's basename
     * @param graphml  the dataset's graph for the same project
     * @param repo     the GitHub repository both describe
     * @param commit   the commit the tool was run on — the last one before the dataset's
     *                 capture date, so the two see the same code
     * @param date     that commit's date
     */
    private record Case(String name, String graphml, String repo, String commit, String date) {
    }

    /** The dataset's project list resolves to these repositories (its README's short links). */
    private static final List<Case> CASES = List.of(
            new Case("spring-petclinic", "spring-petclinic", "spring-petclinic/spring-petclinic-microservices",
                    "8e446ae7218392af7a897fdcec82be6b1db49518", "2021-02-06"),
            new Case("microservices-book", "Microservices_book", "ewolff/microservice",
                    "99d9d07d1b68285d2ceefd8a811f31788ee43d75", "2020-09-17"),
            new Case("tap-and-eat", "Tap-And-Eat-MicroServices", "jferrater/Tap-And-Eat-MicroServices",
                    "3ad20b8fa421dc837ef423270a9bf9ece1615a03", "2017-01-04"),
            new Case("spring-cloud-netflix", "spring-cloud-netflix", "yidongnan/spring-cloud-netflix-example",
                    "3b86bf0e20a7c7da8f4e3e7e2cb15bf4cd407743", "2020-09-11"),
            new Case("spring-cloud-microservice", "spring-cloud-microservice", "zpng/spring-cloud-microservice-examples",
                    "6938297335e924f8066f5558b79ee82fa204c4ee", "2017-03-23"),
            new Case("lakeside-mutual", "Lakeside", "Microservice-API-Patterns/LakesideMutual",
                    "4fc6b430da8a8c5db9a8d5918117e3c7a6a89c6d", "2021-02-26"),
            new Case("robot-shop", "Robot_Shop", "instana/robot-shop",
                    "2fcc0c9835dbb8c0c2db54f888608891f72aa308", "2021-02-24"));

    /**
     * Projects whose node names the dataset and the tool spell identically and where every
     * dataset edge must be drawn. Pinning these is the regression guard for the vocabulary
     * rules (a Compose file's service names are the deployment's names): before them, four
     * of the seven had every edge right and not one node name in common with the dataset.
     */
    private static final Set<String> FULL_AGREEMENT = Set.of(
            "microservices-book", "tap-and-eat", "spring-cloud-netflix", "lakeside-mutual", "robot-shop");

    @Test
    void everyDatasetEdgeIsDrawnOrExplained() throws Exception {
        assertTrue(Files.isDirectory(CORPUS), "run from the repository root");
        assertTrue(Files.isDirectory(DIR), "run GreenfieldProbeTest first — see docs/generalization-external.md");

        StringBuilder md = new StringBuilder();
        md.append("# 對照第三方資料集的邊集（程式計分，標準答案不是我們寫的）\n\n")
          .append("由 `ExternalTruthAgreementTest` 產生。參考邊集＝MicroDepGraph 資料集（Rahman, Panichella & Taibi, ")
          .append("SattoSE 2019）**自己發表的依賴圖**，不是本專案作者標註的 `truth/*.tsv`。\n")
          .append("工具跑的是同一個 repo 在**資料集擷取日（2021-02-26）之前**的 commit，所以兩邊看的是同一份程式碼。\n\n")
          .append("兩邊的「依賴」定義不同（資料集＝Compose `depends_on`/`links` ＋ 內部 API 呼叫，")
          .append("基礎設施當一般節點，沒有外部主機與證據等級），所以這裡報的是**一致度**而不是 precision：")
          .append("資料集的邊工具畫到多少（漏了就是工具的問題），以及只有一邊有的邊**逐條列出**，每條都要能追到原因。\n\n")
          .append("| 專案 | commit（日期） | 資料集邊 | 工具畫到 | 一致度 | 工具另外畫了 |\n")
          .append("|---|---|---|---|---|---|\n");
        StringBuilder details = new StringBuilder();
        // The same numbers as a CSV, so the figure is drawn from what the scorer counted
        // rather than from the table being retyped (docs/charts/plot_external_agreement.py).
        StringBuilder csv = new StringBuilder("project,commit,date,dataset_edges,drawn,agreement,tool_only\n");

        int datasetTotal = 0;
        int drawnTotal = 0;
        Map<String, Double> perProject = new LinkedHashMap<>();

        for (Case c : CASES) {
            Path summary = DIR.resolve(c.name + ".summary.md");
            assertTrue(Files.exists(summary), "missing probe output: " + summary);
            Map<String, String> drawn = drawnEdges(summary);
            Set<String> dataset = datasetEdges(CORPUS.resolve(c.graphml + ".graphml"));
            assertFalse(dataset.isEmpty(), "unreadable dataset graph: " + c.graphml);

            List<String> both = new ArrayList<>();
            List<String> datasetOnly = new ArrayList<>();
            for (String e : dataset) (drawn.containsKey(e) ? both : datasetOnly).add(e);
            List<String> toolOnly = new ArrayList<>();
            for (String e : drawn.keySet()) if (!dataset.contains(e)) toolOnly.add(e);

            datasetTotal += dataset.size();
            drawnTotal += both.size();
            perProject.put(c.name, (double) both.size() / dataset.size());

            md.append(String.format(Locale.ROOT, "| %s | [`%s`](https://github.com/%s/tree/%s) (%s) | %d | %d | **%s** | %d |\n",
                    c.name, c.commit.substring(0, 7), c.repo, c.commit, c.date,
                    dataset.size(), both.size(), ratio(both.size(), dataset.size()), toolOnly.size()));

            csv.append(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%.4f,%d%n",
                    c.name, c.commit.substring(0, 7), c.date, dataset.size(), both.size(),
                    (double) both.size() / dataset.size(), toolOnly.size()));

            details.append("\n## ").append(c.name).append('\n');
            if (datasetOnly.isEmpty()) {
                details.append("\n資料集的 ").append(dataset.size()).append(" 條邊**全部畫到**。\n");
            } else {
                details.append("\n資料集有、工具沒畫（").append(datasetOnly.size()).append(" 條）：\n");
                for (String e : datasetOnly) details.append("- ").append(e).append('\n');
            }
            if (!toolOnly.isEmpty()) {
                details.append("\n工具畫了、資料集沒有（").append(toolOnly.size())
                       .append(" 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：\n");
                for (String e : toolOnly) {
                    details.append("- ").append(e).append("  (").append(drawn.get(e)).append(")\n");
                }
            }
        }

        md.append(String.format(Locale.ROOT, "| **合計** | 7 個專案 | **%d** | **%d** | **%s** | — |\n",
                datasetTotal, drawnTotal, ratio(drawnTotal, datasetTotal)));
        md.append(details);
        Files.writeString(DIR.getParent().resolve("external-agreement.md"), md.toString());
        Files.writeString(DIR.getParent().resolve("external-agreement.csv"), csv.toString());
        System.out.println(md);

        for (String name : FULL_AGREEMENT) {
            assertTrue(perProject.getOrDefault(name, 0.0) >= 1.0,
                    name + ": every dataset edge must still be drawn, got " + perProject.get(name));
        }
        assertTrue((double) drawnTotal / datasetTotal >= 0.95,
                "agreement with the third-party edge set dropped to " + ratio(drawnTotal, datasetTotal));
    }

    /** {@code source -> target} of every edge the probe's summary records, with its confidence. */
    private static Map<String, String> drawnEdges(Path summary) throws IOException {
        Map<String, String> out = new TreeMap<>();
        java.util.regex.Pattern line = java.util.regex.Pattern.compile("^- (\\S+) -> (\\S+)\\s+\\(([\\w-]+), (\\w+)\\)");
        for (String l : Files.readAllLines(summary)) {
            java.util.regex.Matcher m = line.matcher(l);
            if (m.find()) out.put(m.group(1) + " -> " + m.group(2), m.group(3) + ", " + m.group(4));
        }
        return out;
    }

    private static Set<String> datasetEdges(Path graphml) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(graphml.toFile());
        NodeList edges = doc.getElementsByTagName("edge");
        for (int i = 0; i < edges.getLength(); i++) {
            Element e = (Element) edges.item(i);
            String source = e.getAttribute("source");
            String target = e.getAttribute("target");
            if (!source.isBlank() && !target.isBlank()) out.add(source + " -> " + target);
        }
        return out;
    }

    private static String ratio(int a, int b) {
        return b == 0 ? "—" : String.format(Locale.ROOT, "%.2f", (double) a / b);
    }
}
