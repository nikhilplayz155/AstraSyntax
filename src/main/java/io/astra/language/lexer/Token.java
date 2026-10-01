package io.astra.language.lexer;

import java.util.Objects;

/**
 * A single lexical token produced by {@link AstraLexer}.
 *
 * <p>Tokens are flat records with a {@link TokenType}, a raw text value and a source
 * span. The parser never inspects the raw source again after lexing - everything it
 * needs for diagnostics (line, column, offsets) travels with the token.</p>
 */
public final class Token {

    public final TokenType type;
    public final String text;
    public final int line;
    public final int column;
    public final int start;
    public final int end;

    public Token(TokenType type, String text, int line, int column, int start, int end) {
        this.type = type;
        this.text = text;
        this.line = line;
        this.column = column;
        this.start = start;
        this.end = end;
    }

    /** Case-insensitive keyword check against the token text. */
    public boolean is(String keyword) {
        return type == TokenType.WORD && text != null && text.equalsIgnoreCase(keyword);
    }

    /** True when this token can start a statement (used for recovery). */
    public boolean isStructural() {
        return type == TokenType.NEWLINE || type == TokenType.INDENT || type == TokenType.DEDENT
            || type == TokenType.EOF;
    }

    /** True when the token carries literal text (not a structural marker). */
    public boolean hasText() {
        return text != null && !text.isEmpty();
    }

    public int length() {
        return Math.max(1, end - start);
    }

    @Override
    public String toString() {
        return type + (text == null ? "" : "=" + text) + "@" + line + ":" + column;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Token t)) return false;
        return type == t.type
            && start == t.start
            && end == t.end
            && Objects.equals(text, t.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, text, start, end);
    }
}
