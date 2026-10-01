package io.astra.runtime.script;

import io.astra.language.ast.Declaration;
import io.astra.language.ast.ScriptFile;

import java.util.ArrayList;
import java.util.List;

/**
 * The compiled form of one {@code .ar} file.
 *
 * <p>Everything resolvable has been resolved: event classes, action definitions,
 * materials, data keys and function references. The runtime only walks this structure -
 * it never looks at script source again, which is what makes hot reloads and
 * per-event execution cheap.</p>
 */
public record CompiledScript(String name, String sourceHash, List<CompiledRule> rules,
                             List<CompiledCommand> commands, List<CompiledFunction> functions,
                             List<Declaration.Data> data, ScriptFile ast, List<RuleSummary> summaries,
                             List<String> requiredFeatures, long compiledAtMillis) {

    /**
     * Human readable summary of one rule, used by {@code /astra explain}.
     *
     * @param kind        rule kind
     * @param title       short title ("when a player joins")
     * @param details     bullet points describing the behaviour
     * @param natural     true when the rule came from natural language
     * @param span        source location
     */
    public record RuleSummary(RuleKind kind, String title, List<String> details, boolean natural,
                              io.astra.language.ast.Span span) {
        public RuleSummary {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

    public CompiledScript {
        rules = rules == null ? List.of() : List.copyOf(rules);
        commands = commands == null ? List.of() : List.copyOf(commands);
        functions = functions == null ? List.of() : List.copyOf(functions);
        data = data == null ? List.of() : List.copyOf(data);
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
        requiredFeatures = requiredFeatures == null ? List.of() : List.copyOf(requiredFeatures);
    }

    /** Every trigger id referenced by this script's event rules. */
    public List<String> triggerIds() {
        List<String> ids = new ArrayList<>();
        for (CompiledRule rule : rules) {
            if (rule.kind() == RuleKind.EVENT && !ids.contains(rule.triggerId())) ids.add(rule.triggerId());
        }
        return ids;
    }

    public boolean isEmpty() {
        return rules.isEmpty() && commands.isEmpty() && functions.isEmpty();
    }

    /** Total executable rule count (events + timers + commands + functions). */
    public int ruleCount() {
        return rules.size() + commands.size() + functions.size();
    }
}
