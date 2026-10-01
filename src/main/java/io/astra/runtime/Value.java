package io.astra.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * An immutable script value.
 *
 * <p>Values are the currency of the whole runtime: the evaluator produces them, arguments
 * consume them, data storage persists them, and {@code /astra explain} prints them. Making
 * the type sealed means every place that switches on a value is checked by the compiler,
 * which is why adding a new kind of value cannot silently break action dispatch.</p>
 *
 * <p>Entity, player and world values deliberately hold live Bukkit objects and are marked
 * as not persistable through {@link ValueType#isPersistable()} - a script cannot store a
 * player object in a database, and the storage layer rejects it instead of writing
 * nonsense.</p>
 */
public sealed interface Value
    permits Value.Str, Value.Num, Value.Dec, Value.Bool, Value.ListV, Value.Uuid, Value.Loc, Value.Mat,
        Value.Ent, Value.Nul {

    /** Nothing. */
    Value NULL = new Nul();

    ValueType type();

    /** Text form; never {@code null}. */
    String asString();

    /** Whole-number view (rounds decimals and booleans). */
    default long asLong() {
        return 0L;
    }

    /** Decimal view. */
    default double asDouble() {
        return 0d;
    }

    /** Truthiness: non-zero numbers, "true", non-empty strings and lists. */
    default boolean asBoolean() {
        return type() != ValueType.NULL && !asString().isEmpty() && !asString().equalsIgnoreCase("false");
    }

    /** The list view, empty for non-lists. */
    default List<Value> asList() {
        return List.of();
    }

    default boolean isNumber() {
        return type().isNumber();
    }

    default boolean isNull() {
        return type() == ValueType.NULL;
    }

    /** The live entity behind this value, or {@code null}. */
    default Entity entity() {
        return null;
    }

    /** The live player behind this value, or {@code null}. */
    default Player player() {
        return null;
    }

    /** The world name behind this value, or {@code null} when it does not name a world. */
    default String worldName() {
        return null;
    }

    /** The location behind this value, or {@code null}. */
    default Location location() {
        return null;
    }

    /** A copy with the given amount, for item values (used by {@code give 5 diamonds}). */
    default Value withAmount(long amount) {
        return this;
    }

    // ------------------------------------------------------------- factories

    static Value str(String value) {
        return new Str(value == null ? "" : value);
    }

    static Value num(long value) {
        return new Num(value);
    }

    static Value dec(double value) {
        return new Dec(value);
    }

    static Value bool(boolean value) {
        return new Bool(value);
    }

    static Value list(List<Value> values) {
        return new ListV(values);
    }

    static Value uuid(UUID value) {
        return value == null ? NULL : new Uuid(value);
    }

    static Value location(String world, double x, double y, double z, float yaw, float pitch) {
        return new Loc(world, x, y, z, yaw, pitch);
    }

    static Value location(Location location) {
        if (location == null || location.getWorld() == null) return NULL;
        return new Loc(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch());
    }

    static Value material(String material, long amount) {
        return new Mat(material == null ? "" : material, amount);
    }

    static Value entity(Entity entity) {
        return entity == null ? NULL : new Ent(entity, false);
    }

    /**
     * Build an entity value with an explicit type and description.
     *
     * <p>The description is captured at creation time because a dead entity cannot be
     * asked for its name later - a rule that kills a mob and then logs it must still be
     * able to print the mob that died.</p>
     */
    static Value entity(Entity entity, ValueType type, String description) {
        if (entity == null) return NULL;
        boolean asPlayer = type == ValueType.PLAYER || entity instanceof Player;
        return new Ent(entity, asPlayer, description);
    }

    static Value player(Player player) {
        return player == null ? NULL : new Ent(player, true);
    }

    /**
     * Coerce a host object into a value.
     *
     * <p>Used by placeholders and integrations; unknown objects fall back to their text
     * form rather than failing, because a plugin that returns something odd should not
     * break a running script.</p>
     */
    static Value of(Object object) {
        if (object == null) return NULL;
        if (object instanceof Value value) return value;
        if (object instanceof String text) return str(text);
        if (object instanceof Boolean flag) return bool(flag);
        if (object instanceof Byte || object instanceof Short || object instanceof Integer || object instanceof Long) {
            return num(((Number) object).longValue());
        }
        if (object instanceof Float || object instanceof Double) return dec(((Number) object).doubleValue());
        if (object instanceof UUID id) return uuid(id);
        if (object instanceof Player player) return player(player);
        if (object instanceof Entity entity) return entity(entity);
        if (object instanceof Location location) return location(location);
        if (object instanceof World world) return new Loc(world.getName(), 0, 0, 0, 0, 0);
        if (object instanceof List<?> list) {
            List<Value> values = new ArrayList<>(list.size());
            for (Object item : list) values.add(of(item));
            return list(values);
        }
        return str(object.toString());
    }

    /** A list of the given values, skipping nothing (nulls become {@link #NULL}). */
    static Value listOf(Object... items) {
        List<Value> values = new ArrayList<>(items.length);
        for (Object item : items) values.add(of(item));
        return list(values);
    }

    // --------------------------------------------------------------- variants

    /** Text value. */
    record Str(String value) implements Value {
        @Override public ValueType type() { return ValueType.STRING; }

        @Override public String asString() { return value == null ? "" : value; }

        @Override public long asLong() {
            try {
                return Long.parseLong(asString().trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        @Override public double asDouble() {
            try {
                return Double.parseDouble(asString().trim());
            } catch (NumberFormatException e) {
                return 0d;
            }
        }
    }

    /** Whole number. */
    record Num(long value) implements Value {
        @Override public ValueType type() { return ValueType.INT; }

        @Override public String asString() { return Long.toString(value); }

        @Override public long asLong() { return value; }

        @Override public double asDouble() { return value; }

        @Override public boolean asBoolean() { return value != 0L; }
    }

    /** Decimal number. */
    record Dec(double value) implements Value {
        @Override public ValueType type() { return ValueType.DECIMAL; }

        @Override public String asString() {
            if (value == Math.rint(value) && !Double.isInfinite(value)) return Long.toString((long) value);
            return Double.toString(value);
        }

        @Override public long asLong() { return (long) value; }

        @Override public double asDouble() { return value; }

        @Override public boolean asBoolean() { return value != 0d; }
    }

    /** Boolean. */
    record Bool(boolean value) implements Value {
        @Override public ValueType type() { return ValueType.BOOLEAN; }

        @Override public String asString() { return Boolean.toString(value); }

        @Override public long asLong() { return value ? 1L : 0L; }

        @Override public double asDouble() { return value ? 1d : 0d; }

        @Override public boolean asBoolean() { return value; }
    }

    /** Ordered list. */
    record ListV(List<Value> values) implements Value {
        public ListV {
            values = values == null ? List.of() : List.copyOf(values);
        }

        @Override public ValueType type() { return ValueType.LIST; }

        @Override public String asString() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(values.get(i).asString());
            }
            return out.toString();
        }

        @Override public List<Value> asList() { return values; }

        @Override public boolean asBoolean() { return !values.isEmpty(); }
    }

    /** Uuid. */
    record Uuid(UUID value) implements Value {
        @Override public ValueType type() { return ValueType.UUID; }

        @Override public String asString() { return value == null ? "" : value.toString(); }
    }

    /** World position. */
    record Loc(String world, double x, double y, double z, float yaw, float pitch) implements Value {
        @Override public ValueType type() { return ValueType.LOCATION; }

        @Override public String asString() {
            String worldName = world == null ? "" : world;
            return worldName + " " + round(x) + ", " + round(y) + ", " + round(z);
        }

        @Override public String worldName() { return world == null ? "" : world; }

        @Override public Location location() {
            World bukkit = world == null ? null : Bukkit.getWorld(world);
            if (bukkit == null) return null;
            return new Location(bukkit, x, y, z, yaw, pitch);
        }

        private static String round(double value) {
            if (value == Math.rint(value)) return Long.toString((long) value);
            return String.format(Locale.ROOT, "%.2f", value);
        }
    }

    /** Material plus amount. */
    record Mat(String material, long amount) implements Value {
        public Mat {
            material = material == null ? "" : material;
        }

        @Override public ValueType type() { return ValueType.MATERIAL; }

        @Override public String asString() { return material; }

        @Override public Value withAmount(long newAmount) { return new Mat(material, newAmount); }
    }

    /** Live entity or player. */
    record Ent(Entity entity, boolean asPlayer, String described) implements Value {

        public Ent(Entity entity, boolean asPlayer) {
            this(entity, asPlayer, null);
        }

        @Override public ValueType type() { return asPlayer ? ValueType.PLAYER : ValueType.ENTITY; }

        @Override public String asString() {
            if (described != null && !described.isEmpty()) return described;
            if (entity instanceof Player player) return player.getName();
            return entity == null ? "" : entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }

        @Override public Entity entity() { return entity; }

        @Override public Player player() { return entity instanceof Player player ? player : null; }

        @Override public String worldName() {
            return entity == null || entity.getWorld() == null ? null : entity.getWorld().getName();
        }

        @Override public Location location() { return entity == null ? null : entity.getLocation(); }

        @Override public boolean asBoolean() { return entity != null && !entity.isDead(); }
    }

    /** No value. */
    record Nul() implements Value {
        @Override public ValueType type() { return ValueType.NULL; }

        @Override public String asString() { return ""; }

        @Override public boolean asBoolean() { return false; }
    }
}
