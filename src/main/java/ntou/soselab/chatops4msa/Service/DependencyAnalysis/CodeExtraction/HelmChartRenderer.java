package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Rule 52: a Helm chart is rendered before it is read.
 *
 * <p>A chart's {@code templates/} are Go templates ({@code {{ .Values.x }}}, {@code {{- if }}},
 * {@code {{ include "x.fullname" . }}}); unrendered they are not YAML, the YAML reader threw,
 * and the old {@code catch (Exception ignored)} dropped every manifest of
 * skywalking-showcase's Kubernetes deployment without a word (2026-10-27, hold-out D). This
 * class renders a chart the way {@code helm template} does with its defaults — the chart's
 * {@code values.yaml}, {@code Chart.yaml}, namespace {@code default}, and a release name that
 * is left unknown (it is chosen at install time) — with a built-in subset of the template language, so machine B (no
 * {@code helm} binary) gets the same result:
 * <ul>
 *   <li>actions: field chains ({@code .Values.a.b}, {@code $.Chart.Name}, {@code $var}),
 *       pipelines, parenthesised calls, variables ({@code $x := …}), {@code define} /
 *       {@code include} / {@code template} (helpers from every {@code .tpl}), comments and
 *       {@code {{-} / {@code -}}} trimming;</li>
 *   <li>the functions charts use for names and wiring (default, quote, printf, trunc,
 *       trimSuffix, replace, toYaml, nindent, …); an unknown function, a missing file or a
 *       cluster lookup yields a placeholder ({@code ${helm}}) that no host or name rule
 *       accepts, so it can never become a node;</li>
 *   <li>{@code if}: with an {@code else}, the branch the chart's defaults choose; without one,
 *       a body guarded by a boolean switch that defaults to {@code false} is kept (a guard on an
 *       absent or empty value, {@code if .Values.extraAnnotations}, is not) — a feature off by default is still
 *       part of what the chart can deploy (skywalking-showcase guards its whole music
 *       application behind feature flags that default to off). Such optional parts are kept
 *       honest by the precedence rule in the merger: a chart that is not the vocabulary only
 *       wires names the concrete deployment already has;</li>
 *   <li>{@code range} over the default list or map; {@code with} only when its value is set.</li>
 * </ul>
 * A dependency with a {@code file://} repository is rendered as part of its parent, with
 * the parent's {@code <name>:} values merged over its own (yas's twenty service charts all
 * render the shared {@code backend} chart); a chart that is only someone's file dependency
 * is not rendered on its own. What cannot be rendered is reported, never swallowed.
 */
final class HelmChartRenderer {

    private HelmChartRenderer() {
    }

    /** What every unresolvable expression renders as: no host, name or YAML rule accepts it. */
    static final String PLACEHOLDER = "${helm}";

    record Rendered(String template, String chart, String yaml) {
    }

    /** The {@code templates/} directories of every chart under {@code root} (their files are not plain YAML). */
    static Set<Path> templateDirs(Path root) {
        Set<Path> out = new LinkedHashSet<>();
        for (Path chart : charts(root)) {
            Path t = chart.getParent().resolve("templates");
            if (Files.isDirectory(t)) out.add(t.normalize());
        }
        return out;
    }

