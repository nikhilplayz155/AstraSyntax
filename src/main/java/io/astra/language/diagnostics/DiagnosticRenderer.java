package io.astra.language.diagnostics;

import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders diagnostics the way the project specifies: file, line, the offending source
 * line with a caret run underneath, an explanation and suggestions.
 *
 * <pre>
 * AstraSyntax Error
 * File: rewards.ar
 * Line: 12
 *
 *     give player 5 dimonds
 *                   ^^^^^^^
 *
 * Unknown Minecraft material "dimonds".
 *
 * Did you mean:
 *     diamonds
 *
 * Suggested:
 *     give player 5 diamonds
 * </pre>
 */
public final class DiagnosticRenderer {

    private final boolean detailed;
    private final boolean showSuggestions;

    public DiagnosticRenderer(boolean detailed, boolean showSuggestions) {
        this.detailed = detailed;
        this.showSuggestions = showSuggestions;
    }

    /** Render a single diagnostic as a multi-line block. */
    public String render(Diagnostic diagnostic, String sourceText) {
        List<String> lines = renderLines(diagnostic, sourceText);
        return String.join(System.lineSeparator(), lines);
    }

    /** Render a list of diagnostics, most severe first. */
    public String renderAll(List<Diagnostic> diagnostics, String sourceText) {
        List<Diagnostic> ordered = new ArrayList<>(diagnostics);
        ordered.sort((a, b) -> Integer.compare(b.severity().ordinal(), a.severity().ordinal()));
        StringBuilder sb = new StringBuilder();
        for (Diagnostic diagnostic : ordered) {
            sb.append(render(diagnostic, sourceText)).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private List<String> renderLines(Diagnostic diagnostic, String sourceText) {
        List<String> out = new ArrayList<>();
        String header = switch (diagnostic.severity()) {
            case ERROR -> "AstraSyntax Error";
            case WARNING -> "AstraSyntax Warning";
            case INFO -> "AstraSyntax";
        };
        out.add(header);
        out.add("File: " + diagnostic.file());
        if (diagnostic.line() > 0) out.add("Line: " + diagnostic.line());
        out.add("");

        String sourceLine = sourceLine(sourceText, diagnostic.line());
        if (sourceLine != null && !sourceLine.isBlank()) {
            out.add("    " + sourceLine.replace("\t", "    "));
            if (diagnostic.column() > 0) {
                int caretStart = Math.max(0, diagnostic.column() - 1);
                int caretLength = Math.max(1, diagnostic.length());
                StringBuilder caret = new StringBuilder("    ");
                for (int i = 0; i < caretStart && i < sourceLine.length(); i++) {
                    char c = sourceLine.charAt(i);
                    caret.append(c == '\t' ? ' ' : ' ');
                }
                caret.append(Strings.repeat("^", caretLength));
                out.add(caret.toString());
            }
            out.add("");
        }

        out.add(diagnostic.message());
        if (detailed && !diagnostic.explanation().isEmpty()) {
            out.add("");
            out.add(diagnostic.explanation());
        }
        if (showSuggestions && !diagnostic.suggestions().isEmpty()) {
            out.add("");
            out.add(diagnostic.suggestions().size() == 1 ? "Did you mean:" : "Did you mean one of:");
            for (String suggestion : diagnostic.suggestions()) {
                out.add("    " + suggestion);
            }
        }
        if (showSuggestions && !diagnostic.fixedLine().isEmpty()) {
            out.add("");
            out.add("Suggested:");
            out.add("    " + diagnostic.fixedLine());
        }
        return out;
    }

    /** Extract the source line (1-based) from a source text. */
    public static String sourceLine(String sourceText, int line) {
        if (sourceText == null || line <= 0) return null;
        int current = 1;
        int start = 0;
        for (int i = 0; i < sourceText.length(); i++) {
            char c = sourceText.charAt(i);
            if (c == '\n') {
                if (current == line) return sourceText.substring(start, i);
                current++;
                start = i + 1;
            }
        }
        return current == line ? sourceText.substring(start) : null;
    }

    /** One-line console form ({@code [WARN] material: Unknown material "dimonds" (a.ar:12)}). */
    public static String compact(Diagnostic diagnostic) {
        return '[' + diagnostic.severity().name() + "] " + diagnostic.category() + ": "
            + diagnostic.message() + " (" + diagnostic.position() + ")";
    }
}
