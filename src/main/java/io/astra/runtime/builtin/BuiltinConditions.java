package io.astra.runtime.builtin;

import io.astra.runtime.Arguments;
import io.astra.runtime.ExecContext;
import io.astra.runtime.Targets;
import io.astra.runtime.Value;
import io.astra.runtime.ValueType;
import io.astra.runtime.condition.ConditionDefinition;
import io.astra.runtime.condition.ConditionRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The built-in condition catalogue.
 *
 * <p>Conditions are the gatekeepers of a rule: they run after the cheap event filters and
 * before the body, so they may safely do real work (inventory scans, data lookups) while
 * the event pre-filters stay allocation-free. Natural-language rules such as
 * "if the player is sneaking and has 5 diamonds" compile to exactly these conditions.</p>
 */
public final class BuiltinConditions {

    /**
     * Per-script, per-key cooldowns used by the {@code cooldown} condition.
     *
     * <p>Keyed by script + name + player so two scripts - or two rules in one script -
     * cannot silently share a cooldown, which is the behaviour authors expect.</p>
     */
    private static final Map<String, Long> COOLDOWNS = new ConcurrentHashMap<>();

    private BuiltinConditions() {
    }

    /** Register the full built-in condition set. */
    public static void registerAll(ConditionRegistry registry) {
        registerPermissions(registry);
        registerPlayerState(registry);
        registerWorld(registry);
        registerData(registry);
        registerItems(registry);
        registerChanceAndMisc(registry);
    }

    // ------------------------------------------------------------ permissions

    private static void registerPermissions(ConditionRegistry registry) {
        registry.register(ConditionDefinition.builder("has-permission", "Checks a permission node")
            .syntax("has permission <node:permission>")
            .syntax("<who:target> has permission <node:permission>")
            .syntax("player has permission <node:permission>")
            .example("if player has permission astra.vip:")
            .tests((context, arguments) -> {
                String node = arguments.string("node");
                for (Entity entity : entities(context, arguments, "who")) {
                    if (entity instanceof Player player && player.hasPermission(node)) return true;
                }
                return false;
            })
            .build());

        registry.register(ConditionDefinition.builder("is-op", "Checks whether a player is an operator")
            .syntax("<who:target> is op")
            .syntax("<who:target> is an operator")
            .syntax("player is op")
            .example("if player is op:")
            .tests((context, arguments) -> {
                for (Entity entity : entities(context, arguments, "who")) {
                    if (entity instanceof Player player) return player.isOp();
                }
                return false;
            })
            .build());
    }

    // ------------------------------------------------------------ player state

