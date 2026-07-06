package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * In-cluster HTTP traffic driver for end-to-end dependency observation.
 *
 * The ChatOps bot runs as a pod (no docker), so k6-via-docker cannot drive
 * traffic. This toolkit fires N GET requests to an entry URL directly from the
 * pod using the JDK HttpClient. Hitting a microservice landing page makes it
 * fan out to its downstream services, which Istio then records in
 * istio_requests_total.
 *
 * Runs SYNCHRONOUSLY and spreads the requests over ~15-20s, so at least one
 * Prometheus scrape captures the traffic before the pipeline queries the metric.
 */
@Component
public class TrafficToolkit extends ToolkitFunction {

    private static final int MIN_REQUESTS = 1;
    private static final int MAX_REQUESTS = 300;
    private static final long TARGET_TOTAL_MILLIS = 18_000; // spread window so Prometheus scrapes mid-run
    private static final long MIN_GAP_MILLIS = 100;
    private static final long MAX_GAP_MILLIS = 500;

    /**
     * @param url      entry point to drive, e.g. "http://front-end.sock-shop.svc.cluster.local"
     * @param requests number of GET requests to send
     * @return a short summary of the traffic run
     */
    public String toolkitTrafficHttpDrive(String url, String requests) {
        if (url == null || url.isBlank()) {
            return "[traffic] no url provided; skipped.";
        }

        int count;
        try {
            count = Integer.parseInt(requests.trim());
        } catch (NumberFormatException e) {
            count = 50;
        }
        count = Math.max(MIN_REQUESTS, Math.min(MAX_REQUESTS, count));

        long gap = Math.max(MIN_GAP_MILLIS, Math.min(MAX_GAP_MILLIS, TARGET_TOTAL_MILLIS / count));

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        int ok = 0, redirect = 0, clientErr = 0, serverErr = 0, failed = 0;
        Integer firstError = null;

        for (int i = 0; i < count; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("User-Agent", "ChatOps4Msa-traffic-driver")
                        .GET()
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                int sc = response.statusCode();
                if (sc < 300) ok++;
                else if (sc < 400) redirect++;
                else if (sc < 500) { clientErr++; if (firstError == null) firstError = sc; }
                else { serverErr++; if (firstError == null) firstError = sc; }
            } catch (Exception e) {
                failed++;
            }

            if (i < count - 1) {
                try {
                    Thread.sleep(gap);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        boolean anySuccess = ok + redirect > 0;
        return "[traffic] drove " + count + " requests to " + url
                + " over ~" + (count * gap / 1000) + "s"
                + " | 2xx=" + ok + " 3xx=" + redirect + " 4xx=" + clientErr
                + " 5xx=" + serverErr + " failed=" + failed
                + (anySuccess
                    ? " | OK: traffic reached the entry service."
                    : " | WARNING: no request succeeded"
                        + (firstError != null ? " (first error status " + firstError + ")" : "")
                        + " - check the url is reachable from inside the cluster.");
    }
}
