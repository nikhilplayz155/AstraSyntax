package io.astra.runtime.expression;

import io.astra.runtime.ExecContext;
import io.astra.runtime.Value;
import io.astra.runtime.ValueType;
import io.astra.runtime.event.EventPropertyResolver;
import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Property access ({@code health of player}, {@code player's world}).
 *
 * <p>The supported property names are listed in {@link #KNOWN}, which the compiler uses
 * to warn about typos before a script is ever activated.</p>
 */
public final class Properties {

    /** Every property name the runtime understands, with its aliases. */
    public static final List<String> KNOWN = List.of(
        "name", "display name", "uuid", "health", "max health", "food", "saturation", "level", "experience",
        "gamemode", "world", "location", "x", "y", "z", "yaw", "pitch", "held item", "item in hand", "armor",
        "is flying", "is sneaking", "is sprinting", "is op", "is online", "is alive", "is dead", "is burning",
        "is swimming", "is sleeping", "is gliding", "is invisible", "is dead", "target", "vehicle", "passengers",
        "type", "item amount", "item name", "item material", "time", "weather", "difficulty", "players",
        "online players", "player count", "world players", "block", "block type", "damage", "damage cause",
        "message", "slot", "amount", "killer", "victim", "entity", "entity type", "material", "canceled",
        "cancelled", "menu", "button", "sender", "command", "reason", "exp to drop", "projectile", "source");

    private Properties() {}

    /** True when the property name is recognised (aliases included). */
    public static boolean isKnown(String property) {
        if (property == null) return false;
        String key = normalise(property);
        return KNOWN.contains(key) || EventPropertyResolver.owns(key);
    }

    /** Suggestions for a mistyped property. */
    public static List<String> suggestions(String property) {
        List<String> pool = new ArrayList<>(KNOWN);
        pool.addAll(EventPropertyResolver.properties());
        return Strings.nearest(property == null ? "" : property, pool, 3);
    }

    /** Resolve a property of a value. */
    public static Value resolve(Value target, String property, ExecContext context) {
        String key = normalise(property);
        if (target == null || target.isNull()) {
            Value eventValue = EventPropertyResolver.resolve(context, key);
            if (!eventValue.isNull()) return eventValue;
            return Value.NULL;
        }
        if (target instanceof Value.Ent ent && ent.entity() instanceof Entity entity) {
            return ofEntity(entity, key, context);
        }
        if (target.type() == ValueType.STRING || target.type() == ValueType.PLAYER || target.type() == ValueType.UUID) {
            Entity entity = io.astra.runtime.Targets.entity(target, context);
            if (entity != null) return ofEntity(entity, key, context);
            World world = org.bukkit.Bukkit.getWorld(target.asString());
            if (world != null) return ofWorld(world, key, context);
        }
        if (target instanceof Value.Loc loc) {
            return ofLocation(loc, key);
        }
        Value eventValue = EventPropertyResolver.resolve(context, key);
        return eventValue.isNull() ? Value.NULL : eventValue;
    }

    /** Properties of a live entity (player or mob). */
    public static Value ofEntity(Entity entity, String property, ExecContext context) {
        String key = normalise(property);
        switch (key) {
            case "name": return Value.str(entity.getName());
            case "display name": return Value.str(entity instanceof Player p ? p.getDisplayName() : String.valueOf(entity.getCustomName()));
            case "uuid": return Value.uuid(entity.getUniqueId());
            case "world": return Value.str(entity.getWorld().getName());
            case "location": {
                Location location = entity.getLocation();
                return Value.location(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
            }
            case "x": return Value.dec(entity.getLocation().getX());
            case "y": return Value.dec(entity.getLocation().getY());
            case "z": return Value.dec(entity.getLocation().getZ());
            case "type": case "entity type":
                return Value.str(entity.getType().name().toLowerCase(Locale.ROOT));
            case "is alive": return Value.bool(!entity.isDead());
            case "is dead": return Value.bool(entity.isDead());
            case "is burning": return Value.bool(entity.getFireTicks() > 0);
            case "passengers": {
                List<Value> passengers = new ArrayList<>();
                for (Entity passenger : entity.getPassengers()) {
                    passengers.add(Value.entity(passenger, ValueType.ENTITY, ExecContext.describeEntity(passenger)));
                }
                return Value.list(passengers);
            }
            default:
                break;
        }
        if (entity instanceof LivingEntity living) {
            switch (key) {
                case "health": return Value.dec(living.getHealth());
                case "max health": {
                    var attribute = living.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    return Value.dec(attribute == null ? 20d : attribute.getValue());
                }
                case "target": {
                    if (living instanceof org.bukkit.entity.Mob mob && mob.getTarget() != null) {
                        Entity target = mob.getTarget();
                        return Value.entity(target, ValueType.ENTITY, ExecContext.describeEntity(target));
                    }
                    return Value.NULL;
                }
                case "vehicle": {
                    Entity vehicle = living.getVehicle();
                    return vehicle == null ? Value.NULL
                        : Value.entity(vehicle, ValueType.ENTITY, ExecContext.describeEntity(vehicle));
                }
                default:
                    break;
            }
        }
        if (entity instanceof Player player) {
            switch (key) {
                case "food": return Value.num(player.getFoodLevel());
                case "saturation": return Value.dec(player.getSaturation());
                case "level": return Value.num(player.getLevel());
                case "experience": return Value.num(player.getTotalExperience());
                case "gamemode": return Value.str(player.getGameMode().name().toLowerCase(Locale.ROOT));
                case "is flying": return Value.bool(player.isFlying());
                case "is sneaking": return Value.bool(player.isSneaking());
                case "is sprinting": return Value.bool(player.isSprinting());
                case "is op": return Value.bool(player.isOp());
                case "is online": return Value.bool(player.isOnline());
                case "is sleeping": return Value.bool(player.isSleeping());
                case "is gliding": return Value.bool(player.isGliding());
                case "is invisible": return Value.bool(player.isInvisible());
                case "held item": case "item in hand": {
                    ItemStack item = player.getInventory().getItemInMainHand();
                    return itemValue(item);
                }
                case "armor": {
                    List<Value> armor = new ArrayList<>();
                    for (ItemStack piece : player.getInventory().getArmorContents()) {
                        if (piece != null) armor.add(itemValue(piece));
                    }
                    return Value.list(armor);
                }
                default:
                    break;
            }
        }
        return EventPropertyResolver.resolve(context, key);
    }

    /** Properties of a world. */
    public static Value ofWorld(World world, String property, ExecContext context) {
        String key = normalise(property);
        switch (key) {
            case "name": return Value.str(world.getName());
            case "time": return Value.num(world.getTime());
            case "weather": return Value.str(world.hasStorm() ? (world.isThundering() ? "thunder" : "rain") : "clear");
            case "difficulty": return Value.str(world.getDifficulty().name().toLowerCase(Locale.ROOT));
            case "players": case "world players": case "player count": return Value.num(world.getPlayers().size());
            default: return EventPropertyResolver.resolve(context, key);
        }
    }

    /** Properties of a stored location value. */
    public static Value ofLocation(Value.Loc loc, String property) {
        return switch (normalise(property)) {
            case "world" -> Value.str(loc.world());
            case "x" -> Value.dec(loc.x());
            case "y" -> Value.dec(loc.y());
            case "z" -> Value.dec(loc.z());
            case "yaw" -> Value.dec(loc.yaw());
            case "pitch" -> Value.dec(loc.pitch());
            default -> Value.NULL;
        };
    }

    private static Value itemValue(ItemStack item) {
        if (item == null || item.getType().isAir()) return Value.NULL;
        return Value.material(item.getType().name(), item.getAmount());
    }

    /** Split "player's coins" and "health of player" style access. */
    public static String normalise(String property) {
        if (property == null) return "";
        return property.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
    }

    /** True when a game mode name is valid (used by conditions and validation). */
    public static boolean isGamemode(String name) {
        if (Strings.isBlank(name)) return false;
        for (GameMode mode : GameMode.values()) {
            if (mode.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Resolve a game mode by name. */
    public static GameMode gamemode(String name) {
        if (Strings.isBlank(name)) return null;
        for (GameMode mode : GameMode.values()) {
            if (mode.name().equalsIgnoreCase(name)) return mode;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "survival", "s" -> GameMode.SURVIVAL;
            case "creative", "c" -> GameMode.CREATIVE;
            case "adventure", "a" -> GameMode.ADVENTURE;
            case "spectator", "sp" -> GameMode.SPECTATOR;
            default -> null;
        };
    }
}
