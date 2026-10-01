package io.astra.language.parser;

import io.astra.language.ast.Expr;
import io.astra.language.lexer.Token;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches a token line against a {@link SyntaxTemplate}.
 *
 * <p>This is the mechanism that lets the language grow without the parser growing: a
 * template such as {@code give <who:target> <item:item>} is matched by walking the
 * template's segments and trying slot lengths longest-first, so multi-word values
 * ("diamond sword") bind correctly without any special cases in the parser.</p>
 *
 * <p>Slot values are produced by a {@link SlotBinder} supplied by the parser, which owns
 * the vocabulary checks (is "dimonds" a material?) and the diagnostics.</p>
 */
final class TemplateMatcher {

    /** Binds a slice of tokens to a slot value, or returns {@code null} when it cannot. */
    interface SlotBinder {
        Expr bind(SyntaxTemplate.Slot slot, List<Token> tokens, boolean greedy);
    }

    /** A successful match: the bound arguments, in template order. */
    record Match(Map<String, Expr> arguments, int score) { }

    private TemplateMatcher() {
    }

    /**
     * Try to match the whole token list against the template.
     *
     * @return the match, or {@code null} when the tokens do not match exactly
     */
    static Match match(SyntaxTemplate template, List<Token> tokens, SlotBinder binder) {
        if (template == null || tokens == null) return null;
        Map<String, Expr> captured = new LinkedHashMap<>();
        if (!step(template.segments(), 0, tokens, 0, captured, binder)) return null;
        int score = 0;
        for (SyntaxTemplate.Segment segment : template.segments()) {
            if (segment instanceof SyntaxTemplate.Literal literal) score += 2 * literal.alternatives().size();
        }
        score += captured.size();
        return new Match(captured, score);
    }

    private static boolean step(List<SyntaxTemplate.Segment> segments, int segmentIndex, List<Token> tokens,
                                int tokenIndex, Map<String, Expr> captured, SlotBinder binder) {
        if (segmentIndex == segments.size()) {
            // Everything must be consumed: silent trailing words would hide typos.
            return tokenIndex == tokens.size();
        }
        SyntaxTemplate.Segment segment = segments.get(segmentIndex);
        if (segment instanceof SyntaxTemplate.Literal literal) {
            if (tokenIndex >= tokens.size()) return false;
            Token token = tokens.get(tokenIndex);
            if (!isWordLike(token)) return false;
            for (String alternative : literal.alternatives()) {
                if (token.text.equalsIgnoreCase(alternative)) {
                    return step(segments, segmentIndex + 1, tokens, tokenIndex + 1, captured, binder);
                }
            }
            return false;
        }

        SyntaxTemplate.Slot slot = (SyntaxTemplate.Slot) segment;
        if (slot.greedy()) {
            if (tokenIndex >= tokens.size()) {
                return slot.optional() && step(segments, segmentIndex + 1, tokens, tokenIndex, captured, binder);
            }
            List<Token> slice = tokens.subList(tokenIndex, tokens.size());
            Expr bound = binder.bind(slot, slice, true);
            if (bound == null) return false;
            captured.put(slot.name(), bound);
            return step(segments, segmentIndex + 1, tokens, tokens.size(), captured, binder);
        }

        // Longest-first: "5 diamond sword" must be preferred over "5".
        SyntaxTemplate.Segment next = segmentIndex + 1 < segments.size() ? segments.get(segmentIndex + 1) : null;
        for (int end = tokens.size(); end > tokenIndex; end--) {
            // Do not bind a slice that cannot possibly be followed by the template's next
            // literal: probing "give <item:item> to <who>" with the slice "player" would
            // otherwise report "unknown item: player" for a word that is fine.
            if (!canFollow(next, tokens, end)) continue;
            List<Token> slice = tokens.subList(tokenIndex, end);
            Expr bound = binder.bind(slot, slice, false);
            if (bound == null) continue;
            Map<String, Expr> snapshot = new LinkedHashMap<>(captured);
            captured.put(slot.name(), bound);
            if (step(segments, segmentIndex + 1, tokens, end, captured, binder)) return true;
            captured.clear();
            captured.putAll(snapshot);
        }
        if (slot.optional()) {
            return step(segments, segmentIndex + 1, tokens, tokenIndex, captured, binder);
        }
        return false;
    }

    /** True when the token at {@code index} can satisfy the segment that follows a slot. */
    private static boolean canFollow(SyntaxTemplate.Segment next, List<Token> tokens, int index) {
        if (next == null) return index == tokens.size();
        if (!(next instanceof SyntaxTemplate.Literal literal)) return true;
        if (index >= tokens.size()) return false;
        Token token = tokens.get(index);
        if (!isWordLike(token)) return false;
        for (String alternative : literal.alternatives()) {
            if (token.text.equalsIgnoreCase(alternative)) return true;
        }
        return false;
    }

    private static boolean isWordLike(Token token) {
        return token.type == io.astra.language.lexer.TokenType.WORD
            || token.type == io.astra.language.lexer.TokenType.STRING
            || token.type == io.astra.language.lexer.TokenType.NUMBER;
    }
}
