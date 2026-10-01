package io.astra.runtime.script;

import io.astra.language.ast.Span;

import java.util.List;

import org.bukkit.event.Event;

/**
 * A compiled event or timer rule.
 *
 * @param id          unique id across all scripts ({@code script:line})
 * @param kind        what triggered this rule
 * @param triggerId   the registered trigger id
 * @param name        display name used by traces and {@code /astra explain}
 * @param filters     cheap event filters checked before anything else
 * @param conditions  guards inside the rule
 * @param body        compiled statements
 * @param eventClass  the Bukkit event class (null for timers/manual rules)
 * @param periodTicks timer period in ticks (timers only)
 * @param natural     true when the rule came from a natural-language sentence
 * @param description human readable summary produced by the compiler
 * @param span        source location
 */
public record CompiledRule(String id, RuleKind kind, String triggerId, String name, List<CompiledCondition> filters,
                           List<CompiledCondition> conditions, List<CompiledStmt> body,
                           Class<? extends Event> eventClass, long periodTicks, boolean natural, String description,
                           Span span) {

    public CompiledRule {
        filters = filters == null ? List.of() : List.copyOf(filters);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        body = body == null ? List.of() : List.copyOf(body);
    }

    /** The script this rule belongs to, derived from the id prefix. */
    public String scriptName() {
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(0, separator);
    }
}
