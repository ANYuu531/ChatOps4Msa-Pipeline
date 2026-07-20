package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A traffic scenario: an ordered list of HTTP requests that walks a user journey.
 *
 * The wire format is a standard <b>Postman Collection v2.1</b>. That is a deliberate
 * choice over a home-grown DSL:
 *  - It is the industry-standard JSON for exactly this shape — an ordered list of
 *    HTTP requests with variables, chained captures and auth — so the artefact is
 *    recognisable, reusable, and consumable by Newman / the Postman app.
 *  - LLMs have abundant training data for it, so generation is reliable.
 *  - It is still declarative JSON, so the four properties this pipeline relies on
 *    all survive: an LLM can emit it, we can store it in a checkpoint, show it to
 *    the user, and refine it across resumes.
 *
 * The executor ({@link TrafficRunner}) is still our own in-process JDK HttpClient,
 * NOT Newman — see that class for why. It therefore understands only a
 * <em>declarative subset</em> of the {@code pm.*} API, namely capturing a value out
 * of the response body:
 * <pre>pm.collectionVariables.set("VAR", pm.response.json()&lt;access&gt;)</pre>
 * (equivalently {@code pm.environment.set(...)}). Any other {@code pm.*} JS — test
 * assertions and so on — is ignored, not an error: it does not affect traffic
 * observation. A capture written in an unsupported form is recorded as a warning so
 * the gap is visible rather than silently dropped.
 *
 * Why a request list rather than a k6 / load script in the first place:
 *  - To observe a dependency edge, Istio only needs the request to HAPPEN once.
 *    We need breadth (reach every endpoint that makes a downstream call), not
 *    load — so a load-testing tool is the wrong shape.
 *  - Deep edges (orders -> payment) only occur on semantically valid journeys:
 *    log in, capture the token, add to cart, then check out. That needs state
 *    carried between steps, which this models explicitly with capture/variables.
 */
public class TrafficScenario {

    public static class Step {
        public String name = "";
        public String method = "GET";
        /** Either a relative path (resolved against entry_url) or an absolute URL; may contain {{var}}. */
        public String path = "/";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public String body = null;
        /** variable name -> JsonPath into the response body, e.g. token -> $.token */
        public final Map<String, String> capture = new LinkedHashMap<>();
        /** Informational: what dependency edge this step is meant to exercise. */
        public String exercises = "";
        /** False for UNREACHABLE: placeholder items — reported as a gap, never sent. */
        public boolean send = true;
    }

    public final Map<String, String> variables = new LinkedHashMap<>();
    public final List<Step> steps = new ArrayList<>();
    /** Non-fatal problems noticed while parsing (skipped items, unsupported captures). */
    public final List<String> warnings = new ArrayList<>();

    /** Marks an item as a reported gap rather than a request to send. */
    private static final String UNREACHABLE_PREFIX = "UNREACHABLE:";

    /**
     * A supported capture: pm.collectionVariables.set("VAR", pm.response.json()<access>)
     * (or pm.environment.set(...)). <access> is a chain of .name / [int] / ["name"].
     */
    private static final Pattern CAPTURE = Pattern.compile(
            "pm\\.(?:collectionVariables|environment)\\.set\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*"
                    + "pm\\.response\\.json\\(\\)([\\w.\\[\\]\"']*)\\s*\\)");

    /**
     * Parses a Postman Collection v2.1 out of an LLM response. Tolerates surrounding
     * prose and ```json fences: the outermost JSON object is used. Individual
     * malformed items are skipped with a warning rather than failing the whole
     * collection, so one bad step never wastes a full traffic round.
     *
     * @throws IllegalArgumentException when there is no usable collection at all, so
     *         the caller can report that clearly instead of silently driving no traffic.
     */
    public static TrafficScenario parse(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("empty collection response");
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object found in the collection response");
        }

        JSONObject root = new JSONObject(response.substring(start, end + 1));
        TrafficScenario scenario = new TrafficScenario();

        // Collection-level variables: [{key, value}, ...]. A legacy {k: v} object is
        // also accepted so an older checkpoint still parses.
        JSONArray variable = root.optJSONArray("variable");
        if (variable != null) {
            for (int i = 0; i < variable.length(); i++) {
                JSONObject entry = variable.optJSONObject(i);
                if (entry == null) continue;
                String key = entry.optString("key", "").trim();
                if (!key.isEmpty()) scenario.variables.put(key, entry.optString("value", ""));
            }
        } else {
            JSONObject legacy = root.optJSONObject("variables");
            if (legacy != null) {
                for (String key : legacy.keySet()) scenario.variables.put(key, legacy.optString(key, ""));
            }
        }

