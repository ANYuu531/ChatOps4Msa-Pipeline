package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate;

import java.util.List;

/**
 * One predicate call parsed out of a tree-sitter query, e.g. {@code (#match? @topic "^ord")}.
 *
 * This is pure data with no tree-sitter types, so the whole predicate subsystem
 * (parsing modifiers, evaluating operators, the {@code #when?} expression language)
 * can be unit-tested without a native grammar loaded. The tree-sitter-specific
 * step decoding lives in TreeSitterQueryEngine, which produces these.
 *
 * A predicate's arguments are a FLAT list of captures and string literals — that
 * is all tree-sitter's native predicate encoding allows. There is no nesting here:
 * boolean composition (and/or/not) is only available inside a {@code #when?}
 * expression, which carries its own little language in a single string argument.
 */
public final class Predicate {

    /**
     * An argument is either a reference to a capture ({@code @name}) or a string
     * literal. (A plain interface rather than {@code sealed} so the module can build
     * on {@code -source 16}; the two records below are its only implementations.)
     */
    public interface Arg {}

    /** A reference to a capture; its value is the text(s) captured under it in a match. */
    public record Capture(String name) implements Arg {}

    /** A string literal written directly in the query. */
    public record Literal(String text) implements Arg {}

    /** The predicate name exactly as written, including the trailing {@code ?} or {@code !} (e.g. "not-eq?", "set!"). */
    public final String name;

    /** The arguments after the name, in order. */
    public final List<Arg> args;

    public Predicate(String name, List<Arg> args) {
        this.name = name;
        this.args = args;
    }

    @Override
    public String toString() {
        return "(#" + name + " " + args + ")";
    }
}
