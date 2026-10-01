package io.astra.runtime.script;

import io.astra.language.ast.Cond;
import io.astra.language.ast.Expr;
import io.astra.runtime.Arguments;
import io.astra.runtime.ExecContext;
import io.astra.runtime.Value;
import io.astra.runtime.ValueMath;
import io.astra.runtime.condition.ConditionDefinition;
import io.astra.runtime.event.EventPropertyResolver;
import io.astra.runtime.expression.ExpressionEvaluator;

import java.util.List;
import java.util.Map;

/**
 * A condition that has already been resolved against the registries at compile time.
 *
 * <p>Evaluation is short-circuiting: an {@link And} stops at the first false test, an
 * {@link Or} at the first true one, and an event filter is checked before any registry
 * lookup happens. This is what keeps a rule with several guards cheap.</p>
 */
public sealed interface CompiledCondition
    permits CompiledCondition.Test, CompiledCondition.Compare, CompiledCondition.EventProperty,
            CompiledCondition.And, CompiledCondition.Or, CompiledCondition.Not {

    /** Evaluate this condition. */
    boolean test(ExecContext context, ExpressionEvaluator evaluator);

    /** A registry-driven condition. */
    record Test(ConditionDefinition definition, Map<String, Expr> arguments) implements CompiledCondition {
        @Override
        public boolean test(ExecContext context, ExpressionEvaluator evaluator) {
            try {
                return definition.tester().test(context, Arguments.of(context, arguments, evaluator));
            } catch (Exception e) {
                context.services().logger().debug(() -> "Condition '" + definition.id() + "' failed: " + e);
                return false;
            }
        }
    }

    /** A comparison between two expressions. */
    record Compare(Expr left, Cond.Operator operator, Expr right) implements CompiledCondition {
        @Override
        public boolean test(ExecContext context, ExpressionEvaluator evaluator) {
            Value leftValue = evaluator.evaluate(left, context);
            Value rightValue = evaluator.evaluate(right, context);
            return switch (operator) {
                case EQUALS -> ValueMath.equal(leftValue, rightValue);
                case NOT_EQUALS -> !ValueMath.equal(leftValue, rightValue);
                case ABOVE -> ValueMath.compare(leftValue, rightValue) > 0;
                case BELOW -> ValueMath.compare(leftValue, rightValue) < 0;
                case AT_LEAST -> ValueMath.compare(leftValue, rightValue) >= 0;
                case AT_MOST -> ValueMath.compare(leftValue, rightValue) <= 0;
                case CONTAINS -> contains(leftValue, rightValue);
            };
        }

        private static boolean contains(Value container, Value needle) {
            if (container.type() == io.astra.runtime.ValueType.LIST) {
                for (Value element : container.asList()) {
                    if (ValueMath.equal(element, needle)) return true;
                }
                return false;
            }
            return container.asString().contains(needle.asString());
        }
    }

    /**
     * A cheap event filter such as {@code when player breaks diamond ore}.
     *
     * @param property the event property name ({@code material}, {@code entity type}, ...)
     * @param value    the expected value, or {@code null} for a boolean property test
     * @param truthy   when {@code value} is null this is the expected boolean result
     */
    record EventProperty(String property, Expr value, boolean truthy) implements CompiledCondition {

        /** A filter comparing an event property with a value. */
        public static EventProperty equals(String property, Expr value) {
            return new EventProperty(property, value, true);
        }

        /** A boolean filter ("the killer is a player"). */
        public static EventProperty bool(String property, boolean expected) {
            return new EventProperty(property, null, expected);
        }

        @Override
        public boolean test(ExecContext context, ExpressionEvaluator evaluator) {
            if (value == null) {
                return EventPropertyResolver.resolve(context, property).asBoolean() == truthy;
            }
            Value actual = EventPropertyResolver.resolve(context, property);
            Value expected = evaluator.evaluate(value, context);
            return ValueMath.equal(actual, expected);
        }
    }

    /** All sub-conditions must hold (short-circuits). */
    record And(List<CompiledCondition> conditions) implements CompiledCondition {
        @Override
        public boolean test(ExecContext context, ExpressionEvaluator evaluator) {
            for (CompiledCondition condition : conditions) {
                if (!condition.test(context, evaluator)) return false;
            }
            return true;
        }
    }

    /** At least one sub-condition must hold (short-circuits). */
    record Or(List<CompiledCondition> conditions) implements CompiledCondition {
        @Override
        public boolean test(ExecContext context, ExpressionEvaluator evaluator) {
            for (CompiledCondition condition : conditions) {
                if (condition.test(context, evaluator)) return true;
            }
            return false;
        }
    }

    /** Negation. */
    record Not(CompiledCondition condition) implements CompiledCondition {
        @Override
        public boolean test(ExecContext context, ExpressionEvaluator evaluator) {
            return !condition.test(context, evaluator);
        }
    }
}
