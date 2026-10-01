package io.astra.runtime;

import io.astra.language.docs.DocRegistry;
import io.astra.runtime.action.ActionRegistry;
import io.astra.runtime.condition.ConditionRegistry;
import io.astra.runtime.event.EventRegistry;
import io.astra.runtime.expression.ExpressionRegistry;
import io.astra.runtime.expression.PlaceholderService;

/**
 * Bundle of every runtime registry.
 *
 * <p>One object is passed around instead of five, which keeps constructor signatures
 * small and makes "register a module vocabulary" a single operation.</p>
 */
public record Registries(EventRegistry events, ActionRegistry actions, ConditionRegistry conditions,
                         ExpressionRegistry expressions, DocRegistry docs, PlaceholderService placeholders) {

    /** A fully populated set of built-in registries. */
    public static Registries builtins() {
        return io.astra.runtime.builtin.Builtins.create();
    }
}
