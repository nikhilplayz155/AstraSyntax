package io.astra.language.diagnostics;

/** Diagnostic severities shared by the lexer, parser, compiler and runtime checks. */
public enum Severity {

    /** Information only; never blocks loading. */
    INFO,
    /** Suspicious but loadable (unknown placeholder, deprecated syntax, ...). */
    WARNING,
    /** The unit cannot be used; the parser/compiler produced no rule for it. */
    ERROR;

    public boolean isError() {
        return this == ERROR;
    }
}
