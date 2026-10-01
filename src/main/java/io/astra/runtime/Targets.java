package io.astra.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Turns runtime values into Bukkit objects.
 *
 * <p>All the "which player did the script mean?" logic lives here: a target value can
 * be a player reference, a UUID, a name, an entity, or the implicit actor of the rule.
 * Keeping it in one place is what makes {@code tell player}, {@code tell them} and
 * {@code tell {player}} behave identically.</p>
 */
public final class Targets {

    private Targets() {}

    /** The best single entity for a value. */
    public static Entity entity(Value value, ExecContext context) {
        if (value == null || value.isNull()) return context.actor();
        if (value instanceof Value.Ent ent && ent.entity() instanceof Entity entity) return entity;
        if (value.type() == ValueType.UUID) {
            Entity found = byUuid(Bukkit.getPlayer(value.asString()), context);
            if (found != null) return found;
        }
        if (value.type() == ValueType.PLAYER || value.type() == ValueType.STRING) {
            Player player = Bukkit.getPlayerExact(value.asString());
            if (player != null) return player;
        }
        List<Player> players = players(value, context);
        if (!players.isEmpty()) return players.get(0);
        return null;
    }

    private static Entity byUuid(Entity entity, ExecContext context) {
        if (entity != null) return entity;
        return context.actor();
    }

    /** The best single player for a value, or {@code null} when the target is not a player. */
    public static Player player(Value value, ExecContext context) {
        if (value == null || value.isNull()) {
            return context.player() != null ? context.player() : (context.actor() instanceof Player p ? p : null);
        }
        if (value instanceof Value.Ent ent && ent.entity() instanceof Player player) return player;
        if (value.type() == ValueType.UUID) {
            Player player = Bukkit.getPlayer(UUID.fromString(value.asString()));
            if (player != null) return player;
        }
        if (value.type() == ValueType.PLAYER || value.type() == ValueType.STRING) {
            Player player = Bukkit.getPlayerExact(value.asString());
            if (player != null) return player;
        }
        List<Player> players = players(value, context);
        return players.isEmpty() ? null : players.get(0);
    }

    /** Every player a value refers to. */
    public static List<Player> players(Value value, ExecContext context) {
        if (value == null || value.isNull()) {
            Player player = player(value, context);
            return player == null ? List.of() : List.of(player);
        }
        if (value instanceof Value.ListV list) {
            List<Player> out = new ArrayList<>();
            for (Value element : list.values()) {
                Player player = player(element, context);
                if (player != null && !out.contains(player)) out.add(player);
            }
            return out;
        }
        if (value instanceof Value.Ent ent) {
            if (ent.entity() instanceof Player player) return List.of(player);
            return List.of();
        }
        if (value.type() == ValueType.STRING) {
            String text = value.asString();
            if (text.equalsIgnoreCase("everyone") || text.equalsIgnoreCase("all players")) {
                return new ArrayList<>(Bukkit.getOnlinePlayers());
            }
            Player exact = Bukkit.getPlayerExact(text);
            if (exact != null) return List.of(exact);
            Player partial = Bukkit.getPlayer(text);
            return partial == null ? List.of() : List.of(partial);
        }
        Player player = player(value, context);
        return player == null ? List.of() : List.of(player);
    }

    /** A location for a value, falling back to the context anchor. */
    public static Location location(Value value, ExecContext context) {
        if (value == null || value.isNull()) return context.location();
        if (value instanceof Value.Loc loc) {
            World world = Bukkit.getWorld(loc.world());
            if (world == null) world = context.world();
            if (world == null) return null;
            return new Location(world, loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
        }
        if (value instanceof Value.Ent ent && ent.entity() instanceof Entity entity) return entity.getLocation();
        if (value.type() == ValueType.STRING) {
            Player player = Bukkit.getPlayerExact(value.asString());
            if (player != null) return player.getLocation();
            String[] parts = value.asString().trim().split("\\s+");
            if (parts.length >= 4) {
                try {
                    World world = Bukkit.getWorld(parts[0]);
                    if (world != null) {
                        return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3]));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return context.location();
    }

    /** A world for a value. */
    public static World world(Value value, ExecContext context) {
        if (value == null || value.isNull()) return context.world();
        if (value.type() == ValueType.WORLD || value.type() == ValueType.STRING) {
            World world = Bukkit.getWorld(value.asString());
            if (world != null) return world;
        }
        if (value instanceof Value.Loc loc) return Bukkit.getWorld(loc.world());
        return context.world();
    }

    /** A UUID for a value when the target is a player. */
    public static UUID uuid(Value value, ExecContext context) {
        Player player = player(value, context);
        if (player != null) return player.getUniqueId();
        Entity entity = entity(value, context);
        return entity == null ? null : entity.getUniqueId();
    }
}
