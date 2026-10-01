package io.astra.runtime.builtin;

import io.astra.runtime.Arguments;
import io.astra.runtime.ExecContext;
import io.astra.runtime.Targets;
import io.astra.runtime.Value;
import io.astra.runtime.ValueType;
import io.astra.runtime.action.ActionDefinition;
import io.astra.runtime.action.ActionRegistry;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The built-in action catalogue.
 *
 * <p>Every action is a self-contained record of syntax plus behaviour, registered once at
 * startup. Actions never look at the AST: the arguments they receive are already
 * evaluated {@link Value}s wrapped in {@link Arguments}, which is what keeps the executor
 * small and the failure messages precise.</p>
 *
 * <p>Actions that touch the outside world (console commands, kicks, explosions) are marked
 * security-sensitive so {@code security.yml} is consulted before they run - and because
 * natural-language rules compile to exactly these actions, a sentence can never do more
 * than a structured rule could.</p>
 */
public final class BuiltinActions {

    /** Cache for the few reflective calls kept for cross-version resilience. */
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private BuiltinActions() {
    }

    /** Register the full built-in action set. */
    public static void registerAll(ActionRegistry registry) {
        registerMessaging(registry);
        registerCommands(registry);
        registerPlayerState(registry);
        registerWorld(registry);
        registerData(registry);
    }

    // --------------------------------------------------------------- messaging

    private static void registerMessaging(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("tell", "Sends a message to one or more players")
            .syntax("tell <who:target> <message:text...>")
            .syntax("send <who:target> <message:text...>")
            .syntax("send <message:text...> to <who:target>")
            .syntax("message <who:target> <message:text...>")
            .syntax("send <message:text...>")
            .syntax("tell <message:text...>")
            .example("tell player \"&aWelcome back!\"")
            .executes((context, arguments) -> {
                String message = render(context, arguments, "message");
                List<Player> targets = recipients(context, arguments, "who");
                if (targets.isEmpty()) {
                    CommandSender sender = context.sender();
                    if (sender != null && sender != context.player()) {
                        sender.sendMessage(message);
                    } else {
                        context.services().logger().debug(() -> "tell had no players to send to");
                    }
                    return;
                }
                for (Player player : targets) player.sendMessage(message);
            })
            .build());

        registry.register(ActionDefinition.builder("broadcast", "Sends a message to every online player")
            .syntax("broadcast <message:text...>")
            .syntax("announce <message:text...>")
            .syntax("broadcast to everyone <message:text...>")
            .example("broadcast \"&e%player% found treasure!\"")
            .executes((context, arguments) -> {
                String message = render(context, arguments, "message");
                if (message.isEmpty()) return;
                Bukkit.broadcastMessage(message);
            })
            .build());

        registry.register(ActionDefinition.builder("broadcast-to-world", "Sends a message to every player in a world")
            .syntax("broadcast to world <world:world> <message:text...>")
            .example("broadcast to world world_nether \"&cWatch out!\"")
            .executes((context, arguments) -> {
                String message = render(context, arguments, "message");
                World world = arguments.world("world");
                if (world == null) world = context.world();
                if (world == null || message.isEmpty()) return;
                for (Player player : world.getPlayers()) player.sendMessage(message);
            })
            .build());

        registry.register(ActionDefinition.builder("log", "Writes a line to the plugin log")
            .syntax("log <message:text...>")
            .syntax("print <message:text...>")
            .syntax("debug <message:text...>")
            .example("log \"%player% joined\"")
            .executes((context, arguments) -> {
                String message = render(context, arguments, "message");
                if (message.isEmpty()) return;
                context.services().logger().info(message);
            })
            .build());

        registry.register(ActionDefinition.builder("send-title", "Shows a title and optional subtitle to players")
            .syntax("send title <title:text...> to <who:player>")
            .syntax("send title <title:text...>")
            .syntax("send subtitle <subtitle:text...> to <who:player>")
            .syntax("send subtitle <subtitle:text...>")
            .example("send title \"&6Welcome!\" to player")
            .executes((context, arguments) -> {
                boolean subtitle = arguments.has("subtitle");
                String message = render(context, arguments, subtitle ? "subtitle" : "title");
                for (Player player : recipients(context, arguments, "who")) {
                    if (subtitle) {
                        player.sendTitle("", message, 10, 60, 10);
                    } else {
                        player.sendTitle(message, "", 10, 60, 10);
                    }
                }
            })
            .build());

