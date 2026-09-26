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
            "config", 80);

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
        return "java".equals(language) || "python".equals(language);
    }

    /**
     * Runs every query pack of the stack over the stack's source files and adds
     * the resulting edges to the ledger.
     */
    public void extract(Path root, DetectedStack stack, EdgeLedger ledger) {
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
        dropNonAddressUrls(ledger, sources);
        markUnreferencedUrlConstants(root, ledger, sources);

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
