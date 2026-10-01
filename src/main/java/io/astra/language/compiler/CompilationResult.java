package io.astra.language.compiler;

import io.astra.language.diagnostics.Diagnostic;
import io.astra.language.diagnostics.DiagnosticCollector;
import io.astra.runtime.script.CompiledRule;
import io.astra.runtime.script.CompiledScript;

import java.util.List;

/**
 * The outcome of compiling one script.
 *
 * <p>A failed compile still carries whatever was compiled successfully: {@code /astra check}
 * shows the diagnostics and the script manager keeps the previous version active, so a
 * partial result is useful rather than dangerous. Callers that intend to activate the
 * result must check {@link #success()} first.</p>
 *
 * @param script      the compiled script, or {@code null} when nothing could be compiled
 * @param errors      compile errors
 * @param warnings    compile warnings (do not block activation)
 * @param diagnostics the raw collector, used by {@code /astra explain} to render excerpts
 * @param sourceHash  hash of the source the result was produced from, for the compile cache
 */
public record CompilationResult(CompiledScript script, List<Diagnostic> errors, List<Diagnostic> warnings,
                                DiagnosticCollector diagnostics, String sourceHash) {

    public CompilationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** A result that could not be compiled at all. */
    public static CompilationResult failure(DiagnosticCollector diagnostics, String sourceHash) {
        List<Diagnostic> errors = diagnostics == null ? List.of() : diagnostics.errors();
        return new CompilationResult(null, errors, List.of(), diagnostics, sourceHash);
    }

    /** Every compiled rule (events and timers). */
    public List<CompiledRule> rules() {
        return script == null ? List.of() : script.rules();
    }

    /** True when the script may be activated. */
    public boolean success() {
        return script != null && errors.isEmpty();
    }

    /** True when the compile produced warnings the author should look at. */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /** The script, or an exception when the compile failed. */
    public CompiledScript requireScript() {
        if (script == null) {
            throw new IllegalStateException("The script did not compile: " + errors);
        }
        return script;
    }

    /** Rendered diagnostics, ready for the console or chat. */
    public String formatDiagnostics() {
        return diagnostics == null ? "" : diagnostics.format();
    }

    @Override
    public String toString() {
        return success()
            ? "compiled " + (script == null ? 0 : script.ruleCount()) + " rule(s), " + warnings.size() + " warning(s)"
            : "failed with " + errors.size() + " error(s)";
    }
}
