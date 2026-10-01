package io.astra.language.parser;

import io.astra.language.ast.ScriptFile;

/**
 * What the parser produced.
 *
 * <p>The AST is always returned, even when there were errors: the compiler refuses to run
 * a file that has parse errors, but the diagnostics still need the source text to render
 * excerpts, and tools such as {@code /astra explain} want to show the partial tree.</p>
 *
 * @param file  the parsed file (never null)
 * @param valid true when no engine-level error was reported
 */
public record ParseResult(ScriptFile file, boolean valid) {

    public ParseResult {
        if (file == null) {
            file = new ScriptFile("<empty>", "", java.util.List.of(), java.util.List.of(), false);
        }
    }

    /** A failed parse of a file, used when reading itself failed. */
    public static ParseResult failed(String name, String sourceText) {
        return new ParseResult(new ScriptFile(name, sourceText, java.util.List.of(), java.util.List.of(), false), false);
    }

    public boolean hasNaturalLanguage() {
        return file.hasNaturalLanguage();
    }
}
