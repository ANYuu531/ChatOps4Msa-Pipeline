package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A tiny boolean expression language for the {@code #when?} predicate.
 *
 * tree-sitter's native predicates are a flat, implicitly-AND-ed list, so they
 * cannot express "this OR that", grouping, or negated sub-expressions. {@code #when?}
 * carries one string argument in this language instead, e.g.
 *
 * <pre>
 *   @method == "send" and (@topic =~ "^ord" or @topic in ["a", "b"]) and not @url =~ "internal"
 * </pre>
 *
 * This class is a self-contained interpreter — lexer, a recursive-descent parser
 * that builds an AST, and an evaluator — so it is unit-testable on its own.
 *
 * <h2>Grammar</h2>
 * <pre>
 *   or         := and ( "or" and )*
 *   and        := unary ( "and" unary )*
 *   unary      := "not" unary | primary
 *   primary    := "(" or ")" | comparison
 *   comparison := CAPTURE ( ("=="|"!="|"=~"|"!~") STRING
 *                         | ("in"|"not" "in") "[" STRING ( "," STRING )* "]" )
 * </pre>
 *
 * <h2>Capture semantics</h2>
 * A capture may bind several nodes in one match. A comparison holds only if ALL of
 * the capture's nodes satisfy it (matching tree-sitter's default quantifier), and a
 * capture that bound nothing makes the comparison false.
 */
public final class ExpressionInterpreter {

    /** Thrown on a lexing or parsing error; the message says what and where. */
    public static final class ExprException extends RuntimeException {
        public ExprException(String message) {
            super(message);
        }
    }

    private static final ConcurrentHashMap<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();
    /** Parsed ASTs are cached: the same #when? expression string is evaluated once per match. */
    private static final ConcurrentHashMap<String, Node> AST_CACHE = new ConcurrentHashMap<>();

    private ExpressionInterpreter() {
    }

    /** Parses (cached) and evaluates {@code expression} against {@code bindings}. */
    public static boolean evaluate(String expression, Bindings bindings) {
        Node ast = AST_CACHE.computeIfAbsent(expression, ExpressionInterpreter::parse);
        return ast.eval(bindings);
    }

    static Node parse(String expression) {
        List<Token> tokens = lex(expression);
        Parser parser = new Parser(tokens, expression);
        Node node = parser.parseOr();
        parser.expectEnd();
        return node;
    }

    // ---------- AST ----------

    interface Node {
        boolean eval(Bindings bindings);
    }

    private record Or(Node left, Node right) implements Node {
        public boolean eval(Bindings b) {
            return left.eval(b) || right.eval(b);
        }
    }

    private record And(Node left, Node right) implements Node {
        public boolean eval(Bindings b) {
            return left.eval(b) && right.eval(b);
        }
    }

    private record Not(Node inner) implements Node {
        public boolean eval(Bindings b) {
            return !inner.eval(b);
        }
    }

    enum Op { EQ, NE, MATCH, NOT_MATCH, IN, NOT_IN }

    private record Comparison(String capture, Op op, List<String> operands) implements Node {
        public boolean eval(Bindings b) {
            List<String> values = b.valuesOf(capture);
            if (values == null || values.isEmpty()) return false; // nothing bound -> cannot satisfy

            for (String value : values) {
                if (!test(value)) return false; // ALL nodes must satisfy
            }
            return true;
        }

        private boolean test(String value) {
            return switch (op) {
                case EQ -> value.equals(operands.get(0));
                case NE -> !value.equals(operands.get(0));
                case MATCH -> regex(operands.get(0)).matcher(value).find();
                case NOT_MATCH -> !regex(operands.get(0)).matcher(value).find();
                case IN -> new HashSet<>(operands).contains(value);
                case NOT_IN -> !new HashSet<>(operands).contains(value);
            };
        }
    }

    private static Pattern regex(String source) {
        return REGEX_CACHE.computeIfAbsent(source, Pattern::compile);
    }

    // ---------- parser ----------

    private static final class Parser {
        private final List<Token> tokens;
        private final String source;
        private int pos = 0;

        Parser(List<Token> tokens, String source) {
            this.tokens = tokens;
            this.source = source;
        }

        Node parseOr() {
            Node left = parseAnd();
            while (isKeyword("or")) {
                advance();
                left = new Or(left, parseAnd());
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseUnary();
            while (isKeyword("and")) {
                advance();
                left = new And(left, parseUnary());
            }
            return left;
        }

        private Node parseUnary() {
            if (isKeyword("not") && !nextIsIn()) {
                advance();
                return new Not(parseUnary());
            }
            return parsePrimary();
        }

        /** {@code not} is unary UNLESS it is the "not in" that follows a capture; that case is handled in the comparison. */
        private boolean nextIsIn() {
            return pos + 1 < tokens.size()
                    && tokens.get(pos + 1).type == TokenType.KEYWORD
                    && tokens.get(pos + 1).text.equals("in");
        }

        private Node parsePrimary() {
            Token token = peek();
            if (token.type == TokenType.LPAREN) {
                advance();
                Node inner = parseOr();
                expect(TokenType.RPAREN, ")");
                return inner;
            }
            return parseComparison();
        }

        private Node parseComparison() {
            Token subject = expect(TokenType.CAPTURE, "a capture like @name");
            Token operator = peek();

            switch (operator.type) {
                case EQ -> { advance(); return new Comparison(subject.text, Op.EQ, List.of(string())); }
                case NE -> { advance(); return new Comparison(subject.text, Op.NE, List.of(string())); }
                case MATCH -> { advance(); return new Comparison(subject.text, Op.MATCH, List.of(string())); }
                case NOT_MATCH -> { advance(); return new Comparison(subject.text, Op.NOT_MATCH, List.of(string())); }
                case KEYWORD -> {
                    if (operator.text.equals("in")) {
                        advance();
                        return new Comparison(subject.text, Op.IN, stringList());
                    }
                    if (operator.text.equals("not")) {
                        advance();
                        if (!isKeyword("in")) throw error("expected 'in' after 'not'");
                        advance();
                        return new Comparison(subject.text, Op.NOT_IN, stringList());
                    }
                    throw error("expected a comparison operator after " + subject.text);
                }
                default -> throw error("expected a comparison operator after " + subject.text);
            }
        }

        private String string() {
            return expect(TokenType.STRING, "a string literal").text;
        }

        private List<String> stringList() {
            expect(TokenType.LBRACKET, "[");
            List<String> items = new ArrayList<>();
            if (peek().type != TokenType.RBRACKET) {
                items.add(string());
                while (peek().type == TokenType.COMMA) {
                    advance();
                    items.add(string());
                }
            }
            expect(TokenType.RBRACKET, "]");
            if (items.isEmpty()) throw error("'in' needs at least one value");
            return items;
        }

        // -- token cursor --

        private Token peek() {
            return tokens.get(pos);
        }

        private void advance() {
            if (pos < tokens.size() - 1) pos++;
        }

        private boolean isKeyword(String word) {
            Token t = peek();
            return t.type == TokenType.KEYWORD && t.text.equals(word);
        }

        private Token expect(TokenType type, String what) {
            Token t = peek();
            if (t.type != type) throw error("expected " + what + " but found " + t.describe());
            advance();
            return t;
        }

        void expectEnd() {
            if (peek().type != TokenType.EOF) throw error("unexpected " + peek().describe() + " after end of expression");
        }

        private ExprException error(String message) {
            return new ExprException(message + " (in: " + source + ")");
        }
    }

    // ---------- lexer ----------

    private enum TokenType {
        CAPTURE, STRING, KEYWORD, EQ, NE, MATCH, NOT_MATCH,
        LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, EOF
    }

    private record Token(TokenType type, String text) {
        String describe() {
            return type == TokenType.EOF ? "end of expression" : "'" + text + "'";
        }
    }

    private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "in");

    private static List<Token> lex(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            switch (c) {
                case '(' -> { tokens.add(new Token(TokenType.LPAREN, "(")); i++; continue; }
                case ')' -> { tokens.add(new Token(TokenType.RPAREN, ")")); i++; continue; }
                case '[' -> { tokens.add(new Token(TokenType.LBRACKET, "[")); i++; continue; }
                case ']' -> { tokens.add(new Token(TokenType.RBRACKET, "]")); i++; continue; }
                case ',' -> { tokens.add(new Token(TokenType.COMMA, ",")); i++; continue; }
                default -> { /* fall through to multi-char handling below */ }
            }

            if (c == '@') {
                int start = ++i;
                while (i < n && (Character.isLetterOrDigit(input.charAt(i))
                        || input.charAt(i) == '_' || input.charAt(i) == '.' || input.charAt(i) == '-')) {
                    i++;
                }
                if (i == start) throw new ExprException("empty capture name at position " + start + " (in: " + input + ")");
                tokens.add(new Token(TokenType.CAPTURE, input.substring(start, i)));
                continue;
            }

            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++; // opening quote
                boolean closed = false;
                while (i < n) {
                    char d = input.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        sb.append(input.charAt(i + 1));
                        i += 2;
                    } else if (d == '"') {
                        i++;
                        closed = true;
                        break;
                    } else {
                        sb.append(d);
                        i++;
                    }
                }
                if (!closed) throw new ExprException("unterminated string (in: " + input + ")");
                tokens.add(new Token(TokenType.STRING, sb.toString()));
                continue;
            }

            if (c == '=' && i + 1 < n && input.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.EQ, "==")); i += 2; continue;
            }
            if (c == '=' && i + 1 < n && input.charAt(i + 1) == '~') {
                tokens.add(new Token(TokenType.MATCH, "=~")); i += 2; continue;
            }
            if (c == '!' && i + 1 < n && input.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.NE, "!=")); i += 2; continue;
            }
            if (c == '!' && i + 1 < n && input.charAt(i + 1) == '~') {
                tokens.add(new Token(TokenType.NOT_MATCH, "!~")); i += 2; continue;
            }

            if (Character.isLetter(c)) {
                int start = i;
                while (i < n && Character.isLetterOrDigit(input.charAt(i))) i++;
                String word = input.substring(start, i);
                if (!KEYWORDS.contains(word)) {
                    throw new ExprException("unknown word '" + word + "' (only and/or/not/in are keywords) (in: " + input + ")");
                }
                tokens.add(new Token(TokenType.KEYWORD, word));
                continue;
            }

            throw new ExprException("unexpected character '" + c + "' at position " + i + " (in: " + input + ")");
        }

        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }
}
