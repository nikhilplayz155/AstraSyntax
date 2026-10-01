package io.astra.language.ast;

import java.util.List;
import java.util.Map;

/**
 * A condition: the gate between a trigger firing and a body running.
 *
 * <p>{@link EventProperty} exists separately from {@link Test} on purpose: it is the only
 * kind that can be evaluated without touching a registry, so the compiler is able to
 * verify that a trigger's cheap pre-filters are type-compatible with the event
 * ("diamond ore" for a break trigger) instead of failing on the first event.</p>
 */
public sealed interface Cond extends Node {

    /** A registered condition such as {@code has-permission} or {@code data-is}. */
    record Test(String id, Map<String, Expr> arguments, Span span) implements Cond { }

    /** A comparison between two expressions. */
    record Compare(Expr left, Operator operator, Expr right, Span span) implements Cond { }

    /** A cheap event filter, for example {@code material = diamond ore}. */
    record EventProperty(String property, Expr value, Span span) implements Cond { }

    /** All conditions must hold. */
    record And(List<Cond> conditions, Span span) implements Cond {
        public And {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }

    /** At least one condition must hold. */
    record Or(List<Cond> conditions, Span span) implements Cond {
        public Or {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }

    /** Inverts a condition. */
    record Not(Cond condition, Span span) implements Cond { }

    /** Comparison operators written by a script author. */
    enum Operator {
        EQUALS, NOT_EQUALS, ABOVE, BELOW, AT_LEAST, AT_MOST, CONTAINS;

        /** The words this operator is written with, longest first. */
        public static String[] phrases(Operator operator) {
            return switch (operator) {
                case EQUALS -> new String[] {"is equal to", "is exactly", "equals", "is"};
                case NOT_EQUALS -> new String[] {"is not equal to", "is not", "isn't", "does not equal"};
                case ABOVE -> new String[] {"is greater than", "is more than", "is above", "above",
                    "greater than", "more than"};
                case BELOW -> new String[] {"is less than", "is fewer than", "is below", "below", "less than",
                    "fewer than", "under"};
                case AT_LEAST -> new String[] {"is at least", "at least", "minimum of", "or more"};
                case AT_MOST -> new String[] {"is at most", "at most", "maximum of", "or less"};
                case CONTAINS -> new String[] {"contains", "includes", "has inside"};
            };
        }

        /** True when this operator compares magnitudes. */
        public boolean isOrdering() {
            return this == ABOVE || this == BELOW || this == AT_LEAST || this == AT_MOST;
        }
    }
}