    private static void registerPlayerState(ConditionRegistry registry) {
        registry.register(ConditionDefinition.builder("is-sneaking", "Is the entity sneaking?")
            .syntax("<who:target> is sneaking")
            .syntax("player is sneaking")
            .syntax("<who:target> is holding shift")
            .example("if player is sneaking:")
            .tests((context, arguments) -> anyMatch(context, arguments, "who",
                entity -> entity instanceof Player player && player.isSneaking()))
            .build());

        registry.register(ConditionDefinition.builder("is-sprinting", "Is the entity sprinting?")
            .syntax("<who:target> is sprinting")
            .syntax("player is sprinting")
            .example("if player is sprinting:")
            .tests((context, arguments) -> anyMatch(context, arguments, "who",
                entity -> entity instanceof Player player && player.isSprinting()))
            .build());

        registry.register(ConditionDefinition.builder("is-flying", "Is the player flying?")
            .syntax("<who:target> is flying")
            .syntax("player is flying")
            .example("if player is flying:")
            .tests((context, arguments) -> {
                for (Entity entity : entities(context, arguments, "who")) {
                    if (entity instanceof Player player && player.isFlying()) return true;
                }
                return false;
            })
            .build());

        registry.register(ConditionDefinition.builder("is-on-ground", "Is the entity standing on the ground?")
            .syntax("<who:target> is on the ground")
            .syntax("player is on the ground")
            .example("if player is on the ground:")
            .tests((context, arguments) -> anyMatch(context, arguments, "who", Entity::isOnGround))
            .build());

        registry.register(ConditionDefinition.builder("is-in-water", "Is the entity in water?")
            .syntax("<who:target> is in water")
            .syntax("player is in water")
            .syntax("<who:target> is underwater")
            .example("if player is in water:")
            .tests((context, arguments) -> anyMatch(context, arguments, "who",
                entity -> entity.isInWater() || entity instanceof LivingEntity living && living.getRemainingAir()
                    < living.getMaximumAir()))
            .build());

        registry.register(ConditionDefinition.builder("is-in-lava", "Is the entity in lava?")
            .syntax("<who:target> is in lava")
            .syntax("player is in lava")
            .example("if player is in lava:")
            .tests((context, arguments) -> anyMatch(context, arguments, "who",
                entity -> entity.getLocation().getBlock().getType() == Material.LAVA))
            .build());

        registry.register(ConditionDefinition.builder("is-inside-vehicle", "Is the entity riding something?")
            .syntax("<who:target> is inside a vehicle")
            .syntax("<who:target> is riding")
            .syntax("player is riding")
            .example("if player is riding:")
            .tests((context, arguments) -> anyMatch(context, arguments, "who", entity -> !entity.getPassengers().isEmpty()
                || entity.getVehicle() != null))
            .build());

        registry.register(ConditionDefinition.builder("is-alive", "Is the entity alive?")
            .syntax("<who:target> is alive")
            .syntax("player is alive")
            .example("if player is alive:")
            .tests((context, arguments) -> {
                for (Entity entity : entities(context, arguments, "who")) {
                    if (!entity.isDead()) return true;
                }
                return false;
            })
            .build());

        registry.register(ConditionDefinition.builder("health-above", "Is the entity's health above a value?")
            .syntax("health of <who:target> is above <amount:number>")
            .syntax("<who:target>'s health is above <amount:number>")
            .syntax("health of <who:target> is at least <amount:number>")
            .example("if health of player is above 10:")
            .tests((context, arguments) -> compareHealth(context, arguments, true))
            .build());

        registry.register(ConditionDefinition.builder("health-below", "Is the entity's health below a value?")
            .syntax("health of <who:target> is below <amount:number>")
            .syntax("<who:target>'s health is below <amount:number>")
            .syntax("health of <who:target> is at most <amount:number>")
            .example("if health of player is below 5:")
            .tests((context, arguments) -> compareHealth(context, arguments, false))
            .build());

        registry.register(ConditionDefinition.builder("gamemode-is", "Checks a player's game mode")
            .syntax("<who:target> is in <mode:word> mode")
            .syntax("<who:target>'s game mode is <mode:word>")
            .syntax("player is in <mode:word> mode")
            .example("if player is in creative mode:")
            .tests((context, arguments) -> {
                String expected = arguments.string("mode").trim().toLowerCase(Locale.ROOT);
                for (Entity entity : entities(context, arguments, "who")) {
                    if (entity instanceof Player player) {
                        return player.getGameMode().name().equalsIgnoreCase(expected)
                            || player.getGameMode().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                                .equals(expected);
                    }
                }
                return false;
            })
            .build());

        registry.register(ConditionDefinition.builder("is-entity-type", "Checks the entity type")
            .syntax("<who:target> is a <mob:entity>")
            .syntax("entity type of <who:target> is <mob:entity>")
            .example("if attacker is a zombie:")
            .tests((context, arguments) -> {
                String canonical = MaterialTable.shared().resolveEntity(arguments.string("mob"));
                if (canonical == null) return false;
                for (Entity entity : entities(context, arguments, "who")) {
                    if (entity.getType().name().equals(canonical)) return true;
                }
                return false;
            })
            .build());
    }

    // ------------------------------------------------------------------ world

