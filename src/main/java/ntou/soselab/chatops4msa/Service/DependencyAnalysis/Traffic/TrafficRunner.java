package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic;

import com.jayway.jsonpath.JsonPath;
import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a TrafficScenario (a Postman Collection v2.1) against the system under
 * analysis, so that Istio can observe the dependency edges those requests trigger.
 *
 * This is our own in-process executor over the JDK's HttpClient, NOT Newman. The
 * collection is a fully standard, Newman-runnable artefact; running it ourselves is
 * a deliberate engineering choice, for four reasons:
 *  - Pod portability: Newman is a Node app, needing either Node in the image or a
 *    sibling container (docker.sock) — the latter is impossible when ChatOps4Msa
 *    runs as a Kubernetes pod with no Docker daemon. This is the same wall that
 *    ruled out a k6 container. A pure JDK HttpClient adds zero runtime.
 *  - Safety: the collection is LLM-generated. Newman executes any embedded JS; we
 *    only interpret a declarative subset (requests + variables + JsonPath capture),
 *    so LLM-authored scripts cannot run arbitrary code against a real cluster.
 *  - This is traffic OBSERVATION, not test assertion: a 4xx/5xx is not a failure,
 *    it still proves the edge into that endpoint. We treat every response as
 *    evidence and emit a "step -> status -> intended edge" report that feeds the
 *    coverage-refinement loop; Newman's pass/fail model has no notion of edges.
 *  - We need full control of a small subset: retry, cookie jar, variable
 *    substitution, UNREACHABLE gap reporting, report truncation, timeouts.
 *
 * A cookie jar is kept across steps, because session-cookie logins (sock-shop) are
 * otherwise impossible to carry through a journey.
 *
 * Requests are NOT expected to all succeed. A 4xx/5xx still proves the edge into
 * the endpoint, and is reported rather than treated as a failure — but note it
 * only proves the FIRST hop: if a service rejects input before calling
 * downstream, the deeper edges still will not appear.
 */
@Component
public class TrafficRunner {

