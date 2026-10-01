package io.astra.language.diagnostics;

import java.util.List;

/**
 * A single problem reported by any stage of the pipeline.
 *
 * <p>A diagnostic is deliberately rich: the project treats diagnostics as a feature,
 * so every one carries the source span, a category, an explanation of *why* the code
 * is wrong and - where possible - a machine usable suggestion and a ready-to-copy
 * corrected line.</p>
 *
 * @param severity    how serious the problem is
 * @param category    short machine readable category ({@code material}, {@code syntax}, ...)
 * @param message     one-line human readable description
 * @param file        source file name
 * @param line        1-based line number (0 when unknown)
 * @param column      1-based column number (0 when unknown)
 * @param length      highlighted length in characters
 * @param explanation longer explanation, may be empty
 * @param suggestions possible replacements for the offending token
 * @param fixedLine   a corrected version of the whole line, when it can be produced
 */
public record Diagnostic(Severity severity, String category, String message, String file, int line, int column,
                         int length, String explanation, List<String> suggestions, String fixedLine) {

    public Diagnostic {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        explanation = explanation == null ? "" : explanation;
        fixedLine = fixedLine == null ? "" : fixedLine;
        file = file == null ? "<unknown>" : file;
    }

    public boolean isError() {
        return severity.isError();
    }

    /** Position rendering shared by console output and {@code /astra errors}. */
    public String position() {
        if (line <= 0) return file;
        return file + ":" + line + (column > 0 ? ":" + column : "");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity).append(' ').append(category).append(" at ").append(position()).append(": ").append(message);
        if (!suggestions.isEmpty()) sb.append(" (did you mean ").append(String.join(", ", suggestions)).append("?)");
        return sb.toString();
    }
}