    private static void registerWorld(ConditionRegistry registry) {
        registry.register(ConditionDefinition.builder("is-day", "Is it day time?")
            .syntax("it is day")
            .syntax("it is day in <world:world>")
            .syntax("world is day|daytime")
            .example("if it is day:")
            .tests((context, arguments) -> {
                World world = world(context, arguments);
                if (world == null) return false;
                long time = world.getTime();
                return time < 12300 || time > 23850;
            })
            .build());

        registry.register(ConditionDefinition.builder("is-night", "Is it night time?")
            .syntax("it is night")
            .syntax("it is night in <world:world>")
            .syntax("world is night|nighttime")
            .example("if it is night:")
            .tests((context, arguments) -> {
                World world = world(context, arguments);
                if (world == null) return false;
                long time = world.getTime();
                return time >= 12300 && time <= 23850;
            })
            .build());

        registry.register(ConditionDefinition.builder("is-raining", "Is it raining?")
            .syntax("it is raining")
            .syntax("it is raining in <world:world>")
            .syntax("world is raining|stormy")
            .example("if it is raining:")
            .tests((context, arguments) -> {
                World world = world(context, arguments);
                return world != null && world.hasStorm();
            })
            .build());

        registry.register(ConditionDefinition.builder("is-thundering", "Is there a thunderstorm?")
            .syntax("it is thundering")
            .syntax("it is thundering in <world:world>")
            .syntax("world is thundering")
            .example("if it is thundering:")
            .tests((context, arguments) -> {
                World world = world(context, arguments);
                return world != null && world.isThundering();
            })
            .build());

        registry.register(ConditionDefinition.builder("is-in-world", "Is the entity in a specific world?")
            .syntax("<who:target> is in world <world:world>")
            .syntax("<who:target> is in <world:world>")
            .syntax("player is in world <world:world>")
            .example("if player is in world world_nether:")
            .tests((context, arguments) -> {
                String expected = arguments.string("world");
                for (Entity entity : entities(context, arguments, "who")) {
                    if (entity.getWorld().getName().equalsIgnoreCase(expected)) return true;
                }
                return false;
            })
            .build());

        registry.register(ConditionDefinition.builder("block-is", "Checks the block at a location")
            .syntax("block at <where:location> is <block:material>")
            .syntax("the block at <where:location> is <block:material>")
            .example("if block at player's location is water:")
            .tests((context, arguments) -> {
                Material expected = MaterialTable.shared().material(arguments.string("block"));
                if (expected == null) return false;
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                return location != null && location.getBlock().getType() == expected;
            })
            .build());

        registry.register(ConditionDefinition.builder("is-canceled", "Has the event already been cancelled?")
            .syntax("event is canceled")
            .syntax("event is cancelled")
            .syntax("the event is canceled")
            .example("if event is canceled:")
            .tests((context, arguments) -> context.isEventCancelled())
            .build());
    }

    // ------------------------------------------------------------------- data

