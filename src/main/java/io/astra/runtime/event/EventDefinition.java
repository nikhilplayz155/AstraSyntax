package io.astra.runtime.event;

import io.astra.language.docs.DocEntry;
import io.astra.language.docs.DocKind;
import io.astra.language.parser.SyntaxTemplate;
import io.astra.language.parser.Vocabulary;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;

/**
 * A registered event trigger.
 *
 * @param id           stable trigger id ({@code block break}, {@code player join})
 * @param eventClass   the Bukkit event this trigger listens to
 * @param adapter      actor/property extraction for the event
 * @param patterns     trigger phrases the parser matches ("player breaks &lt;block:material&gt;")
 * @param priority     default listener priority
 * @param ignoreCancelled whether cancelled events should be skipped by default
 * @param doc          documentation metadata
 */
public record EventDefinition(String id, Class<? extends Event> eventClass, EventAdapter adapter,
                              List<TriggerPattern> patterns, EventPriority priority, boolean ignoreCancelled,
                              DocEntry doc) {

    public EventDefinition {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        if (doc == null) doc = DocEntry.of(id, DocKind.EVENT, "event " + id).build();
    }

    /**
     * One trigger phrase.
     *
     * @param template    the phrase template ("player breaks &lt;block:material&gt;")
     * @param filterSpecs filter bindings: {@code material:block} binds the event's
     *                    {@code material} property to the template slot {@code block};
     *                    {@code is-player:killer} binds a boolean property
     */
    public record TriggerPattern(SyntaxTemplate template, List<String> filterSpecs) {

        public TriggerPattern {
            filterSpecs = filterSpecs == null ? List.of() : List.copyOf(filterSpecs);
        }
    }

    /** Fluent builder used by the built-in registration code. */
    public static Builder builder(String id, Class<? extends Event> eventClass, String description) {
        return new Builder(id, eventClass, description);
    }

    /** Builds an {@link EventDefinition}. */
    public static final class Builder {
        private final String id;
        private final Class<? extends Event> eventClass;
        private final String description;
        private final List<TriggerPattern> patterns = new ArrayList<>();
        private final List<String> examples = new ArrayList<>();
        private EventAdapter adapter = EventAdapter.ofPlayer();
        private EventPriority priority = EventPriority.NORMAL;
        private boolean ignoreCancelled = true;
        private String since = "1.0";

        private Builder(String id, Class<? extends Event> eventClass, String description) {
            this.id = id;
            this.eventClass = eventClass;
            this.description = description;
        }

        /** Register a trigger phrase with optional filter bindings. */
        public Builder trigger(String template, String... filterSpecs) {
            patterns.add(new TriggerPattern(SyntaxTemplate.compile(template), List.of(filterSpecs)));
            return this;
        }

        public Builder adapter(EventAdapter adapter) {
            this.adapter = adapter;
            return this;
        }

        public Builder priority(EventPriority priority) {
            this.priority = priority;
            return this;
        }

        public Builder ignoreCancelled(boolean value) {
            this.ignoreCancelled = value;
            return this;
        }

        public Builder example(String example) {
            this.examples.add(example);
            return this;
        }

        public Builder since(String version) {
            this.since = version;
            return this;
        }

        public EventDefinition build() {
            DocEntry.Builder doc = DocEntry.of(id, DocKind.EVENT, description).since(since);
            for (TriggerPattern pattern : patterns) doc.syntax("on " + pattern.template().source());
            for (String example : examples) doc.example(example);
            return new EventDefinition(id, eventClass, adapter, patterns, priority, ignoreCancelled, doc.build());
        }
    }

    /** Vocabulary view of this definition's trigger patterns. */
    public List<Vocabulary.EventPattern> vocabularyPatterns() {
        List<Vocabulary.EventPattern> out = new ArrayList<>();
        for (TriggerPattern pattern : patterns) {
            out.add(new Vocabulary.EventPattern(id, pattern.template(), pattern.filterSpecs()));
        }
        return out;
    }
}
