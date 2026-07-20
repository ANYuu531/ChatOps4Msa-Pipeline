package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate.Bindings;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate.Predicate;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate.PredicateEngine;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSQueryPredicateStep;
import org.treesitter.TSQueryPredicateStepType;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a compiled tree-sitter query and returns the captures of each match.
 *
 * IMPORTANT — why predicate handling is here:
 * tree-sitter's C query engine does NOT evaluate the #eq? / #match? / #any-of?
 * predicates. It only records them, and it is the host binding's job to filter
 * matches with them. io.github.bonede does not do that, so without evaluation
 * every pattern would match regardless of its guards and the ledger would fill
 * with false edges.
 *
 * This class no longer evaluates predicates itself. It decodes the flat predicate
 * steps the binding exposes into {@link Predicate} values and hands them to the
 * general {@link PredicateEngine}, which owns the operator semantics (including the
 * {@code #when?} expression language). That keeps this class about tree-sitter and
 * the engine about predicates, and it lets the predicate logic be unit-tested
 * without a native grammar.
 *
 * Thread safety: a TSQuery is immutable once compiled and may be shared, but a
 * TSQueryCursor is not thread-safe, so one is created per run() call. The shared
 * PredicateEngine is immutable after construction.
 */
public class TreeSitterQueryEngine {

    /** Captures whose name starts with '_' are helpers used only by predicates; they are not emitted. */
    private static final String HELPER_PREFIX = "_";

    /** Stateless and immutable after construction, so one instance is shared by every engine. */
    private static final PredicateEngine PREDICATES = new PredicateEngine();

    private final TSQuery query;
    private final String name;
    private final List<String> predicateErrors = new ArrayList<>();
    /** Predicates decoded once per pattern index (a pattern's predicates do not change between matches). */
    private final Map<Integer, List<Predicate>> predicateCache = new ConcurrentHashMap<>();

    /** One captured node: its text plus its position. */
    public static class Capture {
        public final String text;
        public final int line;

        Capture(String text, int line) {
            this.text = text;
            this.line = line;
        }
    }

    /** One match: capture name -> capture. Helper captures are excluded. */
    public static class Match {
        public final Map<String, Capture> captures;
        public final int line;

        Match(Map<String, Capture> captures, int line) {
            this.captures = captures;
            this.line = line;
        }

        public String text(String captureName) {
            Capture c = captures.get(captureName);
            return c == null ? null : c.text;
        }
    }

    public TreeSitterQueryEngine(TSLanguage language, String querySource, String name) {
        this.name = name;
        this.query = new TSQuery(language, querySource);
    }

    public List<String> getPredicateErrors() {
        return predicateErrors;
    }

    public String getName() {
        return name;
    }

    /**
     * @param tree   parsed tree
     * @param source the exact UTF-8 bytes that were handed to the parser (node
     *               offsets are byte offsets into this array)
     */
    public List<Match> run(TSTree tree, byte[] source) {
        List<Match> results = new ArrayList<>();
        TSQueryCursor cursor = new TSQueryCursor();
        // Bound the work a pathological/generated file can cause. Files are also
        // size-capped before parsing (see TreeSitterExtractor).
        cursor.setMatchLimit(10_000);
        cursor.exec(query, tree.getRootNode());

        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            // Group captures by name — a name can be captured more than once per match.
            Map<String, List<Capture>> byName = new LinkedHashMap<>();
            for (TSQueryCapture capture : match.getCaptures()) {
                String captureName = query.getCaptureNameForId(capture.getIndex());
                TSNode node = capture.getNode();
                byName.computeIfAbsent(captureName, k -> new ArrayList<>())
                        .add(new Capture(textOf(node, source), node.getStartPoint().getRow() + 1));
            }

            if (!predicatesHold(match.getPatternIndex(), byName)) continue;

            Map<String, Capture> emitted = new LinkedHashMap<>();
            int line = -1;
            for (Map.Entry<String, List<Capture>> e : byName.entrySet()) {
                if (e.getKey().startsWith(HELPER_PREFIX)) continue;
                Capture first = e.getValue().get(0);
                emitted.put(e.getKey(), first);
                if (line < 0 || first.line < line) line = first.line;
            }
            if (emitted.isEmpty()) continue;
            results.add(new Match(emitted, line));
        }
        return results;
    }

    // ---------- predicate bridge ----------

    /** Evaluates a pattern's predicates against one match through the shared engine. */
    private boolean predicatesHold(int patternIndex, Map<String, List<Capture>> byName) {
        List<Predicate> predicates = predicateCache.computeIfAbsent(patternIndex, this::decodePredicates);
        if (predicates.isEmpty()) return true;

        Bindings bindings = captureName -> {
            List<Capture> captures = byName.get(captureName);
            if (captures == null || captures.isEmpty()) return List.of();
            List<String> texts = new ArrayList<>(captures.size());
            for (Capture c : captures) texts.add(c.text);
            return texts;
        };

        // The engine reports operator-level problems (bad regex, unknown predicate);
        // prefix them with the pack name so a warning points at the right .scm file.
        return PREDICATES.evaluate(predicates, bindings, message -> predicateErrors.add(name + ": " + message));
    }

    /**
     * Turns the binding's flat predicate-step array for a pattern into {@link Predicate}
     * values. The first step of each predicate is its name (a string); the remaining
     * steps are its arguments (captures or string literals); a Done step ends it.
     */
    private List<Predicate> decodePredicates(int patternIndex) {
        List<Predicate> predicates = new ArrayList<>();
        String currentName = null;
        List<Predicate.Arg> currentArgs = new ArrayList<>();
        boolean malformed = false;

        for (TSQueryPredicateStep step : query.getPredicateForPattern(patternIndex)) {
            TSQueryPredicateStepType type = step.getType();
            if (type == TSQueryPredicateStepType.TSQueryPredicateStepTypeDone) {
                if (currentName != null && !malformed) {
                    predicates.add(new Predicate(currentName, List.copyOf(currentArgs)));
                }
                currentName = null;
                currentArgs = new ArrayList<>();
                malformed = false;
            } else if (type == TSQueryPredicateStepType.TSQueryPredicateStepTypeCapture) {
                if (currentName == null) {
                    // A predicate must start with a name (a string), not a capture.
                    malformed = true;
                } else {
                    currentArgs.add(new Predicate.Capture(query.getCaptureNameForId(step.getValueId())));
                }
            } else { // string step
                String value = query.getStringValueForId(step.getValueId());
                if (currentName == null) {
                    currentName = value;
                } else {
                    currentArgs.add(new Predicate.Literal(value));
                }
            }
        }
        if (currentName != null && !malformed) {
            predicates.add(new Predicate(currentName, List.copyOf(currentArgs)));
        }
        return predicates;
    }

    // ---------- source slicing ----------

    /**
     * Node offsets are UTF-8 byte offsets, so the text must be sliced out of the
     * UTF-8 bytes, not out of the Java String (whose indices are UTF-16 units).
     */
    private static String textOf(TSNode node, byte[] source) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start < 0 || end > source.length || end < start) return "";
        return new String(source, start, end - start, StandardCharsets.UTF_8);
    }
}