    private static void registerData(ConditionRegistry registry) {
        registry.register(ConditionDefinition.builder("data-is", "Compares stored data with a value")
            .syntax("<key:data key> of <who:target> is <value:expr>")
            .syntax("<who:target>'s <key:data key> is <value:expr>")
            .syntax("global <key:data key> is <value:expr>")
            .example("if kills of player is 5:")
            .tests((context, arguments) -> {
                Value actual = read(context, arguments);
                Value expected = arguments.has("value") ? arguments.value("value") : Value.NULL;
                return io.astra.runtime.ValueMath.equal(actual, expected);
            })
            .build());

        registry.register(ConditionDefinition.builder("data-above", "Is stored data above a number?")
            .syntax("<key:data key> of <who:target> is above <amount:number>")
            .syntax("<key:data key> of <who:target> is greater than <amount:number>")
            .syntax("<key:data key> of <who:target> is at least <amount:number>")
            .syntax("global <key:data key> is above <amount:number>")
            .example("if coins of player is above 100:")
            .tests((context, arguments) -> compare(context, arguments, true))
            .build());

        registry.register(ConditionDefinition.builder("data-below", "Is stored data below a number?")
            .syntax("<key:data key> of <who:target> is below <amount:number>")
            .syntax("<key:data key> of <who:target> is less than <amount:number>")
            .syntax("<key:data key> of <who:target> is at most <amount:number>")
            .syntax("global <key:data key> is below <amount:number>")
            .example("if coins of player is below 10:")
            .tests((context, arguments) -> compare(context, arguments, false))
            .build());

        registry.register(ConditionDefinition.builder("data-exists", "Does the stored value exist?")
            .syntax("<key:data key> of <who:target> exists")
            .syntax("<who:target> has data <key:data key>")
            .syntax("global <key:data key> exists")
            .example("if first-join of player exists:")
            .tests((context, arguments) -> {
                String key = arguments.string("key");
                if (arguments.has("who")) {
                    Entity entity = Targets.entity(arguments.value("who"), context);
                    if (entity != null) return context.data().has(entity, key);
                }
                return context.data().hasGlobal(key);
            })
            .build());

        registry.register(ConditionDefinition.builder("cooldown-ready", "True when a named cooldown has elapsed")
            .syntax("cooldown <name:word> is ready")
            .syntax("<name:word> is ready")
            .syntax("cooldown <name:word> finished")
            .example("if cooldown daily-reward is ready:")
            .tests((context, arguments) -> {
                String owner = context.script() == null ? "?" : context.script().name();
                String who = context.player() == null ? (context.actor() == null ? "global"
                    : context.actor().getUniqueId().toString()) : context.player().getUniqueId().toString();
                String key = owner + "|" + arguments.string("name") + "|" + who;
                Long until = COOLDOWNS.get(key);
                return until == null || System.currentTimeMillis() >= until;
            })
            .build());

        registry.register(ConditionDefinition.builder("start-cooldown", "Arms a named cooldown")
            .syntax("start cooldown <name:word> for <seconds:duration>")
            .syntax("set cooldown <name:word> to <seconds:duration>")
            .example("start cooldown daily-reward for 24 hours")
            .tests((context, arguments) -> {
                String owner = context.script() == null ? "?" : context.script().name();
                String who = context.player() == null ? (context.actor() == null ? "global"
                    : context.actor().getUniqueId().toString()) : context.player().getUniqueId().toString();
                String key = owner + "|" + arguments.string("name") + "|" + who;
                long seconds = Math.max(0L, arguments.number("seconds"));
                COOLDOWNS.put(key, System.currentTimeMillis() + seconds * 1000L);
                return true;
            })
            .build());
    }

    // ------------------------------------------------------------------ items

    private static void registerItems(ConditionRegistry registry) {
        registry.register(ConditionDefinition.builder("has-item", "Does the player carry an item?")
            .syntax("<who:target> has <item:item>")
            .syntax("player has <item:item>")
            .syntax("<who:target> is carrying <item:item>")
            .example("if player has 5 diamonds:")
            .tests((context, arguments) -> {
                Material material = MaterialTable.shared().material(arguments.material("item"));
                if (material == null) return false;
                int needed = Math.max(1, arguments.amount("item", 1));
                for (Entity entity : entities(context, arguments, "who")) {
                    if (entity instanceof Player player && count(player, material) >= needed) return true;
                }
                return false;
            })
            .build());

        registry.register(ConditionDefinition.builder("is-holding", "Is the player holding an item?")
            .syntax("<who:target> is holding <item:item>")
            .syntax("player is holding <item:material>")
            .syntax("<who:target> is holding <item:material>")
            .example("if player is holding diamond sword:")
            .tests((context, arguments) -> {
                Material material = MaterialTable.shared().material(arguments.material("item"));
                if (material == null) return false;
                for (Entity entity : entities(context, arguments, "who")) {
                    if (entity instanceof Player player) {
                        ItemStack held = player.getInventory().getItemInMainHand();
                        if (held != null && held.getType() == material) return true;
                    }
                }
                return false;
            })
            .build());

        registry.register(ConditionDefinition.builder("inventory-empty", "Is the player's inventory empty?")
            .syntax("<who:target>'s inventory is empty")
            .syntax("player's inventory is empty")
            .example("if player's inventory is empty:")
            .tests((context, arguments) -> {
                for (Entity entity : entities(context, arguments, "who")) {
                    if (entity instanceof Player player && count(player, null) == 0) return true;
                }
                return false;
            })
            .build());
    }

