package io.astra.language.docs;

/** Kinds of documented language elements. */
public enum DocKind {
    EVENT,
    ACTION,
    CONDITION,
    EXPRESSION,
    DATA_TYPE,
    PLACEHOLDER,
    STATEMENT;

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