        JSONArray items = root.optJSONArray("item");
        if (items == null || items.length() == 0) {
            throw new IllegalArgumentException("the collection contains no items");
        }

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                scenario.warnings.add("item " + (i + 1) + " was not an object; skipped");
                continue;
            }
            try {
                Step step = parseItem(item, i, scenario.warnings);
                if (step != null) scenario.steps.add(step);
            } catch (Exception e) {
                scenario.warnings.add("item " + (i + 1) + " (" + item.optString("name", "?")
                        + ") could not be parsed and was skipped: " + e.getMessage());
            }
        }

        if (scenario.steps.isEmpty()) {
            throw new IllegalArgumentException("the collection contains no usable items");
        }
        return scenario;
    }

    private static Step parseItem(JSONObject item, int index, List<String> warnings) {
        // A Postman collection may nest items in folders. We only drive flat request
        // lists; a folder (no `request`) is not a step.
        JSONObject request = item.optJSONObject("request");
        if (request == null) {
            if (item.optJSONArray("item") != null) {
                warnings.add("item " + (index + 1) + " is a folder; nested items are not driven");
            }
            return null;
        }

        Step step = new Step();
        step.name = item.optString("name", "item " + (index + 1));
        step.method = request.optString("method", "GET").toUpperCase();
        step.path = extractUrl(request.opt("url"));
        step.exercises = request.optString("description", item.optString("description", ""));

        if (step.name.trim().toUpperCase().startsWith(UNREACHABLE_PREFIX)) {
            step.send = false;
        }

        JSONArray header = request.optJSONArray("header");
        if (header != null) {
            for (int h = 0; h < header.length(); h++) {
                JSONObject entry = header.optJSONObject(h);
                if (entry == null || entry.optBoolean("disabled", false)) continue;
                String key = entry.optString("key", "").trim();
                if (!key.isEmpty()) step.headers.put(key, entry.optString("value", ""));
            }
        }

        JSONObject body = request.optJSONObject("body");
        if (body != null) {
            // Only raw bodies are driven; other modes (formdata, urlencoded, file)
            // are not part of the supported subset.
            String mode = body.optString("mode", "raw");
            if ("raw".equals(mode)) {
                Object raw = body.opt("raw");
                if (raw != null && !JSONObject.NULL.equals(raw)) {
                    step.body = (raw instanceof JSONObject || raw instanceof JSONArray)
                            ? raw.toString()
                            : String.valueOf(raw);
                    if (step.body.isBlank()) step.body = null;
                }
            } else {
                warnings.add("item " + (index + 1) + " uses body mode '" + mode
                        + "', which is not supported; sent with no body");
            }
        }

        parseCaptures(item, step, index, warnings);
        return step;
    }

    /**
     * Reads captures out of the item's `event` scripts. We only recognise the
     * declarative subset {@code pm.(collectionVariables|environment).set("VAR",
     * pm.response.json()<access>)} and turn each into VAR -> JsonPath. Anything else
     * in a test script (assertions, unsupported captures) is ignored; an unsupported
     * capture is flagged as a warning so it is visible rather than silently lost.
     */
    private static void parseCaptures(JSONObject item, Step step, int index, List<String> warnings) {
        JSONArray events = item.optJSONArray("event");
        if (events == null) return;

        for (int e = 0; e < events.length(); e++) {
            JSONObject event = events.optJSONObject(e);
            if (event == null || !"test".equals(event.optString("listen"))) continue;

            JSONObject script = event.optJSONObject("script");
            if (script == null) continue;

            StringBuilder source = new StringBuilder();
            JSONArray exec = script.optJSONArray("exec");
            if (exec != null) {
                for (int l = 0; l < exec.length(); l++) source.append(exec.optString(l, "")).append('\n');
            } else {
                source.append(script.optString("exec", ""));
            }
            String code = source.toString();

            Matcher matcher = CAPTURE.matcher(code);
            boolean found = false;
            while (matcher.find()) {
                found = true;
                step.capture.put(matcher.group(1), accessToJsonPath(matcher.group(2)));
            }
            // A .set(...) we could not match is a capture we will not honour: say so.
            if (!found && code.contains(".set(")) {
                warnings.add("item " + (index + 1) + " (" + step.name + ") has a capture in an "
                        + "unsupported form; only pm.collectionVariables.set(\"VAR\", "
                        + "pm.response.json()<access>) is honoured");
            }
        }
    }

    /**
     * Turns a Postman access chain into a Jayway JsonPath. The chain is already valid
     * JsonPath tail syntax (.name / [0] / ["name"]), so it only needs the root $.
     * An empty access (the whole body) becomes $.
     */
    private static String accessToJsonPath(String access) {
        if (access == null || access.isBlank()) return "$";
        return access.startsWith("[") ? "$" + access : "$" + (access.startsWith(".") ? access : "." + access);
    }

    /**
     * Extracts a URL string from a Postman `url`, which may be a plain string or an
     * object {raw, host, path, query}. `raw` is preferred; otherwise host/path are
     * reassembled. Variable placeholders {{var}} are left intact for the runner.
     */
    private static String extractUrl(Object url) {
        if (url == null || JSONObject.NULL.equals(url)) return "/";
        if (url instanceof String) {
            String raw = ((String) url).trim();
            return raw.isEmpty() ? "/" : raw;
        }
        if (!(url instanceof JSONObject)) return "/";

        JSONObject obj = (JSONObject) url;
        String raw = obj.optString("raw", "").trim();
        if (!raw.isEmpty()) return raw;

        StringBuilder sb = new StringBuilder();
        Object host = obj.opt("host");
        if (host instanceof JSONArray) {
            sb.append(joinArray((JSONArray) host, "."));
        } else if (host instanceof String) {
            sb.append(((String) host).trim());
        }

        Object path = obj.opt("path");
        String pathStr = path instanceof JSONArray ? joinArray((JSONArray) path, "/")
                : path instanceof String ? ((String) path).trim() : "";
        if (!pathStr.isEmpty()) {
            if (sb.length() > 0 && !pathStr.startsWith("/")) sb.append('/');
            sb.append(pathStr);
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? "/" : result;
    }

    private static String joinArray(JSONArray array, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            String part = array.optString(i, "");
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(part);
        }
        return sb.toString();
    }
}