    // ------------------------------------------------------------ chance/misc

    private static void registerChanceAndMisc(ConditionRegistry registry) {
        registry.register(ConditionDefinition.builder("chance", "True with the given probability")
            .syntax("chance <percent:decimal> percent")
            .syntax("random chance <percent:decimal> percent")
            .syntax("there is a chance of <percent:decimal> percent")
            .example("if there is a chance of 25 percent:")
            .tests((context, arguments) -> {
                double percent = Math.max(0d, Math.min(100d, arguments.decimal("percent")));
                return ThreadLocalRandom.current().nextDouble(100d) < percent;
            })
            .build());

        registry.register(ConditionDefinition.builder("script-enabled", "Is another script loaded and enabled?")
            .syntax("script <name:word> is enabled")
            .syntax("script <name:word> is loaded")
            .example("if script economy is enabled:")
            .tests((context, arguments) -> {
                Object scripts = context.services().plugin();
                if (scripts == null) return false;
                // The manager exposes isEnabled(String); accessed reflectively so the
                // language layer never depends on the plugin bootstrap class.
                try {
                    java.lang.reflect.Method method = scripts.getClass().getMethod("isScriptEnabled", String.class);
                    Object result = method.invoke(scripts, arguments.string("name"));
                    return result instanceof Boolean enabled && enabled;
                } catch (Throwable missing) {
                    return false;
                }
            })
            .build());
    }

    // ----------------------------------------------------------------- helpers

    private static List<Entity> entities(ExecContext context, Arguments arguments, String key) {
        if (arguments.has(key)) {
            Entity entity = Targets.entity(arguments.value(key), context);
            if (entity != null) return List.of(entity);
            List<Player> players = arguments.players(key);
            if (!players.isEmpty()) return List.copyOf(players);
        }
        if (context.actor() != null) return List.of(context.actor());
        if (context.player() != null) return List.of(context.player());
        return List.of();
    }

    private static boolean anyMatch(ExecContext context, Arguments arguments, String key,
                                    java.util.function.Predicate<Entity> predicate) {
        for (Entity entity : entities(context, arguments, key)) {
            if (predicate.test(entity)) return true;
        }
        return false;
    }

    private static boolean compareHealth(ExecContext context, Arguments arguments, boolean above) {
        double amount = arguments.number("amount");
        for (Entity entity : entities(context, arguments, "who")) {
            if (entity instanceof LivingEntity living) {
                if (above ? living.getHealth() > amount : living.getHealth() < amount) return true;
            }
        }
        return false;
    }

    private static boolean compare(ExecContext context, Arguments arguments, boolean above) {
        Value actual = read(context, arguments);
        if (actual.type() != ValueType.INT && actual.type() != ValueType.DECIMAL) return false;
        double amount = arguments.number("amount");
        return above ? actual.asDouble() > amount : actual.asDouble() < amount;
    }

    private static World world(ExecContext context, Arguments arguments) {
        if (arguments.has("world")) return arguments.world("world");
        return context.world();
    }

    /** Reads data the same way the evaluator does, including the offline `key:name` form. */
    private static Value read(ExecContext context, Arguments arguments) {
        String key = arguments.string("key");
        if (arguments.has("who")) {
            Value target = arguments.value("who");
            Entity entity = Targets.entity(target, context);
            if (entity != null) return context.data().get(entity, key);
            if (target.type() == ValueType.STRING && !target.asString().isEmpty()) {
                return context.data().getGlobal(key + ":" + target.asString().toLowerCase(Locale.ROOT));
            }
        }
        return context.data().getGlobal(key);
    }

    private static int count(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            if (material == null || stack.getType() == material) total += stack.getAmount();
        }
        return total;
    }

    /** Forgotten cooldowns are pruned lazily so long-running servers do not leak memory. */
    public static void pruneCooldowns() {
        long now = System.currentTimeMillis();
        COOLDOWNS.entrySet().removeIf(entry -> entry.getValue() < now);
    }
}
