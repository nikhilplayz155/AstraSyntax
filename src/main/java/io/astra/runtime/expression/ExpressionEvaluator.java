package io.astra.runtime.expression;

import io.astra.data.DataStore;
import io.astra.language.ast.Expr;
import io.astra.runtime.Arguments;
import io.astra.runtime.ExecContext;
import io.astra.runtime.RuntimeServices;
import io.astra.runtime.Targets;
import io.astra.runtime.Value;
import io.astra.runtime.ValueMath;
import io.astra.runtime.ValueType;
import io.astra.runtime.event.EventPropertyResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Evaluates the compiled expression tree.
 *
 * <p>This class is the only place that knows how an {@link Expr} becomes a
 * {@link Value}. It runs on already-resolved nodes (no parsing, no string lookups for
 * materials), so a condition such as {@code if player has 500 coins} costs a cache
 * lookup and an integer comparison.</p>
 */
public final class ExpressionEvaluator {

    private final RuntimeServices services;

    public ExpressionEvaluator(RuntimeServices services) {
        this.services = services;
    }

    public RuntimeServices services() {
        return services;
    }

    /** Evaluate an expression, never throwing: failures degrade to {@link Value#NULL}. */
    public Value evaluate(Expr expr, ExecContext context) {
        try {
            return eval(expr, context);
        } catch (ActionFailureCarrier carrier) {
            throw carrier.failure;
        } catch (RuntimeException e) {
            services.logger().debug(() -> "Expression failed: " + expr + " -> " + e);
            return Value.NULL;
        }
    }

    private Value eval(Expr expr, ExecContext context) {
        if (expr == null) return Value.NULL;
        if (expr instanceof Expr.Lit lit) return lit.value();
        if (expr instanceof Expr.Ref ref) return evalRef(ref, context);
        if (expr instanceof Expr.Placeholder placeholder) {
            return services.placeholders().value(placeholder.raw(), context);
        }
        if (expr instanceof Expr.Interpolated interpolated) {
            return Value.str(services.placeholders().resolve(interpolated.template(), context));
        }
        if (expr instanceof Expr.Property property) {
            // "damage of event" and friends resolve straight from the event.
            if (property.target() instanceof Expr.Ref ref && ref.kind() == Expr.RefKind.EVENT) {
                return EventPropertyResolver.resolve(context, property.property());
            }
            Value target = eval(property.target(), context);
            return Properties.resolve(target, property.property(), context);
        }
        if (expr instanceof Expr.Data data) {
            return evalData(data, context);
        }
        if (expr instanceof Expr.Item item) {
            Value materialValue = eval(item.material(), context);
            int amount = item.amount() == null ? 1 : (int) eval(item.amount(), context).asLong();
            String material = materialValue instanceof Value.Mat mat ? mat.material() : materialValue.asString();
            return Value.material(material, Math.max(1, amount));
        }
        if (expr instanceof Expr.Position position) {
            String world = position.world() == null ? (context.world() == null ? "world" : context.world().getName())
                : eval(position.world(), context).asString();
            double x = number(position.x(), context);
            double y = number(position.y(), context);
            double z = number(position.z(), context);
            float yaw = position.yaw() == null ? 0f : (float) number(position.yaw(), context);
            float pitch = position.pitch() == null ? 0f : (float) number(position.pitch(), context);
            return Value.location(world, x, y, z, yaw, pitch);
        }
        if (expr instanceof Expr.Binary binary) {
            Value left = eval(binary.left(), context);
            String operator = binary.operator();
            if (operator.equals("and") || operator.equals("&&")) {
                return Value.bool(left.asBoolean() && eval(binary.right(), context).asBoolean());
            }
            if (operator.equals("or") || operator.equals("||")) {
                return Value.bool(left.asBoolean() || eval(binary.right(), context).asBoolean());
            }
            Value right = eval(binary.right(), context);
            return switch (operator) {
                case "+" -> ValueMath.add(left, right);
                case "-" -> ValueMath.subtract(left, right);
                case "*" -> ValueMath.multiply(left, right);
                case "/" -> ValueMath.divide(left, right);
                case "%" -> ValueMath.modulo(left, right);
                case "==", "is" -> Value.bool(ValueMath.equal(left, right));
                case "!=", "is not" -> Value.bool(!ValueMath.equal(left, right));
                case ">", "is above" -> Value.bool(ValueMath.compare(left, right) > 0);
                case "<", "is below" -> Value.bool(ValueMath.compare(left, right) < 0);
                case ">=", "at least" -> Value.bool(ValueMath.compare(left, right) >= 0);
                case "<=", "at most" -> Value.bool(ValueMath.compare(left, right) <= 0);
                default -> Value.NULL;
            };
        }
        if (expr instanceof Expr.Unary unary) {
            Value operand = eval(unary.operand(), context);
            return switch (unary.operator()) {
                case "not", "!" -> Value.bool(!operand.asBoolean());
                case "-" -> ValueMath.negate(operand);
                default -> operand;
            };
        }
        if (expr instanceof Expr.ListExpr list) {
            List<Value> values = new ArrayList<>(list.items().size());
            for (Expr item : list.items()) values.add(eval(item, context));
            return Value.list(values);
        }
        if (expr instanceof Expr.Call call) {
            var definition = services.registries().expressions().get(call.name());
            if (definition == null) return Value.NULL;
            Arguments arguments = Arguments.of(context, call.arguments(), this);
            try {
                Value value = definition.evaluator().evaluate(context, arguments);
                return value == null ? Value.NULL : value;
            } catch (Exception e) {
                services.logger().debug(() -> "Expression '" + call.name() + "' failed: " + e);
                return Value.NULL;
            }
        }
        return Value.NULL;
    }