    /** Postman variable reference: {{name}}. */
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([^}]+)}}");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_STEPS = 60;
    private static final int MAX_BODY_SNIPPET = 400;

    public static class StepResult {
        public String name;
        public String method;
        public String url;
        public int status;
        public String error;
        /** An UNREACHABLE: item — a declared gap, deliberately not sent. */
        public boolean unreachable;
        public final Map<String, String> captured = new LinkedHashMap<>();
        public String exercises = "";
    }

    public static class RunReport {
        public final List<StepResult> steps = new ArrayList<>();
        public int executed;
        public int transportErrors;
        /** Non-fatal parse problems (skipped items, unsupported captures) surfaced to the user. */
        public final List<String> warnings = new ArrayList<>();

        /** A plain-text report; it is fed back to the LLM when refining coverage. */
        public String render(String baseUrl, int repeats) {
            StringBuilder sb = new StringBuilder();
            sb.append("# Traffic Execution Report\n\n");
            sb.append("Target: ").append(baseUrl).append('\n');
            sb.append("Steps executed: ").append(executed)
                    .append(" (each repeated ").append(repeats).append("x)\n");
            sb.append("Transport errors (never reached the service): ")
                    .append(transportErrors).append("\n\n");

            if (!warnings.isEmpty()) {
                sb.append("Collection warnings (parsed leniently):\n");
                for (String warning : warnings) sb.append("- ").append(warning).append('\n');
                sb.append('\n');
            }

            for (StepResult step : steps) {
                String label = step.unreachable ? "GAP"
                        : step.error != null ? "ERROR" : String.valueOf(step.status);
                sb.append("- [").append(label).append("] ").append(step.method).append(' ').append(step.url);
                if (!step.name.isBlank()) sb.append("  (").append(step.name).append(')');
                sb.append('\n');
                if (!step.exercises.isBlank()) {
                    sb.append(step.unreachable ? "    gap: " : "    intended to exercise: ")
                            .append(step.exercises).append('\n');
                }
                if (step.error != null) {
                    sb.append("    error: ").append(step.error).append('\n');
                }
                if (!step.captured.isEmpty()) {
                    sb.append("    captured: ").append(step.captured.keySet()).append('\n');
                }
            }

            sb.append("\n## How to read this\n");
            sb.append("- A 4xx/5xx still proves a request reached that endpoint, so the edge INTO it "
                    + "is observed. It does NOT prove the endpoint's downstream calls happened: a "
                    + "service that rejects the input before calling downstream produces no deeper edge.\n");
            sb.append("- A transport error means the request never arrived; that step produced no "
                    + "evidence at all.\n");
            sb.append("- A [GAP] step is an UNREACHABLE: marker the generator emitted on purpose; it "
                    + "was not sent. The edge it names still needs whatever it says is missing.\n");
            sb.append("- A step whose capture is missing means later steps depending on that variable "
                    + "were sent with an unresolved placeholder and probably failed.\n");
            return sb.toString();
        }
    }

    /**
     * @param baseUrl the ingress entry point, e.g. http://192.168.101.4
     * @param repeats how many times to run the whole scenario (a couple of passes
     *                make the counters unambiguous; correctness does not need it)
     */
    public RunReport run(TrafficScenario scenario, String baseUrl, int repeats) {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        Map<String, String> variables = new LinkedHashMap<>(scenario.variables);
        // Expose the entry point as {{baseUrl}} so collections written with an
        // absolute {{baseUrl}}/path (the Postman convention) resolve here; relative
        // paths keep working too. The entry_url wins over any collection default.
        variables.put("baseUrl", base);

        RunReport report = new RunReport();
        report.warnings.addAll(scenario.warnings);

        for (int pass = 0; pass < Math.max(1, repeats); pass++) {
            int stepCount = 0;
            for (TrafficScenario.Step step : scenario.steps) {
                if (stepCount++ >= MAX_STEPS) break;

                StepResult result = execute(client, step, base, variables);
                // Only the first pass is reported; later passes just re-drive the traffic.
                if (pass == 0) {
                    report.steps.add(result);
                    if (result.unreachable) continue;
                    report.executed++;
                    if (result.error != null) report.transportErrors++;
                }
            }
        }
        return report;
    }

    private StepResult execute(HttpClient client, TrafficScenario.Step step,
                               String base, Map<String, String> variables) {
        StepResult result = new StepResult();
        result.name = step.name;
        result.method = step.method;
        result.exercises = step.exercises;

        // An UNREACHABLE: marker is a declared gap, not a request. Record it so the
        // user sees the edge that still needs something, and send nothing.
        if (!step.send) {
            result.unreachable = true;
            result.url = "(not sent)";
            return result;
        }

        String path = substitute(step.path, variables);
        String url = path.startsWith("http://") || path.startsWith("https://")
                ? path
                : base + (path.startsWith("/") ? path : "/" + path);
        result.url = url;

        // An unresolved {{...}} means an earlier capture did not happen. Say so
        // plainly: this report is fed back to the next round of scenario
        // generation, and "Illegal character in path" would not tell it anything.
        Matcher unresolved = VARIABLE.matcher(url);
        if (unresolved.find()) {
            result.error = "not sent: the variable {{" + unresolved.group(1)
                    + "}} was never captured by an earlier step";
            return result;
        }

        try {
            String body = step.body == null ? null : substitute(step.body, variables);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT);

            for (Map.Entry<String, String> header : step.headers.entrySet()) {
                String value = substitute(header.getValue(), variables);
                // An unresolved {{...}} would be sent literally; skip rather than
                // send a bogus Authorization header.
                if (VARIABLE.matcher(value).find()) continue;
                builder.header(header.getKey(), value);
            }
            if (body != null && !step.headers.containsKey("Content-Type")) {
                builder.header("Content-Type", "application/json");
            }

            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            builder.method(step.method, publisher);

            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            result.status = response.statusCode();

            for (Map.Entry<String, String> capture : step.capture.entrySet()) {
                try {
                    Object value = JsonPath.read(response.body(), capture.getValue());
                    if (value != null) {
                        variables.put(capture.getKey(), String.valueOf(value));
                        result.captured.put(capture.getKey(), truncate(String.valueOf(value)));
                    }
                } catch (Exception e) {
                    // The path was not in the response: later steps will notice.
                }
            }

        } catch (Exception e) {
            result.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return result;
    }

    /** Replaces {{name}} with a previously captured or predefined variable. */
    private String substitute(String template, Map<String, String> variables) {
        if (template == null || !template.contains("{{")) return template;

        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            // Leave it unresolved when unknown, so the report shows what went wrong
            // rather than silently sending an empty value.
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(value == null ? matcher.group(0) : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String truncate(String value) {
        return value.length() <= MAX_BODY_SNIPPET ? value : value.substring(0, MAX_BODY_SNIPPET) + "...";
    }
}
