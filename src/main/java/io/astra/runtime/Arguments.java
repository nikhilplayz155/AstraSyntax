package io.astra.runtime;

import io.astra.language.ast.Expr;
import io.astra.runtime.expression.ExpressionEvaluator;
import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Typed access to a compiled call's arguments.
 *
 * <p>Executors never touch raw {@link Expr} trees: they ask for a target, a number or a
 * text and get a resolved value, which keeps every built-in action short and keeps
 * coercion rules in one place.</p>
 */
public final class Arguments {

    private final ExecContext context;
    private final Map<String, Expr> expressions;
    private final ExpressionEvaluator evaluator;

    private Arguments(ExecContext context, Map<String, Expr> expressions, ExpressionEvaluator evaluator) {
        this.context = context;
        this.expressions = expressions == null ? Map.of() : expressions;
        this.evaluator = evaluator;
    }

    /** Build the argument view for a call. */
    public static Arguments of(ExecContext context, Map<String, Expr> expressions, ExpressionEvaluator evaluator) {
        return new Arguments(context, expressions, evaluator);
    }

    public ExecContext context() {
        return context;
    }

    public Map<String, Expr> raw() {
        return expressions;
    }

    public boolean has(String name) {
        return expressions.containsKey(name);
    }

    /** The unresolved expression, or {@code null}. */
    public Expr expr(String name) {
        return expressions.get(name);
    }

    /** Evaluate an argument; missing arguments yield {@link Value#NULL}. */
    public Value value(String name) {
        Expr expr = expressions.get(name);
        if (expr == null) return Value.NULL;
        return evaluator.evaluate(expr, context);
    }

    public Value valueOr(String name, Value fallback) {
        Value value = value(name);
        return value.isNull() ? fallback : value;
    }

    /** Text value with placeholders already expanded. */
    public String string(String name) {
        return value(name).asString();
    }

    public String stringOr(String name, String fallback) {
        Value value = value(name);
        return value.isNull() ? fallback : value.asString();
    }

    public long number(String name) {
        return value(name).asLong();
    }

    public long numberOr(String name, long fallback) {
        Value value = value(name);
        return value.isNull() ? fallback : value.asLong();
    }

    public double decimal(String name) {
        return value(name).asDouble();
    }

    public int intOr(String name, int fallback) {
        Value value = value(name);
        return value.isNull() ? fallback : (int) value.asLong();
    }

    public boolean bool(String name) {
        return value(name).asBoolean();
    }

    public boolean boolOr(String name, boolean fallback) {
        Value value = value(name);
        return value.isNull() ? fallback : value.asBoolean();
    }

    /** A single entity: players first, then the actor, then the event's entity. */
    public Entity entity(String name) {
        return Targets.entity(value(name), context);
    }

    /** A single player, or {@code null}. */
    public Player player(String name) {
        return Targets.player(value(name), context);
    }

    /** Every player a target expression refers to. */
    public List<Player> players(String name) {
        return Targets.players(value(name), context);
    }

    public Location location(String name) {
        return Targets.location(value(name), context);
    }

    public World world(String name) {
        return Targets.world(value(name), context);
    }

    public List<Value> list(String name) {
        return value(name).asList();
    }

    /** A material name, or {@code null} when absent/unknown. */
    public String material(String name) {
        Value value = value(name);
        if (value.isNull()) return null;
        if (value instanceof Value.Mat mat) return mat.material();
        String text = value.asString();
        return Strings.isBlank(text) ? null : text;
    }

    /** Amount packed into an item value, or the fallback. */
    public int amount(String name, int fallback) {
        Value value = value(name);
        if (value instanceof Value.Mat mat && mat.amount() > 0) return (int) mat.amount();
        return value.isNull() ? fallback : (int) value.asLong();
    }

    /** All argument names, in declaration order. */
    public List<String> names() {
        return new ArrayList<>(new LinkedHashMap<>(expressions).keySet());
    }
}
