package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the external (outside-the-mesh) hosts out of a code-extracted ledger.
 *
 * "External" is decided deterministically from the hostname shape, because that
 * is knowable without asking the cluster:
 *   - no dot            -> a Kubernetes short service name  (catalogue)        INTERNAL
 *   - *.svc.cluster.local, *.svc, *.local, *.cluster.local                     INTERNAL
 *   - localhost / 127.x / private RFC-1918 IPs                                 INTERNAL
 *   - anything else with a dot (www.googleapis.com, api.stripe.com)            EXTERNAL
 *
 * Placeholder-valued hosts (${...}, {{...}}) are skipped: we do not know the
 * real host, and emitting a ServiceEntry for a literal "${SVC}" would be wrong.
 */
@Component
public class ExternalHostDetector {

    /** Finds URLs embedded anywhere in an edge's field values. */
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>,;)\\]}]+");

    private static final Pattern PRIVATE_IP = Pattern.compile(
            "^(127\\.|10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|0\\.0\\.0\\.0$)");

    /**
     * @return one entry per host:port, with the source evidence merged.
     */
    public List<ExternalHost> detect(EdgeLedger ledger) {
        Map<String, ExternalHost> found = new LinkedHashMap<>();

        for (EdgeLedger.Edge edge : ledger.getEdges()) {
            for (String value : edge.fields.values()) {
                Matcher matcher = URL.matcher(value);
                while (matcher.find()) {
                    ExternalHost host = parse(matcher.group());
                    if (host == null) continue;
                    ExternalHost existing = found.computeIfAbsent(host.key(), k -> host);
                    String where = edge.file + (edge.line > 0 ? ":" + edge.line : "");
                    existing.evidence.add(where);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** @return null when the URL is internal, unparseable, or a placeholder. */
    private ExternalHost parse(String rawUrl) {
        // A target we cannot resolve must not become a ServiceEntry.
        if (rawUrl.contains("${") || rawUrl.contains("{{") || rawUrl.contains("%s")) return null;

        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (Exception e) {
            return null;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) return null;
        host = host.toLowerCase(Locale.ROOT);

        if (!isExternal(host)) return null;

        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port <= 0) port = "https".equals(scheme) ? 443 : 80;
        String protocol = "https".equals(scheme) ? "HTTPS" : "HTTP";

        return new ExternalHost(host, port, protocol);
    }

    private boolean isExternal(String host) {
        if (host.equals("localhost")) return false;
        if (PRIVATE_IP.matcher(host).find()) return false;

        // A bare name with no dot is a Kubernetes short service name.
        if (!host.contains(".")) return false;

        // In-cluster DNS suffixes.
        if (host.endsWith(".svc.cluster.local") || host.endsWith(".cluster.local")
                || host.endsWith(".svc") || host.endsWith(".local")) {
            return false;
        }
        return true;
    }

    /**
     * Renders ready-to-apply ServiceEntry manifests.
     *
     * Note on HTTPS: when the application originates TLS itself, Envoy sees TCP,
     * so the edge shows up in istio_tcp_* metrics (attributed by SNI) rather than
     * in istio_requests_total. The ServiceEntry is still what makes the hostname
     * appear at all instead of PassthroughCluster.
     */
    public String renderServiceEntries(List<ExternalHost> hosts, String namespace) {
        if (hosts.isEmpty()) {
            return "# No external hosts were found in the source code.\n"
                    + "# Nothing to declare: every target resolved to an in-cluster service.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ServiceEntry manifests for the external hosts found in the source code.\n");
        sb.append("# Applying these lets Istio observe and correctly attribute these edges;\n");
        sb.append("# without them the traffic is routed through PassthroughCluster and the\n");
        sb.append("# real hostname is lost from the metrics.\n");
        sb.append("#\n");
        sb.append("#   kubectl apply -f service-entries.yaml\n");
        sb.append("#\n\n");

        for (int i = 0; i < hosts.size(); i++) {
            ExternalHost host = hosts.get(i);
            if (i > 0) sb.append("---\n");
            sb.append("apiVersion: networking.istio.io/v1beta1\n");
            sb.append("kind: ServiceEntry\n");
            sb.append("metadata:\n");
            sb.append("  name: ").append(host.resourceName()).append('\n');
            sb.append("  namespace: ").append(namespace).append('\n');
            sb.append("  annotations:\n");
            sb.append("    chatops4msa.io/evidence: \"")
                    .append(String.join(", ", host.evidence)).append("\"\n");
            sb.append("spec:\n");
            sb.append("  hosts:\n");
            sb.append("    - ").append(host.host).append('\n');
            sb.append("  ports:\n");
            sb.append("    - number: ").append(host.port).append('\n');
            sb.append("      name: ").append(host.protocol.toLowerCase(Locale.ROOT)).append('\n');
            sb.append("      protocol: ").append(host.protocol).append('\n');
            sb.append("  location: MESH_EXTERNAL\n");
            sb.append("  resolution: DNS\n");
        }
        return sb.toString();
    }

    /** A short human summary for the report / health check. */
    public String summarize(List<ExternalHost> hosts) {
        if (hosts.isEmpty()) {
            return "No external hosts were found in the source code.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("External hosts found in source code: ").append(hosts.size()).append('\n');
        for (ExternalHost host : hosts) {
            sb.append("- ").append(host.host).append(':').append(host.port)
                    .append(" (").append(host.protocol).append(')')
                    .append(" [Evidence: ").append(String.join(", ", host.evidence)).append(']');
            if ("HTTPS".equals(host.protocol)) {
                sb.append(" — TLS originated by the app, so runtime evidence will appear in "
                        + "istio_tcp_* metrics, not istio_requests_total");
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
