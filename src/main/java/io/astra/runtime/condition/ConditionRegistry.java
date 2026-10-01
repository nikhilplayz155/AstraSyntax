package io.astra.runtime.condition;

import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registry of every condition the language understands. */
public final class ConditionRegistry {

    private final Map<String, ConditionDefinition> definitions = new LinkedHashMap<>();

    public void register(ConditionDefinition definition) {
        if (definition == null) return;
        definitions.put(definition.id().toLowerCase(Locale.ROOT), definition);
    }

    public ConditionDefinition get(String id) {
        return id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean has(String id) {
        return get(id) != null;
    }

    public Collection<ConditionDefinition> all() {
        return definitions.values();
    }

    public Collection<String> ids() {
        return definitions.keySet();
    }

    public int size() {
        return definitions.size();
    }

    public List<String> suggestions(String word) {
        return Strings.nearest(word, definitions.keySet(), 3);
    }

    /** All syntax forms with their owning condition id. */
    public List<io.astra.language.parser.Vocabulary.Entry> syntaxEntries() {
        List<io.astra.language.parser.Vocabulary.Entry> entries = new ArrayList<>();
        for (ConditionDefinition definition : definitions.values()) {
            for (var template : definition.templates()) {
                entries.add(new io.astra.language.parser.Vocabulary.Entry(
                    definition.id(), "condition", template, template.requiredSlots()));
            }
        }
        entries.sort((a, b) -> Integer.compare(b.template().segments().size(), a.template().segments().size()));
        return entries;
    }
}
