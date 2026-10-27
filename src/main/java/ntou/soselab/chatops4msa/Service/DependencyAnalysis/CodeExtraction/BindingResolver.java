package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rule 47: a call site whose address is a variable is resolved through the same file's
 * bindings, in every language the same way.
 *
 * <p>Rule 22 did this for Python only, with a regex over the source text. The query packs
 * now emit the bindings themselves as {@code var-binding} rows — one per assignment or
 * declaration whose value is something an address can come from:
 * <ul>
 *   <li>an environment read: {@code env} (+ {@code envdefault}) — {@code process.env.X || 'd'},
 *       {@code os.getenv("X", "d")}, {@code getenv('X') ?: 'd'}, {@code System.getenv("X") ?: "d"},
 *       {@code Environment.GetEnvironmentVariable("X") ?? "d"}, Go's {@code os.Getenv};</li>
 *   <li>a .NET configuration read: {@code dotnetkey} ({@code Configuration["A:B"]}, env spelling {@code A__B});</li>
 *   <li>a literal: {@code str};</li>
 *   <li>a URL built around another variable: {@code scheme} + {@code hostref} ({@code "http://" + h});</li>
 *   <li>a value derived from another variable: {@code ref} ({@code f"{BASE}/x"}, {@code sprintf('%s/x', $base)}).</li>
 * </ul>
 * plus {@code var-default} rows for Go's default idiom ({@code if v == "" { v = "d" }}).
 *
 * <p>A use-site row names its variable in {@code urlref} (the whole URL), {@code hostref}
 * (the host, after a literal {@code scheme}), {@code valueref} (an option value, {@code {host:
 * redisHost}}) or {@code nameref} (a registry name). The resolver follows the name to the
 * binding nearest before the use (else the first after it: a module-level constant may be
 * declared below the function that uses it), up to three hops, and writes the result on the
 * use-site row in the fields the merger already reads: {@code url} / {@code value} / {@code
 * name} for a literal, {@code env} + {@code envdefault} for an environment read. The
 * provenance stays the call site. A name nothing binds is left alone and draws no edge.
 *
 * <p>Bindings are lookup rows, not report entries: they are removed from the ledger once
 * resolved, so the rendered ledger is unchanged in size.
 */
final class BindingResolver {

    private BindingResolver() {
    }

    static final String BINDING = "var-binding";
    static final String DEFAULT = "var-default";

    /** What a variable resolves to: a literal, or an environment read (with its default). */
    private record Resolution(String literal, String env, String envdefault, String dotnetkey) {
        static Resolution literal(String s) {
            return new Resolution(s, null, null, null);
        }
    }

    /** The use-site sections and the reference fields they may carry. */
    private static final Set<String> USE_SECTIONS = Set.of("http-client", "grpc", "config", "url", "registry-ref");

    static void resolve(EdgeLedger ledger) {
        Map<String, Map<String, List<EdgeLedger.Edge>>> bindings = new LinkedHashMap<>();
        Map<String, Map<String, List<EdgeLedger.Edge>>> defaults = new LinkedHashMap<>();
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            if (BINDING.equals(e.section)) {
                String name = e.fields.get("name");
                if (name == null || name.isBlank()) continue;
                bindings.computeIfAbsent(e.file, k -> new LinkedHashMap<>())
                        .computeIfAbsent(name.trim(), k -> new ArrayList<>()).add(e);
            } else if (DEFAULT.equals(e.section)) {
                String name = e.fields.get("name");
                if (name == null || name.isBlank()) continue;
                defaults.computeIfAbsent(e.file, k -> new LinkedHashMap<>())
                        .computeIfAbsent(name.trim(), k -> new ArrayList<>()).add(e);
            }
        }

