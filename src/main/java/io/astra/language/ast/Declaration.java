package io.astra.language.ast;

import io.astra.runtime.Value;
import io.astra.runtime.ValueType;

import java.util.List;

/**
 * A top-level declaration in a script.
 *
 * <p>The AST keeps the two authoring modes visible: a natural-language rule is kept as
 * {@link Natural} alongside the structured declarations it produced, so
 * {@code /astra explain} can show both the sentence the author wrote and the rule that
 * actually runs.</p>
 */
public sealed interface Declaration extends Node
    permits Declaration.Event, Declaration.Command, Declaration.Function, Declaration.Timer, Declaration.Data,
        Declaration.Natural {

    /** One line describing the declaration. */
    String describe();

    /** A trigger with a body: {@code on player join: ...}. */
    record Event(String triggerId, List<Cond> filters, Stmt.Block body, int priority, Span span) implements Declaration {
        public Event {
            filters = filters == null ? List.of() : List.copyOf(filters);
        }

        @Override public String describe() {
            return "when " + triggerId;
        }
    }

    /** A command parameter taken from the command line. */
    record CommandArgument(String name, String type, boolean optional, boolean greedy) { }

    /** A custom command: {@code command /heal: ...}. */
    record Command(String name, List<String> aliases, String permission, boolean playerOnly, boolean consoleAllowed,
                   String description, String usage, List<CommandArgument> arguments, long cooldownTicks,
                   String cooldownBypassPermission, Stmt.Block body, Span span) implements Declaration {
        public Command {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        @Override public String describe() {
            return "command /" + name;
        }
    }

    /** A reusable action parameter. */
    record Parameter(String name, ValueType type, boolean optional, Value defaultValue) { }

    /** A reusable action: {@code action welcome(player): ...}. */
    record Function(String name, List<Parameter> parameters, Stmt.Block body, Span span) implements Declaration {
        public Function {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }

        @Override public String describe() {
            return "action " + name;
        }
    }

    /** A repeating task: {@code every 10 minutes: ...}. */
    record Timer(long periodTicks, String rawPeriod, Stmt.Block body, Span span) implements Declaration {
        @Override public String describe() {
            return "every " + rawPeriod;
        }
    }

    /** A declared data key: {@code data coins: number = 0}. */
    record Data(String key, ValueType type, Value defaultValue, boolean persistent, boolean perPlayer,
                Span span) implements Declaration {
        @Override public String describe() {
            return "data " + key;
        }
    }

    /**
     * A natural-language sentence and the declarations it compiled to.
     *
     * <p>{@code compiled} is produced while the script is loaded, never while an event
     * runs - the runtime only ever sees the compiled rules.</p>
     */
    record Natural(String sentence, List<Declaration> compiled, String pattern, Span span) implements Declaration {
        public Natural {
            compiled = compiled == null ? List.of() : List.copyOf(compiled);
        }

        @Override public String describe() {
            return "natural rule";
        }
    }
}