    /** Every Chart.yaml under root, outside ignored trees. */
    private static List<Path> charts(Path root) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("Chart.yaml"))
                    .filter(p -> !SourceScanner.isIgnored(root, p))
                    .forEach(found::add);
        } catch (Exception ignored) {
            // unwalkable tree: whatever was found
        }
        return found;
    }

    /** Renders every chart under {@code root} that is not merely another chart's file dependency. */
    static List<Rendered> render(Path root, Consumer<String> warn) {
        List<Path> charts = charts(root);
        Set<Path> fileDependencies = new LinkedHashSet<>();
        Map<Path, Map<String, Object>> metas = new LinkedHashMap<>();
        for (Path chartYaml : charts) {
            Map<String, Object> meta = loadMap(chartYaml, warn, root);
            metas.put(chartYaml.getParent().normalize(), meta);
            for (Path dep : fileDeps(chartYaml.getParent(), meta)) fileDependencies.add(dep.normalize());
        }
        // A chart inside another chart's charts/ directory is a packaged subchart: rendered with its parent.
        for (Path dir : metas.keySet()) {
            Path parent = dir.getParent();
            if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("charts")
                    && metas.containsKey(parent.getParent() == null ? parent : parent.getParent().normalize())) {
                fileDependencies.add(dir);
            }
        }
        List<Rendered> out = new ArrayList<>();
        for (Map.Entry<Path, Map<String, Object>> chart : metas.entrySet()) {
            if (fileDependencies.contains(chart.getKey())) continue;
            if (!Files.isDirectory(chart.getKey().resolve("templates"))) {
                if (subcharts(chart.getKey(), chart.getValue()).isEmpty()) continue;
            }
            try {
                Map<String, Object> values = loadValues(chart.getKey(), warn, root);
                renderChart(root, chart.getKey(), chart.getValue(), values, out, warn, 0, Map.of());
            } catch (Exception e) {
                warn.accept("unreadable descriptor " + SourceScanner.relative(root, chart.getKey().resolve("Chart.yaml"))
                        + ": Helm chart not rendered (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    private static void renderChart(Path root, Path dir, Map<String, Object> meta, Map<String, Object> values,
                                    List<Rendered> out, Consumer<String> warn, int depth,
                                    Map<String, Node> inherited) throws Exception {
        if (depth > 4) return;
        String chartName = String.valueOf(meta.getOrDefault("name", dir.getFileName().toString()));
        Map<String, Object> chartObj = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            String k = e.getKey();
            chartObj.put(k.isEmpty() ? k : Character.toUpperCase(k.charAt(0)) + k.substring(1), e.getValue());
        }
        Map<String, Object> release = new LinkedHashMap<>();
        // The release name is chosen at install time: a name built from it is not knowable,
        // so it renders as the placeholder and never becomes a node or a host.
        release.put("Name", Unknown.VALUE);
        release.put("Namespace", "default");
        release.put("Service", "Helm");
        release.put("IsInstall", true);
        release.put("IsUpgrade", false);
        release.put("Revision", 1);
        Map<String, Object> capabilities = new LinkedHashMap<>();
        Map<String, Object> kube = new LinkedHashMap<>();
        kube.put("Version", "v1.29.0");
        kube.put("GitVersion", "v1.29.0");
        kube.put("Major", "1");
        kube.put("Minor", "29");
        capabilities.put("KubeVersion", kube);
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("Values", values);
        top.put("Chart", chartObj);
        top.put("Release", release);
        top.put("Capabilities", capabilities);

        // Helpers: named templates are global in Helm — a subchart sees its parent's defines
        // (hotelReservation's service charts only `include` the parent's base templates).
        Map<String, Node> defines = new LinkedHashMap<>(inherited);
        Path templates = dir.resolve("templates");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(templates)) {
            try (Stream<Path> stream = Files.walk(templates)) {
                stream.filter(Files::isRegularFile).sorted().forEach(files::add);
            }
        }
        Map<Path, Node> parsed = new LinkedHashMap<>();
        for (Path f : files) {
            String name = f.getFileName().toString();
            if (!(name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".tpl"))) continue;
            try {
                Node n = new Parser(Files.readString(f, StandardCharsets.UTF_8)).parse();
                collectDefines(n, defines);
                parsed.put(f, n);
            } catch (Exception e) {
                warn.accept("unreadable descriptor " + SourceScanner.relative(root, f) + ": template does not parse (" + e.getMessage() + ")");
            }
        }
        List<Object[]> subs = new ArrayList<>(); // {dir, meta, values}
        for (Map.Entry<String, Path> sub : subcharts(dir, meta).entrySet()) {
            Path subDir = sub.getValue();
            Path subYaml = subDir.resolve("Chart.yaml");
            if (!Files.isRegularFile(subYaml)) continue;
            Map<String, Object> subMeta = loadMap(subYaml, warn, root);
            Map<String, Object> subValues = loadValues(subDir, warn, root);
            Object override = values.get(sub.getKey());
            if (override instanceof Map<?, ?> m) subValues = deepMerge(subValues, castMap(m));
            if (values.get("global") instanceof Map<?, ?> g) {
                Map<String, Object> merged = new LinkedHashMap<>(subValues);
                Map<String, Object> ownGlobal = subValues.get("global") instanceof Map<?, ?> og ? castMap(og) : new LinkedHashMap<>();
                merged.put("global", deepMerge(ownGlobal, castMap(g)));
                subValues = merged;
            }
            subs.add(new Object[]{subDir, subMeta, subValues});
        }

        for (Map.Entry<Path, Node> f : parsed.entrySet()) {
            String name = f.getKey().getFileName().toString();
            if (name.startsWith("_") || name.endsWith(".tpl")) continue;
            Exec exec = new Exec(defines, top);
            StringBuilder sb = new StringBuilder();
            try {
                exec.run(f.getValue(), top, new LinkedHashMap<>(Map.of("$", top)), sb);
            } catch (Exception e) {
                warn.accept("unreadable descriptor " + SourceScanner.relative(root, f.getKey()) + ": template does not render (" + e.getMessage() + ")");
                continue;
            }
            RuleTrace.hit(52, "render " + SourceScanner.relative(root, f.getKey()));
            out.add(new Rendered(SourceScanner.relative(root, f.getKey()), chartName, sb.toString()));
        }
        for (Object[] sub : subs) {
            @SuppressWarnings("unchecked") Map<String, Object> subMeta = (Map<String, Object>) sub[1];
            @SuppressWarnings("unchecked") Map<String, Object> subValues = (Map<String, Object>) sub[2];
            renderChart(root, (Path) sub[0], subMeta, subValues, out, warn, depth + 1, defines);
        }
    }

    /** name (or alias) → directory of each dependency that lives in the repository: file:// or charts/<name>. */
    private static Map<String, Path> subcharts(Path dir, Map<String, Object> meta) {
        Map<String, Path> out = new LinkedHashMap<>();
        if (meta.get("dependencies") instanceof List<?> deps) {
            for (Object d : deps) {
                if (!(d instanceof Map<?, ?> dep)) continue;
                String name = String.valueOf(dep.get("name"));
                String key = dep.get("alias") != null ? String.valueOf(dep.get("alias")) : name;
                String repo = dep.get("repository") == null ? "" : String.valueOf(dep.get("repository"));
                Path p = null;
                if (repo.startsWith("file://")) p = dir.resolve(repo.substring(7)).normalize();
                else if (Files.isDirectory(dir.resolve("charts").resolve(name))) p = dir.resolve("charts").resolve(name);
                if (p != null && Files.isDirectory(p)) out.put(key, p);
            }
        }
        Path chartsDir = dir.resolve("charts");
        if (Files.isDirectory(chartsDir)) {
            try (Stream<Path> listing = Files.list(chartsDir)) {
                for (Path p : listing.toList()) {
                    if (Files.isRegularFile(p.resolve("Chart.yaml")) && !out.containsValue(p)) {
                        out.putIfAbsent(p.getFileName().toString(), p);
                    }
                }
            } catch (Exception ignored) {
                // unreadable: no packaged subcharts
            }
        }
        return out;
    }

    private static List<Path> fileDeps(Path dir, Map<String, Object> meta) {
        List<Path> out = new ArrayList<>();
        if (meta.get("dependencies") instanceof List<?> deps) {
            for (Object d : deps) {
                if (d instanceof Map<?, ?> dep && dep.get("repository") != null
                        && String.valueOf(dep.get("repository")).startsWith("file://")) {
                    out.add(dir.resolve(String.valueOf(dep.get("repository")).substring(7)));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> loadValues(Path dir, Consumer<String> warn, Path root) {
        Path v = dir.resolve("values.yaml");
        if (!Files.isRegularFile(v)) v = dir.resolve("values.yml");
        return Files.isRegularFile(v) ? loadMap(v, warn, root) : new LinkedHashMap<>();
    }

    private static Map<String, Object> loadMap(Path file, Consumer<String> warn, Path root) {
        try {
            Object o = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
            return o instanceof Map<?, ?> m ? castMap(m) : new LinkedHashMap<>();
        } catch (Exception e) {
            warn.accept("unreadable descriptor " + SourceScanner.relative(root, file) + ": " + firstLine(e.getMessage()));
            return new LinkedHashMap<>();
        }
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> over) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object b = out.get(e.getKey());
            if (b instanceof Map<?, ?> bm && e.getValue() instanceof Map<?, ?> om) {
                out.put(e.getKey(), deepMerge(castMap(bm), castMap(om)));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static void collectDefines(Node n, Map<String, Node> defines) {
        if (n instanceof Define d) defines.put(d.name, d.body);
        for (Node c : n.children()) collectDefines(c, defines);
    }

    // ---------------------------------------------------------------- template AST

    private interface Node {
        default List<Node> children() {
            return List.of();
        }
    }

    private record Text(String text) implements Node {
    }

    private record Action(String expr) implements Node {
    }

    private record ListNode(List<Node> items) implements Node {
        public List<Node> children() {
            return items;
        }
    }

    /** if / else if … / else: conditions[i] guards bodies[i]; elseBody may be null. */
    private record If(List<String> conditions, List<Node> bodies, Node elseBody) implements Node {
        public List<Node> children() {
            List<Node> c = new ArrayList<>(bodies);
            if (elseBody != null) c.add(elseBody);
            return c;
        }
    }

    private record Range(String header, Node body, Node elseBody) implements Node {
        public List<Node> children() {
            return elseBody == null ? List.of(body) : List.of(body, elseBody);
        }
    }

    private record With(String expr, Node body, Node elseBody) implements Node {
        public List<Node> children() {
            return elseBody == null ? List.of(body) : List.of(body, elseBody);
        }
    }

    private record Define(String name, Node body) implements Node {
        public List<Node> children() {
            return List.of(body);
        }
    }

    // ---------------------------------------------------------------- parser

    private static final class Parser {
        private final List<Object> tokens = new ArrayList<>(); // String text, or String[]{action}
        private int pos;

        Parser(String src) {
            int i = 0;
            StringBuilder text = new StringBuilder();
            while (i < src.length()) {
                int open = src.indexOf("{{", i);
                if (open < 0) {
                    text.append(src, i, src.length());
                    break;
                }
                text.append(src, i, open);
                int close = findClose(src, open + 2);
                if (close < 0) throw new IllegalArgumentException("unclosed {{ at offset " + open);
                String inner = src.substring(open + 2, close);
                boolean trimLeft = inner.startsWith("-") && (inner.length() == 1 || Character.isWhitespace(inner.charAt(1)));
                boolean trimRight = inner.endsWith("-") && (inner.length() == 1 || Character.isWhitespace(inner.charAt(inner.length() - 2)));
                if (trimLeft) inner = inner.substring(1);
                if (trimRight) inner = inner.substring(0, inner.length() - 1);
                if (trimLeft) {
                    int k = text.length();
                    while (k > 0 && Character.isWhitespace(text.charAt(k - 1))) k--;
                    text.setLength(k);
                }
                tokens.add(text.toString());
                text.setLength(0);
                tokens.add(new String[]{inner.trim()});
                i = close + 2;
                if (trimRight) {
                    while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
                }
            }
            tokens.add(text.toString());
        }

        /** The index of the }} closing an action opened before {@code from}, skipping string literals and comments. */
        private static int findClose(String s, int from) {
            int i = from;
            while (i < s.length() - 1) {
                char c = s.charAt(i);
                if (c == '/' && s.startsWith("/*", i)) {
                    int end = s.indexOf("*/", i + 2);
                    if (end < 0) return -1;
                    i = end + 2;
                    continue;
                }
                if (c == '"') {
                    i++;
                    while (i < s.length() && s.charAt(i) != '"') {
                        if (s.charAt(i) == '\\') i++;
                        i++;
                    }
                    i++;
                    continue;
                }
                if (c == '`') {
                    int end = s.indexOf('`', i + 1);
                    if (end < 0) return -1;
                    i = end + 1;
                    continue;
                }
                if (c == '}' && s.charAt(i + 1) == '}') return i;
                i++;
            }
            return -1;
        }

        Node parse() {
            ListNode n = parseList(Set.of());
            return n;
        }

        private String stopWord;   // the keyword that ended the last list (end / else …)
        private String stopRest;   // the rest of that action

        private ListNode parseList(Set<String> stops) {
            List<Node> items = new ArrayList<>();
            while (pos < tokens.size()) {
                Object t = tokens.get(pos++);
                if (t instanceof String s) {
                    if (!s.isEmpty()) items.add(new Text(s));
                    continue;
                }
                String a = ((String[]) t)[0];
                if (a.startsWith("/*")) continue; // comment
                String word = a.split("\\s+", 2)[0];
                String rest = a.length() > word.length() ? a.substring(word.length()).trim() : "";
                if (stops.contains(word)) {
                    stopWord = word;
                    stopRest = rest;
                    return new ListNode(items);
                }
                switch (word) {
                    case "if" -> items.add(parseIf(rest));
                    case "range" -> {
                        ListNode body = parseList(Set.of("end", "else"));
                        Node elseBody = null;
                        if ("else".equals(stopWord)) elseBody = parseList(Set.of("end"));
                        items.add(new Range(rest, body, elseBody));
                    }
                    case "with" -> {
                        ListNode body = parseList(Set.of("end", "else"));
                        Node elseBody = null;
                        if ("else".equals(stopWord)) elseBody = parseList(Set.of("end"));
                        items.add(new With(rest, body, elseBody));
                    }
                    case "define", "block" -> {
                        String name = unquote(rest.split("\\s+", 2)[0]);
                        ListNode body = parseList(Set.of("end"));
                        items.add(new Define(name, body));
                        if ("block".equals(word)) items.add(new Action("include " + rest));
                    }
                    default -> items.add(new Action(a));
                }
            }
            stopWord = null;
            return new ListNode(items);
        }

        private Node parseIf(String cond) {
            List<String> conds = new ArrayList<>();
            List<Node> bodies = new ArrayList<>();
            conds.add(cond);
            bodies.add(parseList(Set.of("end", "else")));
            Node elseBody = null;
            while ("else".equals(stopWord)) {
                String rest = stopRest;
                if (rest.startsWith("if ")) {
                    conds.add(rest.substring(3).trim());
                    bodies.add(parseList(Set.of("end", "else")));
                } else {
                    elseBody = parseList(Set.of("end"));
                    break;
                }
            }
            return new If(conds, bodies, elseBody);
        }
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("`") && s.endsWith("`"))) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\n", "\n");
        }
        return s;
    }

    // ---------------------------------------------------------------- evaluation

    /** The value of anything that cannot be known statically. */
    private enum Unknown {
        VALUE;

        @Override
        public String toString() {
            return PLACEHOLDER;
        }
    }

    private static final class Exec {
        private final Map<String, Node> defines;
        private final Map<String, Object> top;
        private int depth;

        Exec(Map<String, Node> defines, Map<String, Object> top) {
            this.defines = defines;
            this.top = top;
        }

        void run(Node n, Object dot, Map<String, Object> vars, StringBuilder out) {
            if (++depth > 200) throw new IllegalStateException("template recursion too deep");
            try {
                if (n instanceof Text t) {
                    out.append(t.text);
                } else if (n instanceof ListNode l) {
                    for (Node c : l.items) run(c, dot, vars, out);
                } else if (n instanceof Define) {
                    // collected beforehand; renders nothing in place
                } else if (n instanceof Action a) {
                    String expr = a.expr;
                    java.util.regex.Matcher assign = ASSIGN.matcher(expr);
                    if (assign.matches()) {
                        vars.put(assign.group(1), eval(assign.group(2), dot, vars));
                        return;
                    }
                    if (expr.startsWith("template ")) expr = "include " + expr.substring(9);
                    Object v = eval(expr, dot, vars);
                    out.append(text(v));
                } else if (n instanceof If f) {
                    boolean switchedOff = false;
                    for (int i = 0; i < f.conditions.size(); i++) {
                        Object c = eval(f.conditions.get(i), dot, vars);
                        if (c == Unknown.VALUE || truthy(c)) {
                            run(f.bodies.get(i), dot, vars, out);
                            return;
                        }
                        if (i == 0 && Boolean.FALSE.equals(c)) switchedOff = true;
                    }
                    if (f.elseBody != null) run(f.elseBody, dot, vars, out);
                    // A boolean switch that defaults to off guards an optional part: kept (see the
                    // class doc). An absent or empty value guards nothing to render: skipped.
                    else if (switchedOff) run(f.bodies.get(0), dot, vars, out);
                } else if (n instanceof With w) {
                    Object v = eval(w.expr, dot, vars);
                    if (v != Unknown.VALUE && truthy(v)) run(w.body, v, new LinkedHashMap<>(vars), out);
                    else if (w.elseBody != null) run(w.elseBody, dot, vars, out);
                } else if (n instanceof Range r) {
                    String header = r.header;
                    String keyVar = null, valVar = null;
                    java.util.regex.Matcher m = RANGE_VARS.matcher(header);
                    if (m.matches()) {
                        if (m.group(2) != null) {
                            keyVar = m.group(1);
                            valVar = m.group(2);
                        } else {
                            valVar = m.group(1);
                        }
                        header = m.group(3);
                    }
                    Object v = eval(header, dot, vars);
                    boolean any = false;
                    if (v instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> e : new TreeMap<>(castMap(map)).entrySet()) {
                            any = true;
                            Map<String, Object> scope = new LinkedHashMap<>(vars);
                            if (keyVar != null) scope.put(keyVar, e.getKey());
                            if (valVar != null) scope.put(valVar, e.getValue());
                            run(r.body, e.getValue(), scope, out);
                        }
                    } else if (v instanceof Collection<?> list) {
                        int i = 0;
                        for (Object item : list) {
                            any = true;
                            Map<String, Object> scope = new LinkedHashMap<>(vars);
                            if (keyVar != null) scope.put(keyVar, i);
                            if (valVar != null) scope.put(valVar, item);
                            run(r.body, item, scope, out);
                            i++;
                        }
                    } else if (v instanceof Number num) {
                        for (int i = 0; i < Math.min(num.intValue(), 50); i++) {
                            any = true;
                            Map<String, Object> scope = new LinkedHashMap<>(vars);
                            if (valVar != null) scope.put(valVar, i);
                            run(r.body, i, scope, out);
                        }
                    }
                    if (!any && r.elseBody != null) run(r.elseBody, dot, vars, out);
                }
            } finally {
                depth--;
            }
        }

        private static final java.util.regex.Pattern ASSIGN = java.util.regex.Pattern.compile("^(\\$[A-Za-z_][A-Za-z0-9_]*)\\s*:?=\\s*(.+)$", java.util.regex.Pattern.DOTALL);
        private static final java.util.regex.Pattern RANGE_VARS = java.util.regex.Pattern.compile(
                "^(\\$[A-Za-z_][A-Za-z0-9_]*)\\s*(?:,\\s*(\\$[A-Za-z_][A-Za-z0-9_]*)\\s*)?:=\\s*(.+)$", java.util.regex.Pattern.DOTALL);

        // ---- expressions: pipeline := command ('|' command)* ; command := operand+ ----

        Object eval(String expr, Object dot, Map<String, Object> vars) {
            List<String> tokens = tokenize(expr);
            int[] p = {0};
            Object v = pipeline(tokens, p, dot, vars);
            return v;
        }

        private Object pipeline(List<String> t, int[] p, Object dot, Map<String, Object> vars) {
            Object value = null;
            boolean first = true;
            while (p[0] < t.size() && !t.get(p[0]).equals(")")) {
                List<Object> args = new ArrayList<>();
                String fn = null;
                List<String> rawArgs = new ArrayList<>();
                while (p[0] < t.size() && !t.get(p[0]).equals("|") && !t.get(p[0]).equals(")")) {
                    String tok = t.get(p[0]++);
                    if (fn == null && args.isEmpty() && isIdent(tok)) {
                        fn = tok;
                        continue;
                    }
                    if (tok.equals("(")) {
                        args.add(pipeline(t, p, dot, vars));
                        if (p[0] < t.size() && t.get(p[0]).equals(")")) p[0]++;
                    } else {
                        rawArgs.add(tok);
                        args.add(operand(tok, dot, vars));
                    }
                }
                if (!first) args.add(value);
                if (fn != null) value = call(fn, args, dot, vars);
                else if (args.size() == 1) value = args.get(0);
                else if (!args.isEmpty() && args.get(0) == Unknown.VALUE) value = Unknown.VALUE;
                else if (!args.isEmpty()) value = methodCall(rawArgs, args);
                first = false;
                if (p[0] < t.size() && t.get(p[0]).equals("|")) p[0]++;
            }
            return value;
        }

        /** {@code .Capabilities.APIVersions.Has "x"}: a method call on a field; statically, the capability is present. */
        private Object methodCall(List<String> raw, List<Object> args) {
            if (!raw.isEmpty() && raw.get(0).endsWith(".Has")) return true;
            return Unknown.VALUE;
        }

        private static boolean isIdent(String tok) {
            return tok.matches("[A-Za-z_][A-Za-z0-9_]*") && !tok.equals("true") && !tok.equals("false") && !tok.equals("nil");
        }

        private Object operand(String tok, Object dot, Map<String, Object> vars) {
            if (tok.startsWith("\"") || tok.startsWith("`")) return unquote(tok);
            if (tok.equals("true")) return true;
            if (tok.equals("false")) return false;
            if (tok.equals("nil")) return null;
            if (tok.matches("-?\\d+")) return Long.parseLong(tok);
            if (tok.matches("-?\\d+\\.\\d+")) return Double.parseDouble(tok);
            if (tok.equals(".")) return dot;
            if (tok.startsWith("$")) {
                int dotAt = tok.indexOf('.');
                String name = dotAt < 0 ? tok : tok.substring(0, dotAt);
                Object base = vars.containsKey(name) ? vars.get(name) : (name.equals("$") ? top : Unknown.VALUE);
                return dotAt < 0 ? base : field(base, tok.substring(dotAt + 1));
            }
            if (tok.startsWith(".")) return field(dot, tok.substring(1));
            return Unknown.VALUE;
        }

        private static Object field(Object base, String chain) {
            Object cur = base;
            for (String part : chain.split("\\.")) {
                if (part.isEmpty()) continue;
                if (cur instanceof Map<?, ?> m) {
                    if (!m.containsKey(part)) return null;
                    cur = m.get(part);
                } else {
                    return cur == Unknown.VALUE ? Unknown.VALUE : null;
                }
            }
            return cur;
        }

        private Object call(String fn, List<Object> a, Object dot, Map<String, Object> vars) {
            boolean unknownArg = a.stream().anyMatch(x -> x == Unknown.VALUE);
            switch (fn) {
                case "include" -> {
                    if (a.isEmpty() || !(a.get(0) instanceof String name)) return Unknown.VALUE;
                    Node body = defines.get(name);
                    if (body == null) return Unknown.VALUE;
                    StringBuilder sb = new StringBuilder();
                    Object ctx = a.size() > 1 ? a.get(1) : dot;
                    run(body, ctx, new LinkedHashMap<>(Map.of("$", ctx instanceof Map<?, ?> ? ctx : top)), sb);
                    return sb.toString();
                }
                case "tpl" -> {
                    if (a.isEmpty() || !(a.get(0) instanceof String s)) return Unknown.VALUE;
                    try {
                        StringBuilder sb = new StringBuilder();
                        run(new Parser(s).parse(), a.size() > 1 ? a.get(1) : dot, new LinkedHashMap<>(vars), sb);
                        return sb.toString();
                    } catch (RuntimeException e) {
                        return Unknown.VALUE;
                    }
                }
                case "default" -> {
                    if (a.isEmpty()) return null;
                    Object given = a.size() > 1 ? a.get(a.size() - 1) : null;
                    if (given == Unknown.VALUE) return Unknown.VALUE;
                    return truthy(given) ? given : a.get(0);
                }
                case "required" -> {
                    return a.isEmpty() ? null : a.get(a.size() - 1);
                }
                case "coalesce" -> {
                    for (Object x : a) if (x == Unknown.VALUE || truthy(x)) return x;
                    return null;
                }
                case "ternary" -> {
                    return a.size() < 3 ? Unknown.VALUE : (truthy(a.get(2)) ? a.get(0) : a.get(1));
                }
                case "not" -> {
                    return a.isEmpty() || a.get(0) == Unknown.VALUE ? Unknown.VALUE : !truthy(a.get(0));
                }
                case "and" -> {
                    for (Object x : a) if (x != Unknown.VALUE && !truthy(x)) return x;
                    return unknownArg ? Unknown.VALUE : (a.isEmpty() ? null : a.get(a.size() - 1));
                }
                case "or" -> {
                    for (Object x : a) if (x != Unknown.VALUE && truthy(x)) return x;
                    return unknownArg ? Unknown.VALUE : (a.isEmpty() ? null : a.get(a.size() - 1));
                }
                case "eq" -> {
                    if (unknownArg || a.size() < 2) return Unknown.VALUE;
                    for (int i = 1; i < a.size(); i++) if (String.valueOf(a.get(0)).equals(String.valueOf(a.get(i)))) return true;
                    return false;
                }
                case "ne" -> {
                    return unknownArg || a.size() < 2 ? Unknown.VALUE : !String.valueOf(a.get(0)).equals(String.valueOf(a.get(1)));
                }
                case "lt", "gt", "le", "ge" -> {
                    if (unknownArg || a.size() < 2) return Unknown.VALUE;
                    try {
                        double x = Double.parseDouble(String.valueOf(a.get(0))), y = Double.parseDouble(String.valueOf(a.get(1)));
                        return switch (fn) {
                            case "lt" -> x < y;
                            case "gt" -> x > y;
                            case "le" -> x <= y;
                            default -> x >= y;
                        };
                    } catch (NumberFormatException e) {
                        return Unknown.VALUE;
                    }
                }
                case "empty" -> {
                    return a.isEmpty() || a.get(0) == Unknown.VALUE ? Unknown.VALUE : !truthy(a.get(0));
                }
                case "hasKey" -> {
                    return a.size() >= 2 && a.get(0) instanceof Map<?, ?> m ? m.containsKey(String.valueOf(a.get(1))) : Unknown.VALUE;
                }
                case "get" -> {
                    return a.size() >= 2 && a.get(0) instanceof Map<?, ?> m ? m.get(String.valueOf(a.get(1))) : Unknown.VALUE;
                }
                case "dict" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 0; i + 1 < a.size(); i += 2) m.put(String.valueOf(a.get(i)), a.get(i + 1));
                    return m;
                }
                case "list", "tuple" -> {
                    return new ArrayList<>(a);
                }
                case "len" -> {
                    if (a.isEmpty()) return 0L;
                    Object x = a.get(0);
                    if (x instanceof Collection<?> c) return (long) c.size();
                    if (x instanceof Map<?, ?> m) return (long) m.size();
                    return x == null ? 0L : (long) String.valueOf(x).length();
                }
                case "first" -> {
                    return a.size() == 1 && a.get(0) instanceof List<?> l && !l.isEmpty() ? l.get(0) : Unknown.VALUE;
                }
                case "contains" -> {
                    return unknownArg || a.size() < 2 ? Unknown.VALUE : String.valueOf(a.get(1)).contains(String.valueOf(a.get(0)));
                }
                case "hasPrefix" -> {
                    return unknownArg || a.size() < 2 ? Unknown.VALUE : String.valueOf(a.get(1)).startsWith(String.valueOf(a.get(0)));
                }
                case "hasSuffix" -> {
                    return unknownArg || a.size() < 2 ? Unknown.VALUE : String.valueOf(a.get(1)).endsWith(String.valueOf(a.get(0)));
                }
                case "semverCompare", "kindIs", "typeIs" -> {
                    return Unknown.VALUE;
                }
                default -> {
                    // fall through to the string functions below
                }
            }
            if (unknownArg) return Unknown.VALUE;
            String last = a.isEmpty() ? "" : text(a.get(a.size() - 1));
            switch (fn) {
                case "quote" -> {
                    return "\"" + last.replace("\"", "\\\"") + "\"";
                }
                case "squote" -> {
                    return "'" + last + "'";
                }
                case "lower" -> {
                    return last.toLowerCase(Locale.ROOT);
                }
                case "upper" -> {
                    return last.toUpperCase(Locale.ROOT);
                }
                case "title" -> {
                    return last.isEmpty() ? last : Character.toUpperCase(last.charAt(0)) + last.substring(1);
                }
                case "trim" -> {
                    return last.trim();
                }
                case "toString", "toYaml", "toJson", "toPrettyJson", "toRawJson" -> {
                    Object v = a.isEmpty() ? null : a.get(a.size() - 1);
                    if (fn.equals("toString")) return text(v);
                    return toYaml(v);
                }
                case "int", "int64", "float64", "atoi" -> {
                    try {
                        return (long) Double.parseDouble(last);
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                }
                case "trunc" -> {
                    if (a.size() < 2) return last;
                    int n = (int) Double.parseDouble(text(a.get(0)));
                    return last.length() > n ? last.substring(0, n) : last;
                }
                case "trimSuffix" -> {
                    String suf = a.size() > 1 ? text(a.get(0)) : "";
                    return !suf.isEmpty() && last.endsWith(suf) ? last.substring(0, last.length() - suf.length()) : last;
                }
                case "trimPrefix" -> {
                    String pre = a.size() > 1 ? text(a.get(0)) : "";
                    return !pre.isEmpty() && last.startsWith(pre) ? last.substring(pre.length()) : last;
                }
                case "replace" -> {
                    return a.size() < 3 ? last : last.replace(text(a.get(0)), text(a.get(1)));
                }
                case "nindent", "indent" -> {
                    int n = a.size() > 1 ? (int) Double.parseDouble(text(a.get(0))) : 0;
                    String pad = " ".repeat(Math.max(0, n));
                    String body = pad + last.replace("\n", "\n" + pad);
                    return fn.equals("nindent") ? "\n" + body : body;
                }
                case "printf" -> {
                    if (a.isEmpty()) return "";
                    String format = text(a.get(0));
                    Object[] rest = a.subList(1, a.size()).stream().map(x -> x instanceof Number n ? n.longValue() : (Object) text(x)).toArray();
                    try {
                        return String.format(format.replace("%v", "%s").replace("%q", "\"%s\""), rest);
                    } catch (Exception e) {
                        return Unknown.VALUE;
                    }
                }
                case "print" -> {
                    StringBuilder sb = new StringBuilder();
                    for (Object x : a) sb.append(text(x));
                    return sb.toString();
                }
                case "join" -> {
                    if (a.size() < 2 || !(a.get(1) instanceof Collection<?> c)) return last;
                    List<String> parts = new ArrayList<>();
                    for (Object x : c) parts.add(text(x));
                    return String.join(text(a.get(0)), parts);
                }
                default -> {
                    // b64enc, sha256sum, randAlphaNum, lookup, .Files.Get …: not knowable statically
                    return Unknown.VALUE;
                }
            }
        }

        private static List<String> tokenize(String s) {
            List<String> out = new ArrayList<>();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '"') {
                    int j = i + 1;
                    while (j < s.length() && s.charAt(j) != '"') {
                        if (s.charAt(j) == '\\') j++;
                        j++;
                    }
                    out.add(s.substring(i, Math.min(j + 1, s.length())));
                    i = j + 1;
                } else if (c == '`') {
                    int j = s.indexOf('`', i + 1);
                    if (j < 0) j = s.length() - 1;
                    out.add(s.substring(i, j + 1));
                    i = j + 1;
                } else if (c == '(' || c == ')' || c == '|') {
                    out.add(String.valueOf(c));
                    i++;
                } else {
                    int j = i;
                    while (j < s.length() && !Character.isWhitespace(s.charAt(j)) && "()|".indexOf(s.charAt(j)) < 0) j++;
                    out.add(s.substring(i, j));
                    i = j;
                }
            }
            return out;
        }
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v == Unknown.VALUE) return true;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    static String text(Object v) {
        if (v == null) return "";
        if (v instanceof Map<?, ?> || v instanceof Collection<?>) return toYaml(v);
        if (v instanceof Double d && d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf(d.longValue());
        return String.valueOf(v);
    }

    private static String toYaml(Object v) {
        if (v == null) return "null";
        if (!(v instanceof Map<?, ?>) && !(v instanceof Collection<?>)) return String.valueOf(v);
        if (v instanceof Map<?, ?> m && m.isEmpty()) return "{}";
        if (v instanceof Collection<?> c && c.isEmpty()) return "[]";
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        o.setIndent(2);
        o.setIndicatorIndent(0);
        o.setWidth(4096);
        String s = new Yaml(o).dump(v);
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }
}
