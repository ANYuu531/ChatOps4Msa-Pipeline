package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Queries Prometheus for the dependency analysis.
 *
 * Exists for two reasons, both of which toolkit-restapi-get gets wrong here:
 *
 * 1. IT MUST NOT ABORT THE RUN. toolkit-restapi-get uses RestTemplate with the
 *    default error handler, so a 502 (or a timeout, or a dead host) throws and
 *    kills the whole dependency analysis — throwing away DeepWiki's five
 *    questions and the repository clone with it. Runtime traffic is only ONE of
 *    several evidence sources; losing it must not lose everything else.
 *
 * 2. IT MUST DISTINGUISH "COULD NOT ASK" FROM "ASKED, AND THERE ARE NO EDGES".
 *    These mean opposite things. An empty result set is real evidence that the
 *    mesh has not seen that traffic. An unreachable Prometheus is no evidence at
 *    all — and reporting it as "no dependencies observed" would be a lie. So a
 *    failure is returned as an explicit UNAVAILABLE block that the prompts are
 *    told to treat as "unknown", never as "absent".
 *
 * The PromQL is also encoded here, which removes the hand-URL-encoded query
 * strings the low-code YAML used to carry (unreadable, and easy to get wrong).
 */
@Component
public class PrometheusToolkit extends ToolkitFunction {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /**
     * @param host_url base URL of Prometheus, e.g. http://192.168.101.4:30090
     * @param promql   the raw PromQL expression (NOT URL-encoded)
     * @return the raw Prometheus JSON response, or an UNAVAILABLE block explaining
     *         why it could not be obtained. Never throws.
     */
    public String toolkitPrometheusQuery(String host_url, String promql) {
        if (host_url == null || host_url.isBlank() || promql == null || promql.isBlank()) {
            return unavailable(host_url, promql, "no Prometheus URL or query was configured");
        }

        String base = host_url.trim().replaceAll("\"", "");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        String url = base + "/api/v1/query?query="
                + URLEncoder.encode(promql.trim(), StandardCharsets.UTF_8);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return unavailable(base, promql,
                        "Prometheus returned HTTP " + response.statusCode()
                                + " (" + firstLine(response.body()) + ")");
            }

            // A reverse proxy can answer 200 with an HTML error page. Parsing the
            // body is what actually proves we reached Prometheus and not a proxy.
            String body = response.body();
            try {
                JSONObject json = new JSONObject(body);
                if (!"success".equals(json.optString("status"))) {
                    return unavailable(base, promql,
                            "Prometheus reported status=" + json.optString("status")
                                    + ": " + json.optString("error", "(no error message)"));
                }
                return body;
            } catch (Exception e) {
                return unavailable(base, promql,
                        "the response was not Prometheus JSON (probably a proxy error page): "
                                + firstLine(body));
            }

        } catch (Exception e) {
            // ConnectException and friends often carry a null message, which would
            // render as "ConnectException: null" and tell the reader nothing.
            String message = e.getMessage();
            String reason = (message == null || message.isBlank())
                    ? e.getClass().getSimpleName() + " — host unreachable (is the endpoint "
                            + "up, and reachable from this container?)"
                    : e.getClass().getSimpleName() + ": " + message;
            return unavailable(base, promql, reason);
        }
    }

    /**
     * The failure is deliberately loud IN THE DATA, so the downstream prompt cannot
     * mistake it for an empty result. "We could not ask" is not "there is nothing".
     */
    private String unavailable(String base, String promql, String detail) {
        return "PROMETHEUS_UNAVAILABLE\n"
                + "Endpoint: " + base + "\n"
                + "Query: " + promql + "\n"
                + "Reason: " + detail + "\n"
                + "\n"
                + "This is NOT an empty result. No runtime evidence could be collected at all, so "
                + "the absence of an edge below proves nothing about whether that call happens. "
                + "Report the runtime dimension as UNKNOWN, never as 'no dependencies observed'.";
    }

    private String firstLine(String body) {
        if (body == null || body.isBlank()) return "empty body";
        String trimmed = body.strip();
        int newline = trimmed.indexOf('\n');
        String line = newline < 0 ? trimmed : trimmed.substring(0, newline);
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }
}
