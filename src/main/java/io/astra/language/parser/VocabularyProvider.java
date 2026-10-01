package io.astra.language.parser;

/**
 * Holds the vocabulary used by parsers that were not given one explicitly.
 *
 * <p>The shipped smoke test (and any code that just wants to parse a script) can call
 * {@code new AstraParser(diagnostics)}; the built-in vocabulary is created lazily on
 * first use so the language layer never forces the runtime layer to initialise.</p>
 */
public final class VocabularyProvider {

    private static volatile Vocabulary defaultVocabulary;

    private VocabularyProvider() {}

    /** The shared built-in vocabulary. */
    public static Vocabulary defaultVocabulary() {
        Vocabulary current = defaultVocabulary;
        if (current == null) {
            synchronized (VocabularyProvider.class) {
                current = defaultVocabulary;
                if (current == null) {
                    current = createDefault();
                    defaultVocabulary = current;
                }
            }
        }
        return current;
    }

    /** Replace the shared vocabulary (used by the plugin and by tests). */
    public static void setDefaultVocabulary(Vocabulary vocabulary) {
        defaultVocabulary = vocabulary;
    }

    private static Vocabulary createDefault() {
        try {
            return io.astra.runtime.vocab.BuiltinVocabulary.shared();
        } catch (Throwable error) {
            return new EmptyVocabulary();
        }
    }

    /** A vocabulary that accepts everything; used when the runtime is unavailable. */
    public static final class EmptyVocabulary implements Vocabulary {

        @Override public java.util.List<Entry> actions() { return java.util.List.of(); }

        @Override public java.util.List<Entry> conditions() { return java.util.List.of(); }

        @Override public java.util.List<EventPattern> eventPatterns() { return java.util.List.of(); }

        @Override public boolean isMaterial(String name) { return true; }

        @Override public boolean isEntityType(String name) { return true; }

        @Override public boolean isKeyword(String category, String name) { return true; }

        @Override public java.util.List<String> materialSuggestions(String name) { return java.util.List.of(); }

        @Override public java.util.List<String> entitySuggestions(String name) { return java.util.List.of(); }

        @Override public java.util.List<String> actionSuggestions(String word) { return java.util.List.of(); }

        @Override public java.util.List<String> conditionSuggestions(String word) { return java.util.List.of(); }

        @Override public java.util.List<String> eventSuggestions(String phrase) { return java.util.List.of(); }

        @Override public io.astra.language.docs.DocEntry documentation(String id) { return null; }

        @Override public boolean naturalLanguageEnabled() { return true; }

        @Override public boolean mixedModeAllowed() { return true; }
    }
}
