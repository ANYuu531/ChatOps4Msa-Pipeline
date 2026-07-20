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

        for (Path file : files) {
            String source;
            try {
                source = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue; // binary or non-UTF-8: not source we can analyse
            }
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
