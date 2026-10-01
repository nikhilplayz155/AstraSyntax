package io.astra.language.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates diagnostics for one script/compilation unit.
 *
 * <p>Every stage (lexer, parser, validator, compiler) writes into the same collector,
 * which is why a single compile can report several independent problems instead of
 * stopping at the first one. Fixing one error never reveals a surprise second error
 * on the next run.</p>
 */
public final class DiagnosticCollector {

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private String file = "<script>";

    /** Set the file name stamped on subsequently recorded diagnostics. */
    public void setFile(String fileName) {
        this.file = fileName == null ? "<script>" : fileName;
    }

    public String file() {
        return file;
    }

    public void add(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    public void info(String message, int line, int column) {
        add(new Diagnostic(Severity.INFO, "info", message, file, line, column, 0, "", List.of(), ""));
    }

    public void warn(String message, int line, int column) {
        add(new Diagnostic(Severity.WARNING, "warning", message, file, line, column, 0, "", List.of(), ""));
    }

    public void error(String message, int line, int column, String explanation) {
        add(new Diagnostic(Severity.ERROR, "error", message, file, line, column, 0, explanation, List.of(), ""));
    }

    /** Convenience overload kept for compatibility with the shipped smoke test. */
    public void error(String message, int line, int column, String explanation, List<String> suggestions) {
        add(new Diagnostic(Severity.ERROR, "error", message, file, line, column, 0, explanation, suggestions, ""));
    }

    public void error(Diagnostic diagnostic) {
        add(diagnostic);
    }

    /** Only the errors. */
    public List<Diagnostic> errors() {
        List<Diagnostic> out = new java.util.ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.isError()) out.add(diagnostic);
        }
        return out;
    }

    /** Only the warnings. */
    public List<Diagnostic> warnings() {
        List<Diagnostic> out = new java.util.ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == io.astra.language.diagnostics.Severity.WARNING) out.add(diagnostic);
        }
        return out;
    }

    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }

    public boolean hasErrors() {
        for (Diagnostic diagnostic : diagnostics) if (diagnostic.isError()) return true;
        return false;
    }

    public int errorCount() {
        int count = 0;
        for (Diagnostic diagnostic : diagnostics) if (diagnostic.isError()) count++;
        return count;
    }

    public int warningCount() {
        int count = 0;
        for (Diagnostic diagnostic : diagnostics) if (diagnostic.severity() == Severity.WARNING) count++;
        return count;
    }

    public void clear() {
        diagnostics.clear();
    }

    /** Plain text rendering used when no source text is available. */
    public String format() {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic diagnostic : diagnostics) {
            sb.append('[').append(diagnostic.severity()).append("] ")
              .append(diagnostic.message()).append(" (").append(diagnostic.position()).append(')');
            if (!diagnostic.suggestions().isEmpty()) {
                sb.append(" -> did you mean ").append(String.join(", ", diagnostic.suggestions())).append('?');
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
