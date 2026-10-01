package io.astra.language.lexer;

/**
 * Token kinds for the AstraSyntax (.ar) language.
 *
 * <p>The lexer is hand-written and allocation-light: it produces a flat list of
 * {@link Token} records rather than a tree, so the parser can build the AST from a
 * single forward pass. Indentation is materialised as {@link #INDENT}/{@link #DEDENT}
 * tokens, which is what allows the block-based ("on player join:") grammar to be
 * parsed with an ordinary recursive descent parser.</p>
 */
public enum TokenType {

    // Structural
    EOF,
    NEWLINE,
    INDENT,
    DEDENT,

    // Punctuation
    COLON,
    COMMA,
    DOT,
    SEMICOLON,
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    LBRACE,
    RBRACE,
    AT,
    DOLLAR,
    SLASH,
    BANG,
    QUESTION,
    PIPE,
    AMPERSAND,
    PLUS,
    MINUS,
    STAR,
    PERCENT,
    EQ,
    EQ_EQ,
    BANG_EQ,
    LT,
    GT,
    LT_EQ,
    GT_EQ,

    // Literals
    WORD,
    NUMBER,
    DECIMAL,
    STRING,

    /** Sentinel used by the parser for synthetic tokens. */
    ERROR
}
