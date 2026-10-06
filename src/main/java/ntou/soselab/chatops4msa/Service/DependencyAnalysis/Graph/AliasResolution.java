package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A name the documentation or the code used for a service, that none of the
 * deterministic alignment rules could map onto the graph — and the operator's
 * answer to "which service did you mean?".
 *
 * <p>Why ask instead of guess. The graph vocabulary comes from the cluster, the
 * manifests and the code; the wiki and the residual code edges name the same services
 * in their own words ("Customer API", "CustomersServiceClient", a bare "persistence").
 * The rules in {@link DocGraphMerger} and {@link CodeGraphMerger} catch the regular
 * spellings; what is left is genuinely ambiguous, and the two automatic options are
 * both wrong: dropping the edge hides a dependency the docs asserted, and fuzzy
 * matching it onto the closest name invents one. So, like the traffic generator's
 * Tier 3 ask ({@code AskItem}), the tool records the question and lets a human answer
 * it once; the answer is kept per project so the next run does not ask again.
 *
 * <p>Pure Java, no Spring: the questions and answers live in the checkpoint as JSON,
 * and the parsing of a typed answer is deterministic — no LLM is involved in deciding
 * what a human meant.
 */
public final class AliasResolution {

    /** The operator said the name is not a service on this graph (a technology label, a grouping). */
    public static final String IGNORE = "!ignore";
    /** The operator said it is a real service the other layers missed: introduce it as a node. */
    public static final String NEW = "!new";
    /** How many closest names are offered beside a question. */
    public static final int MAX_CANDIDATES = 4;

    private AliasResolution() {
    }

