package io.astra.runtime.event;

import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.event.Event;

/**
 * Registry of event triggers.
 *
 * <p>Also answers the reverse question the runtime needs: given a Bukkit event class,
 * which trigger ids map to it? That is how the bus decides which listeners are
 * actually needed for the currently loaded scripts and nothing more.</p>
 */
public final class EventRegistry {

    private final Map<String, EventDefinition> definitions = new LinkedHashMap<>();

    public void register(EventDefinition definition) {
        if (definition == null) return;
        definitions.put(definition.id().toLowerCase(Locale.ROOT), definition);
    }

    public EventDefinition get(String id) {
        return id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean has(String id) {
        return get(id) != null;
    }

    public Collection<EventDefinition> all() {
        return definitions.values();
    }

    public Collection<String> ids() {
        return definitions.keySet();
    }

    public int size() {
        return definitions.size();
    }

    public List<String> suggestions(String phrase) {
        List<String> pool = new ArrayList<>();
        for (EventDefinition definition : definitions.values()) {
            pool.add(definition.id());
            for (EventDefinition.TriggerPattern pattern : definition.patterns()) {
                pool.add(pattern.template().source());
            }
        }
        return Strings.nearest(phrase, pool, 3);
    }

    /** Every trigger phrase across all definitions, for the parser. */
    public List<io.astra.language.parser.Vocabulary.EventPattern> vocabularyPatterns() {
        List<io.astra.language.parser.Vocabulary.EventPattern> out = new ArrayList<>();
        for (EventDefinition definition : definitions.values()) {
            out.addAll(definition.vocabularyPatterns());
            // The trigger id is always a valid phrase, so "on block break:" keeps working
            // even when every human-readable phrasing is a verb form ("player breaks ...").
            boolean hasIdPhrase = false;
            for (EventDefinition.TriggerPattern pattern : definition.patterns()) {
                if (pattern.template().source().equalsIgnoreCase(definition.id())) {
                    hasIdPhrase = true;
                    break;
                }
            }
            if (!hasIdPhrase) {
                out.add(new io.astra.language.parser.Vocabulary.EventPattern(definition.id(),
                    io.astra.language.parser.SyntaxTemplate.compile(definition.id()), java.util.List.of()));
            }
        }
        return out;
    }

    /** Event classes that have at least one registered trigger. */
    public Collection<Class<? extends Event>> eventClasses() {
        List<Class<? extends Event>> classes = new ArrayList<>();
        for (EventDefinition definition : definitions.values()) {
            if (!classes.contains(definition.eventClass())) classes.add(definition.eventClass());
        }
        return classes;
    }
}
