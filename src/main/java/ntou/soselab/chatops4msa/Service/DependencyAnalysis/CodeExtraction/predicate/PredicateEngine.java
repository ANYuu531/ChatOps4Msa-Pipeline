package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A general evaluator for tree-sitter query predicates.
 *
 * <h2>Why this is not a switch</h2>
 * tree-sitter's C engine records predicates but never evaluates them (by design —
 * predicates are left to the host). The obvious way to fill that gap is a switch
 * over each name (#eq?, #not-eq?, #any-eq?, #match?, #not-match?, #any-of? ...),
 * but that is an ever-growing enumeration.
 *
 * Instead this engine keeps a registry of BASE operators — {@code eq}, {@code match},
 * {@code any-of}, {@code when} — and derives every standard variant generically by
 * peeling the {@code not-} / {@code any-} modifiers off the predicate name:
 * <pre>
 *   any-not-eq?  ->  base "eq",     quantifier ANY, negate
 *   not-any-of?  ->  base "any-of", quantifier ALL, negate
 * </pre>
 * Adding one base operator gives all of its negated and quantified forms for free;
 * the dispatch never grows. The evaluator itself never names a predicate.
 *
 * <h2>Where nesting lives</h2>
 * A pattern's predicates are a flat, implicitly-AND-ed list (that is all the native
 * encoding supports). Real boolean composition — and / or / not / parentheses —
 * is available only inside the {@code #when?} operator, whose single string
 * argument is a small language parsed and evaluated by {@link ExpressionInterpreter}.
 *
 * The engine is immutable once constructed and safe to share across threads; the
 * per-run error sink is passed in to {@link #evaluate}.
 */
public final class PredicateEngine {

    /** Whether a capture's nodes must ALL satisfy the test, or just ANY of them. */
    public enum Quantifier { ALL, ANY }

    /** A base operator. Modifiers (negate/quantifier) are applied by the engine, not here. */
    @FunctionalInterface
    public interface Operator {
        boolean holds(Invocation call);
    }

    /** Everything an operator needs to decide one predicate. */
    public static final class Invocation {
        public final boolean negate;
        public final Quantifier quantifier;
        public final List<Predicate.Arg> args;
        public final Bindings bindings;
        public final Consumer<String> errors;
        private final PredicateEngine engine;

        Invocation(boolean negate, Quantifier quantifier, List<Predicate.Arg> args,
                   Bindings bindings, Consumer<String> errors, PredicateEngine engine) {
            this.negate = negate;
            this.quantifier = quantifier;
            this.args = args;
            this.bindings = bindings;
            this.errors = errors;
            this.engine = engine;
        }

        /** The captures/literals after the subject (args[1..]), flattened to their texts. */
        public List<String> operandValues() {
            return engine.operandValues(this);
        }

        /**
         * Applies a per-node test to the SUBJECT capture (args[0]) under this call's
         * quantifier and negation. This is the one place quantifier/negation logic
         * lives, so every capture operator gets it identically.
         */
        public boolean applyToSubject(java.util.function.Predicate<String> nodeTest) {
            return engine.applyToSubject(this, nodeTest);
        }
    }

    private final Map<String, Operator> operators = new LinkedHashMap<>();
    /** Base names longest-first, so a multi-word base (any-of) is matched before a shorter suffix. */
    private final List<String> basesByLengthDesc = new ArrayList<>();
    private final Map<String, Pattern> regexCache = new ConcurrentHashMap<>();

    public PredicateEngine() {
        registerDefaults();
    }

    /** Register (or override) a base operator. All of its not-/any- variants come for free. */
    public void register(String baseName, Operator operator) {
        operators.put(baseName, operator);
        basesByLengthDesc.clear();
        basesByLengthDesc.addAll(operators.keySet());
        basesByLengthDesc.sort(Comparator.comparingInt(String::length).reversed());
    }

    /**
     * Evaluates every predicate of a pattern against one match. The predicates are
     * combined with AND (tree-sitter's implicit semantics): the first that fails
     * rejects the match.
     */
    public boolean evaluate(List<Predicate> predicates, Bindings bindings, Consumer<String> errors) {
        for (Predicate predicate : predicates) {
            if (!holds(predicate, bindings, errors)) return false;
        }
        return true;
    }

    private boolean holds(Predicate predicate, Bindings bindings, Consumer<String> errors) {
        String raw = predicate.name;

        // Directives (#set!, #select-adjacent!) attach metadata; they do not filter,
        // so they must not reject the match.
        if (raw.endsWith("!")) return true;

        String name = raw.endsWith("?") ? raw.substring(0, raw.length() - 1) : raw;

        Decoded decoded = decode(name);
        if (decoded == null) {
            // A typo'd predicate must not silently pass as an unguarded match.
            errors.accept("unknown predicate #" + raw + " (match rejected)");
            return false;
        }

        Operator operator = operators.get(decoded.base);
        Invocation call = new Invocation(decoded.negate, decoded.quantifier,
                predicate.args, bindings, errors, this);
        return operator.holds(call);
    }

    // ---------- name decomposition ----------

    private record Decoded(String base, boolean negate, Quantifier quantifier) {}

    /**
     * Splits a predicate name into a registered base operator plus its modifiers.
     * The base is matched as the longest registered suffix so that {@code any-of}
     * (where "any" is part of the operator) is not mistaken for the {@code any-}
     * quantifier.
     */
    private Decoded decode(String name) {
        for (String base : basesByLengthDesc) {
            if (name.equals(base)) {
                return new Decoded(base, false, Quantifier.ALL);
            }
            if (name.endsWith("-" + base)) {
                String prefix = name.substring(0, name.length() - base.length() - 1);
                boolean negate = false;
                Quantifier quantifier = Quantifier.ALL;
                for (String token : prefix.split("-")) {
                    switch (token) {
                        case "not" -> negate = true;
                        case "any" -> quantifier = Quantifier.ANY;
                        default -> {
                            return null; // unknown modifier -> treat whole name as unknown
                        }
                    }
                }
                return new Decoded(base, negate, quantifier);
            }
        }
        return null;
    }

    // ---------- helpers shared by capture operators ----------

    private List<String> operandValues(Invocation call) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < call.args.size(); i++) {
            Predicate.Arg arg = call.args.get(i);
            if (arg instanceof Predicate.Literal literal) {
                out.add(literal.text());
            } else if (arg instanceof Predicate.Capture capture) {
                List<String> values = call.bindings.valuesOf(capture.name());
                if (values != null) out.addAll(values);
            }
        }
        return out;
    }

    private boolean applyToSubject(Invocation call, java.util.function.Predicate<String> nodeTest) {
        if (call.args.isEmpty() || !(call.args.get(0) instanceof Predicate.Capture subject)) {
            return false; // malformed: no subject capture to test
        }
        List<String> nodes = call.bindings.valuesOf(subject.name());
        if (nodes == null || nodes.isEmpty()) return false;

        boolean any = call.quantifier == Quantifier.ANY;
        for (String value : nodes) {
            // negate flips the atomic test per node; the quantifier then combines them.
            boolean pass = nodeTest.test(value) != call.negate;
            if (any && pass) return true;   // ANY: one pass is enough
            if (!any && !pass) return false; // ALL: one failure rejects
        }
        return !any; // ALL with no failures -> true; ANY with no passes -> false
    }

    Pattern regex(String source) {
        return regexCache.computeIfAbsent(source, Pattern::compile);
    }

    // ---------- default operators ----------

    private void registerDefaults() {
        // #eq? @a @b | #eq? @a "lit" — the subject's node text must equal one of the operands.
        register("eq", call -> {
            List<String> operands = call.operandValues();
            if (operands.isEmpty()) return false;
            Set<String> allowed = new HashSet<>(operands);
            return call.applyToSubject(allowed::contains);
        });

        // #any-of? @a "x" "y" ... — spec-distinct name; the atomic test is membership,
        // same as eq, but kept separate so queries read as intended.
        register("any-of", call -> {
            List<String> operands = call.operandValues();
            if (operands.isEmpty()) return false;
            Set<String> allowed = new HashSet<>(operands);
            return call.applyToSubject(allowed::contains);
        });

        // #match? @a "regex" — the subject's node text must contain a match (find, not full match).
        register("match", call -> {
            List<String> operands = call.operandValues();
            if (operands.isEmpty()) {
                call.errors.accept("#match? is missing its regular expression");
                return false;
            }
            Pattern pattern;
            try {
                pattern = regex(operands.get(0));
            } catch (RuntimeException e) {
                call.errors.accept("bad regex in #match? -> " + operands.get(0));
                return false;
            }
            return call.applyToSubject(value -> pattern.matcher(value).find());
        });

        // #when? "<expression>" — the one operator with real nesting. Its single
        // string argument is a boolean expression over the match's captures.
        register("when", call -> {
            if (call.args.size() != 1 || !(call.args.get(0) instanceof Predicate.Literal expression)) {
                call.errors.accept("#when? expects a single string expression argument");
                return false;
            }
            try {
                boolean result = ExpressionInterpreter.evaluate(expression.text(), call.bindings);
                return result != call.negate; // supports #not-when? too
            } catch (ExpressionInterpreter.ExprException e) {
                call.errors.accept("#when? bad expression: " + e.getMessage());
                return false;
            }
        });
    }
}
