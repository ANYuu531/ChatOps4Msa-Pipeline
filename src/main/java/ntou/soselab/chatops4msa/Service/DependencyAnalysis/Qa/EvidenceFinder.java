package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.AddressShape;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.SourceScanner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.AliasResolution;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for the traces of one dependency the operator says exists — "kafka-connect
 * connects to postgres" — in the repository the graph was built from.
 *
 * <p>Why a second look can find what the extraction missed. The static layer reads the
 * formats its conventions cover and draws an edge only when all four questions are
 * answered (a declared name, in an address shape, owned by a caller, at a use site). A
 * request from a human turns the search around: the pair is given, so the search no
 * longer needs to know the format. It reads every text file for the target's name in an
 * address shape — a URL, a {@code host:port}, a value under a key whose name says
 * "host" — and asks the ownership convention whose file that is. The extraction's rules
 * are not loosened; only the question is narrower.
 *
 * <p>Three kinds of trace, strongest first:
 * <ol>
 *   <li>{@link Shape#RESIDUE} — a row the extraction did produce, whose target name the
 *       merge could not align (the tool saw it and could not place it);</li>
 *   <li>{@link Shape#ADDRESS} / {@link Shape#ADDRESS_KEY} — the target in an address
 *       shape, in a file the tool may or may not have read;</li>
 *   <li>{@link Shape#NAME} — the bare name, only kept when the file is the source's.</li>
 * </ol>
 * Each trace says whether the static layer read the file at all, which is the honest
 * answer to "why was this not drawn": an unread format, or a read file whose value no
 * convention handed to the source.
 *
 * <p>Pure Java, deterministic, no LLM: what is offered as evidence is a line that exists.
 */
public final class EvidenceFinder {

    /** How a line names the target. */
    public enum Shape {
        RESIDUE, ADDRESS, ADDRESS_KEY, NAME
    }

    /** One line offered as evidence. */
    public static final class Evidence {
        public final String file;
        public final int line;
        public final String text;
        public final Shape shape;
        /** The node the ownership convention gives the file (or the descriptor block), or null. */
        public final String owner;
        /** Whether the static layer produced any row from this file. */
        public final boolean toolRead;
        /** The edge type the line suggests: db, async, grpc, external or sync-http. */
        public final String suggestedType;
        final int score;

        Evidence(String file, int line, String text, Shape shape, String owner, boolean toolRead,
                 String suggestedType, int score) {
            this.file = file;
            this.line = line;
            this.text = text;
            this.shape = shape;
            this.owner = owner;
            this.toolRead = toolRead;
            this.suggestedType = suggestedType;
            this.score = score;
        }

        public String ref() {
            return file + ":" + line;
        }

        JSONObject toJson() {
            return new JSONObject().put("file", file).put("line", line).put("text", text)
                    .put("shape", shape.name()).put("owner", owner == null ? "" : owner)
                    .put("toolRead", toolRead).put("type", suggestedType).put("score", score);
        }

        static Evidence fromJson(JSONObject j) {
            Shape shape;
            try {
                shape = Shape.valueOf(j.optString("shape", "NAME"));
            } catch (IllegalArgumentException e) {
                shape = Shape.NAME;
            }
            String owner = j.optString("owner", "");
            return new Evidence(j.optString("file", ""), j.optInt("line", 0), j.optString("text", ""), shape,
                    owner.isBlank() ? null : owner, j.optBoolean("toolRead", false),
                    j.optString("type", "sync-http"), j.optInt("score", 0));
        }
    }

    /** What the search reads beside the files: the extraction's rows and who owns each file. */
    public static final class Context {
        final DependencyGraph graph;
        final Function<String, String> ownerOf;
        final Set<String> filesWithRows;
        final List<CodeGraphMerger.Unresolved> residue;

        public Context(DependencyGraph graph, Function<String, String> ownerOf, Set<String> filesWithRows,
                       List<CodeGraphMerger.Unresolved> residue) {
            this.graph = graph;
            this.ownerOf = ownerOf == null ? f -> null : ownerOf;
            this.filesWithRows = filesWithRows == null ? Set.of() : filesWithRows;
            this.residue = residue == null ? List.of() : residue;
        }
    }

    /** Offered per request; more is a list nobody reads. */
    public static final int MAX_RESULTS = 5;
    /** Two lines of one file say the same thing twice. */
    private static final int MAX_PER_FILE = 2;
    /** A hit below this is a word, not a trace. */
    private static final int MIN_SCORE = 4;
    private static final long MAX_FILE_BYTES = 400_000;
    private static final int MAX_LINE = 400;

    /** Build output, dependencies and tool state: not the project's own text. */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out", "vendor", ".gradle", ".idea", ".vscode",
            ".venv", "venv", "__pycache__", ".next", "bin", "obj", ".mvn", "coverage");
    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "ico", "svg", "webp", "bmp", "pdf", "jar", "war", "class", "zip", "gz",
            "tgz", "tar", "7z", "woff", "woff2", "ttf", "eot", "otf", "mp4", "mp3", "lock", "map", "bin", "so",
            "dll", "exe", "pyc", "keystore", "jks", "p12");
    private static final Set<String> SKIP_FILES = Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "go.sum");
    private static final Set<String> DOC_EXTENSIONS = Set.of("md", "adoc", "rst", "txt");
    /** The words every service name carries; "order-service" and "order" are one name here. */
    private static final Set<String> NAME_FILLER = Set.of("service", "server", "svc", "api", "app");

    private static final Pattern URL = Pattern.compile("[A-Za-z][A-Za-z0-9+.\\-]*://[^\\s'\"`<>()\\[\\]{},;]+");
    private static final Pattern HOST_PORT = Pattern.compile("(?<![A-Za-z0-9_.\\-/:@])([A-Za-z][A-Za-z0-9_.\\-]*):(\\d{2,5})\\b");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "^\\s*(?:-\\s*)?[\"']?([A-Za-z_][A-Za-z0-9_.:\\-\\[\\]]*)[\"']?\\s*[:=]\\s*[\"']?([^\"'\\s,#;]+)");
    private static final Pattern YAML_HEADER = Pattern.compile("^\\s*(?:-\\s*)?([A-Za-z0-9_.\\-]+):\\s*$");
    private static final Pattern YAML_NAME = Pattern.compile("^\\s*(?:-\\s*)?name:\\s*[\"']?([A-Za-z0-9_.\\-]+)[\"']?\\s*$");

    private EvidenceFinder() {
    }

    /**
     * Traces of {@code source → target} in the checkout, best first.
     *
     * @param source the caller's node id, or null when only a node is being added (then
     *               every attributable mention of the target counts)
     * @param target the callee's node id, or the name of a node the operator is adding
     */
    public static List<Evidence> find(Path root, Context ctx, String source, String target) {
        List<Evidence> found = new ArrayList<>();
        if (root == null || target == null || target.isBlank()) return found;
        Set<String> targetKeys = nameKeys(target);
        Set<String> sourceKeys = source == null ? Set.of() : nameKeys(source);

        // 1. The extraction's own residue: rows it produced and could not align.
        for (CodeGraphMerger.Unresolved u : ctx.residue) {
            if (u.rawTarget == null || u.file == null) continue;
            String rawHost = AddressShape.host(u.rawTarget).isEmpty() ? u.rawTarget : AddressShape.host(u.rawTarget);
            if (hostMatch(rawHost, targetKeys) == 0) continue;
            String owner = ctx.ownerOf.apply(u.file);
            boolean bySource = source == null || (owner != null && nameKeys(owner).stream().anyMatch(sourceKeys::contains))
                    || (u.rawSource != null && nameKeys(u.rawSource).stream().anyMatch(sourceKeys::contains));
            if (!bySource) continue;
            String text = lineOf(root.resolve(u.file), u.line);
            found.add(new Evidence(u.file, u.line, text == null ? u.rawTarget : text, Shape.RESIDUE, owner, true,
                    suggestType(ctx.graph, target, u.rawTarget, u.section), 8));
        }

        // 2. Every text file, for the target in an address shape or by name.
        for (Path file : textFiles(root)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            List<String> lines = readLines(file);
            if (lines == null) continue;
            String owner = null;
            boolean ownerResolved = false;
            for (int i = 0; i < lines.size(); i++) {
                String raw = lines.get(i);
                if (raw.length() > MAX_LINE || isComment(raw)) continue;
                Hit hit = match(raw, targetKeys, isCode(rel));
                if (hit == null) continue;
                if (!ownerResolved) {
                    owner = safeOwner(ctx.ownerOf, rel);
                    ownerResolved = true;
                }
                String block = isYaml(rel) ? descriptorBlock(lines, i, ctx.graph) : null;
                int score = score(hit, rel, owner, block, source, sourceKeys, target, targetKeys);
                if (score < MIN_SCORE) continue;
                String attributed = block != null ? block : owner;
                found.add(new Evidence(rel, i + 1, raw.trim(), hit.shape, attributed, ctx.filesWithRows.contains(rel),
                        suggestType(ctx.graph, target, hit.value, null), score));
            }
        }

        // Best first; a line already offered as residue is not offered twice.
        found.sort(Comparator.comparingInt((Evidence e) -> -e.score).thenComparingInt(e -> e.shape.ordinal())
                .thenComparing(e -> e.file).thenComparingInt(e -> e.line));
        List<Evidence> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Integer> perFile = new LinkedHashMap<>();
        for (Evidence e : found) {
            if (!seen.add(e.ref())) continue;
            int n = perFile.merge(e.file, 1, Integer::sum);
            if (n > MAX_PER_FILE) continue;
            out.add(e);
            if (out.size() == MAX_RESULTS) break;
        }
        return out;
    }

    /**
     * Reads one line the operator pointed at ({@code path:line}), for the dialogue to
     * check that it exists and names the target. Null when the file or line is not there.
     */
    public static String lineOf(Path file, int line) {
        List<String> lines = readLines(file);
        if (lines == null || line < 1 || line > lines.size()) return null;
        return lines.get(line - 1).trim();
    }

    /** Whether a line the operator pointed at names the target in any of the shapes above. */
    public static boolean namesTarget(String file, String text, String target) {
        return text != null && match(text, nameKeys(target), file != null && isCode(file)) != null;
    }

    // ---------- matching one line ----------

    /** {@code weak}: a two-label host that may be {@code service.namespace} or may be a domain. */
    private record Hit(Shape shape, String value, boolean weak) {
    }

    /**
     * @param code a source file: there a bare word is prose or an identifier ("Payment
     *             status", PAYMENT_FAILED), so only a string that is exactly the name
     *             counts ({@code @FeignClient(name = "payment")}); in a configuration file
     *             a name standing alone is a reference ({@code depends_on: [payment]})
     */
    private static Hit match(String line, Set<String> targetKeys, boolean code) {
        Hit weak = null;
        Matcher url = URL.matcher(line);
        while (url.find()) {
            int m = hostMatch(AddressShape.host(url.group()), targetKeys);
            if (m == 2) return new Hit(Shape.ADDRESS, url.group(), false);
            if (m == 1 && weak == null) weak = new Hit(Shape.ADDRESS, url.group(), true);
        }
        Matcher hp = HOST_PORT.matcher(line);
        while (hp.find()) {
            int m = hostMatch(hp.group(1), targetKeys);
            if (m == 2) return new Hit(Shape.ADDRESS, hp.group(), false);
            if (m == 1 && weak == null) weak = new Hit(Shape.ADDRESS, hp.group(), true);
        }
        Matcher kv = KEY_VALUE.matcher(line);
        if (kv.find()) {
            String key = kv.group(1);
            String value = kv.group(2);
            if (!AddressShape.isInboundKey(key) && AddressShape.keyKind(key) != AddressShape.KeyKind.NONE) {
                String host = AddressShape.isAddressShaped(value) ? AddressShape.host(value) : value;
                int m = hostMatch(host, targetKeys);
                if (m == 2) return new Hit(Shape.ADDRESS_KEY, value, false);
                if (m == 1 && weak == null) weak = new Hit(Shape.ADDRESS_KEY, value, true);
            }
        }
        if (weak != null) return weak;
        for (String k : targetKeys) {
            if (k.length() < 4) continue;
            if (code) {
                Matcher q = QUOTED.matcher(line);
                while (q.find()) if (nameKeys(q.group(2)).contains(k)) return new Hit(Shape.NAME, k, false);
            } else if (wordPattern(k).matcher(line).find()) {
                return new Hit(Shape.NAME, k, false);
            }
        }
        return null;
    }

    private static final Pattern QUOTED = Pattern.compile("([\"'`])([A-Za-z0-9][A-Za-z0-9_.\\-]{2,62})\\1");
    /** Languages with a grammar or a thin surface here; a bare word in them is code, not a reference. */
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "groovy", "go", "py", "js", "jsx", "mjs", "cjs", "ts", "tsx", "cs", "rb",
            "php", "rs", "swift", "c", "cc", "cpp", "h", "hpp", "dart", "ex", "exs", "clj");

    private static boolean isCode(String file) {
        return CODE_EXTENSIONS.contains(extension(file.toLowerCase(Locale.ROOT)));
    }

    /** A commented-out line is what the project does not do (yas's commented webhook service). */
    private static boolean isComment(String line) {
        String t = line.trim();
        return t.startsWith("#") || t.startsWith("//") || t.startsWith("<!--") || t.startsWith("--") || t.startsWith(";")
                || (t.startsWith("*") && !t.startsWith("*/"));
    }

    private static final Map<String, Pattern> WORD_PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The name as a word of its own: "postgres" in "host: postgres", not in "postgres-data". */
    private static Pattern wordPattern(String key) {
        return WORD_PATTERNS.computeIfAbsent(key, k -> {
            // A key has no separators; the name may be written with any of them.
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < k.length(); i++) {
                if (i > 0) p.append("[-_.]?");
                p.append(Pattern.quote(String.valueOf(k.charAt(i))));
            }
            return Pattern.compile("(?i)(?<![A-Za-z0-9_\\-])" + p + "(?![A-Za-z0-9_\\-])");
        });
    }

    /**
     * Whether a host names the target: 2 when it does, 1 when it may, 0 when not.
     *
     * <p>A host names a cluster service the way cluster DNS spells it (convention ②): a
     * single label, or the label with a cluster suffix ({@code .svc}, {@code .cluster.local}).
     * The whole host spelling the target's name counts too — an external host the operator
     * is adding is named by its domain. A domain whose first label merely matches
     * ({@code storefront.yas.local.com}, an ingress host) names no service; two labels
     * ({@code postgresql.postgres}) may be {@code service.namespace} and are kept, weakly.
     */
    static int hostMatch(String host, Set<String> targetKeys) {
        if (host == null || host.isBlank() || AddressShape.isLoopback(host)) return 0;
        String h = host.toLowerCase(Locale.ROOT).trim();
        if (targetKeys.contains(AliasResolution.key(h))) return 2;
        String label = h.contains(".") ? h.substring(0, h.indexOf('.')) : h;
        boolean named = false;
        for (String k : nameKeys(label)) if (targetKeys.contains(k)) named = true;
        if (!named) return 0;
        if (!NameTable.isExternal(h, "")) return 2;
        return h.split("\\.").length == 2 ? 1 : 0;
    }

    /** Whether a host names the target, strongly or weakly. */
    static boolean matchesHost(String host, Set<String> targetKeys) {
        return hostMatch(host, targetKeys) > 0;
    }

    /** The keys a name is known by: as written, and without the filler words and deployment suffixes. */
    static Set<String> nameKeys(String name) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null) return out;
        String n = name.trim().toLowerCase(Locale.ROOT);
        out.add(AliasResolution.key(n));
        for (String suffix : Vocabulary.DEPLOY_NAME_SUFFIXES) {
            if (n.endsWith(suffix)) n = n.substring(0, n.length() - suffix.length());
        }
        List<String> words = new ArrayList<>(List.of(n.split("[^a-z0-9]+")));
        words.removeIf(String::isEmpty);
        List<String> core = new ArrayList<>(words);
        core.removeIf(NAME_FILLER::contains);
        if (!core.isEmpty()) out.add(String.join("", core));
        out.remove("");
        return out;
    }

    // ---------- whose line is it ----------

    private static int score(Hit hit, String file, String owner, String block, String source, Set<String> sourceKeys,
                             String target, Set<String> targetKeys) {
        int score = hit.shape == Shape.NAME ? 1 : hit.weak ? 2 : 4;
        String attributed = block != null ? block : owner;
        boolean bySource = attributed != null && !sourceKeys.isEmpty() && overlaps(nameKeys(attributed), sourceKeys);
        boolean byTarget = attributed != null && overlaps(nameKeys(attributed), targetKeys);
        if (source != null) {
            if (bySource) score += 4;
            else {
                double related = pathRelation(file, source);
                if (related >= 0.99) score += 2;
                else if (related >= 0.5) score += 1;
                // The value is someone else's: another service's own config naming the target.
                if (attributed != null && !byTarget) score -= 1;
            }
        } else if (attributed != null && !byTarget) {
            score += 1;
        }
        // The target's own file naming itself (its application name, its own URL). A bare
        // name there is almost always that; an address less so — a front end in the
        // target's directory (storefront/ holds the Next.js app) addresses its back end.
        if (byTarget && !bySource) score -= hit.shape == Shape.NAME ? 2 : 1;
        String lower = file.toLowerCase(Locale.ROOT);
        // A test tree, by the static layer's own definition: an e2e suite's config names the
        // service it drives (automation-ui/storefront/… → http://storefront/), which is not
        // the service's own address of it.
        if (SourceScanner.isTestPath(lower)) score -= 2;
        if (DOC_EXTENSIONS.contains(extension(lower))) score -= 1;
        return score;
    }

    private static boolean overlaps(Set<String> a, Set<String> b) {
        for (String x : a) if (b.contains(x)) return true;
        return false;
    }

    /**
     * How much of the source's name the path spells: {@code kafka/connects/…} spells both
     * words of {@code kafka-connect}. Used only when the ownership convention gives the file
     * to no one, which is exactly the case of a format the extraction does not read.
     */
    static double pathRelation(String file, String source) {
        List<String> sourceWords = new ArrayList<>(List.of(source.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")));
        sourceWords.removeIf(w -> w.isEmpty() || NAME_FILLER.contains(w));
        if (sourceWords.isEmpty()) return 0;
        String dir = file.contains("/") ? file.substring(0, file.lastIndexOf('/')) : "";
        String spaced = dir.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        Set<String> pathWords = new LinkedHashSet<>(List.of(spaced.split("[^a-z0-9]+")));
        int hit = 0;
        for (String w : sourceWords) {
            for (String p : pathWords) {
                if (p.equals(w) || (Math.min(p.length(), w.length()) >= 4 && (p.startsWith(w) || w.startsWith(p)))) {
                    hit++;
                    break;
                }
            }
        }
        return (double) hit / sourceWords.size();
    }

    /**
     * The service block a YAML line sits in — a Compose {@code services:} entry, a
     * Kubernetes container or manifest {@code name:} — when that block is a node of the
     * graph. Walks up the indentation, nearest enclosing first.
     */
    static String descriptorBlock(List<String> lines, int index, DependencyGraph graph) {
        if (graph == null) return null;
        int indent = indentOf(lines.get(index));
        for (int j = index - 1; j >= 0 && j >= index - 300; j--) {
            String l = lines.get(j);
            if (l.isBlank() || l.trim().startsWith("#")) continue;
            int ind = indentOf(l);
            if (ind > indent) continue;
            Matcher name = YAML_NAME.matcher(l);
            if (name.matches()) {
                String node = nodeNamed(graph, name.group(1));
                if (node != null) return node;
            }
            if (ind < indent) {
                Matcher header = YAML_HEADER.matcher(l);
                if (header.matches()) {
                    String node = nodeNamed(graph, header.group(1));
                    if (node != null) return node;
                }
                indent = ind;
            }
            if (ind == 0 && l.trim().equals("---")) break;
        }
        return null;
    }

    private static String nodeNamed(DependencyGraph graph, String raw) {
        if (AliasResolution.key(raw).length() < 3) return null;
        Set<String> keys = nameKeys(raw);
        for (DependencyGraph.Node n : graph.getNodes()) if (overlaps(nameKeys(n.id), keys)) return n.id;
        return null;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '-')) i++;
        return i;
    }

    private static String safeOwner(Function<String, String> ownerOf, String file) {
        try {
            return ownerOf.apply(file);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- the edge type a trace suggests ----------

    static String suggestType(DependencyGraph graph, String target, String value, String section) {
        DependencyGraph.Node node = graph == null ? null : graph.findNode(target);
        String kind = node != null ? node.kind : DependencyGraph.classifyKind(target);
        if (DependencyGraph.KIND_DB.equals(kind)) return "db";
        if (DependencyGraph.KIND_QUEUE.equals(kind)) return "async";
        if (DependencyGraph.KIND_EXTERNAL.equals(kind)) return "external";
        AddressShape.Address a = AddressShape.parse(value);
        if (a != null && "db".equals(a.kind())) return "db";
        if (a != null && "queue".equals(a.kind())) return "async";
        if (a != null && a.scheme() != null && a.scheme().startsWith("grpc")) return "grpc";
        if (section != null && section.contains("grpc")) return "grpc";
        return "sync-http";
    }

    // ---------- reading the checkout ----------

    static List<Path> textFiles(Path root) {
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (attrs.isRegularFile() && attrs.size() <= MAX_FILE_BYTES && !SKIP_FILES.contains(lower)
                            && !SKIP_EXTENSIONS.contains(extension(lower)) && !lower.endsWith(".min.js")) out.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // a partial walk still answers
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static List<String> readLines(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i < Math.min(bytes.length, 8000); i++) if (bytes[i] == 0) return null; // binary
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return List.of(text.split("\r?\n", -1));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isYaml(String file) {
        String e = extension(file.toLowerCase(Locale.ROOT));
        return e.equals("yml") || e.equals("yaml");
    }

    private static String extension(String name) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        return dot > slash && dot >= 0 ? name.substring(dot + 1) : "";
    }

    /** The files the extraction produced a row from, as the ledger spells them (relative to the root). */
    public static Set<String> filesWithRows(String codeEdgesJson) {
        Set<String> out = new LinkedHashSet<>();
        if (codeEdgesJson == null || codeEdgesJson.isBlank()) return out;
        try {
            org.json.JSONArray rows = new JSONObject(codeEdgesJson).optJSONArray("edges");
            if (rows == null) return out;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && !row.optString("file", "").isBlank()) out.add(row.optString("file").replace('\\', '/'));
            }
        } catch (Exception ignored) {
            // no rows: every trace reads as "not read by the tool", which is then true of what we know
        }
        return out;
    }

    static List<Evidence> listFromJson(org.json.JSONArray array) {
        List<Evidence> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject j = array.optJSONObject(i);
            if (j != null) out.add(Evidence.fromJson(j));
        }
        return out;
    }

    static org.json.JSONArray listToJson(Collection<Evidence> list) {
        org.json.JSONArray array = new org.json.JSONArray();
        for (Evidence e : list) array.put(e.toJson());
        return array;
    }
}
