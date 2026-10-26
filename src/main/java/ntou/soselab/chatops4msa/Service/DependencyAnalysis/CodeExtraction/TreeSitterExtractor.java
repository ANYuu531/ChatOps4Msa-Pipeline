package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.treesitter.TSInputEncoding;
import org.treesitter.TSLanguage;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterPython;

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
            "import", 5000);

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
            System.out.println("[INFO] tree-sitter ready (grammars: java, python; query packs compiled)");
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "tree-sitter failed to initialise. The native library must match the platform: "
                            + "the bundled libs are glibc-linked (an -alpine/musl base image will not work). "
                            + "Cause: " + t, t);
        }
    }

    public boolean supports(String language) {
        return "java".equals(language) || "python".equals(language) || "kotlin".equals(language)
                || "csharp".equals(language);
    }

    /**
     * Runs every query pack of the stack over the stack's source files and adds
     * the resulting edges to the ledger.
     */
    public void extract(Path root, DetectedStack stack, EdgeLedger ledger) {
        if ("kotlin".equals(stack.language) || "csharp".equals(stack.language)) {
            extractThinSurface(root, stack, ledger);
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
        if (engines.isEmpty()) return;

        TSLanguage language = language(stack.language);
        TSParser parser = parsers.get();
        parser.setLanguage(language);

        Map<String, Integer> sectionCounts = new LinkedHashMap<>();
        List<Path> files = SourceScanner.filesWithExtensions(root, stack.extensions);
        int parsed = 0;
        Map<String, String> sources = new LinkedHashMap<>(); // relative path -> text, for the reference check

        for (Path file : files) {
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
            for (TreeSitterQueryEngine engine : engines) {
                for (TreeSitterQueryEngine.Match match : engine.run(tree, bytes)) {
                    emit(match, relative, ledger, sectionCounts);
                }
            }
        }

        ledger.addFilesParsed(parsed);
        resolveConstantHosts(ledger, sources);
        dropNonAddressUrls(ledger, sources);
        dropMetadataUrls(ledger);
        markUnreferencedUrlConstants(root, ledger, sources);
        dropPlainTwinsOfFormatUrls(ledger);
        if ("python".equals(stack.language)) {
            resolvePythonUrlRefs(ledger, sources);
            markDeadPythonCode(root, ledger, sources);
        }

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

            ledger.add(section, entry.getValue(), file, match.line,
                    confidenceOf(section, entry.getValue()));
        }
    }

    /**
     * Fills in the url of an http-client row whose host is a constant:
     * {@code "http://" + SERVICE_NAME + "/user"} with {@code final String SERVICE_NAME =
     * "cloud-simple-service"} declared in the same file becomes
     * {@code http://cloud-simple-service}. Only a String constant of the same file with a
     * literal value counts — a field set at runtime, a property, or a constant from
     * another class is left alone, and the row stays a hostref the merger cannot place.
     */
    public static void resolveConstantHosts(EdgeLedger ledger, Map<String, String> sources) {
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            if (!"http-client".equals(e.section)) continue;
            String ref = e.fields.get("hostref");
            String scheme = e.fields.get("scheme");
            if (ref == null || ref.isBlank() || scheme == null || e.fields.containsKey("url")) continue;
            String source = sources.get(e.file);
            if (source == null) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\bString\\s+" + java.util.regex.Pattern.quote(ref) + "\\s*=\\s*\"([^\"]*)\"").matcher(source);
            if (!m.find()) continue;
            String value = m.group(1).trim();
            if (value.isEmpty() || value.contains("${")) continue;
            e.fields.put("url", value.matches("(?i)^https?://.*") ? value : scheme + value);
        }
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

    private static final java.util.regex.Pattern PY_GETENV = java.util.regex.Pattern.compile(
            "os\\.(?:getenv|environ\\.get)\\(\\s*[\"']([A-Za-z_][A-Za-z0-9_]*)[\"']\\s*(?:,\\s*[\"']([^\"']*)[\"'])?"
                    + "|os\\.environ\\[\\s*[\"']([A-Za-z_][A-Za-z0-9_]*)[\"']\\s*]");
    private static final java.util.regex.Pattern PY_URL_LITERAL = java.util.regex.Pattern.compile("[\"'](https?://[^\"'{}]+)[\"']");
    private static final java.util.regex.Pattern PY_IDENT = java.util.regex.Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\b");

    /**
     * Fills in an http-client row whose URL is a variable ({@code requests.get(url=req_url)},
     * {@code requests.get(f"{PREFIX}/x")}) by following the variable through the same file's
     * assignments, up to three hops: {@code req_url = f"{PREFIX}/…"} → {@code PREFIX =
     * os.getenv("X_API_PREFIX")} yields {@code env=X_API_PREFIX} (plus the getenv default);
     * an assignment to a URL literal yields {@code url}. The row's line is the call site, so
     * the edge's provenance is where the call is made — the convention the reference edge
     * sets follow — and the getenv row it supersedes is marked so it draws nothing twice.
     * A variable nothing assigns, or assigned from anything else, stays unresolved and
     * draws no edge (2026-10-23).
     */
    static void resolvePythonUrlRefs(EdgeLedger ledger, Map<String, String> sources) {
        List<EdgeLedger.Edge> consumed = new ArrayList<>();
        for (EdgeLedger.Edge e : ledger.getEdges()) {
            if (!"http-client".equals(e.section) || e.fields.containsKey("url") || e.fields.containsKey("env")) continue;
            String ref = e.fields.get("urlref");
            String source = ref == null ? null : sources.get(e.file);
            if (source == null) continue;
            Map<String, String> resolved = followAssignment(source, ref.trim(), 0);
            if (resolved == null) continue;
            e.fields.putAll(resolved);
            if (resolved.containsKey("env")) consumed.add(e);
        }
        for (EdgeLedger.Edge call : consumed) {
            for (EdgeLedger.Edge c : ledger.getEdges()) {
                if ("config".equals(c.section) && c.file.equals(call.file)
                        && call.fields.get("env").equals(c.fields.get("env"))) {
                    c.fields.put("superseded", "true");
                }
            }
        }
    }

    /** {@code env}/{@code envdefault} or {@code url} for a variable, following plain assignments; null if none. */
    private static Map<String, String> followAssignment(String source, String name, int depth) {
        if (depth > 3 || name.isEmpty()) return null;
        java.util.regex.Matcher assign = java.util.regex.Pattern.compile(
                "(?m)^\\s*" + java.util.regex.Pattern.quote(name) + "\\s*(?::\\s*[A-Za-z_][\\w\\[\\], ]*)?=\\s*(.+)$").matcher(source);
        if (!assign.find()) return null;
        String rhs = assign.group(1).trim();
        Map<String, String> out = new LinkedHashMap<>();
        java.util.regex.Matcher env = PY_GETENV.matcher(rhs);
        if (env.find()) {
            String envName = env.group(1) != null ? env.group(1) : env.group(3);
            out.put("env", envName);
            if (env.group(2) != null && !env.group(2).isBlank()) out.put("envdefault", env.group(2).trim());
            return out;
        }
        java.util.regex.Matcher url = PY_URL_LITERAL.matcher(rhs);
        if (url.find()) {
            out.put("url", url.group(1));
            return out;
        }
        // f"{OTHER}/path", OTHER + "/path", "{}/path".format(OTHER): one hop to OTHER.
        // Inside string literals only an f-string interpolation {OTHER} is a reference.
        String stripped = java.util.regex.Pattern.compile("[\"'][^\"']*[\"']").matcher(rhs).replaceAll(m -> {
            java.util.regex.Matcher inner = java.util.regex.Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)").matcher(m.group());
            return inner.find() ? " " + inner.group(1) + " " : " ";
        });
        java.util.regex.Matcher ident = PY_IDENT.matcher(stripped);
        while (ident.find()) {
            String candidate = ident.group(1);
            if (candidate.equals(name) || candidate.equals("f") || candidate.equals("format") || candidate.equals("str")) continue;
            Map<String, String> hop = followAssignment(source, candidate, depth + 1);
            if (hop != null) return hop;
        }
        return null;
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
            default -> throw new IllegalArgumentException("no tree-sitter grammar bundled for: " + key);
        });
    }

    private TreeSitterQueryEngine engine(String language, String pack) {
        return queryCache.computeIfAbsent(pack, key -> {
            String source = readQueryPack(key);
            return new TreeSitterQueryEngine(language(language), source, key);
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
