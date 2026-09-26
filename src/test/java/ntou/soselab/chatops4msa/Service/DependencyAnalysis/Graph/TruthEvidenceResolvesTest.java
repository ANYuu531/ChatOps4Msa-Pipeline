package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every citation in the hand-written reference edge sets
 * ({@code docs/generalization/truth/*.tsv}) points at a file a third party can actually
 * open. The point is not tidiness: a reader who cannot find the cited line has no way to
 * disagree with the annotation, and an annotation nobody can check is the weak form of
 * evidence the 2026-09-25 feedback was about. Two citations were simply wrong when this
 * was first run ({@code dispatch/src/main.go} for {@code dispatch/main.go}, and an
 * {@code API.php} that does not exist in Robot Shop at all); both edges were right and
 * only their citations were unusable.
 *
 * <p>Needs the checkouts, so it is skipped unless a directory holding them is given:
 * <pre>
 * mvn -o test -Dtest=TruthEvidenceResolvesTest -Dtruth.repos=/path/to/checkouts
 * </pre>
 * Each project is expected at {@code <dir>/<repo directory name>} as the table below says.
 * Projects whose directory is absent are skipped and counted, so a partial set of checkouts
 * still gives a useful report.
 *
 * <p>Citations that are deliberately not a single file — a generic reference ("every
 * service's application.yml"), a README section, or a Java enum constant used as a call
 * target — are reported separately and do not fail the test.
 */
public class TruthEvidenceResolvesTest {

    private static final Path TRUTH = Path.of("docs/generalization/truth");

    /** truth file name -> the checkout's directory name. */
    private static final Map<String, String> REPOS = new LinkedHashMap<>();

    static {
        REPOS.put("robot-shop", "robot-shop");
        REPOS.put("ewolff-k8s", "microservice-kubernetes");
        REPOS.put("ecommerce", "ecommerce-microservice-backend-app");
        REPOS.put("piggymetrics", "piggymetrics");
        REPOS.put("teastore", "TeaStore");
        REPOS.put("online-boutique", "microservices-demo");
        REPOS.put("bank-of-anthos", "bank-of-anthos");
    }

    /**
     * The first path-shaped token of an evidence cell, with an optional {@code :line}.
     * Dockerfile and Makefile carry no extension but are cited like any other file.
     */
    private static final Pattern CITATION = Pattern.compile(
            "([\\w./\\u2026-]*?(?:[\\w-]+\\.(?:java|py|js|ts|go|php|cs|rb|yml|yaml|conf\\.template|conf|xml|properties|json|md)|Dockerfile|Makefile))(?::(\\d+))?");

    /**
     * An identifier the evidence names — {@code FRONTEND_ADDR}, {@code proxy_pass},
     * {@code redis.createClient} — so that "the file exists" is not the whole check.
     * Only tokens that look like code are used: a SCREAMING_CASE name, a call, or a
     * dotted member. Prose around them is ignored, and a citation that names no such
     * token is checked for existence only.
     */
    private static final Pattern CODE_TOKEN = Pattern.compile(
            "\\b([A-Z][A-Z0-9_]{3,}|[a-z][A-Za-z0-9_]*\\.[a-z][A-Za-z0-9_]*\\(|[a-z][a-z0-9_]{2,}_[a-z0-9_]+)\\b");

    /**
     * The first code-shaped token the evidence names that the cited file does not contain,
     * or null when the citation checks out.
     *
     * <p>Only the text between the citation and the first separator is read, because an
     * evidence cell often names a second source after one ("… Dockerfile:49 ENTRYPOINT …;
     * the value is in loadgenerator.yaml:50") and a token belonging to the second source is
     * not a claim about the first file. The token is looked for anywhere in the file rather
     * than on the cited line: line numbers drift as a repository moves on, and a wrong line
     * is a much smaller problem than a wrong file.
     */
    private static String codeTokenNotInFile(Path file, String evidence, int citationEnd) throws Exception {
        int end = evidence.length();
        for (String separator : List.of("；", ";", "（", "(", "，", ",")) {
            int at = evidence.indexOf(separator, citationEnd);
            if (at >= 0 && at < end) end = at;
        }
        Matcher m = CODE_TOKEN.matcher(evidence.substring(Math.min(citationEnd, end), end));
        if (!m.find()) return null;
        String token = m.group(1).replace("(", "");
        String body = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        return body.contains(token) ? null : token;
    }

    @Test
    void everyCitedFileCanBeOpened() throws Exception {
        String dir = System.getProperty("truth.repos", "");
        Assumptions.assumeTrue(!dir.isBlank(), "pass -Dtruth.repos=<directory holding the checkouts>");
        Path repos = Path.of(dir);
        assertTrue(Files.isDirectory(repos), "no such directory: " + dir);

        int cited = 0;
        int resolvable = 0;
        List<String> broken = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        StringBuilder report = new StringBuilder();

        for (Map.Entry<String, String> e : REPOS.entrySet()) {
            Path truthFile = TRUTH.resolve(e.getKey() + ".tsv");
            Path root = repos.resolve(e.getValue());
            if (!Files.exists(truthFile)) continue;
            if (!Files.isDirectory(root)) {
                skipped.add(e.getKey());
                continue;
            }
            int projectCited = 0;
            int projectOk = 0;
            for (String line : Files.readAllLines(truthFile)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] cols = line.split("\t");
                if (cols.length < 4) continue;
                Matcher m = CITATION.matcher(cols[3]);
                if (!m.find()) continue;
                String path = m.group(1);
                if (path.contains("…")) { // an elided path is not checkable
                    broken.add(e.getKey() + ": elided citation " + path + "  (" + cols[0] + " -> " + cols[1] + ")");
                    projectCited++;
                    continue;
                }
                projectCited++;
                Path file = root.resolve(path);
                if (!Files.exists(file)) {
                    broken.add(e.getKey() + ": " + path + " does not exist  (" + cols[0] + " -> " + cols[1] + ")");
                    continue;
                }
                String missing = codeTokenNotInFile(file, cols[3], m.end());
                if (missing != null) {
                    broken.add(e.getKey() + ": " + path + " does not contain " + missing
                            + "  (" + cols[0] + " -> " + cols[1] + ")");
                    continue;
                }
                projectOk++;
            }
            cited += projectCited;
            resolvable += projectOk;
            report.append(String.format(Locale.ROOT, "%-16s cited=%3d openable=%3d%n", e.getKey(), projectCited, projectOk));
        }

        report.append(String.format(Locale.ROOT, "%-16s cited=%3d openable=%3d%n", "TOTAL", cited, resolvable));
        if (!skipped.isEmpty()) report.append("skipped (no checkout): ").append(skipped).append('\n');
        for (String b : broken) report.append("BROKEN  ").append(b).append('\n');
        System.out.println(report);

        Assumptions.assumeTrue(cited > 0, "no checkouts found under " + dir);
        assertTrue(broken.isEmpty(), "citations that cannot be opened:\n" + String.join("\n", broken));
    }
}
