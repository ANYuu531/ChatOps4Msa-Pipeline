package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.SourceScanner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.ChatCompletions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A <b>second annotator</b> for the hand-written ground truth, so the truth is not one
 * person's word. An LLM that has never seen {@code truth/*.tsv} nor the tool's output is
 * given the same material a human annotator reads — the deployment description, the README
 * and every source line that carries an address — and asked to list the dependencies. Its
 * answer is compared row by row with the author's truth; the disagreements are what a
 * reader should check, and they are listed individually rather than averaged away.
 *
 * <p>This measures <b>agreement between two independent annotators</b>, nothing more. The
 * second annotator is not an oracle: it reads the same repository and can miss or invent,
 * and it is the same family of model the tool itself uses elsewhere (so the two are not
 * fully independent of each other in method, only in that neither saw the other's answer).
 * High agreement means the truth is not idiosyncratic; it does not make the truth correct.
 * The third, independent leg of the argument is the external dataset
 * ({@link ExternalTruthAgreementTest}), which no one here wrote at all.
 *
 * <p>Run (it calls the chat API, so it is skipped unless asked for):
 * <pre>
 * mvn -o test -Dtest=SecondAnnotatorTest -Dannotate=true \
 *   -Dannotate.name=robot-shop -Dannotate.repo=/path/to/checkout
 * </pre>
 * Writes {@code docs/generalization/second-annotator/<name>.md}.
 */
public class SecondAnnotatorTest {

    private static final Path TRUTH = Path.of("docs/generalization/truth");
    private static final Path OUT = Path.of("docs/generalization/second-annotator");

    /** Budgets for the material handed over, so one run stays a few cents. */
    private static final int README_BUDGET = 8_000;
    private static final int DEPLOY_BUDGET = 30_000;
    private static final int CODE_BUDGET = 20_000;

    @Test
    void anIndependentAnnotatorSeesTheSameDependencies() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("annotate")),
                "pass -Dannotate=true: this one calls the chat API");
        String name = System.getProperty("annotate.name", "");
        String repo = System.getProperty("annotate.repo", "");
        Assumptions.assumeTrue(!name.isBlank() && !repo.isBlank(),
                "pass -Dannotate.name=<truth file> -Dannotate.repo=<checkout>");
        Path root = Path.of(repo);
        assertTrue(Files.isDirectory(root), "no such checkout: " + repo);
        Path truthFile = TRUTH.resolve(name + ".tsv");
        assertTrue(Files.exists(truthFile), "no truth file: " + truthFile);

        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream("src/main/resources/application.properties")) {
            props.load(in);
        }
        ChatCompletions chat = ChatCompletions.fromOrNull(props);
        Assumptions.assumeTrue(chat != null, "no openai.api.key in src/main/resources/application.properties");

        String material = material(root);
        assertFalse(material.isBlank(), "nothing to read in " + repo);
        String answer = chat.ask(SYSTEM, "Repository: " + root.getFileName() + "\n\n" + material);

        Set<String> theirs = edgesOf(parse(answer));
        Set<String> ours = new LinkedHashSet<>();
        Map<String, String> evidence = new TreeMap<>();
        for (String line : Files.readAllLines(truthFile)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] cols = line.split("\t");
            if (cols.length < 3) continue;
            if (cols[2].trim().equals("variant")) continue;
            String key = cols[0].trim() + " -> " + cols[1].trim();
            ours.add(key);
            evidence.put(key, cols.length > 3 ? cols[3].trim() : "");
        }
        assertFalse(ours.isEmpty(), "empty truth: " + truthFile);

        List<String> both = new ArrayList<>();
        List<String> onlyOurs = new ArrayList<>();
        for (String e : ours) (theirs.contains(e) ? both : onlyOurs).add(e);
        List<String> onlyTheirs = new ArrayList<>();
        for (String e : theirs) if (!ours.contains(e)) onlyTheirs.add(e);

        StringBuilder md = new StringBuilder();
        md.append("# 第二標註者：").append(name).append("\n\n")
          .append("由 `SecondAnnotatorTest` 產生（模型 `").append(chat.model())
          .append("`，temperature 0，").append(chat.calls()).append(" 次呼叫，prompt ")
          .append(chat.promptTokens()).append(" token）。標註者**沒有看過** `truth/").append(name)
          .append(".tsv`，也沒有看過工具的輸出；它讀的是部署描述、README 與含位址的原始碼行。\n\n")
          .append("量的是**兩個獨立標註者的一致度**，不是誰對：一致度高只代表作者的 truth 不是個人特有的讀法。\n\n")
          .append("| | 條數 |\n|---|---|\n")
          .append("| 兩人都認為存在 | ").append(both.size()).append(" |\n")
          .append("| 只有作者的 truth 有 | ").append(onlyOurs.size()).append(" |\n")
          .append("| 只有第二標註者有 | ").append(onlyTheirs.size()).append(" |\n")
          .append("| 一致度（交集 ÷ 聯集，Jaccard） | **")
          .append(String.format(Locale.ROOT, "%.2f",
                  (double) both.size() / (both.size() + onlyOurs.size() + onlyTheirs.size())))
          .append("** |\n");
        if (!onlyOurs.isEmpty()) {
            md.append("\n## 只有作者的 truth 有（第二標註者沒找到；附作者寫的出處，逐條可查）\n");
            for (String e : onlyOurs) md.append("- ").append(e).append("  ·  ").append(evidence.get(e)).append('\n');
        }
        if (!onlyTheirs.isEmpty()) {
            md.append("\n## 只有第二標註者有（要逐條裁決：truth 漏了，還是標註者看錯）\n");
            for (String e : onlyTheirs) md.append("- ").append(e).append('\n');
        }
        md.append("\n## 標註者的原始回答\n\n```\n").append(answer.strip()).append("\n```\n");

        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name + ".md"), md.toString());
        System.out.println(md);
    }

    private static final String SYSTEM = """
            You are annotating the ground truth for a dependency-graph study. From the
            repository material given, list every dependency between components that the
            material actually states. One per line, tab-separated:

            source<TAB>target<TAB>class<TAB>evidence

            class is one of: business (service calls service), data (service uses a database,
            cache or message broker), external (service calls a host outside the system),
            control (service uses a config server, service registry, tracing collector or
            reverse-proxy front door).

            Rules:
            - Use the names the deployment description uses (a Compose service key, a
              Kubernetes workload name), not source directory names.
            - Only list a dependency the material states. Do not infer from what a system
              like this usually has. If nothing states it, leave it out.
            - evidence must be the file (and line, if you can see it) that states it.
            - Message queues: write consumer -> broker as well as producer -> broker.
            - Output only the rows, no header, no commentary.
            """;

    /** The rows of the answer, tolerating a code fence or stray prose. */
    private static List<String[]> parse(String answer) {
        List<String[]> rows = new ArrayList<>();
        for (String line : answer.split("\n")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("```") || l.startsWith("#")) continue;
            String[] cols = l.split("\t");
            if (cols.length < 2) cols = l.split(" {2,}|\\s*\\|\\s*");
            if (cols.length < 2) continue;
            rows.add(cols);
        }
        return rows;
    }

    private static Set<String> edgesOf(List<String[]> rows) {
        Set<String> out = new LinkedHashSet<>();
        for (String[] r : rows) {
            String source = r[0].strip();
            String target = r[1].strip();
            if (source.isEmpty() || target.isEmpty()) continue;
            out.add(source + " -> " + target);
        }
        return out;
    }

    /**
     * What a human annotator reads: the deployment description (Compose, k8s manifests),
     * the README, and the source lines that carry an address. Deliberately NOT the tool's
     * ledger or graph — the point is an independent reading of the same repository.
     */
    private static String material(Path root) throws Exception {
        StringBuilder sb = new StringBuilder();
        Path readme = root.resolve("README.md");
        if (Files.exists(readme)) {
            sb.append("=== README.md ===\n").append(cut(Files.readString(readme, StandardCharsets.UTF_8), README_BUDGET)).append("\n\n");
        }
        int deployBudget = DEPLOY_BUDGET;
        for (Path f : SourceScanner.filesWithExtensions(root, List.of(".yml", ".yaml", ".conf", ".conf.template"))) {
            if (deployBudget <= 0) break;
            String rel = SourceScanner.relative(root, f);
            String lower = f.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean deployment = lower.startsWith("docker-compose") || lower.startsWith("compose.")
                    || lower.endsWith(".conf") || lower.endsWith(".conf.template")
                    || rel.contains("k8s") || rel.contains("kubernetes") || rel.contains("manifest")
                    || lower.startsWith("application") || lower.startsWith("bootstrap");
            if (!deployment) continue;
            String body = cut(Files.readString(f, StandardCharsets.UTF_8), Math.min(6_000, deployBudget));
            deployBudget -= body.length();
            sb.append("=== ").append(rel).append(" ===\n").append(body).append("\n\n");
        }
        int codeBudget = CODE_BUDGET;
        sb.append("=== source lines that carry an address ===\n");
        java.util.regex.Pattern address = java.util.regex.Pattern.compile(
                "(?i)(https?://|jdbc:|mongodb://|amqp://|redis://|_HOST|_ADDR|_ENDPOINT|_URI|_URL)");
        for (Path f : SourceScanner.filesWithExtensions(root,
                List.of(".java", ".py", ".js", ".go", ".php", ".cs", ".rb", ".ts"))) {
            if (codeBudget <= 0) break;
            String rel = SourceScanner.relative(root, f);
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && codeBudget > 0; i++) {
                if (!address.matcher(lines.get(i)).find()) continue;
                String row = rel + ":" + (i + 1) + "  " + lines.get(i).strip() + "\n";
                codeBudget -= row.length();
                sb.append(row);
            }
        }
        return sb.toString();
    }

    private static String cut(String text, int budget) {
        return text.length() <= budget ? text : text.substring(0, budget) + "\n… (truncated)";
    }
}
