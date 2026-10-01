package io.astra.language.ast;

/**
 * A source location.
 *
 * <p>Lives in the AST rather than in the diagnostics package because every node carries
 * one - that is what lets a runtime failure point back at the exact line of the script
 * that caused it, and what makes {@code /astra errors} useful.</p>
 *
 * @param file    source file name
 * @param line    one-based line (0 when unknown)
 * @param column  one-based column
 * @param length  length of the construct in characters
 * @param flavor  which authoring mode produced the node
 */
public record Span(String file, int line, int column, int length, Flavor flavor) {

    /** Which front end produced a node. */
    public enum Flavor {
        /** Written in the structured Astra language. */
        ASTRA,
        /** Written as an English sentence. */
        NATURAL,
        /** Produced by the compiler itself. */
        GENERATED
    }

    public Span(String file, int line, int column, int length) {
        this(file, line, column, length, Flavor.ASTRA);
    }

    /** An unknown position in a file. */
    public static Span unknown(String file) {
        return new Span(file, 0, 0, 0, Flavor.GENERATED);
    }

    /** The same position, marked as natural language. */
    public Span asNatural() {
        return new Span(file, line, column, length, Flavor.NATURAL);
    }

    /** True when the span points at a real line. */
    public boolean isKnown() {
        return line > 0;
    }

    @Override
    public String toString() {
        return line > 0 ? file + ":" + line + ":" + column : file;
    }
}
