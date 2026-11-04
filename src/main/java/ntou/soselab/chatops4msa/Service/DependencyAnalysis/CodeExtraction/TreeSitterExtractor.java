package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import jakarta.annotation.PostConstruct;
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
    static final List<String> OPTIONAL_GRAMMARS = List.of("javascript", "typescript", "go", "php", "kotlin", "csharp");

    /** Languages that keep the thin regex surface as a fallback when their grammar fails to load (rules 28, 40). */
    private static final Set<String> THIN_FALLBACK = Set.of("kotlin", "csharp");

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
        dropNonAddressUrls(ledger, sources);
        dropMetadataUrls(ledger);
        markUnreferencedUrlConstants(root, ledger, sources);
        dropPlainTwinsOfFormatUrls(ledger);
        if (ANYWHERE_URL_LANGUAGES.contains(stack.language)) keepUrlLiteralsAtUseSites(root, ledger, sources);
        if ("java".equals(stack.language) || "kotlin".equals(stack.language)) bindConfigurationProperties(ledger, sources);
        if ("python".equals(stack.language)) markDeadPythonCode(root, ledger, sources);
        if ("go".equals(stack.language)) registryNamesThroughParameters(ledger, sources);
        if ("javascript".equals(stack.language) || "typescript".equals(stack.language)) markBrowserScripts(root, ledger, sources.keySet());

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

    /** {@code <script … src="x.js">} in an HTML page. */
    private static final java.util.regex.Pattern SCRIPT_SRC = java.util.regex.Pattern.compile(
            "(?is)<script\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"'>]+)[\"']");

    /**
     * Rule 51: a script an HTML page of the repository loads ({@code <script src="x.js">},
     * resolved against the page's directory, or by its path suffix when the src is
     * root-absolute) runs in the visitor's browser. Its calls are the browser's — to a Google
     * API, a CDN — not the serving container's, and a browser cannot reach a cluster name
     * anyway; its http-client / url / config rows are marked not an address and draw nothing.
     * A bundled single-page app whose page loads only the bundle is not matched: its source
     * files keep their edges. AICS's frontend loads its sheet page this way (2026-10-27).
     */
    private static void markBrowserScripts(Path root, EdgeLedger ledger, Set<String> files) {
        Set<String> browser = new java.util.HashSet<>();
        for (Path html : SourceScanner.filesWithExtensions(root, List.of(".html", ".htm"))) {
            String text;
            try {
                text = Files.readString(html, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            String dir = html.getParent() == null ? "" : SourceScanner.relative(root, html.getParent());
            java.util.regex.Matcher m = SCRIPT_SRC.matcher(text);
            while (m.find()) {
                String src = m.group(1).trim();
                if (src.isEmpty() || src.contains("://") || src.startsWith("//") || src.contains("{")) continue;
                int q = src.indexOf('?');
                if (q >= 0) src = src.substring(0, q);
                if (src.startsWith("/")) {
                    String suffix = src.substring(1);
                    for (String f : files) if (f.equals(suffix) || f.endsWith("/" + suffix)) browser.add(f);
                } else {
                    String resolved = java.nio.file.Paths.get(dir.isEmpty() ? "." : dir).resolve(src).normalize().toString().replace('\\', '/');
                    if (files.contains(resolved)) browser.add(resolved);
                }
            }
        }
        if (browser.isEmpty()) return;
        int marked = 0;
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            if (!browser.contains(e.file)) continue;
            if (!("http-client".equals(e.section) || "url".equals(e.section) || "config".equals(e.section))) continue;
            if ("true".equals(e.fields.get("unreferenced"))) continue;
            e.fields.put("unreferenced", "true");
            e.fields.put("not-an-address", "browser-script");
            RuleTrace.hit(51, "browser " + e.file + ":" + e.line);
            marked++;
        }
        if (marked > 0) {
            ledger.addWarning(marked + " call/URL row(s) sit in scripts an HTML page loads (" + browser.size()
                    + " file(s)): they run in the browser, not in the serving container, and are drawn as no edge.");
        }
    }

    /** A Go function header: {@code func (s *Server) initX(name string) error {}. */
    private static final java.util.regex.Pattern GO_FUNC = java.util.regex.Pattern.compile(
            "(?m)^func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)");
    /** A registry dial: a {@code Dial} / {@code DialContext} on anything but grpc / net / http (those take addresses). */
    private static final java.util.regex.Pattern GO_REGISTRY_DIAL = java.util.regex.Pattern.compile(
            "\\b(?!grpc\\.|net\\.|http\\.)[A-Za-z_][A-Za-z0-9_]*\\.Dial(?:Context)?\\s*\\(");

    /**
     * Rule 49, through a parameter: a function whose parameter reaches a registry dial
     * ({@code initReviewClient(name string)} → {@code dialer.Dial(name, …)}, or via another
     * such function, {@code initUserClient(name)} → {@code s.getGprcConn(name)} →
     * {@code dialer.Dial(fmt.Sprintf("consul://%s/%s", addr, name))}) is a dial wrapper; a
     * string literal passed in that parameter's position anywhere in the file is a
     * registry name, listed like a literal handed to the dial itself. hotelReservation's
     * frontend wires all seven of its clients this way (2026-10-27). Same-file only, a
     * fixpoint over the file's functions; nothing is guessed about what the name resolves to.
     */
    static void registryNamesThroughParameters(EdgeLedger ledger, Map<String, String> sources) {
        for (Map.Entry<String, String> src : sources.entrySet()) {
            String text = src.getValue();
            if (!text.contains("Dial")) continue;
            // name -> parameters, and name -> body
            Map<String, List<String>> params = new LinkedHashMap<>();
            Map<String, String> bodies = new LinkedHashMap<>();
            java.util.regex.Matcher fm = GO_FUNC.matcher(text);
            List<int[]> spans = new ArrayList<>();
            List<String> names = new ArrayList<>();
            while (fm.find()) {
                spans.add(new int[]{fm.start(), fm.end()});
                names.add(fm.group(1));
                params.put(fm.group(1), goParamNames(fm.group(2)));
            }
            for (int i = 0; i < spans.size(); i++) {
                int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
                bodies.put(names.get(i), text.substring(spans.get(i)[1], end));
            }
            // wrapper name -> the parameter positions that reach a dial
            Map<String, Set<Integer>> wrappers = new LinkedHashMap<>();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String fn : names) {
                    List<String> ps = params.get(fn);
                    String body = bodies.get(fn);
                    for (int i = 0; i < ps.size(); i++) {
                        if (wrappers.getOrDefault(fn, Set.of()).contains(i)) continue;
                        if (reachesDial(body, ps.get(i), wrappers, params)) {
                            wrappers.computeIfAbsent(fn, k -> new java.util.TreeSet<>()).add(i);
                            changed = true;
                        }
                    }
                }
            }
            if (wrappers.isEmpty()) continue;
            String[] lines = text.split("\n", -1);
            for (Map.Entry<String, Set<Integer>> w : wrappers.entrySet()) {
                java.util.regex.Pattern call = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(w.getKey()) + "\\s*\\(");
                for (int ln = 0; ln < lines.length; ln++) {
                    java.util.regex.Matcher cm = call.matcher(lines[ln]);
                    while (cm.find()) {
                        if (lines[ln].trim().startsWith("func ")) continue; // the declaration itself
                        List<String> args = goCallArgs(lines[ln], cm.end());
                        for (int pos : w.getValue()) {
                            if (pos >= args.size()) continue;
                            String a = args.get(pos).trim();
                            if (!a.matches("\"[A-Za-z][A-Za-z0-9-]*\"")) continue;
                            Map<String, String> fields = new LinkedHashMap<>();
                            fields.put("name", a.substring(1, a.length() - 1));
                            fields.put("via", w.getKey());
                            ledger.add("registry-ref", fields, src.getKey(), ln + 1, "High");
                        }
                    }
                }
            }
        }
    }

    /** Whether {@code param} is an argument (at any depth) of a registry dial or a known wrapper's wrapped position in {@code body}. */
    private static boolean reachesDial(String body, String param, Map<String, Set<Integer>> wrappers, Map<String, List<String>> params) {
        if (param.isEmpty() || param.equals("_")) return false;
        java.util.regex.Pattern ref = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(param) + "\\b");
        java.util.regex.Matcher dial = GO_REGISTRY_DIAL.matcher(body);
        while (dial.find()) {
            String args = balanced(body, dial.end());
            if (ref.matcher(args).find()) return true;
        }
        for (Map.Entry<String, Set<Integer>> w : wrappers.entrySet()) {
            java.util.regex.Matcher call = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(w.getKey()) + "\\s*\\(").matcher(body);
            while (call.find()) {
                List<String> args = goCallArgs(body, call.end());
                for (int pos : w.getValue()) {
                    if (pos < args.size() && ref.matcher(args.get(pos)).find()) return true;
                }
            }
        }
        return false;
    }

    /** The text inside the parentheses opened just before {@code from}, up to the matching close. */
    private static String balanced(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return text.substring(from, i);
        }
        return text.substring(from);
    }

    /** Top-level comma-separated arguments of the call whose '(' ends just before {@code from}. */
    private static List<String> goCallArgs(String text, int from) {
        String inner = balanced(text, from);
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean str = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"' && (i == 0 || inner.charAt(i - 1) != '\\')) str = !str;
            if (!str && (c == '(' || c == '{' || c == '[')) depth++;
            if (!str && (c == ')' || c == '}' || c == ']')) depth--;
            if (!str && depth == 0 && c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** The parameter names of a Go parameter list: {@code a, b string, opts ...Option} → [a, b, opts]. */
    private static List<String> goParamNames(String list) {
        List<String> out = new ArrayList<>();
        for (String part : list.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String first = p.split("\\s+")[0];
            out.add(first.matches("[A-Za-z_][A-Za-z0-9_]*") ? first : "");
        }
        return out;
    }

    /**
     * Where a URL is a name rather than an address. A line that carries an http URL and
     * mentions a namespace, a schema location or a SOAP action is declaring an XML
     * identifier, in any of the spellings the libraries use: {@code @XmlSchema(namespace =
     * …)}, {@code setTargetNamespace(…)}, {@code xmlns:x=…}, {@code schemaLocation},
     * {@code new SoapActionCallback("http://…/GetCustomerRequest")}. All three appear in
     * LakesideMutual, and none of them is something any component calls.
     */
    private static final java.util.regex.Pattern XML_NAMESPACE_SITE =
            java.util.regex.Pattern.compile("(?i)(namespace|schemalocation|xmlns|soapaction)");

    /**
     * An XML namespace URI is an identifier, not an address. {@code @XmlSchema(namespace =
     * "http://example.com/interfaces/xsd")} need not resolve to anything and nothing calls
     * it; in JAXB-generated code it is often the only URL in the file. Found on 2026-09-25
     * while scoring against the MicroDepGraph dataset: two such lines had put two external
     * hosts on LakesideMutual's graph that no code ever calls. The row is marked the same
     * way an unreferenced constant is, so the merger draws no edge for it.
     */
    private void dropNonAddressUrls(EdgeLedger ledger, Map<String, String> sources) {
        int dropped = 0;
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            if (!"url".equals(e.section) || e.line <= 0) continue;
            String source = sources.get(e.file);
            if (source == null) continue;
            String[] lines = source.split("\n", -1);
            if (e.line > lines.length) continue;
            // The call that names it may be wrapped onto the line above: the URL literal of
            // new SoapActionCallback(\n "http://…") is on its own line.
            StringBuilder window = new StringBuilder();
            for (int l = Math.max(1, e.line - 2); l <= e.line; l++) window.append(lines[l - 1]).append('\n');
            if (!XML_NAMESPACE_SITE.matcher(window).find()) continue;
            e.fields.put("unreferenced", "true");
            e.fields.put("not-an-address", "xml-namespace");
            RuleTrace.hit(13, "drop " + e.fields.get("value"));
            dropped++;
        }
        if (dropped > 0) {
            ledger.addWarning(dropped + " URL literal(s) are XML namespaces or schema locations, "
                    + "not call targets; they are drawn as no edge.");
        }
    }

    /**
     * A URL that is an argument of an OpenAPI / Swagger metadata builder ({@code new
     * License().url(…)}, {@code new ExternalDocumentation().url(…)}, {@code @Info(termsOfService
     * = …)}) documents the API; nothing calls it. The query pack emits such literals as
     * {@code url-metadata}; the {@code url} row for the same literal (same file, line and
     * value) is marked not an address, so the merger draws no edge and keeps no residue.
     * The test is the builder, never the domain (2026-10-26, mall-swarm).
     */
    static void dropMetadataUrls(EdgeLedger ledger) {
        List<EdgeLedger.Edge> metadata = new ArrayList<>();
        for (EdgeLedger.Edge e : ledger.getEdges()) if ("url-metadata".equals(e.section)) metadata.add(e);
        if (metadata.isEmpty()) return;
        int dropped = 0;
        for (EdgeLedger.Edge m : metadata) {
            for (EdgeLedger.Edge e : ledger.getEdges()) {
                if ("url".equals(e.section) && e.file.equals(m.file) && e.line == m.line
                        && java.util.Objects.equals(e.fields.get("value"), m.fields.get("value"))
                        && !"true".equals(e.fields.get("unreferenced"))) {
                    e.fields.put("unreferenced", "true");
                    e.fields.put("not-an-address", "openapi-metadata");
                    RuleTrace.hit(29, "drop " + e.fields.get("value"));
                    dropped++;
                }
            }
        }
        if (dropped > 0) {
            ledger.addWarning(dropped + " URL literal(s) are OpenAPI/Swagger metadata (license, contact, "
                    + "external docs, terms of service), not call targets; they are drawn as no edge.");
        }
    }

    /**
     * A URL literal that lives in a static constant is a dependency only if the
     * constant is used. For every {@code url-constant} row, the constant's name is
     * searched in the source of its own module (the nearest directory up from the file
     * that carries a build marker) outside the declaring line; when nothing refers to
     * it, the {@code url} row emitted for the same literal is marked
     * {@code unreferenced}, and the merger leaves it out. Declared, not used.
     */
    private void markUnreferencedUrlConstants(Path root, EdgeLedger ledger, Map<String, String> sources) {
        List<EdgeLedger.Edge> constants = new ArrayList<>();
        for (EdgeLedger.Edge e : ledger.getEdges()) if ("url-constant".equals(e.section)) constants.add(e);
        if (constants.isEmpty()) return;

        int unreferenced = 0;
        for (EdgeLedger.Edge constant : constants) {
            String name = constant.fields.get("name");
            if (name == null || name.isBlank()) continue;
            String module = moduleOf(root, constant.file);
            java.util.regex.Pattern ref = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b");
            boolean referenced = false;
            for (Map.Entry<String, String> src : sources.entrySet()) {
                if (!src.getKey().startsWith(module)) continue;
                String[] lines = src.getValue().split("\n", -1);
                for (int i = 0; i < lines.length && !referenced; i++) {
                    if (src.getKey().equals(constant.file) && i + 1 == constant.line) continue; // the declaration itself
                    if (ref.matcher(lines[i]).find()) referenced = true;
                }
                if (referenced) break;
            }
            if (referenced) continue;
            unreferenced++;
            RuleTrace.hit(9, "unreferenced " + name + "=" + constant.fields.get("value"));
            for (EdgeLedger.Edge e : ledger.getEdges()) {
                if ("url".equals(e.section) && e.file.equals(constant.file) && e.line == constant.line
                        && constant.fields.get("value").equals(e.fields.get("value"))) {
                    e.fields.put("unreferenced", "true");
                }
            }
        }
        if (unreferenced > 0) {
            ledger.addWarning(unreferenced + " URL constant(s) are declared but never referenced in their module; "
                    + "their url rows are marked unreferenced and drawn as no edge.");
        }
    }

    /** The packs that capture an absolute URL literal anywhere; the others capture it only at a use site. */
    private static final Set<String> ANYWHERE_URL_LANGUAGES = Set.of("java", "csharp", "kotlin", "python");

    /** A row that is a component using an address: a client call, a client option, a producer or consumer. */
    private static final Set<String> USE_SITE_SECTIONS = Set.of("http-client", "feign", "grpc", "persistence",
            "kafka-produce", "kafka-consume", "rabbit-produce", "rabbit-consume", "registry-ref", "name-ref");

    /**
     * The client calls of each ecosystem's documented HTTP / gRPC client APIs, for a call whose
     * argument shape no query pattern captures ({@code getForObject(AppConstant.X.URL + "/" + id)}).
     */
    private static final java.util.regex.Pattern CLIENT_CALL = java.util.regex.Pattern.compile(
            "(?:\\.(?:getForObject|getForEntity|postForObject|postForEntity|postForLocation|exchange|rootUri|baseUrl|uri|url|fromUriString|fromHttpUrl"
            + "|newCall|openConnection|forTarget|forAddress|GetAsync|PostAsync|PutAsync|DeleteAsync|PatchAsync|SendAsync"
            + "|GetFromJsonAsync|PostAsJsonAsync|PutAsJsonAsync|GetStringAsync|GetStreamAsync|GetByteArrayAsync|ForAddress"
            + "|insecure_channel|secure_channel|urlopen|Request)\\s*\\("
            + "|\\bBaseAddress\\s*=|\\b(?:requests|httpx|session|aiohttp|urllib3|feedparser)\\.\\w+\\s*\\(|\\burlopen\\s*\\(|\\bdriver\\.get\\s*\\(|\\.WriteTo\\.\\w+\\s*\\(|\\bHttp(?:Get|Post|Put|Patch|Delete|Head)\\s*\\("
            + "|\\bsmtplib\\.SMTP(?:_SSL)?\\s*\\()");

    /** The name a value is assigned to on its line: {@code NAME = …}, {@code String NAME = …}, {@code val NAME: T = …}. */
    private static final java.util.regex.Pattern ASSIGNED_NAME = java.util.regex.Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*[A-Za-z_][\\w.<>?\\[\\], ]*)?=(?![=>])");

    /**
     * Convention ④ for every grammar (rule 47 widened, 2026-11-03): a URL literal is an
     * address only at a use site. The JavaScript, Go and PHP packs never captured a bare
     * literal; the Java, C#, Kotlin and Python packs captured every {@code http(s)://}
     * string and then excused, case by case, the ones that are not called — an unused
     * constant (rule 9), an XML namespace (13), OpenAPI metadata (29). The edge audit of
     * the 100-system corpus found what the cases miss: an RFC link in an error response,
     * a ProblemDetails type URI, a test user's claim, a Swagger license link — all C# or
     * Java, all drawn as dependencies.
     *
     * <p>A use site is a line with a use-site row (a client call, a client option, a producer
     * or consumer) or a call of a documented client API ({@link #CLIENT_CALL}). A literal is
     * kept when a use site is on its line or the two lines around it (a call wrapped over
     * lines), or when the name it is assigned to ({@code ORDER_API = "http://…"}, {@code
     * builder = fromHttpUrl("http://…")}) — or a name assigned from that one, up to three
     * steps, as rule 47 follows bindings — is mentioned at a use site of the same module.
     * Every other {@code url} row is marked as no edge, with the reason, so the ledger still
     * shows it.
     */
    private void keepUrlLiteralsAtUseSites(Path root, EdgeLedger ledger, Map<String, String> sources) {
        Map<String, Set<Integer>> useLines = new LinkedHashMap<>();
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            if (USE_SITE_SECTIONS.contains(e.section) && e.line > 0) useLines.computeIfAbsent(e.file, k -> new java.util.HashSet<>()).add(e.line);
        }
        for (Map.Entry<String, String> src : sources.entrySet()) {
            String[] lines = src.getValue().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (CLIENT_CALL.matcher(lines[i]).find()) useLines.computeIfAbsent(src.getKey(), k -> new java.util.HashSet<>()).add(i + 1);
            }
        }
        Map<String, String> moduleCache = new LinkedHashMap<>();
        int dropped = 0;
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            if (!"url".equals(e.section) || e.line <= 0 || "true".equals(e.fields.get("unreferenced"))) continue;
            if (e.fields.containsKey("env")) continue; // String.format(…, getenv(…)): rule 18 reads it as a use
            String source = sources.get(e.file);
            if (source == null) continue;
            if (nearUseSite(useLines.get(e.file), e.line)) continue;
            String[] lines = source.split("\n", -1);
            String holder = null;
            if (e.line <= lines.length) holder = assignedName(lines, e.line - 1, e.fields.getOrDefault("value", ""));
            if (holder != null && mentionedAtUseSite(holder, moduleCache.computeIfAbsent(e.file, f -> moduleOf(root, f)),
                    sources, useLines, moduleCache, root)) continue;
            e.fields.put("unreferenced", "true");
            e.fields.put("not-an-address", "not-at-use-site");
            RuleTrace.hit(47, "not-use-site " + e.file + ":" + e.line + " " + e.fields.get("value"));
            dropped++;
        }
        if (dropped > 0) {
            ledger.addWarning(dropped + " URL literal(s) are not at a use site (no client call, client option, producer "
                    + "or consumer uses them); they are drawn as no edge.");
        }
    }

    /** {@code @ConfigurationProperties(prefix = "yas.services")} (or {@code ("p")} / {@code value = "p"}), then the record or class it annotates. */
    private static final java.util.regex.Pattern CONFIGURATION_PROPERTIES = java.util.regex.Pattern.compile(
            "@ConfigurationProperties\\s*\\(\\s*(?:(?:prefix|value)\\s*=\\s*)?\"([\\w.-]*)\"\\s*\\)[\\s\\S]{0,300}?\\b(record|class)\\s+(\\w+)\\b");

    /** Every line of the sources that is a use site: a use-site row's line, or a line the client-call vocabulary matches. */
    private static Map<String, Set<Integer>> useLines(EdgeLedger ledger, Map<String, String> sources) {
        Map<String, Set<Integer>> useLines = new LinkedHashMap<>();
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            if (USE_SITE_SECTIONS.contains(e.section) && e.line > 0) useLines.computeIfAbsent(e.file, k -> new java.util.HashSet<>()).add(e.line);
        }
        for (Map.Entry<String, String> src : sources.entrySet()) {
            String[] lines = src.getValue().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (CLIENT_CALL.matcher(lines[i]).find()) useLines.computeIfAbsent(src.getKey(), k -> new java.util.HashSet<>()).add(i + 1);
            }
        }
        return useLines;
    }

    /**
     * Spring's three-step binding, followed to the call (convention ④, 2026-11-04): an
     * environment variable {@code YAS_SERVICES_PRODUCT} is the property
     * {@code yas.services.product} by relaxed binding; {@code @ConfigurationProperties(prefix =
     * "yas.services") record ServiceUrlConfig(String product)} binds it to a field; and the
     * call site reads it through the accessor, {@code fromUriString(serviceUrlConfig.product())}.
     * The tool knew the first step (the merger's env table) and nothing of the other two, so
     * yas's cart → product was only ever the manifest's inferred edge, and storefront — which
     * binds the whole {@code yas} map and calls nothing — looked the same as cart. Here every
     * accessor of a bound field that sits at a use site becomes a config row carrying the
     * property as a placeholder, which the merger resolves through the same table a
     * {@code @Value("${…}")} uses. A {@code Map}-valued field read as {@code services().get("cart")}
     * is the key {@code prefix.services.cart}. The binding class may live in a shared library
     * module, so bindings are collected across the repo; the use sites are attributed to the
     * file they are in.
     */
    private void bindConfigurationProperties(EdgeLedger ledger, Map<String, String> sources) {
        // type -> prefix, type -> fields (name -> is a Map)
        Map<String, String> prefixes = new LinkedHashMap<>();
        Map<String, Map<String, Boolean>> fields = new LinkedHashMap<>();
        for (String text : sources.values()) {
            java.util.regex.Matcher m = CONFIGURATION_PROPERTIES.matcher(text);
            while (m.find()) {
                String prefix = m.group(1);
                String type = m.group(3);
                Map<String, Boolean> bound = new LinkedHashMap<>();
                int from = m.end();
                if ("record".equals(m.group(2))) {
                    int open = text.indexOf('(', from), close = open < 0 ? -1 : text.indexOf(')', open);
                    if (open < 0 || close < 0) continue;
                    String params = text.substring(open + 1, close);
                    while (params.matches(".*<[^<>]*>.*")) params = params.replaceAll("<[^<>]*>", "");
                    for (String p : params.split(",")) {
                        String[] words = p.trim().split("\\s+");
                        if (words.length >= 2) bound.put(words[words.length - 1], words[words.length - 2].endsWith("Map"));
                    }
                } else {
                    int open = text.indexOf('{', from);
                    if (open < 0) continue;
                    String body = text.substring(open + 1, Math.min(text.length(), open + 4000));
                    java.util.regex.Matcher f = java.util.regex.Pattern.compile("(?m)^\\s*(?:private|protected|public)?\\s*(?:final\\s+)?([\\w.]+(?:<[^;=]*>)?)\\s+(\\w+)\\s*[;=]").matcher(body);
                    while (f.find()) bound.put(f.group(2), f.group(1).contains("Map"));
                }
                if (bound.isEmpty()) continue;
                // yas has a ServiceUrlConfig per module, all under yas.services with different
                // fields: the same simple name under the same prefix is one binding, fields unioned.
                if (prefixes.containsKey(type) && !prefixes.get(type).equals(prefix)) continue;
                prefixes.put(type, prefix);
                fields.computeIfAbsent(type, k -> new LinkedHashMap<>()).putAll(bound);
            }
        }
        if (prefixes.isEmpty()) return;
        Map<String, Set<Integer>> useLines = useLines(ledger, sources);
        int emitted = 0;
        for (Map.Entry<String, String> src : sources.entrySet()) {
            String text = src.getValue();
            boolean kotlin = src.getKey().endsWith(".kt");
            // the variables of each bound type in this file: `ServiceUrlConfig serviceUrlConfig` / `val x: ServiceUrlConfig`
            Map<String, String> varsToType = new LinkedHashMap<>();
            for (String type : prefixes.keySet()) {
                if (!text.contains(type)) continue;
                java.util.regex.Matcher v = java.util.regex.Pattern.compile("\\b" + type + "\\s+(\\w+)\\b|\\b(\\w+)\\s*:\\s*" + type + "\\b").matcher(text);
                while (v.find()) varsToType.put(v.group(1) != null ? v.group(1) : v.group(2), type);
            }
            if (varsToType.isEmpty()) continue;
            String[] lines = text.split("\n", -1);
            for (Map.Entry<String, String> var : varsToType.entrySet()) {
                String type = var.getValue();
                for (Map.Entry<String, Boolean> field : fields.get(type).entrySet()) {
                    String f = field.getKey();
                    String getter = "get" + Character.toUpperCase(f.charAt(0)) + f.substring(1);
                    java.util.regex.Pattern access = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(var.getKey())
                            + "\\s*\\.\\s*(?:" + f + "|" + getter + ")\\s*" + (kotlin ? "(?:\\(\\s*\\))?" : "\\(\\s*\\)")
                            + "(?:\\s*\\.\\s*get\\(\\s*\"([\\w.-]+)\"\\s*\\))?");
                    for (int i = 0; i < lines.length; i++) {
                        java.util.regex.Matcher a = access.matcher(lines[i]);
                        if (!a.find() || !nearUseSite(useLines.get(src.getKey()), i + 1)) continue;
                        String key = prefixes.get(type) + "." + f + (a.group(1) != null ? "." + a.group(1) : "");
                        if (key.startsWith(".")) key = key.substring(1);
                        Map<String, String> row = new LinkedHashMap<>();
                        row.put("property", "${" + key + "}");
                        row.put("binding", "configuration-properties");
                        row.put("usesite", "true");
                        ledger.add("config", row, src.getKey(), i + 1, "Medium (property indirection)");
                        RuleTrace.hit(47, "configuration-properties " + src.getKey() + ":" + (i + 1) + " " + key);
                        emitted++;
                    }
                }
            }
        }
        if (emitted > 0) ledger.addWarning(emitted + " @ConfigurationProperties accessor(s) at use sites were followed to their property keys.");
    }

    private static boolean nearUseSite(Set<Integer> lines, int line) {
        if (lines == null) return false;
        for (int l = line - 2; l <= line + 2; l++) if (lines.contains(l)) return true;
        return false;
    }

    /**
     * The name the literal is kept in, or null: the last assignment before it on its line, or
     * the parameter or field an annotation injects it into ({@code @Value("http://${X}/b")
     * String balancesUri}, on the same or the next line).
     */
    private static String assignedName(String[] lines, int index, String value) {
        String line = lines[index];
        int at = value.isEmpty() ? -1 : line.indexOf(value.length() > 12 ? value.substring(0, 12) : value);
        if (at < 0) return null;
        java.util.regex.Matcher m = ASSIGNED_NAME.matcher(line.substring(0, at));
        String name = null;
        while (m.find()) name = m.group(1);
        if (name != null || at == 0 || line.charAt(at - 1) != '"' || !line.substring(0, at).matches(".*@\\w+\\s*\\(\\s*(?:\\w+\\s*=\\s*)?\"$")) return name;
        String rest = line.substring(at) + (index + 1 < lines.length ? " " + lines[index + 1] : "");
        java.util.regex.Matcher injected = INJECTED_NAME.matcher(rest);
        return injected.find() ? injected.group(1) : null;
    }

    /** After an annotation's string argument: {@code ") [final] Type name"}. */
    private static final java.util.regex.Pattern INJECTED_NAME = java.util.regex.Pattern.compile(
            "^[^\"]*\"\\s*\\)\\s*(?:(?:private|protected|public|final|static|val|var|lateinit)\\s+)*(?:[\\w.<>\\[\\]?]+\\s+)?([A-Za-z_]\\w*)\\s*[:;,)=]?");

    /**
     * Whether {@code name}, or a name assigned from it in the same file (up to three steps),
     * occurs on or within two lines of a use site of {@code module}.
     */
    private static boolean mentionedAtUseSite(String name, String module, Map<String, String> sources,
                                              Map<String, Set<Integer>> useLines, Map<String, String> moduleCache, Path root) {
        for (Map.Entry<String, Set<Integer>> use : useLines.entrySet()) {
            if (!use.getKey().startsWith(module)) continue;
            if (!module.equals(moduleCache.computeIfAbsent(use.getKey(), f -> moduleOf(root, f)))) continue;
            String source = sources.get(use.getKey());
            if (source == null) continue;
            String[] lines = source.split("\n", -1);
            Set<String> names = new java.util.HashSet<>(Set.of(name));
            // A bean property is read through its accessor (JavaBeans: baseUrl -> getBaseUrl()).
            if (Character.isLowerCase(name.charAt(0))) names.add("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
            for (int step = 0; step < 3; step++) {
                java.util.regex.Pattern ref = namesPattern(names);
                for (String l : lines) {
                    if (!ref.matcher(l).find()) continue;
                    java.util.regex.Matcher m = ASSIGNED_NAME.matcher(l);
                    if (m.find() && !names.contains(m.group(1)) && ref.matcher(l.substring(m.end())).find()) names.add(m.group(1));
                }
            }
            java.util.regex.Pattern ref = namesPattern(names);
            for (int line : use.getValue()) {
                for (int l = Math.max(1, line - 2); l <= Math.min(lines.length, line + 2); l++) {
                    if (ref.matcher(lines[l - 1]).find()) return true;
                }
            }
        }
        return false;
    }

    private static java.util.regex.Pattern namesPattern(Set<String> names) {
        return java.util.regex.Pattern.compile("\\b(" + String.join("|", names.stream().map(java.util.regex.Pattern::quote).toList()) + ")\\b");
    }

    /**
     * A {@code String.format("http://%s/…", getenv(…))} literal is emitted twice: once by
     * the bare URL-literal rule and once, with its env name and default, by the format rule.
     * The plain twin would only ever be residue, so it is marked as drawn as no edge.
     */
    private static void dropPlainTwinsOfFormatUrls(EdgeLedger ledger) {
        for (EdgeLedger.Edge withEnv : ledger.getEdges()) {
            if (!"url".equals(withEnv.section) || !withEnv.fields.containsKey("env")) continue;
            for (EdgeLedger.Edge e : ledger.getEdges()) {
                if (e != withEnv && "url".equals(e.section) && !e.fields.containsKey("env")
                        && e.file.equals(withEnv.file) && e.line == withEnv.line
                        && java.util.Objects.equals(e.fields.get("value"), withEnv.fields.get("value"))) {
                    e.fields.put("unreferenced", "true");
                    e.fields.put("twin-of", "format-url");
                }
            }
        }
    }

    private static final java.util.regex.Pattern PY_DEF = java.util.regex.Pattern.compile("^(\\s*)(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final java.util.regex.Pattern PY_ENV_ASSIGN = java.util.regex.Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?::[^=]*)?=\\s*os\\.(?:getenv|environ)");

    /** A Python function definition: where it is, and whether anything decorates it. */
    private record PyDef(String file, String name, int start, int end, boolean decorated) {
        boolean contains(String f, int line) {
            return file.equals(f) && line >= start && line <= end;
        }
    }

    /**
     * Declared, not used — for Python. A function nothing in its module ever references
     * (transitively: a function called only from such functions is as dead), and a
     * module-level {@code NAME = os.getenv(…)} nothing live references, are declarations;
     * the getenv and request rows inside them are marked {@code unreferenced} and draw no
     * edge. The module is the nearest directory with a build marker, as for Java constants.
     * A decorated function (a route handler, a scheduled job, an event hook) is live by
     * definition — the framework calls it — and so is anything dunder/private-named, since
     * those are called by protocol. AICS's user-service keeps a complete communicator for
     * config-service whose only entry point nobody calls (2026-10-23).
     */
    private void markDeadPythonCode(Path root, EdgeLedger ledger, Map<String, String> sources) {
        // Group files by module.
        Map<String, List<String>> filesByModule = new LinkedHashMap<>();
        for (String file : sources.keySet()) {
            if (!file.endsWith(".py")) continue;
            filesByModule.computeIfAbsent(moduleOf(root, file), k -> new ArrayList<>()).add(file);
        }
        int marked = 0;
        for (Map.Entry<String, List<String>> module : filesByModule.entrySet()) {
            List<PyDef> defs = new ArrayList<>();
            Map<String, String[]> lines = new LinkedHashMap<>();
            for (String file : module.getValue()) {
                String[] ls = sources.get(file).split("\n", -1);
                lines.put(file, ls);
                for (int i = 0; i < ls.length; i++) {
                    java.util.regex.Matcher m = PY_DEF.matcher(ls[i]);
                    if (!m.find()) continue;
                    int indent = m.group(1).length();
                    int end = ls.length;
                    for (int j = i + 1; j < ls.length; j++) {
                        String t = ls[j];
                        if (t.isBlank()) continue;
                        int ind = t.length() - t.stripLeading().length();
                        if (ind <= indent) {
                            end = j;
                            break;
                        }
                    }
                    boolean decorated = false;
                    for (int j = i - 1; j >= 0; j--) {
                        if (ls[j].isBlank()) continue;
                        decorated = ls[j].trim().startsWith("@");
                        break;
                    }
                    defs.add(new PyDef(file, m.group(2), i + 1, end, decorated));
                }
            }
            if (defs.isEmpty()) continue;

            // Fixpoint: a def is live when referenced from a line outside every dead def.
            Set<PyDef> dead = new java.util.HashSet<>();
            for (PyDef d : defs) if (!d.decorated && !d.name.startsWith("_")) dead.add(d);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (PyDef d : new ArrayList<>(dead)) {
                    if (referencedFromLiveCode(d.name, d, lines, dead)) {
                        dead.remove(d);
                        changed = true;
                    }
                }
            }
            if (dead.isEmpty() && module.getValue().stream().noneMatch(f -> hasEnvAssignment(sources.get(f)))) continue;

            for (EdgeLedger.Edge e : ledger.getEdges()) {
                if (!("config".equals(e.section) || "http-client".equals(e.section))) continue;
                if (!lines.containsKey(e.file) || e.line <= 0) continue;
                boolean unreferenced = false;
                for (PyDef d : dead) {
                    if (d.contains(e.file, e.line)) {
                        unreferenced = true;
                        break;
                    }
                }
                if (!unreferenced && "config".equals(e.section) && e.fields.containsKey("env")) {
                    String[] ls = lines.get(e.file);
                    if (e.line <= ls.length) {
                        java.util.regex.Matcher m = PY_ENV_ASSIGN.matcher(ls[e.line - 1]);
                        if (m.find()) {
                            String var = m.group(1);
                            boolean live = referencedFromLiveCode(var, new PyDef(e.file, var, e.line, e.line, false), lines, dead);
                            if (!live) unreferenced = true;
                        }
                    }
                }
                if (unreferenced && !"true".equals(e.fields.get("unreferenced"))) {
                    e.fields.put("unreferenced", "true");
                    e.fields.put("dead-code", "python");
                    RuleTrace.hit(22, "dead " + e.file + ":" + e.line);
                    marked++;
                }
            }
        }
        if (marked > 0) {
            ledger.addWarning(marked + " Python getenv/request row(s) sit in code nothing calls, or read a variable "
                    + "nothing uses; they are declared, not used, and drawn as no edge.");
        }
    }

    private static boolean hasEnvAssignment(String source) {
        for (String l : source.split("\n")) if (PY_ENV_ASSIGN.matcher(l).find()) return true;
        return false;
    }

    /** Whether {@code name} occurs, outside its own declaration and outside every dead def, anywhere in the module. */
    private static boolean referencedFromLiveCode(String name, PyDef self, Map<String, String[]> lines, Set<PyDef> dead) {
        java.util.regex.Pattern ref = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b");
        for (Map.Entry<String, String[]> f : lines.entrySet()) {
            String[] ls = f.getValue();
            for (int i = 0; i < ls.length; i++) {
                int line = i + 1;
                if (self.contains(f.getKey(), line)) continue; // its own body, or the declaration
                String text = ls[i];
                int hash = text.indexOf('#');
                if (hash >= 0) text = text.substring(0, hash);
                if (!ref.matcher(text).find()) continue;
                boolean inDead = false;
                for (PyDef d : dead) {
                    if (d != self && d.contains(f.getKey(), line)) {
                        inDead = true;
                        break;
                    }
                }
                if (!inDead) return true;
            }
        }
        return false;
    }

    /**
     * The module a file belongs to: the path prefix up to the nearest ancestor that
     * holds a build marker (pom.xml, package.json, …), or "" for the whole repository.
     */
    private static String moduleOf(Path root, String relativeFile) {
        Path dir = root.resolve(relativeFile).getParent();
        while (dir != null && !dir.equals(root)) {
            for (String marker : ServiceRootScanner.markerFiles()) {
                try (var listing = Files.list(dir)) {
                    if (listing.anyMatch(p -> p.getFileName().toString().equalsIgnoreCase(marker))) {
                        return SourceScanner.relative(root, dir) + "/";
                    }
                } catch (Exception ignored) {
                    // unreadable directory: keep climbing
                }
            }
            dir = dir.getParent();
        }
        return "";
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