    /** {@code "Customer API"}, {@code customer-api} and {@code CustomerAPI} are one key here. */
    public static String key(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** A documented name reduced to a k8s-service-style id, for a node the operator asked to add. */
    public static String nodeId(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        s = s.replaceAll("[\\s_./]+", "-").replaceAll("[^a-z0-9-]", "");
        return s.replaceAll("-{2,}", "-").replaceAll("(^-|-$)", "");
    }

    // ---------- one question ----------

    public static final class Question {
        /** The name exactly as the document or the code wrote it. */
        public final String name;
        /** {@code doc} (the wiki ledger) or {@code code} (a residual code edge). */
        public final String origin;
        /** Where it was seen, so the operator can look it up: an edge, a file:line. */
        public final String seenIn;
        /** The closest known service ids, best first; may be empty. */
        public final List<String> candidates;
        /** How many edges mention this name — a name seen five times is worth answering. */
        public int mentions;

        Question(String name, String origin, String seenIn, List<String> candidates, int mentions) {
            this.name = name;
            this.origin = origin;
            this.seenIn = seenIn;
            this.candidates = candidates;
            this.mentions = mentions;
        }

        public String key() {
            return AliasResolution.key(name);
        }

        JSONObject toJson() {
            return new JSONObject()
                    .put("name", name)
                    .put("origin", origin)
                    .put("seenIn", seenIn)
                    .put("candidates", new JSONArray(candidates))
                    .put("mentions", mentions);
        }

        static Question fromJson(JSONObject json) {
            List<String> candidates = new ArrayList<>();
            JSONArray array = json.optJSONArray("candidates");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) candidates.add(array.optString(i, ""));
            }
            return new Question(json.optString("name", ""), json.optString("origin", ""),
                    json.optString("seenIn", ""), candidates, Math.max(1, json.optInt("mentions", 1)));
        }
    }

    // ---------- the open questions of one run ----------

    /** The unresolved names collected while the graph was being merged, de-duplicated by key. */
    public static final class Questions {
        private final Map<String, Question> byKey = new LinkedHashMap<>();
        /**
         * Every service id an answer may name: the services on the built graph plus
         * every candidate offered. Kept beside the questions so the form can accept
         * "discovery" for cloud-eureka-server although no ranking put it forward —
         * the operator knows names the ranking cannot guess (2026-10-06, first
         * Discord run: the two answers that mattered were in no candidate list).
         */
        private final Set<String> vocabulary = new LinkedHashSet<>();

        public void addVocabulary(Collection<String> ids) {
            if (ids == null) return;
            for (String id : ids) if (id != null && !id.isBlank()) vocabulary.add(id);
        }

        /** The service ids an answer may name, candidates included. */
        public Set<String> vocabulary() {
            Set<String> out = new LinkedHashSet<>(vocabulary);
            for (Question q : byKey.values()) out.addAll(q.candidates);
            return out;
        }

        public static String vocabularyToJson(Collection<String> ids) {
            return new JSONArray(ids == null ? List.of() : new ArrayList<>(ids)).toString();
        }

        public static Set<String> vocabularyFromJson(String json) {
            Set<String> out = new LinkedHashSet<>();
            if (json == null || json.isBlank()) return out;
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    String id = array.optString(i, "");
                    if (!id.isBlank()) out.add(id);
                }
            } catch (Exception ignored) {
                // unreadable: an empty vocabulary, which the form treats as "unknown"
            }
            return out;
        }

        /**
         * Records one unresolved name. The same name seen again only counts another
         * mention; the first place it was seen is kept as the example.
         */
        public void add(String name, String origin, String seenIn, Collection<String> knownServices) {
            if (name == null || name.isBlank()) return;
            String k = key(name);
            if (k.isEmpty()) return;
            Question existing = byKey.get(k);
            if (existing != null) {
                existing.mentions++;
                return;
            }
            // The node under question is never its own candidate (a library-named node
            // is still on the graph when it is asked about).
            List<String> candidates = new ArrayList<>();
            for (String c : rankCandidates(name, knownServices, MAX_CANDIDATES + 1)) {
                if (!key(c).equals(k) && candidates.size() < MAX_CANDIDATES) candidates.add(c);
            }
            byKey.put(k, new Question(name.trim(), origin, seenIn == null ? "" : seenIn, candidates, 1));
        }

        public List<Question> list() {
            return new ArrayList<>(byKey.values());
        }

        public boolean isEmpty() {
            return byKey.isEmpty();
        }

        public int size() {
            return byKey.size();
        }

        public String toJson() {
            JSONArray array = new JSONArray();
            for (Question q : byKey.values()) array.put(q.toJson());
            return array.toString();
        }

        /** Tolerant of an absent/blank/corrupt stage: unreadable means "nothing pending". */
        public static Questions fromJson(String json) {
            Questions questions = new Questions();
            if (json == null || json.isBlank()) return questions;
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject entry = array.optJSONObject(i);
                    if (entry == null) continue;
                    Question q = Question.fromJson(entry);
                    if (!q.key().isEmpty()) questions.byKey.put(q.key(), q);
                }
            } catch (Exception ignored) {
                // Not readable: treat as nothing pending rather than failing the run.
            }
            return questions;
        }
    }

    // ---------- the operator's answers ----------

    /**
     * What the operator decided for each name: a known node id, {@link #IGNORE} or
     * {@link #NEW}. Keyed by {@link #key(String)} so any spelling of the name finds it.
     */
    public static final class Answers {
        private final Map<String, String> names = new LinkedHashMap<>();
        private final Map<String, String> decisions = new LinkedHashMap<>();

        public void put(String name, String decision) {
            if (name == null || decision == null || decision.isBlank()) return;
            String k = key(name);
            if (k.isEmpty()) return;
            names.put(k, name.trim());
            decisions.put(k, decision.trim());
        }

        /** The decision for this spelling, or null when the operator was never asked / never answered. */
        public String decisionFor(String name) {
            return decisions.get(key(name));
        }

        public boolean has(String name) {
            return decisions.containsKey(key(name));
        }

        public boolean isEmpty() {
            return decisions.isEmpty();
        }

        public int size() {
            return decisions.size();
        }

        /** The answers as (name as written, decision) pairs, in the order they were given. */
        public Map<String, String> asMap() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : decisions.entrySet()) {
                out.put(names.getOrDefault(e.getKey(), e.getKey()), e.getValue());
            }
            return out;
        }

        /** Adds every answer of {@code other}; a later answer for the same name wins. */
        public void merge(Answers other) {
            if (other == null) return;
            for (Map.Entry<String, String> e : other.asMap().entrySet()) put(e.getKey(), e.getValue());
        }

        /** An array, not an object: the order the operator answered in is kept (a JSONObject would not keep it). */
        public String toJson() {
            JSONArray array = new JSONArray();
            for (Map.Entry<String, String> e : decisions.entrySet()) {
                array.put(new JSONObject()
                        .put("name", names.getOrDefault(e.getKey(), e.getKey()))
                        .put("decision", e.getValue()));
            }
            return array.toString();
        }

        public static Answers fromJson(String json) {
            Answers answers = new Answers();
            if (json == null || json.isBlank()) return answers;
            try {
                String text = json.trim();
                if (text.startsWith("[")) {
                    JSONArray array = new JSONArray(text);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject entry = array.optJSONObject(i);
                        if (entry == null) continue;
                        answers.put(entry.optString("name", ""), entry.optString("decision", ""));
                    }
                } else {
                    // {key: {name, decision}} — accepted too, order not guaranteed.
                    JSONObject object = new JSONObject(text);
                    for (String k : object.keySet()) {
                        JSONObject entry = object.optJSONObject(k);
                        if (entry == null) continue;
                        answers.put(entry.optString("name", k), entry.optString("decision", ""));
                    }
                }
            } catch (Exception ignored) {
                // Same as above: an unreadable file means "nothing answered yet".
            }
            return answers;
        }
    }

    // ---------- ranking the closest known names ----------

    /**
     * The known service ids that look most like {@code raw}, best first. Three
     * signals, summed: one name containing the other, shared words, and plain edit
     * distance. The score only orders the suggestions shown to the operator; it never
     * decides anything on its own.
     */
    public static List<String> rankCandidates(String raw, Collection<String> known, int max) {
        List<String> out = new ArrayList<>();
        if (raw == null || known == null) return out;
        String k = key(raw);
        if (k.isEmpty()) return out;
        Set<String> rawTokens = tokens(raw);
        // The name with its filler words removed: "Customer API" and "customers-service"
        // are compared as "customer" against "customers".
        String kCore = core(rawTokens, k);

        List<Map.Entry<String, Integer>> scored = new ArrayList<>();
        for (String node : new LinkedHashSet<>(known)) {
            if (node == null || node.isBlank()) continue;
            String n = key(node);
            if (n.isEmpty()) continue;
            int score = 0;
            if (n.equals(k)) {
                score = 100;
            } else {
                Set<String> nodeTokens = tokens(node);
                String nCore = core(nodeTokens, n);
                if (nCore.contains(kCore) || kCore.contains(nCore)) {
                    score += 50 + (int) (20.0 * Math.min(nCore.length(), kCore.length())
                            / Math.max(nCore.length(), kCore.length()));
                }
                int shared = 0;
                for (String t : rawTokens) {
                    for (String u : nodeTokens) {
                        if (sameWord(t, u)) {
                            shared++;
                            break;
                        }
                    }
                }
                int union = rawTokens.size() + nodeTokens.size() - shared;
                if (union > 0) score += (int) (50.0 * shared / union);
                int distance = levenshtein(kCore, nCore);
                int longest = Math.max(kCore.length(), nCore.length());
                if (longest > 0) score += (int) (40.0 * (1.0 - (double) distance / longest));
            }
            if (score >= 30) scored.add(Map.entry(node, score));
        }
        scored.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : scored) {
            if (out.size() >= max) break;
            out.add(e.getKey());
        }
        return out;
    }

    /** Words of a name, lower-cased, with the filler words every service name carries dropped. */
    private static Set<String> tokens(String raw) {
        Set<String> out = new LinkedHashSet<>();
        String s = raw.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        for (String t : s.split("[^a-z0-9]+")) {
            if (t.isEmpty() || FILLER.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    private static final Set<String> FILLER = Set.of("service", "svc", "api", "server", "app", "client", "the", "a");

    /** The name's non-filler words joined, or the whole key when every word was filler. */
    private static String core(Set<String> tokens, String wholeKey) {
        String joined = String.join("", tokens);
        return joined.isEmpty() ? wholeKey : joined;
    }

    /** "customer" and "customers" are the same word; so are "order" and "ordering". */
    private static boolean sameWord(String a, String b) {
        if (a.equals(b)) return true;
        int shortest = Math.min(a.length(), b.length());
        return shortest >= 4 && (a.startsWith(b) || b.startsWith(a));
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ---------- reading what the operator typed ----------

    private static final Set<String> IGNORE_WORDS = Set.of(
            "ignore", "skip", "no", "none", "not a service", "drop", "-", "x", "忽略", "略過", "不是");
    private static final Set<String> NEW_WORDS = Set.of(
            "new", "add", "new node", "new service", "新增", "新服務", "新");

    /**
     * Turns one typed answer into a decision: a known node id, {@link #IGNORE},
     * {@link #NEW}, or null when the text was blank or not understood (the question
     * then stays pending — a misread answer must never silently become a merge).
     *
     * Accepted forms: a candidate's number ("2"), a node id in any spelling
     * ("customers-service", "CustomersService"), "ignore"/"skip", "new".
     */
    public static String parseAnswer(String typed, Question question, Collection<String> knownNodes) {
        if (typed == null) return null;
        String text = typed.trim();
        if (text.isEmpty()) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (IGNORE_WORDS.contains(lower)) return IGNORE;
        if (NEW_WORDS.contains(lower)) return NEW;

        List<String> candidates = question == null ? List.of() : question.candidates;
        if (lower.matches("\\d{1,2}")) {
            int index = Integer.parseInt(lower) - 1;
            return index >= 0 && index < candidates.size() ? candidates.get(index) : null;
        }
        String k = key(text);
        if (knownNodes != null) {
            for (String node : knownNodes) if (node.equalsIgnoreCase(text)) return node;
            for (String node : knownNodes) if (key(node).equals(k)) return node;
        }
        for (String c : candidates) if (key(c).equals(k)) return c;
        return null;
    }

    /** A typed answer shaped like a service id (lower-case letters, digits, hyphens). */
    public static boolean looksLikeServiceId(String typed) {
        return typed != null && typed.trim().toLowerCase(Locale.ROOT).matches("[a-z0-9][a-z0-9-]{0,62}");
    }

    /** How a decision reads back to the operator. */
    public static String describe(String decision) {
        if (IGNORE.equals(decision)) return "ignored (not a service on this graph)";
        if (NEW.equals(decision)) return "added as a new service node";
        return "→ `" + decision + "`";
    }
}