        registry.register(ActionDefinition.builder("send-action-bar", "Shows a message above the hotbar")
            .syntax("send action bar <message:text...> to <who:player>")
            .syntax("send action bar <message:text...>")
            .syntax("send actionbar <message:text...> to <who:player>")
            .example("send action bar \"&b+5 coins\" to player")
            .executes((context, arguments) -> {
                String message = render(context, arguments, "message");
                for (Player player : recipients(context, arguments, "who")) {
                    ActionBar.send(player, message);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("play-sound", "Plays a sound at a player or location")
            .syntax("play sound <sound:word> to <who:player>")
            .syntax("play sound <sound:word> for <who:player>")
            .syntax("play sound <sound:word> at <where:location>")
            .example("play sound entity.experience_orb.pickup to player")
            .executes((context, arguments) -> {
                String sound = arguments.string("sound");
                if (sound.isEmpty()) return;
                if (arguments.has("where")) {
                    Location location = arguments.location("where");
                    if (location != null && location.getWorld() != null) {
                        location.getWorld().playSound(location, sound, 1f, 1f);
                    }
                    return;
                }
                for (Player player : recipients(context, arguments, "who")) {
                    player.playSound(player.getLocation(), sound, 1f, 1f);
                }
            })
            .build());
    }

    // ---------------------------------------------------------------- commands

    private static void registerCommands(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("console-command", "Runs a command as the console")
            .syntax("run <command:text...> as console")
            .syntax("execute <command:text...> as console")
            .syntax("run console command <command:text...>")
            .syntax("run command <command:text...> as console")
            .example("run \"give %player% diamond 1\" as console")
            .security()
            .executes((context, arguments) -> {
                String command = stripSlash(render(context, arguments, "command"));
                if (command.isEmpty()) return;
                context.services().security().checkConsoleCommand(command, context);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            })
            .build());

        registry.register(ActionDefinition.builder("player-command", "Makes a player run a command")
            .syntax("force <who:player> to run <command:text...>")
            .syntax("make <who:player> run <command:text...>")
            .syntax("let <who:player> run <command:text...>")
            .example("force player to run \"spawn\"")
            .security()
            .executes((context, arguments) -> {
                String command = stripSlash(render(context, arguments, "command"));
                if (command.isEmpty()) return;
                context.services().security().checkConsoleCommand(command, context);
                for (Player player : recipients(context, arguments, "who")) {
                    player.performCommand(command);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("kick", "Kicks a player from the server")
            .syntax("kick <who:player>")
            .syntax("kick <who:player> for <reason:text...>")
            .syntax("kick <who:player> with reason <reason:text...>")
            .example("kick player for \"&cBe nicer next time\"")
            .security()
            .executes((context, arguments) -> {
                String reason = arguments.has("reason") ? render(context, arguments, "reason") : "Kicked by AstraSyntax";
                for (Player player : recipients(context, arguments, "who")) {
                    player.kickPlayer(reason);
                }
            })
            .build());
    }

    // ------------------------------------------------------------ player state

    private static void registerPlayerState(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("give", "Gives items to a player - the amount may be written with the item")
            .syntax("give <who:target> <item:item>")
            .syntax("give <item:item> to <who:target>")
            .syntax("give <who:target> <amount:number> <item:material>")
            .example("give player 3 diamonds")
            .executes((context, arguments) -> {
                int amount = arguments.has("amount") ? (int) arguments.number("amount") : arguments.amount("item", 1);
                Material material = resolveMaterial(context, arguments.material("item"), arguments);
                if (material == null) return;
                ItemStack stack = new ItemStack(material, Math.max(1, amount));
                for (Entity entity : targets(context, arguments, "who")) {
                    if (entity instanceof Player player) {
                        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
                        for (ItemStack remaining : leftover.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), remaining);
                        }
                    } else if (entity != null) {
                        entity.getWorld().dropItemNaturally(entity.getLocation(), stack);
                    }
                }
            })
            .build());

        registry.register(ActionDefinition.builder("take", "Removes items from a player's inventory")
            .syntax("take <item:item> from <who:player>")
            .syntax("remove <item:item> from <who:player>")
            .syntax("take <amount:number> <item:material> from <who:player>")
            .example("take 5 diamonds from player")
            .executes((context, arguments) -> {
                int amount = arguments.has("amount") ? (int) arguments.number("amount") : arguments.amount("item", 1);
                Material material = resolveMaterial(context, arguments.material("item"), arguments);
                if (material == null) return;
                ItemStack stack = new ItemStack(material, Math.max(1, amount));
                for (Player player : recipients(context, arguments, "who")) {
                    player.getInventory().removeItem(stack);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("heal", "Restores health")
            .syntax("heal <who:target>")
            .syntax("heal <who:target> by <amount:number>")
            .syntax("set health of <who:target> to <amount:number>")
            .example("heal player by 5")
            .executes((context, arguments) -> {
                for (Entity entity : targets(context, arguments, "who")) {
                    if (!(entity instanceof Damageable damageable)) continue;
                    double max = entity instanceof LivingEntity living ? living.getMaxHealth() : 20d;
                    double value = arguments.has("amount")
                        ? (arguments.has("by") ? damageable.getHealth() + arguments.number("amount")
                            : arguments.number("amount"))
                        : max;
                    damageable.setHealth(Math.max(0d, Math.min(max, value)));
                }
            })
            .build());

        registry.register(ActionDefinition.builder("damage", "Deals damage to an entity")
            .syntax("damage <who:target> by <amount:number>")
            .syntax("hurt <who:target> by <amount:number>")
            .syntax("damage <who:target>")
            .example("damage player by 3")
            .executes((context, arguments) -> {
                double amount = arguments.has("amount") ? arguments.decimal("amount") : 1d;
                for (Entity entity : targets(context, arguments, "who")) {
                    if (entity instanceof Damageable damageable) damageable.damage(Math.max(0d, amount));
                }
            })
            .build());

        registry.register(ActionDefinition.builder("kill", "Kills an entity")
            .syntax("kill <who:target>")
            .example("kill player")
            .executes((context, arguments) -> {
                for (Entity entity : targets(context, arguments, "who")) {
                    if (entity instanceof Damageable damageable) damageable.setHealth(0d);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("feed", "Restores a player's food level")
            .syntax("feed <who:player>")
            .syntax("feed <who:player> by <amount:number>")
            .syntax("set food of <who:player> to <amount:number>")
            .example("feed player")
            .executes((context, arguments) -> {
                for (Player player : recipients(context, arguments, "who")) {
                    int food = arguments.has("amount")
                        ? (arguments.has("by") ? player.getFoodLevel() + (int) arguments.number("amount")
                            : (int) arguments.number("amount"))
                        : 20;
                    player.setFoodLevel(Math.max(0, Math.min(20, food)));
                    player.setSaturation(20f);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("set-gamemode", "Changes a player's game mode")
            .syntax("set game mode of <who:player> to <mode:word>")
            .syntax("set gamemode of <who:player> to <mode:word>")
            .syntax("set <who:player>'s game mode to <mode:word>")
            .example("set game mode of player to creative")
            .executes((context, arguments) -> {
                String mode = arguments.string("mode").trim().toLowerCase(Locale.ROOT);
                for (Player player : recipients(context, arguments, "who")) {
                    try {
                        player.setGameMode(org.bukkit.GameMode.valueOf(mode.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException error) {
                        throw io.astra.runtime.ActionFailure.silent("'" + mode
                            + "' is not a game mode (use survival, creative, adventure or spectator)");
                    }
                }
            })
            .build());

        registry.register(ActionDefinition.builder("give-experience", "Gives experience points or levels")
            .syntax("give <who:player> <amount:number> experience")
            .syntax("give <amount:number> experience to <who:player>")
            .syntax("give <who:player> <amount:number> levels")
            .example("give player 30 experience")
            .executes((context, arguments) -> {
                int amount = (int) arguments.number("amount");
                boolean levels = arguments.has("levels") || arguments.has("level");
                for (Player player : recipients(context, arguments, "who")) {
                    if (levels) {
                        player.giveExpLevels(amount);
                    } else {
                        player.giveExp(amount);
                    }
                }
            })
            .build());

        registry.register(ActionDefinition.builder("set-level", "Sets a player's experience level")
            .syntax("set level of <who:player> to <amount:number>")
            .syntax("set <who:player>'s level to <amount:number>")
            .example("set level of player to 10")
            .executes((context, arguments) -> {
                int level = Math.max(0, (int) arguments.number("amount"));
                for (Player player : recipients(context, arguments, "who")) player.setLevel(level);
            })
            .build());

        registry.register(ActionDefinition.builder("clear-inventory", "Clears a player's inventory")
            .syntax("clear inventory of <who:player>")
            .syntax("clear <who:player>'s inventory")
            .example("clear inventory of player")
            .executes((context, arguments) -> {
                for (Player player : recipients(context, arguments, "who")) player.getInventory().clear();
            })
            .build());

        registry.register(ActionDefinition.builder("close-inventory", "Closes the open inventory of players")
            .syntax("close inventory of <who:player>")
            .syntax("close <who:player>'s inventory")
            .example("close inventory of player")
            .executes((context, arguments) -> {
                for (Player player : recipients(context, arguments, "who")) player.closeInventory();
            })
            .build());

        registry.register(ActionDefinition.builder("set-flight", "Allows or denies flight")
            .syntax("allow <who:player> to fly")
            .syntax("make <who:player> fly")
            .syntax("stop <who:player> from flying")
            .example("allow player to fly")
            .executes((context, arguments) -> {
                boolean allow = !arguments.has("stop");
                for (Player player : recipients(context, arguments, "who")) {
                    player.setAllowFlight(allow);
                    if (!allow) player.setFlying(false);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("set-speed", "Sets walk or fly speed")
            .syntax("set walk speed of <who:player> to <amount:decimal>")
            .syntax("set fly speed of <who:player> to <amount:decimal>")
            .example("set walk speed of player to 0.3")
            .executes((context, arguments) -> {
                float speed = (float) Math.max(-1d, Math.min(1d, arguments.decimal("amount")));
                boolean fly = arguments.has("fly");
                for (Player player : recipients(context, arguments, "who")) {
                    if (fly) {
                        player.setFlySpeed(speed);
                    } else {
                        player.setWalkSpeed(speed);
                    }
                }
            })
            .build());

        registry.register(ActionDefinition.builder("apply-effect", "Applies a potion effect")
            .syntax("apply <effect:word> to <who:target> for <seconds:duration>")
            .syntax("give <who:target> <effect:word> for <seconds:duration>")
            .syntax("apply <effect:word> to <who:target>")
            .example("apply speed to player for 30 seconds")
            .executes((context, arguments) -> {
                PotionEffectType type = resolveEffect(context, arguments.string("effect"), arguments);
                if (type == null) return;
                int ticks = arguments.has("seconds")
                    ? (int) Math.min(Integer.MAX_VALUE, Math.max(1L, arguments.number("seconds") * 20L))
                    : 200;
                for (Entity entity : targets(context, arguments, "who")) {
                    if (entity instanceof LivingEntity living) {
                        living.addPotionEffect(new PotionEffect(type, ticks, 0));
                    }
                }
            })
            .build());

        registry.register(ActionDefinition.builder("set-fire", "Sets an entity on fire")
            .syntax("set <who:target> on fire")
            .syntax("set <who:target> on fire for <seconds:number>")
            .syntax("ignite <who:target>")
            .example("set player on fire for 5 seconds")
            .executes((context, arguments) -> {
                int ticks = arguments.has("seconds") ? (int) (arguments.number("seconds") * 20L) : 100;
                for (Entity entity : targets(context, arguments, "who")) entity.setFireTicks(ticks);
            })
            .build());

        registry.register(ActionDefinition.builder("extinguish", "Puts out fire on an entity")
            .syntax("extinguish <who:target>")
            .example("extinguish player")
            .executes((context, arguments) -> {
                for (Entity entity : targets(context, arguments, "who")) entity.setFireTicks(0);
            })
            .build());

        registry.register(ActionDefinition.builder("teleport", "Teleports an entity")
            .syntax("teleport <who:target> to <where:location>")
            .syntax("teleport <who:target> to <where:target>")
            .example("teleport player to spawn")
            .executes((context, arguments) -> {
                Location destination = arguments.location("where");
                if (destination == null) {
                    throw io.astra.runtime.ActionFailure.silent("The teleport destination could not be resolved");
                }
                for (Entity entity : targets(context, arguments, "who")) entity.teleport(destination);
            })
            .build());
    }

    // ------------------------------------------------------------------- world

    private static void registerWorld(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("spawn-entity", "Spawns one or more mobs")
            .syntax("spawn <mob:entity> at <where:location>")
            .syntax("spawn <count:number> <mob:entity> at <where:location>")
            .syntax("spawn <mob:entity>")
            .example("spawn 3 zombies at player's location")
            .executes((context, arguments) -> {
                MaterialTable materials = MaterialTable.shared();
                String canonical = materials.resolveEntity(arguments.string("mob"));
                if (canonical == null) {
                    throw io.astra.runtime.ActionFailure.silent("'" + arguments.string("mob")
                        + "' is not a known mob" + suggestEntity(arguments.string("mob")));
                }
                org.bukkit.entity.EntityType type;
                try {
                    type = org.bukkit.entity.EntityType.valueOf(canonical);
                } catch (IllegalArgumentException error) {
                    return;
                }
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null || location.getWorld() == null) {
                    throw io.astra.runtime.ActionFailure.silent("There is no place to spawn the mob");
                }
                int count = arguments.has("count") ? (int) Math.max(1, arguments.number("count")) : 1;
                for (int i = 0; i < Math.min(count, 100); i++) {
                    location.getWorld().spawnEntity(location, type);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("set-block", "Places a block")
            .syntax("set block at <where:location> to <block:material>")
            .syntax("place <block:material> at <where:location>")
            .example("set block at player's location to diamond block")
            .executes((context, arguments) -> {
                Material material = resolveMaterial(context, arguments.material("block"), arguments);
                if (material == null || !material.isBlock()) {
                    throw io.astra.runtime.ActionFailure.silent("'" + arguments.material("block")
                        + "' is not a placeable block");
                }
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null) return;
                location.getBlock().setType(material);
            })
            .build());

        registry.register(ActionDefinition.builder("break-block", "Removes a block")
            .syntax("break block at <where:location>")
            .syntax("remove block at <where:location>")
            .example("break block at player's location")
            .executes((context, arguments) -> {
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null) return;
                location.getBlock().setType(Material.AIR);
            })
            .build());

        registry.register(ActionDefinition.builder("lightning", "Strikes lightning")
            .syntax("strike lightning at <where:location>")
            .syntax("strike lightning effect at <where:location>")
            .example("strike lightning at player's location")
            .executes((context, arguments) -> {
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null || location.getWorld() == null) return;
                if (arguments.has("effect")) {
                    location.getWorld().strikeLightningEffect(location);
                } else {
                    location.getWorld().strikeLightning(location);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("explode", "Creates an explosion")
            .syntax("explode at <where:location> with power <amount:decimal>")
            .syntax("explode at <where:location>")
            .syntax("create explosion at <where:location> with power <amount:decimal>")
            .example("explode at player's location with power 2")
            .security()
            .executes((context, arguments) -> {
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null || location.getWorld() == null) return;
                float power = (float) (arguments.has("amount") ? arguments.decimal("amount") : 2d);
                location.getWorld().createExplosion(location.getX(), location.getY(), location.getZ(),
                    Math.max(0f, Math.min(20f, power)), false);
            })
            .build());

        registry.register(ActionDefinition.builder("drop-item", "Drops an item on the ground")
            .syntax("drop <item:item> at <where:location>")
            .syntax("drop <item:item>")
            .example("drop 1 diamond at player's location")
            .executes((context, arguments) -> {
                Material material = resolveMaterial(context, arguments.material("item"), arguments);
                if (material == null) return;
                int amount = Math.max(1, arguments.amount("item", 1));
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null || location.getWorld() == null) return;
                location.getWorld().dropItemNaturally(location, new ItemStack(material, amount));
            })
            .build());

        registry.register(ActionDefinition.builder("spawn-particle", "Spawns particles")
            .syntax("spawn particle <particle:word> at <where:location>")
            .syntax("spawn <count:number> particles <particle:word> at <where:location>")
            .example("spawn particle flame at player's location")
            .executes((context, arguments) -> {
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null || location.getWorld() == null) return;
                Object particle = MaterialTable.shared().resolveParticle(arguments.string("particle"));
                if (particle == null) {
                    throw io.astra.runtime.ActionFailure.silent("'" + arguments.string("particle")
                        + "' is not a known particle");
                }
                int count = arguments.has("count") ? (int) Math.max(1, arguments.number("count")) : 10;
                Particles.spawn(location.getWorld(), particle, location, count);
            })
            .build());

        registry.register(ActionDefinition.builder("set-time", "Sets the time of a world")
            .syntax("set time in <world:world> to <value:word>")
            .syntax("set time in <world:world> to <ticks:number>")
            .syntax("make it <value:word> in <world:world>")
            .example("set time in world to night")
            .executes((context, arguments) -> {
                World world = arguments.has("world") ? arguments.world("world") : context.world();
                if (world == null) return;
                long ticks;
                if (arguments.has("ticks")) {
                    ticks = arguments.number("ticks");
                } else {
                    ticks = switch (arguments.string("value").trim().toLowerCase(Locale.ROOT)) {
                        case "day", "morning", "sunrise" -> 0L;
                        case "noon" -> 6000L;
                        case "afternoon" -> 9000L;
                        case "sunset", "evening", "dusk" -> 12000L;
                        case "night" -> 13000L;
                        case "midnight" -> 18000L;
                        default -> -1L;
                    };
                    if (ticks < 0) {
                        throw io.astra.runtime.ActionFailure.silent("'" + arguments.string("value")
                            + "' is not a time of day (use day, noon, sunset, night or midnight)");
                    }
                }
                world.setTime(ticks);
            })
            .build());

        registry.register(ActionDefinition.builder("set-weather", "Changes the weather of a world")
            .syntax("set weather in <world:world> to <weather:word>")
            .syntax("make it <weather:word> in <world:world>")
            .example("set weather in world to thunder")
            .executes((context, arguments) -> {
                World world = arguments.has("world") ? arguments.world("world") : context.world();
                if (world == null) return;
                String weather = arguments.string("weather").trim().toLowerCase(Locale.ROOT);
                switch (weather) {
                    case "clear", "sun", "sunny" -> {
                        world.setStorm(false);
                        world.setThundering(false);
                    }
                    case "rain", "raining", "rainy" -> {
                        world.setStorm(true);
                        world.setThundering(false);
                    }
                    case "storm", "thunder", "thundering", "snow" -> {
                        world.setStorm(true);
                        world.setThundering(true);
                    }
                    default -> throw io.astra.runtime.ActionFailure.silent("'" + weather
                        + "' is not a weather type (use clear, rain or thunder)");
                }
            })
            .build());

        registry.register(ActionDefinition.builder("give-exp-orb", "Spawns experience orbs")
            .syntax("spawn <amount:number> experience orbs at <where:location>")
            .example("spawn 30 experience orbs at player's location")
            .executes((context, arguments) -> {
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null || location.getWorld() == null) return;
                ExperienceOrb orb = location.getWorld().spawn(location, ExperienceOrb.class);
                orb.setExperience((int) Math.max(1L, arguments.number("amount")));
            })
            .build());
    }

    // -------------------------------------------------------------------- data

    private static void registerData(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("set-data", "Stores a value for a player, entity or globally")
            .syntax("set <key:data key> of <who:target> to <value:expr>")
            .syntax("set <who:target>'s <key:data key> to <value:expr>")
            .syntax("set global <key:data key> to <value:expr>")
            .example("set kills of player to 0")
            .executes((context, arguments) -> {
                String key = arguments.string("key");
                Value value = arguments.has("value") ? arguments.value("value") : Value.NULL;
                writeData(context, arguments, key, value);
            })
            .build());

        registry.register(ActionDefinition.builder("add-data", "Adds to a stored number")
            .syntax("add <amount:number> to <key:data key> of <who:target>")
            .syntax("add <amount:number> <key:data key> to <who:target>")
            .syntax("increase <key:data key> of <who:target> by <amount:number>")
            .syntax("add <amount:number> to global <key:data key>")
            .syntax("add <amount:number> <key:data key> to global")
            .example("add 5 coins to player")
            .executes((context, arguments) -> {
                String key = arguments.string("key");
                double delta = arguments.number("amount");
                Value current = readData(context, arguments, key);
                writeData(context, arguments, key, io.astra.runtime.ValueMath.add(current, Value.dec(delta)));
            })
            .build());

        registry.register(ActionDefinition.builder("subtract-data", "Subtracts from a stored number")
            .syntax("remove <amount:number> from <key:data key> of <who:target>")
            .syntax("remove <amount:number> <key:data key> from <who:target>")
            .syntax("decrease <key:data key> of <who:target> by <amount:number>")
            .syntax("remove <amount:number> from global <key:data key>")
            .example("remove 5 coins from player")
            .executes((context, arguments) -> {
                String key = arguments.string("key");
                double delta = arguments.number("amount");
                Value current = readData(context, arguments, key);
                writeData(context, arguments, key, io.astra.runtime.ValueMath.subtract(current, Value.dec(delta)));
            })
            .build());

        registry.register(ActionDefinition.builder("delete-data", "Deletes a stored value")
            .syntax("delete <key:data key> of <who:target>")
            .syntax("unset <key:data key> of <who:target>")
            .syntax("delete global <key:data key>")
            .example("delete kills of player")
            .executes((context, arguments) -> {
                String key = arguments.string("key");
                if (arguments.has("who")) {
                    Entity entity = Targets.entity(arguments.value("who"), context);
                    if (entity != null) {
                        context.data().remove(entity, key);
                        return;
                    }
                }
                context.data().setGlobal(key, Value.NULL);
            })
            .build());

        registry.register(ActionDefinition.builder("save-data", "Flushes stored data to disk")
            .syntax("save data")
            .syntax("save data of <who:target>")
            .example("save data of player")
            .executes((context, arguments) -> {
                if (arguments.has("who")) {
                    Entity entity = Targets.entity(arguments.value("who"), context);
                    if (entity != null) {
                        context.data().flush(entity.getUniqueId());
                        return;
                    }
                }
                context.data().flushAll();
            })
            .build());
    }

    // ----------------------------------------------------------------- helpers

    /** Renders a text argument: placeholders first, then colour tags. */
    private static String render(ExecContext context, Arguments arguments, String key) {
        String raw = arguments.string(key);
        return context.services().text().render(raw, text -> context.services().placeholders().resolve(text, context));
    }

    /** Players named by an argument, falling back to the context's player. */
    private static List<Player> recipients(ExecContext context, Arguments arguments, String key) {
        if (arguments.has(key)) {
            List<Player> players = arguments.players(key);
            if (!players.isEmpty()) return players;
        }
        if (context.player() != null) return List.of(context.player());
        return List.of();
    }

    /** Entities named by an argument, falling back to the context's actor. */
    private static List<Entity> targets(ExecContext context, Arguments arguments, String key) {
        if (arguments.has(key)) {
            Entity entity = Targets.entity(arguments.value(key), context);
            if (entity != null) return List.of(entity);
            List<Player> players = arguments.players(key);
            if (!players.isEmpty()) return new ArrayList<>(players);
        }
        if (context.actor() != null) return List.of(context.actor());
        if (context.player() != null) return List.of(context.player());
        return List.of();
    }

    private static String stripSlash(String command) {
        String trimmed = command == null ? "" : command.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    private static Material resolveMaterial(ExecContext context, String name, Arguments arguments) {
        if (name == null || name.isBlank()) {
            throw io.astra.runtime.ActionFailure.silent("No item was given");
        }
        Material material = MaterialTable.shared().material(name);
        if (material == null) {
            throw io.astra.runtime.ActionFailure.silent("'" + name + "' is not a known item"
                + suggest(name));
        }
        return material;
    }

    private static PotionEffectType resolveEffect(ExecContext context, String name, Arguments arguments) {
        if (name == null || name.isBlank()) {
            throw io.astra.runtime.ActionFailure.silent("No potion effect was given");
        }
        Object resolved = MaterialTable.shared().resolvePotionEffect(name);
        if (!(resolved instanceof PotionEffectType type)) {
            throw io.astra.runtime.ActionFailure.silent("'" + name + "' is not a known potion effect");
        }
        return type;
    }

    private static String suggest(String name) {
        List<String> suggestions = MaterialTable.shared().suggestions(name);
        return suggestions.isEmpty() ? "" : " (did you mean " + String.join(", ", suggestions) + "?)";
    }

    private static String suggestEntity(String name) {
        List<String> suggestions = MaterialTable.shared().entitySuggestions(name);
        return suggestions.isEmpty() ? "" : " (did you mean " + String.join(", ", suggestions) + "?)";
    }

    /** Writes through the entity store, or the global store for a named/offline target. */
    private static void writeData(ExecContext context, Arguments arguments, String key, Value value) {
        if (arguments.has("who")) {
            Value targetValue = arguments.value("who");
            Entity entity = Targets.entity(targetValue, context);
            if (entity != null) {
                context.data().set(entity, key, value);
                return;
            }
            if (targetValue.type() == ValueType.STRING && !targetValue.asString().isEmpty()) {
                context.data().setGlobal(key + ":" + targetValue.asString().toLowerCase(Locale.ROOT), value);
                return;
            }
        }
        context.data().setGlobal(key, value);
    }

    /** Mirrors {@link #writeData} for reads so both halves agree on the storage key. */
    private static Value readData(ExecContext context, Arguments arguments, String key) {
        if (arguments.has("who")) {
            Value targetValue = arguments.value("who");
            Entity entity = Targets.entity(targetValue, context);
            if (entity != null) return context.data().get(entity, key);
            if (targetValue.type() == ValueType.STRING && !targetValue.asString().isEmpty()) {
                return context.data().getGlobal(key + ":" + targetValue.asString().toLowerCase(Locale.ROOT));
            }
        }
        return context.data().getGlobal(key);
    }

    /**
     * Action-bar support.
     *
     * <p>Spigot's API has no {@code sendActionBar}, and this project compiles against the
     * Bukkit API only (no Paper, no Adventure), so the call is made reflectively when the
     * server provides it and silently degrades to a normal message otherwise - a script
     * running on a Paper server gets a real action bar, and nothing breaks on Spigot.</p>
     */
    private static final class ActionBar {

        private ActionBar() {
        }

        static void send(Player player, String message) {
            if (message == null || message.isEmpty()) return;
            Method method = METHODS.computeIfAbsent("sendActionBar", key -> {
                try {
                    return Player.class.getMethod("sendActionBar", String.class);
                } catch (Throwable missing) {
                    return null;
                }
            });
            if (method != null) {
                try {
                    method.invoke(player, message);
                    return;
                } catch (Throwable failed) {
                    // Fall through to a plain message rather than losing the text.
                }
            }
            player.sendMessage(message);
        }
    }

    /** Particle support that tolerates API moves between Minecraft versions. */
    private static final class Particles {

        private Particles() {
        }

        static void spawn(World world, Object particle, Location location, int count) {
            Method method = METHODS.computeIfAbsent("spawnParticle", key -> {
                try {
                    return World.class.getMethod("spawnParticle", Class.forName("org.bukkit.Particle"),
                        Location.class, int.class);
                } catch (Throwable missing) {
                    return null;
                }
            });
            if (method == null) return;
            try {
                method.invoke(world, particle, location, count);
            } catch (Throwable failed) {
                // Particles are cosmetic: failing to draw them must never stop a rule.
            }
        }
    }

    /** Exposed for the documentation generator. */
    public static List<String> actionIds() {
        return List.of("tell", "broadcast", "broadcast-to-world", "log", "send-title", "send-action-bar",
            "play-sound", "console-command", "player-command", "kick", "give", "take", "heal", "damage",
            "kill", "feed", "set-gamemode", "give-experience", "set-level", "clear-inventory",
            "close-inventory", "set-flight", "set-speed", "apply-effect", "set-fire", "extinguish",
            "teleport", "spawn-entity", "set-block", "break-block", "lightning", "explode", "drop-item",
            "spawn-particle", "set-time", "set-weather", "give-exp-orb", "set-data", "add-data",
            "subtract-data", "delete-data", "save-data");
    }
}