    private Value evalData(Expr.Data data, ExecContext context) {
        DataStore store = context.data();
        if (store == null) return Value.NULL;
        if (data.target() == null) return store.getGlobal(data.key());
        Value target = eval(data.target(), context);
        Entity entity = Targets.entity(target, context);
        if (entity == null) {
            if (target.type() == ValueType.STRING && !target.asString().isEmpty()) {
                // A named player who is offline: read the stored value through the global store.
                return store.getGlobal(data.key() + ":" + target.asString().toLowerCase(Locale.ROOT));
            }
            return store.getGlobal(data.key());
        }
        return store.get(entity, data.key());
    }

    private Value evalRef(Expr.Ref ref, ExecContext context) {
        return switch (ref.kind()) {
            case ACTOR -> context.actor() == null ? Value.NULL
                : Value.entity(context.actor(), context.actor() instanceof Player ? ValueType.PLAYER : ValueType.ENTITY,
                    ExecContext.describeEntity(context.actor()));
            case ALL_PLAYERS -> {
                List<Value> players = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    players.add(Value.entity(player, ValueType.PLAYER, player.getName()));
                }
                yield Value.list(players);
            }
            case RANDOM_PLAYER -> {
                List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
                yield online.isEmpty() ? Value.NULL
                    : Value.entity(online.get(ThreadLocalRandom.current().nextInt(online.size())), ValueType.PLAYER,
                        online.get(0).getName());
            }
            case WORLD -> context.world() == null ? Value.NULL : Value.str(context.world().getName());
            case CONSOLE -> Value.str("console");
            case EVENT -> Value.NULL;
            case SENDER -> context.sender() == null ? Value.NULL : Value.str(context.sender().getName());
            case NAME -> {
                Value resolved = context.resolveName(ref.name());
                if (resolved != null) yield resolved;
                DataStore store = context.data();
                if (store != null && context.actor() != null && store.isDeclared(ref.name())) {
                    yield store.get(context.actor(), ref.name());
                }
                yield Value.NULL;
            }
        };
    }

    private double number(Expr expr, ExecContext context) {
        return expr == null ? 0d : eval(expr, context).asDouble();
    }

    /** Unwraps checked script failures so {@link #evaluate} can rethrow them untouched. */
    private static final class ActionFailureCarrier extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final io.astra.runtime.ActionFailure failure;

        ActionFailureCarrier(io.astra.runtime.ActionFailure failure) {
            this.failure = failure;
        }
    }
}
