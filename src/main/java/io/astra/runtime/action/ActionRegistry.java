package io.astra.runtime.action;

import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of every action the language understands.
 *
 * <p>Registration is the only thing that makes an action usable from a script: the
 * parser reads syntax forms from here, the compiler resolves ids from here and the
 * runtime executes executors from here. Core and modules therefore go through exactly
 * the same path.</p>
 */
public final class ActionRegistry {

    private final Map<String, ActionDefinition> definitions = new LinkedHashMap<>();

    public void register(ActionDefinition definition) {
        if (definition == null) return;
        definitions.put(definition.id().toLowerCase(Locale.ROOT), definition);
    }

    public ActionDefinition get(String id) {
        return id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean has(String id) {
        return get(id) != null;
    }

    public Collection<ActionDefinition> all() {
        return definitions.values();
    }

    public Collection<String> ids() {
        return definitions.keySet();
    }

    public int size() {
        return definitions.size();
    }

    /** Action ids that look like the mistyped word (used by diagnostics). */
    public List<String> suggestions(String word) {
        return Strings.nearest(word, definitions.keySet(), 3);
    }

    /** All syntax forms with their owning action id. */
    public List<io.astra.language.parser.Vocabulary.Entry> syntaxEntries() {
        List<io.astra.language.parser.Vocabulary.Entry> entries = new ArrayList<>();
        for (ActionDefinition definition : definitions.values()) {
            for (var template : definition.templates()) {
                entries.add(new io.astra.language.parser.Vocabulary.Entry(
                    definition.id(), "action", template, template.requiredSlots()));
            }
        }
        // Longer, more specific forms are matched first.
        entries.sort((a, b) -> Integer.compare(b.template().segments().size(), a.template().segments().size()));
        return entries;
    }
}