        List<EdgeLedger.Edge> consumedEnv = new ArrayList<>();
        if (!bindings.isEmpty()) {
            for (EdgeLedger.Edge e : ledger.getEdges()) {
                if (!USE_SECTIONS.contains(e.section)) continue;
                Map<String, List<EdgeLedger.Edge>> fileBindings = bindings.get(e.file);
                if (fileBindings == null) continue;
                Map<String, List<EdgeLedger.Edge>> fileDefaults = defaults.getOrDefault(e.file, Map.of());
                String field = refField(e);
                if (field == null) continue;
                String ref = e.fields.get(field).trim();
                Resolution r = follow(fileBindings, fileDefaults, ref, e.line, 0);
                if (r == null) continue;
                RuleTrace.hit(47, field + " " + e.file + ":" + e.line + " " + ref);
                apply(e, field, r);
                if (r.env != null) consumedEnv.add(e);
            }
        }
        // A plain env read the resolved call site consumes: the call site carries the edge
        // (and the provenance reference sets expect — where the call is made).
        for (EdgeLedger.Edge call : consumedEnv) {
            for (EdgeLedger.Edge c : ledger.getEdges()) {
                if ("config".equals(c.section) && c != call && c.file.equals(call.file)
                        && call.fields.get("env").equals(c.fields.get("env"))
                        && !c.fields.containsKey("valueref")) {
                    c.fields.put("superseded", "true");
                }
            }
        }
        ledger.getEdges().removeIf(e -> BINDING.equals(e.section) || DEFAULT.equals(e.section));
    }

    /** The reference field a use-site row carries and has not yet resolved, or null. */
    private static String refField(EdgeLedger.Edge e) {
        Map<String, String> f = e.fields;
        if (f.containsKey("env") || f.containsKey("url")) return null;
        if (f.containsKey("hostref") && f.containsKey("scheme") && !f.containsKey("value")) return "hostref";
        if (f.containsKey("urlref")) return "urlref";
        if (f.containsKey("valueref") && !f.containsKey("value")) return "valueref";
        if (f.containsKey("nameref") && !f.containsKey("name")) return "nameref";
        return null;
    }

    private static void apply(EdgeLedger.Edge e, String field, Resolution r) {
        Map<String, String> f = e.fields;
        String scheme = "hostref".equals(field) ? f.getOrDefault("scheme", "") : "";
        if (r.literal != null) {
            String lit = r.literal.trim();
            String value = !scheme.isEmpty() && !lit.matches("(?i)^[a-z][a-z0-9+.-]*://.*") ? scheme + lit : lit;
            switch (e.section) {
                case "http-client", "grpc" -> f.put("url", value);
                case "registry-ref" -> f.put("name", value);
                default -> f.put("value", value);
            }
            return;
        }
        if ("url".equals(e.section)) {
            // A url row whose host is an environment read ("http://$host/x", Addr: addr): the
            // format rule (rule 18) fills %s from the env table, else the default.
            f.put("value", scheme + "%s");
        }
        f.put("env", r.env);
        String def = r.envdefault;
        if (def != null && !def.isBlank() && !scheme.isEmpty() && !def.contains("://")) def = scheme + def;
        if (def != null && !def.isBlank()) f.put("envdefault", def);
        if (r.dotnetkey != null) f.put("dotnetkey", r.dotnetkey);
        if ("config".equals(e.section)) f.put("usesite", "true");
    }

    /** The binding of {@code name} as seen from {@code line}, following up to three hops; null if none resolves. */
    private static Resolution follow(Map<String, List<EdgeLedger.Edge>> bindings, Map<String, List<EdgeLedger.Edge>> defaults,
                                     String name, int line, int depth) {
        if (depth > 3 || name == null || name.isBlank()) return null;
        List<EdgeLedger.Edge> candidates = bindings.get(name);
        if (candidates == null || candidates.isEmpty()) return null;
        for (EdgeLedger.Edge b : ordered(candidates, line)) {
            Map<String, String> f = b.fields;
            if (f.containsKey("env")) {
                String def = f.get("envdefault");
                if (def == null || def.isBlank()) def = nearestDefault(defaults.get(name), b.line);
                return new Resolution(null, f.get("env").trim(), def, null);
            }
            if (f.containsKey("dotnetkey")) {
                String key = f.get("dotnetkey").trim();
                return new Resolution(null, key.replace(":", "__"), null, key);
            }
            if (f.containsKey("str")) {
                String s = f.get("str").trim();
                if (s.isEmpty() || s.contains("${")) continue;
                return Resolution.literal(s);
            }
            if (f.containsKey("hostref")) {
                Resolution host = follow(bindings, defaults, f.get("hostref").trim(), b.line, depth + 1);
                if (host == null) continue;
                String scheme = f.getOrDefault("scheme", "");
                if (host.literal != null) {
                    String lit = host.literal;
                    return Resolution.literal(lit.matches("(?i)^[a-z][a-z0-9+.-]*://.*") ? lit : scheme + lit);
                }
                String def = host.envdefault;
                if (def != null && !def.isBlank() && !def.contains("://")) def = scheme + def;
                return new Resolution(null, host.env, def, host.dotnetkey);
            }
            if (f.containsKey("ref")) {
                String ref = f.get("ref").trim();
                if (ref.equals(name)) continue;
                Resolution r = follow(bindings, defaults, ref, b.line, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The bindings nearest before {@code line} first (latest first), then those after it (earliest first). */
    private static List<EdgeLedger.Edge> ordered(List<EdgeLedger.Edge> candidates, int line) {
        List<EdgeLedger.Edge> before = new ArrayList<>();
        List<EdgeLedger.Edge> after = new ArrayList<>();
        for (EdgeLedger.Edge b : candidates) (b.line <= line ? before : after).add(b);
        // Several rows for one declaration (with and without a default): the richer one first.
        Comparator<EdgeLedger.Edge> richer = Comparator.comparingInt(b -> -b.fields.size());
        before.sort(Comparator.<EdgeLedger.Edge>comparingInt(b -> -b.line).thenComparing(richer));
        after.sort(Comparator.<EdgeLedger.Edge>comparingInt(b -> b.line).thenComparing(richer));
        List<EdgeLedger.Edge> out = new ArrayList<>(before);
        out.addAll(after);
        return out;
    }

    /** Go's {@code if v == "" { v = "d" }} after the read: the nearest such default at or below the binding. */
    private static String nearestDefault(List<EdgeLedger.Edge> defaults, int bindingLine) {
        if (defaults == null) return null;
        EdgeLedger.Edge best = null;
        for (EdgeLedger.Edge d : defaults) {
            if (d.line >= bindingLine && (best == null || d.line < best.line)) best = d;
        }
        return best == null ? null : best.fields.get("value");
    }
}
