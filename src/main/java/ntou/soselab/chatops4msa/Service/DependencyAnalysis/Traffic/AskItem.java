package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One value the traffic generator needs from a HUMAN before an edge can be driven.
 *
 * This is Tier 3 of the payload strategy. Tiers 1 and 2 are automatic — harvest the
 * project's own example requests, then feed a 4xx response back so the generator can
 * correct itself. What is left over is the irreducible remainder: a value that exists
 * only in the operator's head or in another system (a real account number, a card
 * token, a tenant id). No amount of static analysis produces it, and guessing it just
 * 4xx's, which stops the journey before the deep edge ever happens.
 *
 * So instead of silently degrading to UNREACHABLE, the tool asks. The distinction is:
 *   ASK:         a specific value a human can supply -> we ask, then retry
 *   UNREACHABLE: no value would help (needs a real payment gateway, another system)
 *
 * <b>Wire format.</b> An ask is declared inside the Postman collection itself, as a
 * collection variable with an empty value and an {@code ASK:} description:
 * <pre>
 * "variable": [
 *   { "key": "account_num", "value": "",
 *     "description": "ASK: an existing account number to deposit into | e.g. 1011226111" }
 * ]
 * </pre>
 * Declaring it this way (rather than in a side-channel field) keeps the artefact a
 * valid, Newman-runnable Postman collection — the same reasoning as the
 * {@code UNREACHABLE:} name prefix, and the reason the whole scenario format is
 * Postman in the first place.
 */
public class AskItem {

    /** The collection-variable name; later steps reference it as {{key}}. */
    public String key = "";
    /** What to ask the user, in one line. */
    public String question = "";
    /** An optional sample value, shown as the input's placeholder. May be empty. */
    public String example = "";
    /**
     * True when the key names a credential. Such a value is never echoed back to the
     * channel and never put in a prompt — only substituted into the request itself.
     */
    public boolean secret;

    /** Marks a collection variable as a value to ask the user for. */
    public static final String ASK_PREFIX = "ASK:";
    /** Separates the question from its optional example: "ASK: <question> | e.g. <example>". */
    private static final String EXAMPLE_SEPARATOR = "|";

    /**
     * A key we can actually round-trip: it becomes a {{placeholder}} in the collection
     * AND the id of a Discord text input. A key with a space or punctuation would
     * either never substitute or make the modal itself invalid, taking the whole
     * button down — so such an ask is not honoured at all (the value is simply never
     * asked for, and the edge stays uncovered, which is visible rather than broken).
     */
    private static final java.util.regex.Pattern USABLE_KEY =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,45}");

    public static boolean isUsableKey(String key) {
        return key != null && USABLE_KEY.matcher(key).matches();
    }

    private static final String[] SECRET_HINTS = {
            "password", "passwd", "pwd", "token", "secret", "apikey", "api_key",
            "credential", "authorization"
    };

    public AskItem() {
    }

    public AskItem(String key, String question, String example) {
        this.key = key == null ? "" : key.trim();
        this.question = question == null ? "" : question.trim();
        this.example = example == null ? "" : example.trim();
        this.secret = looksSecret(this.key);
    }

    /**
     * Builds an ask from a collection variable's description, which is expected to be
     * {@code ASK: <question>} with an optional {@code | e.g. <example>} tail. A
     * description without the prefix is still accepted (the caller has already decided
     * this is an ask), so a slightly off-format LLM answer is not lost.
     */
    public static AskItem parse(String key, String description) {
        String text = description == null ? "" : description.trim();
        if (text.toUpperCase(Locale.ROOT).startsWith(ASK_PREFIX)) {
            text = text.substring(ASK_PREFIX.length()).trim();
        }

        String question = text;
        String example = "";
        int separator = text.indexOf(EXAMPLE_SEPARATOR);
        if (separator >= 0) {
            question = text.substring(0, separator).trim();
            example = stripEgPrefix(text.substring(separator + 1).trim());
        }
        if (question.isEmpty()) question = "a value for " + key;
        return new AskItem(key, question, example);
    }

    /** "e.g. 1011226111" / "eg: 1011226111" -> "1011226111". */
    private static String stripEgPrefix(String example) {
        String value = example.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        for (String prefix : new String[]{"e.g.", "eg.", "e.g", "eg:", "eg"}) {
            if (lower.startsWith(prefix)) {
                return value.substring(prefix.length()).replaceFirst("^[:\\s]+", "").trim();
            }
        }
        return value;
    }

    private static boolean looksSecret(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String hint : SECRET_HINTS) {
            if (lower.contains(hint)) return true;
        }
        return false;
    }

    /** The value as it may be shown in the channel: a credential is never echoed. */
    public String maskIfSecret(String value) {
        if (value == null) return "";
        return secret ? "•".repeat(Math.min(8, Math.max(3, value.length()))) : value;
    }

    // ---------- persistence (the checkpoint stores these as JSON) ----------

    public JSONObject toJson() {
        return new JSONObject()
                .put("key", key)
                .put("question", question)
                .put("example", example)
                .put("secret", secret);
    }

    public static String toJson(List<AskItem> asks) {
        JSONArray array = new JSONArray();
        for (AskItem ask : asks) array.put(ask.toJson());
        return array.toString();
    }

    /** Tolerant of an absent/blank/corrupt stage: an unreadable checkpoint means "nothing pending". */
    public static List<AskItem> fromJson(String json) {
        List<AskItem> asks = new ArrayList<>();
        if (json == null || json.isBlank()) return asks;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry == null) continue;
                String key = entry.optString("key", "").trim();
                if (key.isEmpty()) continue;
                AskItem ask = new AskItem(key,
                        entry.optString("question", ""),
                        entry.optString("example", ""));
                if (entry.has("secret")) ask.secret = entry.optBoolean("secret", ask.secret);
                asks.add(ask);
            }
        } catch (Exception ignored) {
            // Not readable: treat as nothing pending rather than failing the run.
        }
        return asks;
    }

    // ---------- the answers ----------

    /** The user's answers, stored as a flat {key: value} object in the checkpoint. */
    public static String valuesToJson(Map<String, String> values) {
        JSONObject json = new JSONObject();
        values.forEach(json::put);
        return json.toString();
    }

    public static Map<String, String> valuesFromJson(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return values;
        try {
            JSONObject object = new JSONObject(json);
            for (String key : object.keySet()) {
                String value = object.optString(key, "");
                if (!value.isBlank()) values.put(key, value);
            }
        } catch (Exception ignored) {
            // Same as above: an unreadable checkpoint means "nothing supplied yet".
        }
        return values;
    }

    /**
     * Cleans one answer typed by a human before it is substituted into a request.
     *
     * The value goes into an HTTP request this user already asked us to send, so this
     * is not a trust boundary in the prompt-injection sense — but it must not corrupt
     * the substitution itself: a stray {{...}} would be re-expanded, and newlines
     * would break a header. Length is capped so a paste cannot blow up a URL.
     */
    public static String sanitize(String value) {
        if (value == null) return "";
        String clean = value.replace("{{", "").replace("}}", "")
                .replaceAll("[\\r\\n\\t]", " ")
                .trim();
        return clean.length() <= 200 ? clean : clean.substring(0, 200);
    }
}
