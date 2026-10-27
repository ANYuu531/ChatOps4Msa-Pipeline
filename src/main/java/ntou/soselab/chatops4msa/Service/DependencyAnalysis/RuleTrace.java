package ntou.soselab.chatops4msa.Service.DependencyAnalysis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A lightweight record of which extraction / merge rule fired, and on what. Every one
 * of the 46 numbered rules (the appendix table of the COMPSAC paper and
 * {@code docs/generalization-2026-09-22.md} §3–3c) calls {@link #hit(int, String)} at
 * the point where it changes an outcome — an edge drawn, dropped or regraded, a node
 * created, merged or suppressed, a name resolved — with a short token naming the thing
 * affected ({@code edge a->b}, {@code node x}, {@code alias db=mysql}, {@code env KEY}).
 * A rule that only parses (Compose v1, the Kotlin / C# thin surface) counts the files
 * or services it parsed.
 *
 * <p>The trace answers "is this just enumeration?": a rule that fires on many of the
 * 20 systems is a convention of the field; one that fires only on the system it was
 * induced on is a special case. It never changes behaviour — the collector is a side
 * channel; {@code hitIf} returns its condition unchanged so a call can sit inside an
 * existing {@code if}.
 *
 * <p>Counts are per distinct token: the same edge reached twice (the same host cleaned
 * in two passes, the same file seen by two walks) is one thing affected. Raw hits are
 * kept beside it. Thread-safe; {@link #clear()} starts a new analysis.
 */
public final class RuleTrace {

    /** The number of rules in the appendix table; the trace reports every one, fired or not. */
    public static final int RULE_COUNT = 46;

    /** Distinct tokens kept per rule; beyond this only the counters grow. */
    private static final int MAX_TOKENS = 20000;

    private static final class Entry {
        final AtomicInteger hits = new AtomicInteger();
        final Set<String> tokens = new LinkedHashSet<>();
        int overflow;

        synchronized void add(String token) {
            if (tokens.size() < MAX_TOKENS) tokens.add(token);
            else if (!tokens.contains(token)) overflow++;
        }

        synchronized int distinct() {
            return tokens.size() + overflow;
        }

        synchronized List<String> examples(int n) {
            List<String> out = new ArrayList<>();
            for (String t : tokens) {
                if (out.size() >= n) break;
                out.add(t);
            }
            return out;
        }
    }

    private static final Map<Integer, Entry> ENTRIES = new ConcurrentHashMap<>();

    private RuleTrace() {
    }

    /** Forgets everything recorded so far: call at the start of an analysis. */
    public static void clear() {
        ENTRIES.clear();
    }

    /** Tokens are one line and short: whitespace runs collapse, long values are cut. */
    private static final int MAX_TOKEN_LENGTH = 160;

    /** Records that rule {@code rule} changed an outcome described by {@code what}. */
    public static void hit(int rule, String what) {
        Entry e = ENTRIES.computeIfAbsent(rule, k -> new Entry());
        e.hits.incrementAndGet();
        String token = what == null ? "" : what.replaceAll("\\s+", " ").trim();
        e.add(token.length() > MAX_TOKEN_LENGTH ? token.substring(0, MAX_TOKEN_LENGTH - 1) + "…" : token);
    }

    /** {@link #hit} when {@code condition} holds; returns {@code condition} unchanged so it can guard an existing branch. */
    public static boolean hitIf(boolean condition, int rule, String what) {
        if (condition) hit(rule, what);
        return condition;
    }

    /** Distinct things rule {@code rule} affected in this analysis. */
    public static int count(int rule) {
        Entry e = ENTRIES.get(rule);
        return e == null ? 0 : e.distinct();
    }

    /** Raw number of times rule {@code rule} fired in this analysis. */
    public static int hits(int rule) {
        Entry e = ENTRIES.get(rule);
        return e == null ? 0 : e.hits.get();
    }

    /** Up to {@code n} example tokens of rule {@code rule}, in first-seen order. */
    public static List<String> examples(int rule, int n) {
        Entry e = ENTRIES.get(rule);
        return e == null ? List.of() : e.examples(n);
    }

    /** Whether any rule fired. */
    public static boolean isEmpty() {
        return ENTRIES.isEmpty();
    }

    /**
     * The trace as a Markdown section: one line per rule 1..{@link #RULE_COUNT},
     * {@code - rule N: <distinct count> (<up to 5 example tokens>)}, then any rule
     * number outside that range that fired (a mis-numbered call site would show here).
     */
    public static String toMarkdown() {
        StringBuilder sb = new StringBuilder("## 規則觸發\n");
        sb.append("計數＝該規則改變了多少個不同的東西（邊、節點、名稱、檔案；同一個東西重複碰到算一次）；括號內最多列 5 個例子。\n");
        Set<Integer> rules = new java.util.TreeSet<>(ENTRIES.keySet());
        for (int r = 1; r <= RULE_COUNT; r++) rules.add(r);
        for (int r : rules) {
            sb.append("- rule ").append(r).append(": ").append(count(r));
            List<String> ex = examples(r, 5);
            if (!ex.isEmpty()) sb.append(" (").append(String.join("; ", ex)).append(')');
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The trace as JSON: {@code {"rules": {"1": {"count", "hits", "examples": [...]}, ...}}}. */
    public static JSONObject toJson() {
        JSONObject rules = new JSONObject();
        Map<Integer, Entry> sorted = new TreeMap<>(ENTRIES);
        for (int r = 1; r <= RULE_COUNT; r++) sorted.putIfAbsent(r, new Entry());
        for (Map.Entry<Integer, Entry> e : sorted.entrySet()) {
            JSONObject one = new JSONObject();
            one.put("count", e.getValue().distinct());
            one.put("hits", e.getValue().hits.get());
            one.put("examples", new JSONArray(e.getValue().examples(20)));
            rules.put(String.valueOf(e.getKey()), one);
        }
        return new JSONObject().put("rules", rules);
    }
}
