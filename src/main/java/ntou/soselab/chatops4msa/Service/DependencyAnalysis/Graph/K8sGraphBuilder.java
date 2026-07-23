package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enriches a {@link DependencyGraph} with Kubernetes deployment status,
 * <b>deterministically</b> — no LLM. This is what turns "a node in the graph"
 * into "a workload that is (or is not) actually running in the cluster": a
 * service the code/docs reference but the cluster never deployed (e.g.
 * {@code genai-service}) is drawn differently from a live one, and a live one
 * carries its deployment metadata (image, replicas, when it was created).
 *
 * The input is the raw text of a deployments query shaped for machine reading:
 * <pre>
 *   kubectl get deployments -n &lt;ns&gt; -o custom-columns=\
 *     NAME:.metadata.name,CREATED:.metadata.creationTimestamp,\
 *     READY:.status.readyReplicas,REPLICAS:.spec.replicas,\
 *     IMAGE:.spec.template.spec.containers[0].image --no-headers
 * </pre>
 * i.e. one deployment per line, exactly five whitespace-separated single-token
 * columns.
 *
 * That kubectl stdout does not arrive raw: the k8s MCP server returns a
 * {@code CommandResult} whose {@code output} field carries the stdout, which the
 * MCP toolkit serialises to JSON and wraps in a Markdown envelope. So the real
 * stored value is a JSON blob (inside Markdown) where the deployment lines are one
 * JSON-escaped string ({@code "output":"...\n..."}). We therefore first lift the
 * {@code output} value out of the JSON (unescaping it back to real lines) and only
 * fall back to treating the input as plain text when there is no such field.
 *
 * Parsing is then line-oriented and tolerant: any line that does not match the
 * shape (a wrapper envelope, a header, an error page) is skipped, so the
 * deterministic graph is never corrupted by unexpected output — at worst the
 * deployment styling is simply absent.
 *
 * Deployment status is only asserted where it is meaningful:
 * <ul>
 *   <li>a node matching a Deployment → {@code deployed = TRUE} (+ metadata)</li>
 *   <li>a {@code service} with NO match → {@code deployed = FALSE} (a workload was
 *       expected, none runs)</li>
 *   <li>a gateway / db / queue / external with no match → left {@code null}:
 *       gateways commonly live in another namespace (istio-ingressgateway), and
 *       datastores/brokers/externals are legitimately externally managed, so their
 *       absence from this namespace's Deployment inventory is not evidence they are
 *       "not deployed".</li>
 * </ul>
 * When the input is blank (an old checkpoint captured before this stage existed)
 * nothing is asserted at all, so the graph is unchanged.
 */
public class K8sGraphBuilder {

    /** What the cluster reports about one deployed workload. */
    private static final class Deploy {
        final String createdAt;
        final String image;
        final String replicas; // "ready/desired"

        Deploy(String createdAt, String image, String replicas) {
            this.createdAt = createdAt;
            this.image = image;
            this.replicas = replicas;
        }
    }

    /** An ISO-8601 UTC instant is how kubectl renders creationTimestamp; used to recognise a data line. */
    private static final Pattern ISO_INSTANT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z?");

    private K8sGraphBuilder() {
    }

    /**
     * Enriches every node in {@code graph} in place with deployment status parsed
     * from {@code deploymentsRaw}. A no-op (graph unchanged) when the input is
     * blank or nothing parseable is found, so it never introduces false negatives.
     */
    public static void enrich(DependencyGraph graph, String deploymentsRaw) {
        if (graph == null || deploymentsRaw == null || deploymentsRaw.isBlank()) return;

        Map<String, Deploy> deployed = parse(deploymentsRaw);
        if (deployed.isEmpty()) return; // nothing usable parsed -> assert nothing

        for (DependencyGraph.Node node : graph.getNodes()) {
            Deploy d = lookup(deployed, node.id);
            if (d != null) {
                node.deployed = Boolean.TRUE;
                node.deployedAt = d.createdAt;
                node.image = d.image;
                node.replicas = d.replicas;
            } else if (DependencyGraph.KIND_SERVICE.equals(node.kind)) {
                // A service the graph references but the cluster does not run.
                // (Only services: gateways may live in another namespace, and
                //  db/queue/external are commonly externally managed.)
                node.deployed = Boolean.FALSE;
            }
        }
    }

    /** name (lower-cased) -&gt; deployment metadata. Tolerant, line-oriented parse. */
    private static Map<String, Deploy> parse(String raw) {
        Map<String, Deploy> out = new LinkedHashMap<>();
        String text = liftKubectlOutput(raw);
        for (String line : text.split("\\R")) {
            String[] c = line.trim().split("\\s+");
            if (c.length != 5) continue;                       // not a 5-column data row
            if (!ISO_INSTANT.matcher(c[1]).find()) continue;   // 2nd column must be creationTimestamp
            String name = c[0].toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.equals("name")) continue;
            String ready = "<none>".equals(c[2]) ? "0" : c[2];
            String desired = "<none>".equals(c[3]) ? "0" : c[3];
            out.put(name, new Deploy(c[1], c[4], ready + "/" + desired));
        }
        return out;
    }

    /**
     * Lifts the kubectl stdout out of the MCP {@code CommandResult} JSON, unescaping
     * it back to real lines. Returns {@code raw} unchanged when there is no
     * {@code "output"} field (so a plain-text input still parses as-is). Robust to
     * the surrounding Markdown envelope and to pretty- or compact-printed JSON,
     * since it only reads the one string value after the {@code "output"} key.
     */
    static String liftKubectlOutput(String raw) {
        if (raw == null) return "";
        int key = raw.indexOf("\"output\"");
        if (key < 0) return raw; // plain text (e.g. a direct kubectl dump, or a test fixture)

        // Advance to the opening quote of the value: "output" <ws>? : <ws>? "
        int i = key + "\"output\"".length();
        while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
        if (i >= raw.length() || raw.charAt(i) != ':') return raw;
        i++;
        while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
        if (i >= raw.length() || raw.charAt(i) != '"') return raw;
        i++;

        StringBuilder sb = new StringBuilder();
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '"') break;                 // end of the JSON string value
            if (c == '\\' && i + 1 < raw.length()) {
                char e = raw.charAt(++i);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 < raw.length()) {
                            try {
                                sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignore) {
                                sb.append('\\').append('u');
                            }
                        } else {
                            sb.append('\\').append('u');
                        }
                    }
                    default -> sb.append(e); // unknown escape: keep the char verbatim
                }
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Matches a node id to a Deployment name: exact (case-insensitive) first, then
     * the Istio-workload / versioned-deployment equivalence (one being the other
     * plus a {@code -suffix}, e.g. node {@code customers-service} vs deployment
     * {@code customers-service-v1}).
     */
    private static Deploy lookup(Map<String, Deploy> deployed, String nodeId) {
        if (nodeId == null) return null;
        String id = nodeId.toLowerCase(Locale.ROOT);
        Deploy exact = deployed.get(id);
        if (exact != null) return exact;
        for (Map.Entry<String, Deploy> e : deployed.entrySet()) {
            String dep = e.getKey();
            if (dep.startsWith(id + "-") || id.startsWith(dep + "-")) return e.getValue();
        }
        return null;
    }
}
