package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import jakarta.annotation.PostConstruct;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.UseSite;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.treesitter.TSInputEncoding;
import org.treesitter.TSLanguage;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCSharp;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterKotlin;
import org.treesitter.TreeSitterPhp;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterTypescript;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic, syntax-level dependency extraction driven by tree-sitter query
 * packs (src/main/resources/treesitter/*.scm).
 *
 * Adding a new framework idiom means adding a pattern to a .scm file: the capture
 * naming convention (&lt;section&gt;.&lt;field&gt;) is what this class turns into ledger
 * edges, so no Java change is needed.
 *
 * Thread safety: TSLanguage and a compiled TSQuery are immutable and shared;
 * TSParser and TSQueryCursor are not, so a parser is held per thread and a cursor
 * is created per query run.
 */
@Component
public class TreeSitterExtractor {

    /** Section caps, so one noisy repository cannot flood the report. */
    private static final Map<String, Integer> SECTION_CAPS = Map.of(
            "url", 60,
            "config", 80,
            // Lookup rows, not report entries: every reference matters for resolution.
            "name-ref", 5000,
            "name-constant", 2000,
            "import", 5000,
            // Rule 47's bindings: lookup rows the resolver consumes and removes.
            BindingResolver.BINDING, 50000,
            BindingResolver.DEFAULT, 5000,
            "registry-ref", 500,
            "registry-name", 500);

    private static final int DEFAULT_SECTION_CAP = 200;

    private final Map<String, TSLanguage> languages = new ConcurrentHashMap<>();
    private final Map<String, TreeSitterQueryEngine> queryCache = new ConcurrentHashMap<>();
    private final ThreadLocal<TSParser> parsers = ThreadLocal.withInitial(TSParser::new);

    /**
     * Fails fast at container start rather than deep inside a user's request.
     *
     * The native library is extracted from the jar on first use, so a wrong
     * platform, a musl base image or a read-only temp dir would otherwise only
     * surface halfway through an analysis. Parsing two lines here turns that into
     * a startup failure.
     */
    @PostConstruct
    void smokeTest() {
        try {
            TSLanguage java = language("java");
            TSParser parser = parsers.get();
            parser.setLanguage(java);
            String source = "class A { void f() { t.send(\"topic\"); } }";
            TSTree tree = parser.parseStringEncoding(null, source, TSInputEncoding.TSInputEncodingUTF8);
            if (tree.getRootNode().hasError()) {
                throw new IllegalStateException("tree-sitter parsed a trivial Java snippet with errors");
            }
            // Also compile every query pack now, so a malformed .scm is a startup
            // failure instead of a silently empty section later.
            for (String pack : List.of("java-spring", "java-generic")) {
                engine("java", pack);
            }
            for (String pack : List.of("python-web", "python-generic")) {
                engine("python", pack);
            }
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "tree-sitter failed to initialise. The native library must match the platform: "
                            + "the bundled libs are glibc-linked (an -alpine/musl base image will not work). "
                            + "Cause: " + t, t);
        }
        // The round-4 grammars are not a startup condition: a platform whose native
        // library for one of them is missing degrades that language (Kotlin and C# to the
        // thin regex surface, the others to unread), and the ledger says so per analysis.
        // A malformed query pack, on the other hand, is a bug and fails startup.
        List<String> ready = new ArrayList<>(List.of("java", "python"));
        for (String lang : OPTIONAL_GRAMMARS) {
            try {
                language(lang);
            } catch (Throwable t) {
                System.out.println("[WARN] tree-sitter grammar '" + lang + "' unavailable on this platform: " + t);
                continue;
            }
            engine(lang, packFamily(lang) + "-generic");
            ready.add(lang);
        }
        System.out.println("[INFO] tree-sitter ready (grammars: " + String.join(", ", ready) + "; query packs compiled)");
    }

    /** Languages this instance treats as if their native grammar failed to load (the fallback's test hook). */
    private final Set<String> simulatedUnavailable = new java.util.HashSet<>();

    /** Treats {@code language}'s grammar as unavailable on this instance, so the fallback path can be exercised. */
    public TreeSitterExtractor withoutGrammar(String language) {
        simulatedUnavailable.add(language);
        return this;
    }

    /** Grammars added in round 4 (2026-10-27); each has a generic query pack. */
    static final List<String> OPTIONAL_GRAMMARS = Vocabulary.OPTIONAL_GRAMMARS;

    /** Languages that keep the thin regex surface as a fallback when their grammar fails to load (rules 28, 40). */
    private static final Set<String> THIN_FALLBACK = Vocabulary.THIN_FALLBACK;

    public boolean supports(String language) {
        return "java".equals(language) || "python".equals(language) || OPTIONAL_GRAMMARS.contains(language);
    }

    /** The query-pack family of a language: TypeScript is read with the JavaScript packs (its grammar is a superset). */
    static String packFamily(String language) {
        return "typescript".equals(language) ? "javascript" : language;
    }

    /**
     * Runs every query pack of the stack over the stack's source files and adds
     * the resulting edges to the ledger.
     */
    public void extract(Path root, DetectedStack stack, EdgeLedger ledger) {
        boolean thinFallback = THIN_FALLBACK.contains(stack.language);
        TSLanguage language;
        try {
            if (simulatedUnavailable.contains(stack.language)) {
                throw new UnsatisfiedLinkError("grammar marked unavailable for this extractor");
            }
            language = language(stack.language);
        } catch (Throwable t) {
            // A grammar whose native library does not load on this platform: Kotlin and C#
            // still have the thin regex surface; any other language is left unread, and the
            // ledger says which, so the graph's reader knows that layer is missing.
            if (thinFallback) {
                ledger.addWarning("tree-sitter grammar for " + stack.language + " failed to load (" + t
                        + "); its files were read with the thin regex surface instead.");
                extractThinSurface(root, stack, ledger);
            } else {
                ledger.addWarning("tree-sitter grammar for " + stack.language + " failed to load (" + t
                        + "); its source files were not read.");
            }
            return;
        }
        List<TreeSitterQueryEngine> engines = new ArrayList<>();
        for (String pack : stack.queryPacks()) {
            try {
                engines.add(engine(stack.language, pack));
            } catch (Exception e) {
                ledger.addWarning("query pack '" + pack + "' failed to compile: " + e.getMessage());
            }
        }
        if (engines.isEmpty()) {
            if (thinFallback) extractThinSurface(root, stack, ledger);
            return;
        }

        TSParser parser = parsers.get();
        parser.setLanguage(language);
        boolean csharp = "csharp".equals(stack.language);

        Map<String, Integer> sectionCounts = new LinkedHashMap<>();
        List<Path> files = SourceScanner.filesWithExtensions(root, stack.extensions);
        int parsed = 0;
        Map<String, String> sources = new LinkedHashMap<>(); // relative path -> text, for the reference check

        for (Path file : files) {
            // .NET build output (obj/, bin/) holds generated .cs files, not the project's source.
            if (csharp && ("/" + SourceScanner.relative(root, file)).matches("(?i).*/(obj|bin)/.*")) continue;
            String source;
            try {
                source = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue; // binary or non-UTF-8: not source we can analyse
            }
            sources.put(SourceScanner.relative(root, file), source);
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);

            TSTree tree;
            try {
                tree = parser.parseStringEncoding(null, source, TSInputEncoding.TSInputEncodingUTF8);
            } catch (Exception e) {
                continue;
            }
            parsed++;
            // Unlike a throwing parser, tree-sitter still yields a usable tree here:
            // the broken region becomes ERROR nodes and the rest is still queried.
            if (tree.getRootNode().hasError()) ledger.incFilesWithErrors();

            String relative = SourceScanner.relative(root, file);
            // Rules 28 / 40 (Kotlin, C#): now grammar-backed; the regex surface is the fallback.
            if ("kotlin".equals(stack.language)) RuleTrace.hit(28, "kt-file " + relative);
            if (csharp) RuleTrace.hit(40, "cs-file " + relative);
            for (TreeSitterQueryEngine engine : engines) {
                for (TreeSitterQueryEngine.Match match : engine.run(tree, bytes)) {
                    emit(match, relative, ledger, sectionCounts);
                }
            }
        }

        ledger.addFilesParsed(parsed);
        // The directories a grammar actually read: the merger's evidence that a module's
        // code is readable at all (a Rust or Ruby module has none), 2026-11-04.
        Set<String> parsedDirs = new java.util.LinkedHashSet<>();
        for (String relative : sources.keySet()) {
            int slash = relative.lastIndexOf('/');
            parsedDirs.add(slash < 0 ? "" : relative.substring(0, slash));
        }
        for (String dir : parsedDirs) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("dir", dir);
            fields.put("language", stack.language);
            ledger.add("parsed-dir", fields, dir, -1, "High (files a grammar parsed)");
        }
        if (csharp) normaliseDotnetConfigReads(ledger, sources.keySet());
        BindingResolver.resolve(ledger);
        UseSite.apply(stack.language, root, ledger, sources);

        for (TreeSitterQueryEngine engine : engines) {
            for (String error : engine.getPredicateErrors()) ledger.addWarning(error);
        }
        for (Map.Entry<String, Integer> entry : sectionCounts.entrySet()) {
            int cap = SECTION_CAPS.getOrDefault(entry.getKey(), DEFAULT_SECTION_CAP);
            if (entry.getValue() > cap) {
                ledger.addWarning("section '" + entry.getKey() + "' truncated at " + cap
                        + " entries (" + entry.getValue() + " found in " + stack.language + ")");
            }
        }
    }

    /** {@code System.getenv("KAFKA_ADDR")}, with an optional Kotlin elvis default {@code ?: "kafka:9092"}. */
    private static final java.util.regex.Pattern KT_GETENV = java.util.regex.Pattern.compile(
            "System\\.getenv\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*\\)(?:\\s*\\?:\\s*\"([^\"]*)\")?");
    private static final java.util.regex.Pattern KT_URL = java.util.regex.Pattern.compile("\"(https?://[^\"\\s$]+)\"");
    /** {@code @KafkaListener(topics = ["orders"])} / {@code @RabbitListener(queues = ["q"])}, Kotlin array or single literal. */
    private static final java.util.regex.Pattern KT_LISTENER = java.util.regex.Pattern.compile(
            "@(KafkaListener|RabbitListener)\\s*\\([^)]*?(?:topics|queues|queuesToDeclare|topicPattern)\\s*=\\s*\\[?\\s*\"([^\"]+)\"");

    /**
     * The thin regex surface for a JVM language with no bundled grammar (Kotlin): the
     * same framework-neutral signals the Java generic pack reads — environment reads,
     * absolute URL literals and messaging listener annotations — matched textually,
     * comment lines skipped. Everything a grammar would add (call receivers, constant
     * resolution, dead-code checks) is absent, and the ledger says so, so the graph's
     * reader knows a Kotlin module's code layer is shallower than a Java module's.
     */
    private void extractThinSurface(Path root, DetectedStack stack, EdgeLedger ledger) {
        boolean csharp = "csharp".equals(stack.language);
        List<Path> files = SourceScanner.filesWithExtensions(root, stack.extensions);
        int parsed = 0;
        Map<String, Integer> sectionCounts = new LinkedHashMap<>();
        for (Path file : files) {
            // .NET build output (obj/, bin/) holds generated .cs files, not the project's source.
            String relative = SourceScanner.relative(root, file);
            if (csharp && ("/" + relative).matches("(?i).*/(obj|bin)/.*")) continue;
            String source;
            try {
                source = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            parsed++;
            RuleTrace.hit(csharp ? 40 : 28, (csharp ? "cs-file " : "kt-file ") + relative);
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String code = line.trim().startsWith("//") || line.trim().startsWith("*") ? "" : line;
                int comment = code.indexOf("//");
                if (comment >= 0 && code.indexOf('"') > comment) code = code.substring(0, comment);
                java.util.regex.Matcher env = (csharp ? CS_GETENV : KT_GETENV).matcher(code);
                while (env.find()) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("env", env.group(1));
                    if (env.group(2) != null && !env.group(2).isBlank()) fields.put("envdefault", env.group(2));
                    emitThin("config", fields, relative, i + 1, ledger, sectionCounts);
                }
                if (csharp) {
                    // IConfiguration reads. GetConnectionString("X") is ConnectionStrings:X; an
                    // indexer / GetValue / GetSection key "A:B" is the same key the environment
                    // spells A__B — so the row carries the env spelling and the merger's env
                    // table (filled from appsettings.json and the Compose override) resolves it.
                    java.util.regex.Matcher conn = CS_CONNECTION_STRING.matcher(code);
                    while (conn.find()) {
                        Map<String, String> fields = new LinkedHashMap<>();
                        fields.put("env", "ConnectionStrings__" + conn.group(1));
                        fields.put("dotnetkey", "ConnectionStrings:" + conn.group(1));
                        emitThin("config", fields, relative, i + 1, ledger, sectionCounts);
                    }
                    java.util.regex.Matcher key = CS_CONFIG_KEY.matcher(code);
                    while (key.find()) {
                        String k = key.group(1) != null ? key.group(1) : key.group(2);
                        if (k == null || k.isBlank()) continue;
                        Map<String, String> fields = new LinkedHashMap<>();
                        fields.put("env", k.replace(":", "__"));
                        fields.put("dotnetkey", k);
                        emitThin("config", fields, relative, i + 1, ledger, sectionCounts);
                    }
                }
                java.util.regex.Matcher url = KT_URL.matcher(code);
                while (url.find()) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("value", url.group(1));
                    emitThin("url", fields, relative, i + 1, ledger, sectionCounts);
                }
                if (!csharp) {
                    java.util.regex.Matcher listener = KT_LISTENER.matcher(code);
                    while (listener.find()) {
                        boolean kafka = listener.group(1).startsWith("Kafka");
                        Map<String, String> fields = new LinkedHashMap<>();
                        fields.put(kafka ? "topic" : "queue", listener.group(2));
                        emitThin(kafka ? "kafka-consume" : "rabbit-consume", fields, relative, i + 1, ledger, sectionCounts);
                    }
                }
            }
        }
        ledger.addFilesParsed(parsed);
        if (parsed > 0 && csharp) {
            ledger.addWarning(parsed + " C# file(s) read with the thin regex surface (IConfiguration reads, "
                    + "GetEnvironmentVariable, URL literals): no tree-sitter grammar is bundled for C#, so call "
                    + "sites whose host comes from a variable or a constant are not seen, and MassTransit / gRPC "
                    + "client wiring is only seen through the configuration key it reads.");
        } else if (parsed > 0) {
            ledger.addWarning(parsed + " Kotlin file(s) read with the thin regex surface (getenv, URL literals, "
                    + "listener annotations): no tree-sitter grammar is bundled for Kotlin, so call sites whose "
                    + "host comes from a variable or a constant are not seen.");
        }
    }

    /** {@code Environment.GetEnvironmentVariable("X")}, with an optional {@code ?? "default"}. */
    private static final java.util.regex.Pattern CS_GETENV = java.util.regex.Pattern.compile(
            "GetEnvironmentVariable\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*\\)(?:\\s*\\?\\?\\s*\"([^\"]*)\")?");
    /** {@code GetConnectionString("Database")}. */
    private static final java.util.regex.Pattern CS_CONNECTION_STRING = java.util.regex.Pattern.compile(
            "GetConnectionString\\(\\s*\"([A-Za-z0-9_:.-]+)\"\\s*\\)");
    /** {@code Configuration["A:B"]} / {@code config["A:B"]}, {@code GetValue<T>("A:B")}, {@code GetSection("A:B")}. */
    private static final java.util.regex.Pattern CS_CONFIG_KEY = java.util.regex.Pattern.compile(
            "(?i)(?:configuration|config|settings|conf)\\s*\\[\\s*\"([A-Za-z0-9_:.-]+)\"\\s*\\]"
                    + "|(?:GetValue<[^>]+>|GetSection)\\(\\s*\"([A-Za-z0-9_:.-]+)\"");

    private void emitThin(String section, Map<String, String> fields, String file, int line,
                          EdgeLedger ledger, Map<String, Integer> sectionCounts) {
        int count = sectionCounts.merge(section, 1, Integer::sum);
        if (count > SECTION_CAPS.getOrDefault(section, DEFAULT_SECTION_CAP)) return;
        ledger.add(section, fields, file, line, confidenceOf(section, fields));
    }

    /**
     * Turns capture names into an edge. A capture is named &lt;section&gt;.&lt;field&gt;;
     * all captures of one section within a match become one edge.
     */
    private void emit(TreeSitterQueryEngine.Match match, String file,
                      EdgeLedger ledger, Map<String, Integer> sectionCounts) {

        Map<String, Map<String, String>> bySection = new LinkedHashMap<>();
        for (Map.Entry<String, TreeSitterQueryEngine.Capture> entry : match.captures.entrySet()) {
            String name = entry.getKey();
            int dot = name.indexOf('.');
            if (dot <= 0 || dot == name.length() - 1) continue; // not a <section>.<field> capture
            String section = name.substring(0, dot);
            String field = name.substring(dot + 1);
            bySection.computeIfAbsent(section, k -> new LinkedHashMap<>())
                    .put(field, entry.getValue().text);
        }

        for (Map.Entry<String, Map<String, String>> entry : bySection.entrySet()) {
            String section = entry.getKey();
            int count = sectionCounts.merge(section, 1, Integer::sum);
            int cap = SECTION_CAPS.getOrDefault(section, DEFAULT_SECTION_CAP);
            if (count > cap) continue;

            // Rule 9's parsing halves: JPA markers are a Java-standard signal (java-generic pack),
            // and a JAX-RS @Path is an inbound endpoint like a Spring mapping.
            if ("jpa".equals(section)) RuleTrace.hit(9, "jpa " + file);
            if ("http-server".equals(section) && "Path".equals(entry.getValue().get("verb"))) RuleTrace.hit(9, "jaxrs " + file + ":" + match.line);
            // Convention ② in code: a bare host literal under a name is an address only when
            // the name is host-shaped (smtp_server = 'smtp.gmail.com'; a version string under
            // `release` is not). It becomes a url row, so convention ④ — reaching a client
            // call — still decides whether it is drawn.
            if ("hostlit".equals(section)) {
                Map<String, String> f = entry.getValue();
                if (AddressShape.keyKind(f.get("key")) == AddressShape.KeyKind.NONE) continue;
                Map<String, String> url = new LinkedHashMap<>();
                url.put("value", f.get("value"));
                url.put("bare", "true");
                ledger.add("url", url, file, match.line, confidenceOf("url", url));
                continue;
            }
            ledger.add(section, entry.getValue(), file, match.line,
                    confidenceOf(section, entry.getValue()));
        }
    }

    /**
     * C#'s configuration reads in the merger's vocabulary (the thin surface's contract,
     * rule 40): {@code Configuration["A:B"]} / {@code GetValue("A:B")} carry the key in
     * {@code dotnetkey} and its environment spelling {@code A__B} in {@code env}, so the env
     * table (the Compose override) and appsettings.json both resolve it;
     * {@code GetConnectionString("X")} is the key {@code ConnectionStrings:X}. A MassTransit /
     * client {@code Host("rabbitmq")} literal is a host under the key {@code host}.
     */
    private static void normaliseDotnetConfigReads(EdgeLedger ledger, Set<String> files) {
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            if (!"config".equals(e.section) || !files.contains(e.file)) continue;
            Map<String, String> f = e.fields;
            String conn = f.remove("connstr");
            if (conn != null && !conn.isBlank()) f.put("dotnetkey", "ConnectionStrings:" + conn.trim());
            String key = f.get("dotnetkey");
            if (key != null && !key.isBlank() && !f.containsKey("env")) f.put("env", key.trim().replace(":", "__"));
            if (f.containsKey("value") && !f.containsKey("key") && !f.containsKey("env")) f.put("key", "host");
        }
    }


    /**
     * A target that is a placeholder rather than a literal host cannot be trusted
     * as a concrete edge, and is marked so the report does not overstate it.
     */
    private String confidenceOf(String section, Map<String, String> fields) {
        for (String value : fields.values()) {
            if (value.contains("${") || value.contains("{{") || value.contains("%s")) {
                return "Medium (property indirection)";
            }
        }
        // For an OUTBOUND call, a path with no url means the host was concatenated
        // in from a field or a property, so the callee is not proven by this
        // expression alone. An INBOUND endpoint has no host by definition — a bare
        // path there is complete, not partial.
        if ("http-client".equals(section) && fields.containsKey("path") && !fields.containsKey("url")) {
            return "Medium (path only; host comes from a variable or property)";
        }
        return "High";
    }

    // ---------- language / query loading ----------

    private TSLanguage language(String name) {
        return languages.computeIfAbsent(name, key -> switch (key) {
            case "java" -> new TreeSitterJava();
            case "python" -> new TreeSitterPython();
            case "javascript" -> new TreeSitterJavascript();
            case "typescript" -> new TreeSitterTypescript();
            case "go" -> new TreeSitterGo();
            case "php" -> new TreeSitterPhp();
            case "kotlin" -> new TreeSitterKotlin();
            case "csharp" -> new TreeSitterCSharp();
            default -> throw new IllegalArgumentException("no tree-sitter grammar bundled for: " + key);
        });
    }

    private TreeSitterQueryEngine engine(String language, String pack) {
        // Keyed by grammar too: TypeScript and JavaScript share a pack but not a grammar,
        // and a query compiled for one cannot run on the other's trees.
        return queryCache.computeIfAbsent(language + "/" + pack, key -> {
            String source = readQueryPack(pack);
            return new TreeSitterQueryEngine(language(language), source, pack);
        });
    }

    private String readQueryPack(String pack) {
        String path = "treesitter/" + pack + ".scm";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStreamReader reader =
                         new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                return FileCopyUtils.copyToString(reader);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read query pack " + path, e);
        }
    }
}
