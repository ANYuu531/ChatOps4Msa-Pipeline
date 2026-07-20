package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate.Bindings;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate.ExpressionInterpreter;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate.Predicate;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate.PredicateEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for the predicate subsystem — no Spring context and no native
 * tree-sitter grammar, so they always run.
 *
 * They cover the two things the redesign is about:
 *  1. the generic operator engine that derives every #not-/#any- variant from a
 *     base operator instead of a switch, and
 *  2. the #when? expression language (lexer + parser + evaluator).
 */
public class PredicateEngineTest {

    private final PredicateEngine engine = new PredicateEngine();

    // -- helpers --

    private static Bindings bind(Map<String, List<String>> map) {
        return name -> map.getOrDefault(name, List.of());
    }

    private static Bindings single(String capture, String value) {
        return bind(Map.of(capture, List.of(value)));
    }

    private static Predicate.Arg cap(String name) {
        return new Predicate.Capture(name);
    }

    private static Predicate.Arg lit(String text) {
        return new Predicate.Literal(text);
    }

    private boolean holds(Predicate predicate, Bindings bindings) {
        List<String> errors = new ArrayList<>();
        boolean result = engine.evaluate(List.of(predicate), bindings, errors::add);
        return result;
    }

    private List<String> errorsOf(Predicate predicate, Bindings bindings) {
        List<String> errors = new ArrayList<>();
        engine.evaluate(List.of(predicate), bindings, errors::add);
        return errors;
    }

    // -- standard operators, derived variants --

    @Test
    void eqAndItsNegation() {
        assertTrue(holds(new Predicate("eq?", List.of(cap("m"), lit("send"))), single("m", "send")));
        assertFalse(holds(new Predicate("eq?", List.of(cap("m"), lit("send"))), single("m", "recv")));
        // #not-eq? is the SAME base operator with the negate modifier peeled off the name.
        assertFalse(holds(new Predicate("not-eq?", List.of(cap("m"), lit("send"))), single("m", "send")));
        assertTrue(holds(new Predicate("not-eq?", List.of(cap("m"), lit("send"))), single("m", "recv")));
    }

    @Test
    void quantifiersAllVersusAny() {
        Bindings mixed = bind(Map.of("t", List.of("send", "recv")));
        // ALL (default): every node must equal -> false because one is "recv".
        assertFalse(holds(new Predicate("eq?", List.of(cap("t"), lit("send"))), mixed));
        // ANY: at least one node equals -> true. Same base op, quantifier from the name.
        assertTrue(holds(new Predicate("any-eq?", List.of(cap("t"), lit("send"))), mixed));
    }

    @Test
    void matchRegex() {
        assertTrue(holds(new Predicate("match?", List.of(cap("u"), lit("^https?://"))), single("u", "https://x")));
        assertFalse(holds(new Predicate("match?", List.of(cap("u"), lit("^https?://"))), single("u", "ftp://x")));
        assertTrue(holds(new Predicate("not-match?", List.of(cap("u"), lit("internal"))), single("u", "example.com")));
    }

    @Test
    void anyOfMembershipAndNegation() {
        Predicate anyOf = new Predicate("any-of?", List.of(cap("v"), lit("GET"), lit("POST")));
        assertTrue(holds(anyOf, single("v", "GET")));
        assertFalse(holds(anyOf, single("v", "PATCH")));
        // #not-any-of? must peel to base "any-of" (not the "any-" quantifier) and negate.
        Predicate notAnyOf = new Predicate("not-any-of?", List.of(cap("v"), lit("GET"), lit("POST")));
        assertTrue(holds(notAnyOf, single("v", "PATCH")));
        assertFalse(holds(notAnyOf, single("v", "GET")));
    }

    @Test
    void unknownPredicateIsReportedNotSilentlyPassed() {
        Predicate bogus = new Predicate("totally-made-up?", List.of(cap("v"), lit("x")));
        assertFalse(holds(bogus, single("v", "x")));
        assertTrue(errorsOf(bogus, single("v", "x")).stream().anyMatch(e -> e.contains("unknown predicate")));
    }

    @Test
    void directivesDoNotFilter() {
        // #set! attaches metadata; it must never reject a match.
        assertTrue(holds(new Predicate("set!", List.of(cap("v"), lit("key"), lit("value"))), single("v", "x")));
    }

    @Test
    void multiplePredicatesAreAnded() {
        Bindings b = bind(Map.of("m", List.of("send"), "u", List.of("https://x")));
        List<String> errors = new ArrayList<>();
        boolean ok = engine.evaluate(List.of(
                new Predicate("eq?", List.of(cap("m"), lit("send"))),
                new Predicate("match?", List.of(cap("u"), lit("^https")))
        ), b, errors::add);
        assertTrue(ok);
        boolean second = engine.evaluate(List.of(
                new Predicate("eq?", List.of(cap("m"), lit("send"))),
                new Predicate("eq?", List.of(cap("u"), lit("nope")))
        ), b, errors::add);
        assertFalse(second);
    }

    // -- the #when? expression language --

    @Test
    void whenSupportsBooleanCompositionAndNesting() {
        Bindings b = bind(Map.of("method", List.of("send"), "topic", List.of("orders")));
        String expr = "@method == \"send\" and (@topic =~ \"^ord\" or @topic in [\"a\", \"b\"])";
        assertTrue(holds(new Predicate("when?", List.of(lit(expr))), b));

        Bindings b2 = bind(Map.of("method", List.of("send"), "topic", List.of("zzz")));
        assertFalse(holds(new Predicate("when?", List.of(lit(expr))), b2));
    }

    @Test
    void whenNotAndInAndNegatedWhen() {
        Bindings b = bind(Map.of("url", List.of("https://api.example.com")));
        assertTrue(ExpressionInterpreter.evaluate("not @url =~ \"internal\"", b));
        assertTrue(ExpressionInterpreter.evaluate("@url in [\"https://api.example.com\", \"x\"]", b));
        assertFalse(ExpressionInterpreter.evaluate("@url not in [\"https://api.example.com\"]", b));
        // #not-when? negates the whole expression through the same modifier machinery.
        assertFalse(holds(new Predicate("not-when?", List.of(lit("@url =~ \"example\""))), b));
    }

    @Test
    void whenAllNodesMustSatisfy() {
        Bindings mixed = bind(Map.of("t", List.of("orders", "shipments")));
        assertTrue(ExpressionInterpreter.evaluate("@t =~ \"s$\"", mixed)); // both end in s
        assertFalse(ExpressionInterpreter.evaluate("@t == \"orders\"", mixed)); // not all equal
    }

    @Test
    void whenReportsBadExpressionAsError() {
        Predicate bad = new Predicate("when?", List.of(lit("@a === \"x\"")));
        List<String> errors = errorsOf(bad, single("a", "x"));
        assertFalse(holds(bad, single("a", "x")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("#when?")));
    }

    @Test
    void expressionPrecedenceOrBindsLooserThanAnd() {
        // false and false or true  ==  (false and false) or true  == true
        Bindings b = bind(Map.of("a", List.of("1"), "b", List.of("1"), "c", List.of("1")));
        assertTrue(ExpressionInterpreter.evaluate("@a == \"0\" and @b == \"0\" or @c == \"1\"", b));
        // With grouping it flips: false and (false or true) == false
        assertFalse(ExpressionInterpreter.evaluate("@a == \"0\" and (@b == \"0\" or @c == \"1\")", b));
    }
}
