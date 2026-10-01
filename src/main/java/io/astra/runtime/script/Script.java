package io.astra.runtime.script;

import io.astra.language.ast.Declaration;
import io.astra.language.ast.ScriptFile;
import io.astra.language.diagnostics.Diagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@code .ar} script and its lifecycle.
 *
 * <p>A script always keeps the last successfully compiled version. If a reload fails,
 * the previous version stays active - the single most important property of the hot
 * reload design, because a typo must never take a running system offline.</p>
 */
public final class Script {

    private final String name;
    private final Path path;
    private final ScriptStats stats = new ScriptStats();
    private final long discoveredAtMillis = System.currentTimeMillis();

    private volatile ScriptState state = ScriptState.DISCOVERED;
    private volatile CompiledScript active;
    private volatile CompiledScript previous;
    private volatile String source = "";
    private volatile String sourceHash = "";
    private volatile List<Diagnostic> diagnostics = List.of();
    private volatile ScriptFile ast;
    private volatile long lastReloadMillis;
    private volatile String lastError = "";
    private volatile String packageName;

    public Script(String name, Path path) {
        this.name = name;
        this.path = path;
    }

    /** The script name without extension (for example {@code welcome}). */
    public String name() {
        return name;
    }

    public Path path() {
        return path;
    }

    public ScriptState state() {
        return state;
    }

    public void setState(ScriptState state) {
        this.state = state;
    }

    /** The compiled version currently in use. */
    public CompiledScript active() {
        return active;
    }

    /** The version that was active before the most recent successful compile. */
    public CompiledScript previous() {
        return previous;
    }

    public void activate(CompiledScript compiled) {
        this.previous = this.active;
        this.active = compiled;
    }

    /** Keep a compiled version without activating it (used by {@code /astra check}). */
    public CompiledScript staging() {
        return active;
    }

    public String source() {
        return source;
    }

    public void setSource(String source) {
        this.source = source == null ? "" : source;
        this.sourceHash = io.astra.util.Hash.sha256(this.source);
    }

    public String sourceHash() {
        return sourceHash;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public void setDiagnostics(List<Diagnostic> diagnostics) {
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public ScriptFile ast() {
        return ast;
    }

    public void setAst(ScriptFile ast) {
        this.ast = ast;
    }

    public ScriptStats stats() {
        return stats;
    }

    public long discoveredAtMillis() {
        return discoveredAtMillis;
    }

    public long lastReloadMillis() {
        return lastReloadMillis;
    }

    public void markReloaded() {
        this.lastReloadMillis = System.currentTimeMillis();
    }

    public String lastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError == null ? "" : lastError;
    }

    public String packageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    /** The data keys this script declares, including the ones implied by natural language. */
    public List<Declaration.Data> dataDeclarations() {
        CompiledScript compiled = active;
        if (compiled == null) return List.of();
        return compiled.data();
    }

    /** Every trigger id the active version listens to. */
    public List<String> activeTriggers() {
        CompiledScript compiled = active;
        return compiled == null ? List.of() : compiled.triggerIds();
    }

    /** Human readable one-line status. */
    public String describe() {
        CompiledScript compiled = active;
        int rules = compiled == null ? 0 : compiled.ruleCount();
        return name + " [" + state.name().toLowerCase(java.util.Locale.ROOT) + "] "
            + rules + " rule(s), " + stats.invocations() + " invocation(s)";
    }

    /** A copy of the diagnostics for the last failed load. */
    public List<Diagnostic> errors() {
        List<Diagnostic> errors = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.isError()) errors.add(diagnostic);
        }
        return errors;
    }

    @Override
    public String toString() {
        return name + ":" + state;
    }
}
