package io.astra.language.parser;

import io.astra.language.ast.Expr;
import io.astra.language.ast.Span;
import io.astra.language.lexer.Token;
import io.astra.language.lexer.TokenType;
import io.astra.runtime.Value;
import io.astra.runtime.expression.Properties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses value expressions out of a token slice.
 *
 * <p>Expressions cover arithmetic, text concatenation, property access
 * ({@code player's health}, {@code health of player}), data values ({@code coins of player}),
 * numeric literals with unit words, references ({@code player}, {@code everyone},
 * {@code attacker}), placeholders and calls to registered expressions
 * ({@code random number between 1 and 10}).</p>
 *
 * <p>The parser is strict: it either consumes the whole slice or fails, which is what lets
 * the action matcher try candidate slot lengths longest-first.</p>
 */
final class ExpressionParser {

    /** Everything the parser needs from the surrounding script parser. */
    interface Resolver {
        Vocabulary vocabulary();

        /** True when the script declares (or has already used) this data key. */
        boolean isDataKey(String name);

        /** True when a variable with this name is in scope. */
        boolean isVariable(String name);

        String file();

        /** A span for the given token slice. */
        Span span(List<Token> tokens);

        /** Report a recoverable problem, with a suggestion when one exists. */
        void report(String category, String message, Token token, String explanation, List<String> suggestions);
    }

    private static final List<String> REF_ACTOR = List.of("player", "them", "him", "her", "self", "me", "i",
        "actor", "entity", "target", "mob", "victim", "killed");
    private static final List<String> REF_SECONDARY = List.of("attacker", "killer", "damager", "source", "shooter");

    private final List<Token> tokens;
    private final Resolver resolver;
    private int position;
    private int callDepth;

    /** Templates may nest a few calls deep, but never indefinitely. */
    private static final int MAX_CALL_DEPTH = 4;

    /** Cross-instance guard against a template that re-parses its own input. */
    private static final int MAX_NESTING_DEPTH = 8;

    private static final ThreadLocal<Integer> NESTING = ThreadLocal.withInitial(() -> 0);

    private ExpressionParser(List<Token> tokens, Resolver resolver) {
        this.tokens = tokens;
        this.resolver = resolver;
    }

    /**
     * Parse the slice as an expression, requiring every token to be consumed.
     *
     * <p>The nesting depth is tracked across parser instances on purpose: a syntax
     * template such as {@code <where:location>} hands its slot back to this same entry
     * point, so a per-instance counter would reset on every level and never fire.</p>
     */
    static Expr parseStrict(List<Token> tokens, Resolver resolver) {
        if (tokens == null || tokens.isEmpty()) return null;
        int depth = NESTING.get();
        if (depth >= MAX_NESTING_DEPTH) return null;
        NESTING.set(depth + 1);
        try {
            ExpressionParser parser = new ExpressionParser(tokens, resolver);
            Expr expression = parser.parseOr();
            if (expression == null || parser.position != tokens.size()) return null;
            return expression;
        } finally {
            NESTING.set(depth);
        }
    }

    /** Parse an expression from the current position without requiring full consumption. */
    private Expr parse() {
        return parseOr();
    }

    // ------------------------------------------------------------------ grammar

    private Expr parseOr() {
        Expr left = parseAnd();
        if (left == null) return null;
        while (true) {
            if (!consumeWord("or") && !consumePipes()) break;
            Expr right = parseAnd();
            if (right == null) return null;
            left = new Expr.Binary("or", left, right, span(left));
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseAdditive();
        if (left == null) return null;
        while (true) {
            if (!consumeWord("and") && !consumeAmpersands()) break;
            Expr right = parseAdditive();
            if (right == null) return null;
            left = new Expr.Binary("and", left, right, span(left));
        }
        return left;
    }

    private Expr parseAdditive() {
        Expr left = parseMultiplicative();
        if (left == null) return null;
        while (position < tokens.size()) {
            TokenType type = tokens.get(position).type;
            String operator = switch (type) {
                case PLUS -> "+";
                case MINUS -> "-";
                default -> null;
            };
            if (operator == null) break;
            position++;
            Expr right = parseMultiplicative();
            if (right == null) return null;
            left = new Expr.Binary(operator, left, right, span(left));
        }
        return left;
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        if (left == null) return null;
        while (position < tokens.size()) {
            TokenType type = tokens.get(position).type;
            String operator = switch (type) {
                case STAR -> "*";
                case SLASH -> "/";
                case PERCENT -> "%";
                default -> null;
            };
            if (operator == null) break;
            position++;
            Expr right = parseUnary();
            if (right == null) return null;
            left = new Expr.Binary(operator, left, right, span(left));
        }
        return left;
    }

    private Expr parseUnary() {
        if (position < tokens.size()) {
            Token token = tokens.get(position);
            if (token.type == TokenType.MINUS) {
                position++;
                Expr operand = parseUnary();
                return operand == null ? null : new Expr.Unary("-", operand, span(token));
            }
            if (token.type == TokenType.BANG || (token.is("not"))) {
                position++;
                Expr operand = parseUnary();
                return operand == null ? null : new Expr.Unary("not", operand, span(token));
            }
        }
        return parsePostfix(parsePrimary());
    }

    private Expr parsePostfix(Expr expression) {
        if (expression == null) return null;
        while (position < tokens.size()) {
            Token token = tokens.get(position);
            // player's health
            if (token.type == TokenType.WORD && token.text.toLowerCase(Locale.ROOT).endsWith("'s")
                && token.text.length() > 2) {
                String property = token.text.substring(0, token.text.length() - 2);
                position++;
                // "player's health" but also "the player's health" handled elsewhere.
                expression = propertyOrData(expression, resolveAliasProperty(property), span(token));
                continue;
            }
            // health of player
            if (token.type == TokenType.WORD && token.text.equalsIgnoreCase("of")) {
                int mark = position;
                position++;
                Expr target = parsePostfix(parsePrimary());
                if (target == null) {
                    position = mark;
                    break;
                }
                String name = simpleName(expression);
                if (name == null) {
                    resolver.report("syntax", "The left side of 'of' must be a property or data name", token,
                        "Write it as '<property> of <target>', for example 'health of player'.", List.of());
                    return null;
                }
                expression = propertyOrData(target, name, span(token));
                continue;
            }
            break;
        }
        return expression;
    }

    private Expr parsePrimary() {
        if (position >= tokens.size()) return null;
        Token token = tokens.get(position);

        // A registered expression call ("random number between 1 and 10").
        Expr call = tryExpressionCall();
        if (call != null) return call;

        switch (token.type) {
            case NUMBER -> {
                position++;
                return new Expr.Lit(Value.num(parseLong(token.text)), span(token));
            }
            case DECIMAL -> {
                position++;
                return new Expr.Lit(Value.dec(parseDouble(token.text)), span(token));
            }
            case STRING -> {
                position++;
                return stringExpression(token.text, span(token));
            }
            case LBRACE -> {
                return placeholder('{', '}');
            }
            case PERCENT -> {
                if (position + 2 < tokens.size() && tokens.get(position + 2).type == TokenType.PERCENT) {
                    String raw = tokens.get(position + 1).text;
                    position += 3;
                    return new Expr.Placeholder(raw, span(token));
                }
                // Otherwise it is the modulo operator and not a valid primary.
                return null;
            }
            case LPAREN -> {
                position++;
                Expr inner = parse();
                if (inner == null) return null;
                if (position >= tokens.size() || tokens.get(position).type != TokenType.RPAREN) return null;
                position++;
                return inner;
            }
            case LBRACKET -> {
                position++;
                List<Expr> items = new ArrayList<>();
                while (position < tokens.size() && tokens.get(position).type != TokenType.RBRACKET) {
                    Expr item = parse();
                    if (item == null) return null;
                    items.add(item);
                    if (position < tokens.size() && tokens.get(position).type == TokenType.COMMA) {
                        position++;
                    } else {
                        break;
                    }
                }
                if (position >= tokens.size() || tokens.get(position).type != TokenType.RBRACKET) return null;
                position++;
                return new Expr.ListExpr(items, span(token));
            }
            case WORD -> {
                return parseWordPrimary(token);
            }
            default -> {
                return null;
            }
        }
    }

    private Expr parseWordPrimary(Token token) {
        String word = token.text.toLowerCase(Locale.ROOT);
        // Unit words such as "5 seconds" are not values on their own, but "10 seconds"
        // reaches here through the DURATION slot binder, not through expressions.
        switch (word) {
            case "true", "yes", "on" -> {
                position++;
                return new Expr.Lit(Value.bool(true), span(token));
            }
            case "false", "no", "off" -> {
                position++;
                return new Expr.Lit(Value.bool(false), span(token));
            }
            default -> {
            }
        }

        // "a random player" / "all players" / "every player".
        if (word.equals("a") || word.equals("an") || word.equals("the")) {
            if (position + 1 < tokens.size()) {
                Token next = tokens.get(position + 1);
                if (next.type == TokenType.WORD && next.text.equalsIgnoreCase("random")) {
                    position += 2;
                    return new Expr.Ref("random player", Expr.RefKind.RANDOM_PLAYER, span(token));
                }
            }
            return null;
        }
        if (word.equals("all") || word.equals("every") || word.equals("everyone") || word.equals("everybody")) {
            if (word.equals("all") || word.equals("every")) {
                if (position + 1 >= tokens.size()
                    || !tokens.get(position + 1).text.equalsIgnoreCase("players")) {
                    position++;
                    return new Expr.Ref("everyone", Expr.RefKind.ALL_PLAYERS, span(token));
                }
                position += 2;
                return new Expr.Ref("all players", Expr.RefKind.ALL_PLAYERS, span(token));
            }
            position++;
            return new Expr.Ref("everyone", Expr.RefKind.ALL_PLAYERS, span(token));
        }
        if (word.equals("random")) {
            if (position + 1 < tokens.size() && tokens.get(position + 1).text.equalsIgnoreCase("player")) {
                position += 2;
                return new Expr.Ref("random player", Expr.RefKind.RANDOM_PLAYER, span(token));
            }
            return null;
        }

        position++;
        if (REF_ACTOR.contains(word)) return new Expr.Ref(word, Expr.RefKind.ACTOR, span(token));
        if (REF_SECONDARY.contains(word)) {
            // Attackers and killers live in the event, not in the context's actor slot.
            return new Expr.Property(new Expr.Ref("event", Expr.RefKind.EVENT, span(token)), word, span(token));
        }
        if (word.equals("world")) return new Expr.Ref("world", Expr.RefKind.WORLD, span(token));
        if (word.equals("console") || word.equals("server")) {
            return new Expr.Ref("console", Expr.RefKind.CONSOLE, span(token));
        }
        if (word.equals("event")) return new Expr.Ref("event", Expr.RefKind.EVENT, span(token));
        if (word.equals("sender")) return new Expr.Ref("sender", Expr.RefKind.SENDER, span(token));
        return new Expr.Ref(token.text, Expr.RefKind.NAME, span(token));
    }

    private Expr placeholder(char open, char close) {
        Token start = tokens.get(position);
        position++;
        StringBuilder name = new StringBuilder();
        while (position < tokens.size()) {
            Token token = tokens.get(position);
            if (token.type == TokenType.RBRACE || token.type == TokenType.PERCENT) break;
            if (name.length() > 0 && token.type != TokenType.WORD) name.append(character(token));
            name.append(token.text);
            position++;
        }
        if (position < tokens.size()) position++;
        return new Expr.Placeholder(name.toString(), span(start));
    }

    /** Build a text expression, detecting embedded placeholders. */
    private static Expr stringExpression(String text, Span span) {
        if (text.indexOf('{') >= 0 || text.indexOf('%') >= 0) {
            return new Expr.Interpolated(text, span);
        }
        return new Expr.Lit(Value.str(text), span);
    }

    /** Property when the name is a known property, otherwise a declared/auto data key. */
    private Expr propertyOrData(Expr target, String name, Span span) {
        String normalised = Properties.normalise(name);
        if (Properties.isKnown(normalised)) return new Expr.Property(target, normalised, span);
        return new Expr.Data(name, target, span);
    }

    private static String resolveAliasProperty(String property) {
        return property.equalsIgnoreCase("game mode") ? "gamemode" : property;
    }

    /** The simple name of an expression used as the left side of {@code of}. */
    private static String simpleName(Expr expression) {
        if (expression instanceof Expr.Ref ref) return ref.name();
        if (expression instanceof Expr.Lit lit && lit.value().type() == io.astra.runtime.ValueType.STRING) {
            return lit.value().asString();
        }
        return null;
    }

    // ------------------------------------------------------- expression calls

    private Expr tryExpressionCall() {
        List<Token> remaining = tokens.subList(position, tokens.size());
        if (remaining.isEmpty()) return null;
        Token first = remaining.get(0);
        if (first.type != TokenType.WORD && first.type != TokenType.STRING) return null;
        Vocabulary vocabulary = resolver.vocabulary();
        if (vocabulary == null) return null;
        // Two guards keep a template such as "<value:expr>" (or a chain of them) from
        // recursing into itself: the depth cap, and refusing slots that swallow the whole
        // remaining input, which could only re-enter this same grammar at this position.
        int depth = callDepth;
        if (depth >= MAX_CALL_DEPTH) return null;
        int available = remaining.size();
        Expr best = null;
        int bestScore = -1;
        callDepth = depth + 1;
        try {
            for (Vocabulary.Entry entry : vocabulary.expressions()) {
                TemplateMatcher.Match match = TemplateMatcher.match(entry.template(), remaining,
                    (slot, slice, greedy) -> bindCallSlot(slot, slice, available, recurses(slot.type())));
                if (match == null) continue;
                if (match.score() > bestScore) {
                    bestScore = match.score();
                    best = new Expr.Call(entry.id(), match.arguments(), resolver.span(remaining));
                }
            }
        } finally {
            callDepth = depth;
        }
        if (best == null) return null;
        position = tokens.size();
        return best;
    }

    /** True when binding this slot type recurses back into the expression grammar. */
    private static boolean recurses(SlotType type) {
        return type == SlotType.EXPR || type == SlotType.LIST || type == SlotType.TARGET
            || type == SlotType.LOCATION;
    }

    private Expr bindCallSlot(SyntaxTemplate.Slot slot, List<Token> slice, int available, boolean recursive) {
        if (recursive && slice.size() >= available) return null;
        return switch (slot.type()) {
            case EXPR, TARGET, LOCATION -> ExpressionParser.parseStrict(slice, resolver);
            case NUMBER -> singleNumber(slice, false);
            case DECIMAL -> singleNumber(slice, true);
            case BOOLEAN -> singleBoolean(slice);
            case TEXT, STRING, WORD, MATERIAL, ENTITY, WORLD, PERMISSION, DATA_KEY -> singleText(slice);
            case DURATION -> singleDuration(slice);
            case ITEM -> null; // item arguments are bound by the action matcher, not inside expressions
            case LIST -> ExpressionParser.parseStrict(slice, resolver);
        };
    }

    private static Expr singleNumber(List<Token> slice, boolean decimal) {
        if (slice.size() != 1) return null;
        Token token = slice.get(0);
        if (token.type == TokenType.NUMBER) {
            return new Expr.Lit(decimal ? Value.dec(parseDouble(token.text)) : Value.num(parseLong(token.text)),
                new Span("", token.line, token.column, token.length()));
        }
        if (decimal && token.type == TokenType.DECIMAL) {
            return new Expr.Lit(Value.dec(parseDouble(token.text)),
                new Span("", token.line, token.column, token.length()));
        }
        return null;
    }

    private static Expr singleBoolean(List<Token> slice) {
        if (slice.size() != 1 || slice.get(0).type != TokenType.WORD) return null;
        String word = slice.get(0).text.toLowerCase(Locale.ROOT);
        if (word.equals("true") || word.equals("yes") || word.equals("on")) return new Expr.Lit(Value.bool(true),
            new Span("", slice.get(0).line, slice.get(0).column, slice.get(0).length()));
        if (word.equals("false") || word.equals("no") || word.equals("off")) return new Expr.Lit(Value.bool(false),
            new Span("", slice.get(0).line, slice.get(0).column, slice.get(0).length()));
        return null;
    }

    private static Expr singleText(List<Token> slice) {
        if (slice.size() != 1) return null;
        Token token = slice.get(0);
        if (token.type != TokenType.WORD && token.type != TokenType.STRING) return null;
        return new Expr.Lit(Value.str(token.text), new Span("", token.line, token.column, token.length()));
    }

    private static Expr singleDuration(List<Token> slice) {
        StringBuilder raw = new StringBuilder();
        for (Token token : slice) {
            if (raw.length() > 0) raw.append(' ');
            raw.append(token.text);
        }
        try {
            long ticks = io.astra.util.Durations.parseTicks(raw.toString());
            Token first = slice.get(0);
            return new Expr.Lit(Value.num(ticks), new Span("", first.line, first.column, first.length()));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean consumeWord(String word) {
        if (position < tokens.size() && tokens.get(position).type == TokenType.WORD
            && tokens.get(position).text.equalsIgnoreCase(word)) {
            position++;
            return true;
        }
        return false;
    }

    private boolean consumePipes() {
        if (position + 1 < tokens.size() && tokens.get(position).type == TokenType.PIPE
            && tokens.get(position + 1).type == TokenType.PIPE) {
            position += 2;
            return true;
        }
        return false;
    }

    private boolean consumeAmpersands() {
        if (position + 1 < tokens.size() && tokens.get(position).type == TokenType.AMPERSAND
            && tokens.get(position + 1).type == TokenType.AMPERSAND) {
            position += 2;
            return true;
        }
        return false;
    }

    private Span span(Expr expression) {
        return expression == null ? Span.unknown(resolver.file()) : expression.span();
    }

    private Span span(Token token) {
        return new Span(resolver.file(), token.line, token.column, token.length());
    }

    /** The single-character form of a punctuation token, used when rebuilding text. */
    private static String character(Token token) {
        return switch (token.type) {
            case DOT -> ".";
            case COMMA -> ",";
            case SLASH -> "/";
            case COLON -> ":";
            case SEMICOLON -> ";";
            case BANG -> "!";
            case QUESTION -> "?";
            case PLUS -> "+";
            case MINUS -> "-";
            case STAR -> "*";
            case LBRACE -> "{";
            case RBRACE -> "}";
            case LPAREN -> "(";
            case RPAREN -> ")";
            case LBRACKET -> "[";
            case RBRACKET -> "]";
            default -> " ";
        };
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double parseDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /** Convenience used by the action matcher for building simple argument maps. */
    static Map<String, Expr> single(String name, Expr value) {
        Map<String, Expr> map = new LinkedHashMap<>();
        map.put(name, value);
        return map;
    }
}
