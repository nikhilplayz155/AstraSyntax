package io.astra.language.lexer;

import io.astra.language.diagnostics.DiagnosticCollector;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written lexer for the AstraSyntax (.ar) language.
 *
 * <p>Single forward pass, no backtracking. Indentation is emitted as
 * {@link TokenType#INDENT}/{@link TokenType#DEDENT} tokens so the parser can treat
 * braces and indentation uniformly. Strings support double and single quotes plus
 * the usual escapes; numbers are classified as {@link TokenType#NUMBER} (integral) or
 * {@link TokenType#DECIMAL}.</p>
 *
 * <p>Natural-language lines are lexed the same way as structured Astra - the
 * difference is purely how the parser interprets the resulting word tokens, which is
 * what allows mixed-mode scripts in a single file.</p>
 */
public final class AstraLexer {

    private static final int TAB_WIDTH = 4;

    private final DiagnosticCollector diag;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Integer> indentStack = new ArrayList<>();

    private String src = "";
    private int pos;
    private int line;
    private int col;
    private boolean lineHasContent;

    public AstraLexer(DiagnosticCollector diag) {
        this.diag = diag;
    }

    /** Lex a whole script. Diagnostics for malformed literals land in the collector. */
    public List<Token> lex(String source, String fileName) {
        this.src = source == null ? "" : source;
        this.tokens.clear();
        this.indentStack.clear();
        this.indentStack.add(0);
        this.pos = 0;
        this.line = 1;
        this.col = 1;
        this.lineHasContent = false;

        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\r') { pos++; continue; }
            if (c == '\n') {
                emitNewline();
                pos++;
                line++;
                col = 1;
                continue;
            }
            if (c == ' ' || c == '\t') {
                if (!lineHasContent) {
                    measureIndent();
                } else {
                    pos++;
                    col++;
                }
                continue;
            }
            if (c == '/' && peek(1) == '/') { skipLineComment(); continue; }
            if (c == '/' && peek(1) == '*') { skipBlockComment(); continue; }
            if (c == '#') { skipLineComment(); continue; }
            lexToken();
        }

        if (lineHasContent) emitNewline();
        while (indentStack.size() > 1) {
            indentStack.remove(indentStack.size() - 1);
            add(TokenType.DEDENT, "", pos, 0);
        }
        add(TokenType.EOF, "", pos, 0);
        return tokens;
    }

    /** Tokens produced by the most recent {@link #lex} call. */
    public List<Token> tokens() {
        return tokens;
    }

    // ------------------------------------------------------------------ lexing

    private void lexToken() {
        char c = src.charAt(pos);
        if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
            lexNumber();
            return;
        }
        if (c == '"' || c == '\'') {
            lexString(c);
            return;
        }
        if (isWordStart(c)) {
            lexWord();
            return;
        }
        lexPunctuation(c);
    }

    private void lexNumber() {
        int start = pos;
        int startCol = col;
        boolean decimal = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isDigit(c)) { pos++; col++; continue; }
            if (c == '.' && Character.isDigit(peek(1)) && !decimal) { decimal = true; pos++; col++; continue; }
            break;
        }
        String text = src.substring(start, pos);
        add(decimal ? TokenType.DECIMAL : TokenType.NUMBER, text, start, startCol);
        lineHasContent = true;
    }

    private void lexString(char quote) {
        int start = pos;
        int startCol = col;
        pos++;
        col++;
        StringBuilder value = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < src.length()) {
                char next = src.charAt(pos + 1);
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case '\\' -> value.append('\\');
                    case '"' -> value.append('"');
                    case '\'' -> value.append('\'');
                    default -> value.append(next);
                }
                pos += 2;
                col += 2;
                continue;
            }
            if (c == quote) {
                pos++;
                col++;
                add(TokenType.STRING, value.toString(), start, startCol);
                lineHasContent = true;
                return;
            }
            if (c == '\n') break;
            value.append(c);
            pos++;
            col++;
        }
        diag.error("Unterminated string literal", line, startCol,
            "Close the string with " + quote + " before the end of the line.");
        add(TokenType.STRING, value.toString(), start, startCol);
        lineHasContent = true;
    }

    private void lexWord() {
        int start = pos;
        int startCol = col;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '\'') {
                pos++;
                col++;
                continue;
            }
            break;
        }
        add(TokenType.WORD, src.substring(start, pos), start, startCol);
        lineHasContent = true;
    }

    private void lexPunctuation(char c) {
        int start = pos;
        int startCol = col;
        TokenType type;
        switch (c) {
            case ':' -> type = TokenType.COLON;
            case ',' -> type = TokenType.COMMA;
            case '.' -> type = TokenType.DOT;
            case ';' -> type = TokenType.SEMICOLON;
            case '(' -> type = TokenType.LPAREN;
            case ')' -> type = TokenType.RPAREN;
            case '[' -> type = TokenType.LBRACKET;
            case ']' -> type = TokenType.RBRACKET;
            case '{' -> type = TokenType.LBRACE;
            case '}' -> type = TokenType.RBRACE;
            case '@' -> type = TokenType.AT;
            case '$' -> type = TokenType.DOLLAR;
            case '/' -> type = TokenType.SLASH;
            case '|' -> type = TokenType.PIPE;
            case '?' -> type = TokenType.QUESTION;
            case '%' -> type = TokenType.PERCENT;
            case '&' -> type = TokenType.AMPERSAND;
            case '+' -> type = TokenType.PLUS;
            case '*' -> type = TokenType.STAR;
            case '-' -> type = TokenType.MINUS;
            case '!' -> type = peek(1) == '=' ? null : TokenType.BANG;
            case '=' -> type = peek(1) == '=' ? null : TokenType.EQ;
            case '<' -> type = peek(1) == '=' ? null : TokenType.LT;
            case '>' -> type = peek(1) == '=' ? null : TokenType.GT;
            default -> {
                diag.error("Unexpected character '" + c + "'", line, startCol,
                    "Remove the character or wrap the text in a string.");
                pos++;
                col++;
                return;
            }
        }
        if (type == null) {
            String two = src.substring(pos, Math.min(pos + 2, src.length()));
            TokenType compound = switch (two) {
                case "!=" -> TokenType.BANG_EQ;
                case "==" -> TokenType.EQ_EQ;
                case "<=" -> TokenType.LT_EQ;
                case ">=" -> TokenType.GT_EQ;
                default -> TokenType.ERROR;
            };
            pos += 2;
            col += 2;
            add(compound, two, start, startCol);
            lineHasContent = true;
            return;
        }
        pos++;
        col++;
        add(type, String.valueOf(c), start, startCol);
        lineHasContent = true;
    }

    private void measureIndent() {
        int indent = 0;
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ') { indent++; pos++; }
            else if (c == '\t') { indent += TAB_WIDTH; pos++; }
            else break;
        }
        if (pos < src.length() && src.charAt(pos) == '\n') {
            // Blank line: keep the offset, do not emit indentation.
            col = indent + 1;
            return;
        }
        if (pos >= src.length()) {
            col = indent + 1;
            return;
        }
        int current = indentStack.get(indentStack.size() - 1);
        if (indent > current) {
            indentStack.add(indent);
            add(TokenType.INDENT, "", start, 1);
        } else if (indent < current) {
            while (indentStack.size() > 1 && indent < indentStack.get(indentStack.size() - 1)) {
                indentStack.remove(indentStack.size() - 1);
                add(TokenType.DEDENT, "", start, 1);
            }
            if (indent != indentStack.get(indentStack.size() - 1)) {
                diag.error("Inconsistent indentation", line, 1,
                    "Expected " + indentStack.get(indentStack.size() - 1) + " spaces, found " + indent + ".");
            }
        }
        col = indent + 1;
    }

    private void emitNewline() {
        if (!lineHasContent) return;
        add(TokenType.NEWLINE, "\n", pos, col);
        lineHasContent = false;
    }

    private void skipLineComment() {
        while (pos < src.length() && src.charAt(pos) != '\n') pos++;
    }

    private void skipBlockComment() {
        int startLine = line;
        int startCol = col;
        pos += 2;
        col += 2;
        while (pos + 1 < src.length()) {
            if (src.charAt(pos) == '*' && src.charAt(pos + 1) == '/') {
                pos += 2;
                col += 2;
                return;
            }
            if (src.charAt(pos) == '\n') { line++; col = 1; } else col++;
            pos++;
        }
        diag.error("Unterminated block comment", startLine, startCol, "Add */ to close the comment.");
    }

    private char peek(int offset) {
        int index = pos + offset;
        return index < src.length() ? src.charAt(index) : '\0';
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private void add(TokenType type, String text, int start, int startCol) {
        tokens.add(new Token(type, text, line, startCol, start, start + Math.max(1, text.length())));
    }
}
