package io.astra.language.ast;

import io.astra.runtime.Value;

import java.util.List;
import java.util.Map;

/**
 * A value-producing expression.
 *
 * <p>Expressions are shared by both authoring modes: the structured parser builds them
 * from tokens and the natural-language compiler builds them from sentence fragments, but
 * the runtime only ever sees this one shape.</p>
 */
public sealed interface Expr extends Node {

    /** A literal value. */
    record Lit(Value value, Span span) implements Expr { }

    /** What a bare name refers to. */
    enum RefKind {
        /** The current player or entity - {@code player}, {@code them}, {@code actor}. */
        ACTOR,
        /** {@code everyone} / {@code all players}. */
        ALL_PLAYERS,
        /** {@code a random player}. */
        RANDOM_PLAYER,
        /** {@code world}. */
        WORLD,
        /** {@code console} / {@code server}. */
        CONSOLE,
        /** {@code event} - resolved through the event property resolver. */
        EVENT,
        /** {@code sender} - whoever ran the command. */
        SENDER,
        /** A declared data key used as a bare name ({@code coins}). */
        NAME
    }

    /**
     * A named reference: {@code player}, {@code everyone}, {@code attacker},
     * {@code killer}, {@code victim}, {@code sender}, {@code console}, {@code world}.
     */
    record Ref(String name, RefKind kind, Span span) implements Expr { }

    /** {@code {player}} - resolved through the placeholder service. */
    record Placeholder(String raw, Span span) implements Expr { }

    /** Text with embedded placeholders, for example {@code "Hello {player}!"}. */
    record Interpolated(String template, Span span) implements Expr { }

    /** A declared data value: {@code coins of player} or {@code global coins}. */
    record Data(String key, Expr target, Span span) implements Expr { }

    /** A property: {@code player's health}, {@code health of player}. */
    record Property(Expr target, String property, Span span) implements Expr { }

    /** An item reference: {@code 5 diamonds}. */
    record Item(Expr material, Expr amount, Span span) implements Expr { }

    /** A position: {@code world 10 64 20} or {@code player's location}. */
    record Position(Expr world, Expr x, Expr y, Expr z, Expr yaw, Expr pitch, Span span) implements Expr { }

    /** Arithmetic or text concatenation. */
    record Binary(String operator, Expr left, Expr right, Span span) implements Expr { }

    /** Prefix negation or "not". */
    record Unary(String operator, Expr operand, Span span) implements Expr { }

    /** A comma-separated list. */
    record ListExpr(List<Expr> items, Span span) implements Expr { }

    /** A call to a registered expression, for example {@code random number between 1 and 5}. */
    record Call(String name, Map<String, Expr> arguments, Span span) implements Expr { }

    /** Convenience: the text of a string literal, or empty when it is not one. */
    default String literalText() {
        return this instanceof Lit lit && lit.value().type() == io.astra.runtime.ValueType.STRING
            ? lit.value().asString() : "";
    }
}
