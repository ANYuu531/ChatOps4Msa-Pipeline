package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    // Common gateway->backend paths (microservice-demo / sock-shop style); harmless 404s elsewhere.
    private static final String[] EXTRA_PATHS =
            {"/catalogue", "/tags", "/cart", "/orders", "/customers", "/category.html"};
    private static final long TARGET_TOTAL_MILLIS = 18_000; // spread window so Prometheus scrapes mid-run
    private static final long MIN_GAP_MILLIS = 100;
    private static final long MAX_GAP_MILLIS = 500;

    /**
     * Query Istio runtime edges from Prometheus, FAIL-SOFT.
     *
     * toolkit-restapi-get throws on any non-2xx (e.g. a transient 502 from the Prometheus
     * proxy), which aborts the whole dependency-analysis pipeline. This wrapper never
     * throws: on any error it returns a short note so the report can still be produced
     * (with runtime edges simply marked unavailable this run).
     *
     * @param prometheus_url base Prometheus URL, e.g. "https://.../"
     * @param namespace      namespace to filter destination workloads by
     * @return the raw Prometheus JSON on success, or a "[istio-query] ..." note on failure
     */
    private static final String IN_CLUSTER_PROMETHEUS = "http://prometheus.istio-system:9090";

    public String toolkitTrafficQueryIstio(String prometheus_url, String namespace) {
        String promql = "sum by (source_workload, destination_workload) "
                + "(istio_requests_total{reporter=\"destination\",destination_workload_namespace=\"" + namespace + "\"})";
        String queryPath = "/api/v1/query?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8);

        StringBuilder problems = new StringBuilder();
        // Try the configured endpoint first, then fall back to the in-cluster Prometheus
        // (the external proxy has been observed returning transient 502s).
        for (String base : new String[]{prometheus_url, IN_CLUSTER_PROMETHEUS}) {
            if (base == null || base.isBlank()) continue;
            String b = base.trim();
            if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            String body = tryQuery(b + queryPath);
            if (body != null) return body;
            problems.append(b).append(" -> failed; ");
        }
        return "[istio-query] Prometheus query failed (" + problems + "); "
                + "runtime edges unavailable this run. namespace=" + namespace;
    }

    /** Returns the response body on 2xx, or null on any failure (never throws). */
    private String tryQuery(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int sc = response.statusCode();
            return (sc >= 200 && sc < 300) ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One-shot: resolve the entry and drive traffic, or explain the manual fallback.
     * Self-contained so the pipeline needs no mid-flow toolkit-flow-if (which the
     * orchestrator treats as terminal and would abort the rest of the pipeline).
     *
     * @param entry_url    'auto' | a URL | 'none'/other
     * @param namespace    namespace being analyzed
     * @param k8s_services raw "kubectl get services ..." output
     * @param requests     number of GET requests when driving
     * @return a summary of what happened (drove traffic, or manual fallback guidance)
     */
    public String toolkitTrafficAutoDrive(String entry_url, String namespace, String k8s_services, String requests) {
        String url = toolkitTrafficResolveEntry(entry_url, namespace, k8s_services);
        if (url.isEmpty()) {
            return "[traffic] No traffic auto-driven "
                    + "(entry_url='" + entry_url + "'; no NodePort/LoadBalancer entry auto-detected). "
                    + "Drive traffic manually through the system, then run supplement-dependency-traffic "
                    + "namespace=" + namespace + ", or re-run with entry_url set to a reachable in-cluster URL. "
                    + "Any traffic already observed in the mesh is still included.";
        }
        return "Resolved entry: " + url + "\n" + toolkitTrafficHttpDrive(url, requests);
    }

    /**
     * Resolve the effective entry URL to drive.
     *
     * @param entry_url    user input: a URL (used as-is), "auto" (detect from services),
     *                     or anything else / "none" (returns empty -> manual mode).
     * @param namespace    the namespace being analyzed.
     * @param k8s_services raw output of the pipeline's "kubectl get services ..." (custom-columns
     *                     NAME TYPE PORTS ... --no-headers).
     * @return an "http://..." URL to drive, or "" if it should fall back to manual.
     */
    public String toolkitTrafficResolveEntry(String entry_url, String namespace, String k8s_services) {
        if (entry_url != null) {
            String e = entry_url.trim();
            if (e.startsWith("http://") || e.startsWith("https://")) return e;   // explicit override
            if (!e.equalsIgnoreCase("auto")) return "";                          // "none" / anything -> manual
        }
        // auto-detect: pick a NodePort / LoadBalancer service as the entry.
        if (k8s_services == null || k8s_services.isBlank()) return "";

        String best = null, bestPort = null;
        int bestScore = -1;
        for (String rawLine : k8s_services.replace("\\n", "\n").split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            if (t.length < 3) continue;
            String name = t[0];
            String type = t[1];
            if (!type.equalsIgnoreCase("NodePort") && !type.equalsIgnoreCase("LoadBalancer")) continue;

            String port = firstPort(t[2]);
            if (port == null) continue;

            int score = nameScore(name);
            if (score > bestScore) {
                bestScore = score;
                best = name;
                bestPort = port;
            }
        }
        if (best == null) return "";
        String portSuffix = "80".equals(bestPort) ? "" : ":" + bestPort;
        return "http://" + best + "." + namespace + ".svc.cluster.local" + portSuffix;
    }

    /** Entry URL first, then origin + each common API path. */
    private java.util.List<String> buildTargets(String url) {
        java.util.List<String> targets = new java.util.ArrayList<>();
        targets.add(url);
        try {
            URI u = URI.create(url);
            String origin = u.getScheme() + "://" + u.getAuthority();
            for (String p : EXTRA_PATHS) targets.add(origin + p);
        } catch (Exception ignored) {
            // malformed url: just drive the entry as given
        }
        return targets;
    }

    /** Prefer names that look like a user-facing front door. */
    private int nameScore(String name) {
        String n = name.toLowerCase();
        for (String hint : new String[]{"front", "gateway", "ingress", "productpage", "ui", "web", "portal", "api"}) {
            if (n.contains(hint)) return 2;
        }
        return 1;
    }

    private String firstPort(String portsField) {
        for (String p : portsField.split("[,;/]")) {
            String d = p.replaceAll("[^0-9].*$", "").trim();
            if (!d.isEmpty()) return d;
        }
        return null;
    }

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

        // Hit the entry itself PLUS common gateway->backend API paths, so the entry
        // service fans out to its downstreams (a plain GET of a UI root usually does
        // not trigger the backend calls that a browser makes via JS). Unknown paths
        // just 404 harmlessly on other apps.
        java.util.List<String> targets = buildTargets(url);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        int ok = 0, redirect = 0, clientErr = 0, serverErr = 0, failed = 0;
        Integer firstError = null;

        for (int i = 0; i < count; i++) {
            String target = targets.get(i % targets.size());
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(target))
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
                + " (+" + (targets.size() - 1) + " api paths)"
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
