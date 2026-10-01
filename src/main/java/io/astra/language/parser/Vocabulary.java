package io.astra.language.parser;

import io.astra.language.docs.DocEntry;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The vocabulary the parser validates against.
 *
 * <p>The parser itself knows nothing about Minecraft: it asks this interface whether an
 * action, condition, event trigger or material exists and what syntax it accepts. The
 * runtime registries implement it, which is why a module can register an action and
 * have scripts use it immediately - and why the parser can be unit tested with a tiny
 * in-memory vocabulary.</p>
 */
public interface Vocabulary {

    /** Registered action syntax forms (with their ids). */
    List<Entry> actions();

    /** Registered condition syntax forms. */
    List<Entry> conditions();

    /** Registered event trigger patterns. */
    List<EventPattern> eventPatterns();

    /**
     * Registered expression syntax forms.
     *
     * <p>Defaults to none so a small test vocabulary only has to implement what it uses.</p>
     */
    default List<Entry> expressions() {
        return List.of();
    }

    /** True when the material name is known. */
    boolean isMaterial(String name);

    /** True when the entity type name is known. */
    boolean isEntityType(String name);

    /** True when the word is a known world-independent keyword (sound, effect, ...). */
    boolean isKeyword(String category, String name);

    /** Candidate corrections for an unknown token. */
    List<String> materialSuggestions(String name);

    /** Candidate corrections for unknown entity types. */
    List<String> entitySuggestions(String name);

    /** Candidate corrections for an action/condition/event word. */
    List<String> actionSuggestions(String word);

    List<String> conditionSuggestions(String word);

    List<String> eventSuggestions(String phrase);

    /** Documentation for a registered id (may be empty). */
    DocEntry documentation(String id);

    /** True when natural-language rules are allowed in scripts. */
    boolean naturalLanguageEnabled();

    /** True when a script may mix structured and natural-language rules. */
    boolean mixedModeAllowed();

    /**
     * One registered syntax form.
     *
     * @param id       registry id such as {@code tell} or {@code has-permission}
     * @param kind     {@code action}, {@code condition} or {@code expression}
     * @param template the syntax template
     * @param priority higher values are matched first (used to prefer specific forms)
     */
    record Entry(String id, String kind, SyntaxTemplate template, int priority) { }

    /**
     * An event trigger pattern.
     *
     * @param triggerId   the registered event id, for example {@code block break}
     * @param template    the phrase pattern, for example {@code player breaks <block:material>}
     * @param filterSpecs filter specifications such as {@code material:block} or
     *                    {@code is-player:killer}; the part before the colon is the
     *                    event property, the part after it is the slot name (or a
     *                    literal such as {@code player})
     */
    record EventPattern(String triggerId, SyntaxTemplate template, List<String> filterSpecs) {
        public EventPattern {
            filterSpecs = filterSpecs == null ? List.of() : List.copyOf(filterSpecs);
        }
    }

    /** Helpers shared by vocabulary implementations. */
    final class Helper {

        private Helper() {}

        /** Best matches for a mistyped word using the levenshtein helper. */
        public static List<String> suggest(String word, Collection<String> candidates, int limit) {
            if (word == null) return List.of();
            return io.astra.util.Strings.nearest(word.toLowerCase(Locale.ROOT), candidates, limit);
        }
    }
}
