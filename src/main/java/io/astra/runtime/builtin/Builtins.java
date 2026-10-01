package io.astra.runtime.builtin;

import io.astra.language.docs.DocRegistry;
import io.astra.runtime.ExecContext;
import io.astra.runtime.Registries;
import io.astra.runtime.Value;
import io.astra.runtime.action.ActionDefinition;
import io.astra.runtime.action.ActionRegistry;
import io.astra.runtime.condition.ConditionDefinition;
import io.astra.runtime.condition.ConditionRegistry;
import io.astra.runtime.event.EventDefinition;
import io.astra.runtime.event.EventRegistry;
import io.astra.runtime.expression.ExpressionDefinition;
import io.astra.runtime.expression.ExpressionRegistry;
import io.astra.runtime.expression.PlaceholderService;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Wires the built-in vocabulary together.
 *
 * <p>This is the single place that decides what a fresh AstraSyntax can do: the event
 * triggers, actions, conditions and expressions are registered here, mirrored into the
 * documentation registry (so {@code /astra info} can never drift from what actually
 * runs), and the built-in placeholders are installed.</p>
 *
 * <p>Modules add to the same registries after this call; nothing here is final.</p>
 */
public final class Builtins {

    private Builtins() {
    }

    /** Create a fully populated registry set.
     *
     * <p>The gameplay catalogue is registered here like any other built-in. It needs no
     * services at registration time because every gameplay action looks them up from the
     * executing context instead, which is what keeps a registry a plain lookup table.</p>
     */
    public static Registries create() {
        EventRegistry events = new EventRegistry();
        ActionRegistry actions = new ActionRegistry();
        ConditionRegistry conditions = new ConditionRegistry();
        ExpressionRegistry expressions = new ExpressionRegistry();
        DocRegistry docs = new DocRegistry();
        PlaceholderService placeholders = new PlaceholderService();

        BuiltinEvents.registerAll(events);
        BuiltinActions.registerAll(actions);
        BuiltinConditions.registerAll(conditions);
        BuiltinExpressions.registerAll(expressions);
        BuiltinEconomy.registerAll(actions, conditions, expressions);
        BuiltinNetwork.registerAll(actions);
        // The gameplay catalogue is registered with the services it drives. Its triggers
        // are always registered: a script that never uses them costs nothing, because the
        // event bus only attaches listeners for triggers the loaded scripts actually name.
        BuiltinGameplay.registerTriggers(events);
        BuiltinGameplay.registerActions(actions);
        BuiltinGameplay.registerConditions(conditions);
        BuiltinGameplay.registerExpressions(expressions);
        registerPlaceholders(placeholders);

        // Documentation is derived, never hand-maintained: every registered element
        // publishes the DocEntry that was built with it.
        for (EventDefinition definition : events.all()) docs.register(definition.doc());
        for (ActionDefinition definition : actions.all()) docs.register(definition.doc());
        for (ConditionDefinition definition : conditions.all()) docs.register(definition.doc());
        for (ExpressionDefinition definition : expressions.all()) docs.register(definition.doc());

        return new Registries(events, actions, conditions, expressions, docs, placeholders);
    }

    /**
     * The built-in {@code {placeholders}}.
     *
     * <p>They are resolved from the execution context, never from a static global, so the
     * same text renders differently for each player and each event - which is the whole
     * reason the placeholder service is instantiated per plugin rather than shared
     * statically.</p>
     */
    private static void registerPlaceholders(PlaceholderService placeholders) {
        placeholders.register("player", (name, context) -> {
            Player player = context.player();
            return player == null ? Value.NULL : Value.str(player.getName());
        });
        placeholders.register("uuid", (name, context) -> {
            Player player = context.player();
            return player == null ? Value.NULL : Value.str(player.getUniqueId().toString());
        });
        placeholders.register("world", (name, context) -> {
            World world = context.world();
            return world == null ? Value.NULL : Value.str(world.getName());
        });
        placeholders.register("x", (name, context) -> coordinate(context, 'x'));
        placeholders.register("y", (name, context) -> coordinate(context, 'y'));
        placeholders.register("z", (name, context) -> coordinate(context, 'z'));
        placeholders.register("online", (name, context) -> Value.num(Bukkit.getOnlinePlayers().size()));
        placeholders.register("online-players", (name, context) -> Value.num(Bukkit.getOnlinePlayers().size()));
        placeholders.register("script", (name, context) ->
            context.script() == null ? Value.str("unknown") : Value.str(context.script().name()));
        placeholders.register("rule", (name, context) ->
            context.rule() == null ? Value.str("unknown") : Value.str(context.rule().id()));
        placeholders.register("trigger", (name, context) ->
            context.triggerId() == null ? Value.str("manual") : Value.str(context.triggerId()));
        placeholders.register("sender", (name, context) ->
            context.sender() == null ? Value.NULL : Value.str(context.sender().getName()));
        placeholders.register("name", (name, context) -> {
            if (context.player() != null) return Value.str(context.player().getName());
            if (context.actor() != null) return Value.str(context.actor().getName());
            return Value.NULL;
        });
        placeholders.register("opposite", (name, context) -> context.local("opposite"));
    }

    private static Value coordinate(ExecContext context, char axis) {
        if (context.location() == null) return Value.NULL;
        double value = switch (axis) {
            case 'x' -> context.location().getX();
            case 'y' -> context.location().getY();
            default -> context.location().getZ();
        };
        return Value.num(Math.round(value));
    }

    /** Human readable summary used by the startup log line. */
    public static String describe(Registries registries) {
        return registries.events().size() + " triggers, " + registries.actions().size() + " actions, "
            + registries.conditions().size() + " conditions, " + registries.expressions().size() + " expressions, "
            + registries.placeholders().names().size() + " placeholders";
    }

    /** Lower-cased id set used by the plugin's self-check command. */
    public static boolean isKnownAction(Registries registries, String id) {
        return id != null && registries.actions().has(id.toLowerCase(Locale.ROOT));
    }
}
