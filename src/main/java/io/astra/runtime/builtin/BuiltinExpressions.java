package io.astra.runtime.builtin;

import io.astra.runtime.ExecContext;
import io.astra.runtime.Targets;
import io.astra.runtime.Value;
import io.astra.runtime.expression.ExpressionDefinition;
import io.astra.runtime.expression.ExpressionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The built-in expression catalogue.
 *
 * <p>Simple values ({@code player's health}, literals, arithmetic) are evaluated directly
 * by {@link io.astra.runtime.expression.ExpressionEvaluator}; only call-like forms that
 * need real logic live here. Registering them keeps the evaluator free of Minecraft
 * knowledge and makes the same forms available to modules.</p>
 */
public final class BuiltinExpressions {

    private BuiltinExpressions() {
    }

    /** Register the full built-in expression set. */
    public static void registerAll(ExpressionRegistry registry) {
        registry.register(ExpressionDefinition.builder("random-number", "A random whole number between two values")
            .syntax("random number between <min:number> and <max:number>")
            .syntax("a random number from <min:number> to <max:number>")
            .returns("number")
            .example("set coins of player to random number between 1 and 10")
            .evaluates((context, arguments) -> {
                long min = arguments.number("min");
                long max = arguments.number("max");
                if (max < min) {
                    long swap = min;
                    min = max;
                    max = swap;
                }
                long range = max - min + 1;
                if (range <= 0) return Value.num(min);
                return Value.num(min + ThreadLocalRandom.current().nextLong(range));
            })
            .build());

        registry.register(ExpressionDefinition.builder("random-decimal", "A random decimal number")
            .syntax("random decimal between <min:decimal> and <max:decimal>")
            .syntax("random chance")
            .returns("decimal")
            .example("set speed of player to random decimal between 0.1 and 0.3")
            .evaluates((context, arguments) -> {
                if (!arguments.has("min")) return Value.dec(ThreadLocalRandom.current().nextDouble());
                double min = arguments.decimal("min");
                double max = arguments.decimal("max");
                if (max < min) {
                    double swap = min;
                    min = max;
                    max = swap;
                }
                return Value.dec(min + ThreadLocalRandom.current().nextDouble() * (max - min));
            })
            .build());

        registry.register(ExpressionDefinition.builder("distance-between", "The distance between two locations")
            .syntax("distance between <from:location> and <to:location>")
            .syntax("distance from <from:location> to <to:location>")
            .returns("decimal")
            .example("if distance from player to spawn is greater than 100:")
            .evaluates((context, arguments) -> {
                Location from = arguments.location("from");
                Location to = arguments.location("to");
                if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
                    return Value.NULL;
                }
                if (!from.getWorld().equals(to.getWorld())) return Value.dec(Double.MAX_VALUE);
                return Value.dec(from.distance(to));
            })
            .build());

        registry.register(ExpressionDefinition.builder("item-count", "How many of an item a player carries")
            .syntax("number of <item:item> in <who:player>'s inventory")
            .syntax("amount of <item:item> in <who:player>'s inventory")
            .syntax("how many <item:item> <who:player> has")
            .returns("number")
            .example("if number of diamonds in player's inventory is above 5:")
            .evaluates((context, arguments) -> {
                org.bukkit.Material material = MaterialTable.shared().material(arguments.material("item"));
                if (material == null) return Value.num(0);
                List<Player> players = arguments.has("who") ? arguments.players("who") : fallbackPlayers(context);
                int total = 0;
                for (Player player : players) {
                    for (ItemStack stack : player.getInventory().getContents()) {
                        if (stack != null && stack.getType() == material) total += stack.getAmount();
                    }
                }
                return Value.num(total);
            })
            .build());

        registry.register(ExpressionDefinition.builder("online-players", "How many players are online")
            .syntax("number of online players")
            .syntax("online player count")
            .syntax("how many players are online")
            .returns("number")
            .example("if number of online players is above 10:")
            .evaluates((context, arguments) -> Value.num(Bukkit.getOnlinePlayers().size()))
            .build());

        registry.register(ExpressionDefinition.builder("world-time", "The current time of a world")
            .syntax("time in <world:world>")
            .syntax("world time in <world:world>")
            .returns("number")
            .example("set time in world to time in world_nether")
            .evaluates((context, arguments) -> {
                World world = arguments.has("world") ? arguments.world("world") : context.world();
                return world == null ? Value.NULL : Value.num(world.getTime());
            })
            .build());

        registry.register(ExpressionDefinition.builder("entity-type-of", "The entity type as a string")
            .syntax("entity type of <who:target>")
            .syntax("mob type of <who:target>")
            .returns("string")
            .example("if entity type of attacker is zombie:")
            .evaluates((context, arguments) -> {
                Entity entity = arguments.has("who") ? Targets.entity(arguments.value("who"), context) : context.actor();
                if (entity == null) return Value.str("unknown");
                return Value.str(entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            })
            .build());

        registry.register(ExpressionDefinition.builder("uuid-of", "The unique id of an entity")
            .syntax("uuid of <who:target>")
            .syntax("<who:target>'s uuid")
            .returns("string")
            .example("set last-id of player to uuid of player")
            .evaluates((context, arguments) -> {
                Entity entity = arguments.has("who") ? Targets.entity(arguments.value("who"), context) : context.actor();
                return entity == null ? Value.NULL : Value.str(entity.getUniqueId().toString());
            })
            .build());

        registry.register(ExpressionDefinition.builder("location-of", "The location of an entity")
            .syntax("location of <who:target>")
            .syntax("<who:target>'s location")
            .returns("location")
            .example("set block at location of player to air")
            .evaluates((context, arguments) -> {
                Entity entity = arguments.has("who") ? Targets.entity(arguments.value("who"), context) : context.actor();
                if (entity == null) return context.location() == null ? Value.NULL : locationValue(context.location());
                return locationValue(entity.getLocation());
            })
            .build());

        registry.register(ExpressionDefinition.builder("player-name", "The name of a player")
            .syntax("name of <who:target>")
            .syntax("<who:target>'s name")
            .returns("string")
            .example("log \"hello \" + name of player")
            .evaluates((context, arguments) -> {
                Entity entity = arguments.has("who") ? Targets.entity(arguments.value("who"), context) : context.actor();
                if (entity == null) return Value.NULL;
                if (entity instanceof Player player) return Value.str(player.getName());
                if (entity instanceof org.bukkit.Nameable nameable && nameable.getCustomName() != null) {
                    return Value.str(nameable.getCustomName());
                }
                return Value.str(entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            })
            .build());

        registry.register(ExpressionDefinition.builder("players-in-world", "How many players are in a world")
            .syntax("number of players in <world:world>")
            .syntax("players in <world:world>")
            .returns("number")
            .example("if number of players in world is above 5:")
            .evaluates((context, arguments) -> {
                World world = arguments.has("world") ? arguments.world("world") : context.world();
                return world == null ? Value.num(0) : Value.num(world.getPlayers().size());
            })
            .build());
    }

    private static List<Player> fallbackPlayers(ExecContext context) {
        List<Player> players = new ArrayList<>();
        if (context.player() != null) players.add(context.player());
        if (players.isEmpty()) players.addAll(Bukkit.getOnlinePlayers());
        return players;
    }

    private static Value locationValue(Location location) {
        if (location == null || location.getWorld() == null) return Value.NULL;
        return Value.location(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch());
    }
}
