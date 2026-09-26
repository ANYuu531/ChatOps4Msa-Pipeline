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

    /** The first path-shaped token of an evidence cell, with an optional {@code :line}. */
    private static final Pattern CITATION = Pattern.compile(
            "([\\w./\\u2026-]*?[\\w-]+\\.(?:java|py|js|ts|go|php|cs|rb|yml|yaml|conf\\.template|conf|xml|properties|json|md))(?::(\\d+))?");

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
                if (Files.exists(root.resolve(path))) {
                    projectOk++;
                } else {
                    broken.add(e.getKey() + ": " + path + " does not exist  (" + cols[0] + " -> " + cols[1] + ")");
                }
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
