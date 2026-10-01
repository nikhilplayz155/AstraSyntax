package io.astra.runtime.expression;

import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registry of expression providers. */
public final class ExpressionRegistry {

    private final Map<String, ExpressionDefinition> definitions = new LinkedHashMap<>();

    public void register(ExpressionDefinition definition) {
        if (definition == null) return;
        definitions.put(definition.id().toLowerCase(Locale.ROOT), definition);
    }

    /** True when an expression with this id is registered. */
    public boolean has(String id) {
        return get(id) != null;
    }

    public ExpressionDefinition get(String id) {
        return id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<ExpressionDefinition> all() {
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

    public List<io.astra.language.parser.Vocabulary.Entry> syntaxEntries() {
        List<io.astra.language.parser.Vocabulary.Entry> entries = new ArrayList<>();
        for (ExpressionDefinition definition : definitions.values()) {
            for (var template : definition.templates()) {
                entries.add(new io.astra.language.parser.Vocabulary.Entry(
                    definition.id(), "expression", template, template.requiredSlots()));
            }
        }
        entries.sort((a, b) -> Integer.compare(b.template().segments().size(), a.template().segments().size()));
        return entries;
    }
}
