package io.astra.runtime.vocab;

import io.astra.language.docs.DocEntry;
import io.astra.language.parser.SyntaxTemplate;
import io.astra.language.parser.Vocabulary;
import io.astra.runtime.Registries;
import io.astra.runtime.builtin.MaterialTable;

import java.util.ArrayList;
import java.util.List;

/**
 * The shipped vocabulary: the parser's view of everything the runtime can execute.
 *
 * <p>It is a thin adapter over the registries and the material table, which is the whole
 * point of the design - there is exactly one source of truth for "what can a script say",
 * and the documentation, the diagnostics and module-contributed syntax all read from it.
 * A module that registers an action makes that action parseable immediately, with no
 * parser change and no vocabulary rebuild.</p>
 *
 * <p>{@link #shared()} is used by {@code VocabularyProvider} when a parser is created
 * without an explicit vocabulary (for example by the smoke test); the plugin itself passes
 * the live registries so module additions are visible without restarting.</p>
 */
public final class BuiltinVocabulary implements Vocabulary {

    private static volatile BuiltinVocabulary shared;

    private final Registries registries;
    private final MaterialTable materials;
    private final boolean naturalLanguageEnabled;
    private final boolean mixedModeAllowed;

    public BuiltinVocabulary(Registries registries, MaterialTable materials,
                            boolean naturalLanguageEnabled, boolean mixedModeAllowed) {
        this.registries = registries;
        this.materials = materials == null ? MaterialTable.shared() : materials;
        this.naturalLanguageEnabled = naturalLanguageEnabled;
        this.mixedModeAllowed = mixedModeAllowed;
    }

    /** The shared default vocabulary, created on first use. */
    public static BuiltinVocabulary shared() {
        BuiltinVocabulary current = shared;
        if (current == null) {
            synchronized (BuiltinVocabulary.class) {
                current = shared;
                if (current == null) {
                    current = new BuiltinVocabulary(Registries.builtins(), MaterialTable.shared(), true, true);
                    shared = current;
                }
            }
        }
        return current;
    }

    /** Replace the shared instance (used by tests and by the plugin on reload). */
    public static void setShared(BuiltinVocabulary vocabulary) {
        shared = vocabulary;
    }

    /** The registries behind this vocabulary. */
    public Registries registries() {
        return registries;
    }

    // ------------------------------------------------------------ registrations

    @Override
    public List<Entry> actions() {
        return new ArrayList<>(registries.actions().syntaxEntries());
    }

    @Override
    public List<Entry> conditions() {
        return new ArrayList<>(registries.conditions().syntaxEntries());
    }

    @Override
    public List<EventPattern> eventPatterns() {
        return new ArrayList<>(registries.events().vocabularyPatterns());
    }

    /** Expression syntax forms, used by the parser when it meets a call-like fragment. */
    public List<Entry> expressions() {
        return new ArrayList<>(registries.expressions().syntaxEntries());
    }

    /** Every syntax form, in documentation order. */
    public List<Entry> allEntries() {
        List<Entry> entries = new ArrayList<>();
        entries.addAll(actions());
        entries.addAll(conditions());
        entries.addAll(expressions());
        return entries;
    }

    /** The raw templates of one registry id (used by {@code /astra explain}). */
    public List<SyntaxTemplate> templatesFor(String id) {
        List<SyntaxTemplate> templates = new ArrayList<>();
        if (id == null) return templates;
        if (registries.actions().has(id)) templates.addAll(registries.actions().get(id).templates());
        if (registries.conditions().has(id)) templates.addAll(registries.conditions().get(id).templates());
        var expression = registries.expressions().get(id);
        if (expression != null) templates.addAll(expression.templates());
        return templates;
    }

    // --------------------------------------------------------------- materials

    @Override
    public boolean isMaterial(String name) {
        return materials.isMaterial(name);
    }

    @Override
    public boolean isEntityType(String name) {
        return materials.isEntityType(name);
    }

    @Override
    public boolean isKeyword(String category, String name) {
        return materials.isKeyword(category, name);
    }

    @Override
    public List<String> materialSuggestions(String name) {
        return materials.suggestions(name);
    }

    @Override
    public List<String> entitySuggestions(String name) {
        return materials.entitySuggestions(name);
    }

    // -------------------------------------------------------------- suggestions

    @Override
    public List<String> actionSuggestions(String word) {
        return registries.actions().suggestions(word);
    }

    @Override
    public List<String> conditionSuggestions(String word) {
        return registries.conditions().suggestions(word);
    }

    @Override
    public List<String> eventSuggestions(String phrase) {
        return registries.events().suggestions(phrase);
    }

    @Override
    public DocEntry documentation(String id) {
        DocEntry entry = registries.docs().get(id);
        if (entry != null) return entry;
        // Fall back to the registries so an id that was never mirrored into the doc
        // registry still renders something useful instead of "unknown".
        if (registries.actions().has(id)) return registries.actions().get(id).doc();
        if (registries.conditions().has(id)) return registries.conditions().get(id).doc();
        var expression = registries.expressions().get(id);
        if (expression != null) return expression.doc();
        var event = registries.events().get(id);
        if (event != null) return event.doc();
        return null;
    }

    // ------------------------------------------------------------------ modes

    @Override
    public boolean naturalLanguageEnabled() {
        return naturalLanguageEnabled;
    }

    @Override
    public boolean mixedModeAllowed() {
        return mixedModeAllowed;
    }

    /** Documentation for every registered id, used by {@code /astra info <id>} listings. */
    public List<String> documentedIds() {
        List<String> ids = new ArrayList<>(registries.docs().ids());
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }
}
