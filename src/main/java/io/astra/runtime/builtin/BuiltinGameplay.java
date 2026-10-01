package io.astra.runtime.builtin;

import io.astra.runtime.ActionFailure;
import io.astra.runtime.Arguments;
import io.astra.runtime.ExecContext;
import io.astra.runtime.GameplayServices;
import io.astra.runtime.Targets;
import io.astra.runtime.Value;
import io.astra.runtime.action.ActionDefinition;
import io.astra.runtime.action.ActionRegistry;
import io.astra.runtime.condition.ConditionDefinition;
import io.astra.runtime.condition.ConditionRegistry;
import io.astra.runtime.event.EventAdapter;
import io.astra.runtime.event.EventDefinition;
import io.astra.runtime.event.EventRegistry;
import io.astra.runtime.expression.ExpressionDefinition;
import io.astra.runtime.expression.ExpressionRegistry;
import io.astra.runtime.region.RegionDefinition;
import io.astra.runtime.region.RegionEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * The gameplay catalogue: regions, menus, scoreboards, boss bars, holograms, NPCs, bosses
 * and quests.
 *
 * <p>It is one class because the entries are one family - they all speak to the services in
 * {@link GameplayServices}, they are all gated by the {@code features.*} switches in
 * {@code config.yml}, and they are all resolved through the same argument helpers. Splitting
 * them into eight near-identical files would spread the same three lines of wiring across
 * eight classes.</p>
 *
 * <p>No action here touches the world directly: each one delegates to its service, which
 * owns the lifetime of what it creates (so an unloaded script gives its entities back) and
 * the safety rules (so a menu button cannot empty an inventory).</p>
 */
public final class BuiltinGameplay {

    /** Names for bosses so each one can be tracked and removed with its script. */
    private static final java.util.concurrent.atomic.AtomicInteger BOSS_SEQUENCE =
        new java.util.concurrent.atomic.AtomicInteger();

    private BuiltinGameplay() {
    }

    // --------------------------------------------------------------- registration

    /** Registers the region triggers. */
    public static void registerTriggers(EventRegistry registry) {
        EventAdapter transition = new EventAdapter() {
            @Override public Player player(org.bukkit.event.Event event) {
                return event instanceof RegionEvents.Transition crossing ? crossing.player() : null;
            }

            @Override public Entity actor(org.bukkit.event.Event event) {
                return player(event);
            }

            @Override public void prepare(ExecContext context, org.bukkit.event.Event event) {
                if (event instanceof RegionEvents.Transition crossing) {
                    context.setArgument("region", Value.str(crossing.regionName()));
                    RegionDefinition previous = crossing.previous();
                    context.setArgument("previous_region",
                        previous == null ? Value.NULL : Value.str(previous.name()));
                }
            }
        };

        registry.register(EventDefinition.builder("region enter", RegionEvents.Enter.class,
                "A player crosses into a region declared by a script")
            .adapter(transition)
            .trigger("player enters region <region:text>", "region:region")
            .trigger("player enters the region <region:text>", "region:region")
            .trigger("player enters a region")
            .trigger("a player enters region <region:text>", "region:region")
            .trigger("player walks into region <region:text>", "region:region")
            .example("on player enters region spawn:\n    send \"&aWelcome to spawn!\"")
            .build());

        registry.register(EventDefinition.builder("region leave", RegionEvents.Leave.class,
                "A player crosses out of a region declared by a script")
            .adapter(transition)
            .trigger("player leaves region <region:text>", "region:region")
            .trigger("player leaves the region <region:text>", "region:region")
            .trigger("player leaves a region")
            .trigger("a player leaves region <region:text>", "region:region")
            .trigger("player walks out of region <region:text>", "region:region")
            .example("on player leaves region spawn:\n    send \"&7Goodbye!\"")
            .build());
    }

    /** Registers the gameplay actions. */
    public static void registerActions(ActionRegistry registry) {
        registerItemActions(registry);
        registerMenuActions(registry);
        registerScoreboardActions(registry);
        registerBossBarActions(registry);
        registerHologramActions(registry);
        registerNpcActions(registry);
        registerMobActions(registry);
        registerQuestActions(registry);
    }

    /** Registers the gameplay conditions. */
    public static void registerConditions(ConditionRegistry registry) {
        registry.register(ConditionDefinition.builder("inside-region", "Checks whether a player is inside a region")
            .syntax("player is inside region <name:text>")
            .syntax("player is in region <name:text>")
            .syntax("the player is inside the region <name:text>")
            .syntax("<who:target> is inside region <name:text>")
            .example("if player is inside region spawn:\n    send \"&aYou are at spawn\"")
            .tests((context, arguments) -> {
                GameplayServices gameplay = gameplay(context);
                if (gameplay == null || gameplay.regions() == null) return false;
                Location location = who(context, arguments).isEmpty() ? context.location()
                    : who(context, arguments).get(0).getLocation();
                if (location == null || location.getWorld() == null) return false;
                return gameplay.regions().contains(arguments.string("name"), location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ());
            })
            .build());

        registry.register(ConditionDefinition.builder("is-npc", "Checks whether an entity is an NPC spawned by a script")
            .syntax("the entity is an npc")
            .syntax("entity is an npc")
            .syntax("the clicked entity is an npc")
            .syntax("<who:target> is an npc")
            .example("on player interacts:\n    if the clicked entity is an npc:\n        send \"&7Hello!\"")
            .tests((context, arguments) -> {
                GameplayServices gameplay = gameplay(context);
                if (gameplay == null || gameplay.npcs() == null) return false;
                Entity entity = arguments.has("who") ? Targets.entity(arguments.value("who"), context)
                    : context.secondary() != null ? context.secondary() : context.actor();
                return entity != null && gameplay.npcs().nameOf(entity) != null;
            })
            .build());

        registry.register(ConditionDefinition.builder("quest-complete", "Checks whether a player has finished a quest")
            .syntax("player has completed quest <quest:text>")
            .syntax("player has finished quest <quest:text>")
            .syntax("quest <quest:text> is complete")
            .example("if player has completed quest tutorial:\n    give player 1 diamond")
            .tests((context, arguments) ->
                quests(context) != null && quests(context).isComplete(holder(context), arguments.string("quest")))
            .build());

        registry.register(ConditionDefinition.builder("quest-progress", "Checks a player's quest progress")
            .syntax("player has at least <amount:number> progress in quest <quest:text>")
            .syntax("quest <quest:text> progress is at least <amount:number>")
            .example("if player has at least 5 progress in quest tutorial:\n    send \"&aWell done!\"")
            .tests((context, arguments) -> {
                var quests = quests(context);
                if (quests == null) return false;
                return quests.progress(holder(context), arguments.string("quest")) >= arguments.number("amount");
            })
            .build());

        registry.register(ConditionDefinition.builder("menu-open", "Checks whether a player has an Astra menu open")
            .syntax("player has a menu open")
            .syntax("the player is viewing a menu")
            .example("if player has a menu open:\n    cancel event")
            .tests((context, arguments) -> {
                GameplayServices gameplay = gameplay(context);
                if (gameplay == null || gameplay.menus() == null) return false;
                List<Player> players = who(context, arguments);
                Player player = players.isEmpty() ? context.player() : players.get(0);
                return player != null && gameplay.menus().isViewing(player);
            })
            .build());
    }

    /** Registers the gameplay expressions. */
    public static void registerExpressions(ExpressionRegistry registry) {
        registry.register(ExpressionDefinition.builder("region-at", "The innermost region at a location")
            .syntax("the region at <where:location>")
            .syntax("region at <where:location>")
            .returns("text")
            .example("set {current} to the region at player's location")
            .evaluates((context, arguments) -> {
                GameplayServices gameplay = gameplay(context);
                if (gameplay == null || gameplay.regions() == null) return Value.NULL;
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null || location.getWorld() == null) return Value.NULL;
                RegionDefinition region = gameplay.regions().regionAt(location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ());
                return region == null ? Value.NULL : Value.str(region.name());
            })
            .build());

        registry.register(ExpressionDefinition.builder("quest-percent", "A player's quest progress as a percentage")
            .syntax("quest <quest:text> percent of <who:target>")
            .syntax("the quest <quest:text> percent of <who:target>")
            .returns("number")
            .example("set {progress} to quest tutorial percent of player")
            .evaluates((context, arguments) -> {
                var quests = quests(context);
                if (quests == null) return Value.num(0);
                Entity holder = arguments.has("who") ? Targets.entity(arguments.value("who"), context)
                    : context.player();
                return Value.num(quests.percent(holder, arguments.string("quest")));
            })
            .build());
    }

    // -------------------------------------------------------------------- items

    private static void registerItemActions(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("give-custom-item",
                "Gives an item declared by a script")
            .syntax("give <who:target> <amount:number> custom item <item:text>")
            .syntax("give <who:target> custom item <item:text>")
            .syntax("give custom item <item:text> to <who:target>")
            .example("give player 1 custom item legendary_sword")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                String name = arguments.string("item");
                var definition = gameplay.items().definition(name);
                if (definition == null) {
                    throw ActionFailure.silent("'" + name + "' is not an item declared by a script"
                        + suggestions(gameplay, name));
                }
                int amount = arguments.intOr("amount", definition.amount());
                List<Player> targets = who(context, arguments);
                if (targets.isEmpty()) throw ActionFailure.silent("There is nobody to give the item to");
                for (Player player : targets) {
                    var stack = gameplay.items().create(definition, amount);
                    if (stack == null) continue;
                    player.getInventory().addItem(stack).forEach((index, leftover) ->
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                }
            })
            .build());
    }

    // -------------------------------------------------------------------- menus

    private static void registerMenuActions(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("open-menu", "Opens a menu declared by a script")
            .syntax("open menu <menu:text> for <who:target>")
            .syntax("open <menu:text> menu for <who:target>")
            .syntax("open menu <menu:text>")
            .example("on player right clicks a block:\n    open menu shop for player")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                String name = arguments.string("menu");
                if (gameplay.menus().definition(name) == null) {
                    throw ActionFailure.silent("'" + name + "' is not a menu declared by a script");
                }
                List<Player> targets = who(context, arguments);
                if (targets.isEmpty()) throw ActionFailure.silent("There is nobody to open the menu for");
                for (Player player : targets) gameplay.menus().open(player, name);
            })
            .build());

        registry.register(ActionDefinition.builder("close-menu", "Closes the menu a player has open")
            .syntax("close menu of <who:target>")
            .syntax("close the menu of <who:target>")
            .syntax("close menu")
            .example("on player clicks in a menu:\n    close menu of player")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                for (Player player : who(context, arguments)) gameplay.menus().close(player);
            })
            .build());
    }

    // -------------------------------------------------------------- scoreboards

    private static void registerScoreboardActions(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("create-scoreboard", "Creates or replaces a sidebar scoreboard")
            .syntax("create scoreboard <name:text> titled <title:text...>")
            .syntax("create scoreboard <name:text>")
            .example("create scoreboard stats titled \"&6&lYour Stats\"")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                String title = arguments.has("title") ? render(context, arguments, "title") : arguments.string("name");
                gameplay.scoreboards().register(context.script() == null ? "" : context.script().name(),
                    arguments.string("name"), title, List.of());
            })
            .build());

        registry.register(ActionDefinition.builder("set-scoreboard-line",
                "Sets one line of a scoreboard and refreshes everyone seeing it")
            .syntax("set line <line:number> of scoreboard <name:text> to <text:text...>")
            .syntax("set scoreboard <name:text> line <line:number> to <text:text...>")
            .example("set line 1 of scoreboard stats to \"&aCoins: &f%coins%\"")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                String name = arguments.string("name");
                if (gameplay.scoreboards().definition(name) == null) {
                    // A line update on a missing board creates it, which is what a
                    // "create the board in one line" script expects.
                    gameplay.scoreboards().register(context.script() == null ? "" : context.script().name(),
                        name, name, List.of());
                }
                gameplay.scoreboards().setLine(name, (int) arguments.number("line"), render(context, arguments, "text"));
            })
            .build());

        registry.register(ActionDefinition.builder("show-scoreboard", "Shows a scoreboard to players")
            .syntax("show scoreboard <name:text> to <who:target>")
            .syntax("show scoreboard <name:text>")
            .example("on player join:\n    show scoreboard stats to player")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                if (gameplay.scoreboards().definition(arguments.string("name")) == null) {
                    throw ActionFailure.silent("'" + arguments.string("name")
                        + "' is not a scoreboard declared by a script");
                }
                for (Player player : who(context, arguments)) {
                    gameplay.scoreboards().show(player, arguments.string("name"));
                }
            })
            .build());

        registry.register(ActionDefinition.builder("hide-scoreboard", "Restores the normal scoreboard for players")
            .syntax("hide scoreboard for <who:target>")
            .syntax("hide scoreboard")
            .example("on player quits:\n    hide scoreboard for player")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                for (Player player : who(context, arguments)) gameplay.scoreboards().hide(player);
            })
            .build());

        registry.register(ActionDefinition.builder("remove-scoreboard", "Removes a scoreboard declaration")
            .syntax("remove scoreboard <name:text>")
            .example("remove scoreboard stats")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                if (!gameplay.scoreboards().remove(arguments.string("name"))) {
                    throw ActionFailure.silent("'" + arguments.string("name")
                        + "' is not a scoreboard declared by a script");
                }
            })
            .build());
    }

    // ---------------------------------------------------------------- boss bars

    private static void registerBossBarActions(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("create-bossbar", "Creates or replaces a boss bar")
            .syntax("create bossbar <name:text> titled <title:text...>")
            .syntax("create bossbar <name:text> titled <title:text...> colored <color:text>")
            .syntax("create bossbar <name:text>")
            .example("create bossbar wave titled \"&cWave 1\" colored red")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                String title = arguments.has("title") ? render(context, arguments, "title") : arguments.string("name");
                String color = arguments.stringOr("color", "purple");
                gameplay.bossbars().create(context.script() == null ? "" : context.script().name(),
                    arguments.string("name"), title, color, "solid",
                    arguments.has("progress") ? arguments.decimal("progress") : 1.0, true);
            })
            .build());

        registry.register(ActionDefinition.builder("set-bossbar", "Updates a boss bar in place")
            .syntax("set bossbar <name:text> title to <title:text...>")
            .syntax("set bossbar <name:text> progress to <progress:number>")
            .syntax("set bossbar <name:text> color to <color:text>")
            .syntax("set bossbar <name:text> style to <style:text>")
            .syntax("set bossbar <name:text> visible to <visible:boolean>")
            .syntax("set bossbar <name:text> title <title:text...>")
            .syntax("set bossbar <name:text> progress <progress:number>")
            .example("set bossbar wave progress to 0.5")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                String name = arguments.string("name");
                String title = arguments.has("title") ? render(context, arguments, "title") : null;
                Double progress = arguments.has("progress") ? progress(context, arguments) : null;
                String color = arguments.has("color") ? arguments.string("color") : null;
                String style = arguments.has("style") ? arguments.string("style") : null;
                Boolean visible = arguments.has("visible") ? arguments.bool("visible") : null;
                if (title == null && progress == null && color == null && style == null && visible == null) {
                    throw ActionFailure.silent("Nothing to change on bossbar '" + name + "'");
                }
                if (!gameplay.bossbars().update(name, title, progress, color, style, visible)) {
                    throw ActionFailure.silent("'" + name + "' is not a bossbar declared by a script");
                }
            })
            .build());

        registry.register(ActionDefinition.builder("show-bossbar", "Shows a boss bar to players")
            .syntax("show bossbar <name:text> to <who:target>")
            .syntax("show bossbar <name:text>")
            .example("on player join:\n    show bossbar wave to player")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                String name = arguments.string("name");
                if (!arguments.has("who") && arguments.has("name")) {
                    gameplay.bossbars().showToEveryone(name);
                    return;
                }
                for (Player player : who(context, arguments)) gameplay.bossbars().showTo(name, player);
            })
            .build());

        registry.register(ActionDefinition.builder("hide-bossbar", "Hides a boss bar from players")
            .syntax("hide bossbar <name:text> from <who:target>")
            .syntax("hide bossbar <name:text>")
            .example("hide bossbar wave from player")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                String name = arguments.string("name");
                for (Player player : who(context, arguments)) gameplay.bossbars().hideFrom(name, player);
            })
            .build());

        registry.register(ActionDefinition.builder("remove-bossbar", "Removes a boss bar completely")
            .syntax("remove bossbar <name:text>")
            .example("remove bossbar wave")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                if (!gameplay.bossbars().remove(arguments.string("name"))) {
                    throw ActionFailure.silent("'" + arguments.string("name")
                        + "' is not a bossbar declared by a script");
                }
            })
            .build());
    }

    // ---------------------------------------------------------------- holograms

    private static void registerHologramActions(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("create-hologram", "Creates a floating text hologram")
            .syntax("create hologram <name:text> at <where:location> with text <text:text...>")
            .syntax("create hologram <name:text> at <where:location>")
            .syntax("create hologram <name:text> with text <text:text...>")
            .example("create hologram shop_sign at player's location with text \"&6&lSHOP\"")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null || location.getWorld() == null) {
                    throw ActionFailure.silent("There is nowhere to place the hologram");
                }
                List<String> lines = arguments.has("text") ? List.of(render(context, arguments, "text")) : List.of();
                if (gameplay.holograms().create(context.script() == null ? "" : context.script().name(),
                    arguments.string("name"), location, lines) == null) {
                    throw ActionFailure.silent("The hologram could not be created");
                }
            })
            .build());

        registry.register(ActionDefinition.builder("set-hologram-text", "Replaces a hologram's text")
            .syntax("set hologram <name:text> text to <text:text...>")
            .syntax("set hologram <name:text> to <text:text...>")
            .example("set hologram shop_sign text to \"&6&lSALE\"")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                if (!gameplay.holograms().setText(arguments.string("name"),
                    List.of(render(context, arguments, "text")))) {
                    throw ActionFailure.silent("'" + arguments.string("name")
                        + "' is not a hologram declared by a script");
                }
            })
            .build());

        registry.register(ActionDefinition.builder("move-hologram", "Moves a hologram to a new location")
            .syntax("move hologram <name:text> to <where:location>")
            .example("move hologram shop_sign to player's location")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null || !gameplay.holograms().move(arguments.string("name"), location,
                    context.script() == null ? "" : context.script().name())) {
                    throw ActionFailure.silent("'" + arguments.string("name")
                        + "' is not a hologram declared by a script");
                }
            })
            .build());

        registry.register(ActionDefinition.builder("remove-hologram", "Removes a hologram")
            .syntax("remove hologram <name:text>")
            .syntax("delete hologram <name:text>")
            .example("remove hologram shop_sign")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                if (!gameplay.holograms().remove(arguments.string("name"))) {
                    throw ActionFailure.silent("'" + arguments.string("name")
                        + "' is not a hologram declared by a script");
                }
            })
            .build());
    }

    // --------------------------------------------------------------------- NPCs

    private static void registerNpcActions(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("spawn-npc", "Spawns a script-owned NPC")
            .syntax("spawn npc <name:text> of type <type:text> at <where:location>")
            .syntax("spawn npc <name:text> at <where:location>")
            .syntax("spawn npc <name:text> of type <type:text> at <where:location> named <display:text...>")
            .example("spawn npc shopkeeper of type villager at player's location named \"&aShopkeeper\"")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                String type = arguments.stringOr("type", "villager");
                String display = arguments.has("display") ? render(context, arguments, "display") : null;
                if (gameplay.npcs().spawn(context.script() == null ? "" : context.script().name(),
                    arguments.string("name"), type, location, display) == null) {
                    throw ActionFailure.silent("The NPC could not be spawned here");
                }
            })
            .build());

        registry.register(ActionDefinition.builder("remove-npc", "Removes a script-owned NPC")
            .syntax("remove npc <name:text>")
            .syntax("despawn npc <name:text>")
            .example("remove npc shopkeeper")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                if (!gameplay.npcs().remove(arguments.string("name"))) {
                    throw ActionFailure.silent("'" + arguments.string("name") + "' is not an NPC spawned by a script");
                }
            })
            .build());

        registry.register(ActionDefinition.builder("npc-say", "Shows a line of speech on an NPC's name tag")
            .syntax("make npc <name:text> say <message:text...>")
            .syntax("npc <name:text> says <message:text...>")
            .example("make npc shopkeeper say \"&aWelcome to my shop!\"")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                if (!gameplay.npcs().say(arguments.string("name"), render(context, arguments, "message"))) {
                    throw ActionFailure.silent("'" + arguments.string("name") + "' is not an NPC spawned by a script");
                }
            })
            .build());

        registry.register(ActionDefinition.builder("npc-look", "Turns an NPC towards a player")
            .syntax("make npc <name:text> look at <who:target>")
            .syntax("npc <name:text> looks at <who:target>")
            .example("make npc shopkeeper look at player")
            .executes((context, arguments) -> {
                GameplayServices gameplay = require(context);
                List<Player> targets = who(context, arguments);
                if (targets.isEmpty()) throw ActionFailure.silent("There is nobody for the NPC to look at");
                if (!gameplay.npcs().lookAt(arguments.string("name"), targets.get(0))) {
                    throw ActionFailure.silent("'" + arguments.string("name") + "' is not an NPC spawned by a script");
                }
            })
            .build());
    }

    // ------------------------------------------------------------ mobs and bosses

    private static void registerMobActions(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("spawn-boss", "Spawns a named mob with health")
            .syntax("spawn boss <type:entity> at <where:location> with <health:number> health")
            .syntax("spawn boss <type:entity> at <where:location>")
            .example("spawn boss zombie at player's location with 100 health")
            .executes((context, arguments) -> {
                Location location = arguments.has("where") ? arguments.location("where") : context.location();
                if (location == null || location.getWorld() == null) {
                    throw ActionFailure.silent("There is nowhere to spawn the boss");
                }
                var type = MaterialTable.shared().entityType(arguments.string("type"));
                if (type == null) {
                    throw ActionFailure.silent("'" + arguments.string("type") + "' is not a known mob");
                }
                Entity spawned = location.getWorld().spawnEntity(location, type);
                if (!(spawned instanceof LivingEntity living)) {
                    spawned.remove();
                    throw ActionFailure.silent("'" + arguments.string("type") + "' is not a living mob");
                }
                double health = arguments.has("health") ? Math.max(1, arguments.decimal("health")) : 20;
                living.setMaxHealth(health);
                living.setHealth(health);
                GameplayServices gameplay = gameplay(context);
                if (gameplay != null && gameplay.npcs() != null) {
                    String script = context.script() == null ? "" : context.script().name();
                    gameplay.npcs().track(script, "boss_" + BOSS_SEQUENCE.incrementAndGet(), living);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("set-mob-health", "Sets the health of a living entity")
            .syntax("set health of <who:target> to <health:number>")
            .syntax("set <who:target> health to <health:number>")
            .example("set health of the target to 10")
            .executes((context, arguments) -> {
                List<Entity> entities = targets(context, arguments);
                if (entities.isEmpty()) throw ActionFailure.silent("There is no entity to change");
                double health = Math.max(0, arguments.decimal("health"));
                for (Entity entity : entities) {
                    if (entity instanceof LivingEntity living) {
                        living.setMaxHealth(Math.max(1, health));
                        living.setHealth(Math.min(health, living.getMaxHealth()));
                    }
                }
            })
            .build());

        registry.register(ActionDefinition.builder("set-mob-name", "Names a living entity")
            .syntax("name <who:target> <name:text...>")
            .syntax("set the name of <who:target> to <name:text...>")
            .example("name the target \"&cBoss\"")
            .executes((context, arguments) -> {
                List<Entity> entities = targets(context, arguments);
                if (entities.isEmpty()) throw ActionFailure.silent("There is no entity to name");
                String name = render(context, arguments, "name");
                for (Entity entity : entities) {
                    entity.setCustomName(name);
                    entity.setCustomNameVisible(true);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("make-mob-target", "Makes a mob attack a target")
            .syntax("make <who:target> target <victim:target>")
            .syntax("make <who:target> attack <victim:target>")
            .example("make the zombie target the player")
            .executes((context, arguments) -> {
                if (!(arguments.has("who") && arguments.has("victim"))) {
                    throw ActionFailure.silent("Say who should attack whom");
                }
                Entity attacker = Targets.entity(arguments.value("who"), context);
                Entity victim = Targets.entity(arguments.value("victim"), context);
                if (attacker instanceof org.bukkit.entity.Mob mob && victim instanceof LivingEntity living) {
                    mob.setTarget(living);
                    return;
                }
                throw ActionFailure.silent("Only a mob can be told to attack something");
            })
            .build());
    }

    // ------------------------------------------------------------------- quests

    private static void registerQuestActions(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("start-quest", "Starts a quest with a target goal")
            .syntax("start quest <quest:text> with goal <goal:number> for <who:target>")
            .syntax("start quest <quest:text> with goal <goal:number>")
            .syntax("start quest <quest:text> for <who:target>")
            .example("on player joins:\n    start quest tutorial with goal 5 for player")
            .executes((context, arguments) -> {
                var quests = requireQuests(context);
                int goal = (int) Math.max(1, arguments.has("goal") ? arguments.number("goal") : 1);
                for (Entity holder : holders(context, arguments)) {
                    quests.addProgress(holder, arguments.string("quest"), 0, goal);
                }
            })
            .build());

        registry.register(ActionDefinition.builder("add-quest-progress", "Adds progress to a quest")
            .syntax("add <amount:number> progress to quest <quest:text> for <who:target>")
            .syntax("add <amount:number> progress to quest <quest:text>")
            .syntax("add <amount:number> to quest <quest:text> of <who:target>")
            .syntax("add <amount:number> to quest <quest:text>")
            .example("add 1 progress to quest tutorial for player")
            .executes((context, arguments) -> {
                var quests = requireQuests(context);
                String quest = arguments.string("quest");
                int amount = (int) arguments.number("amount");
                for (Entity holder : holders(context, arguments)) {
                    if (quests.addProgress(holder, quest, amount, 0)) {
                        context.services().logger().debug("Quest '" + quest + "' completed for "
                            + io.astra.runtime.ExecContext.describeEntity(holder));
                    }
                }
            })
            .build());

        registry.register(ActionDefinition.builder("complete-quest", "Marks a quest as completed")
            .syntax("complete quest <quest:text> for <who:target>")
            .syntax("complete quest <quest:text>")
            .example("complete quest tutorial for player")
            .executes((context, arguments) -> {
                var quests = requireQuests(context);
                for (Entity holder : holders(context, arguments)) {
                    quests.complete(holder, arguments.string("quest"));
                }
            })
            .build());

        registry.register(ActionDefinition.builder("reset-quest", "Clears a quest's progress")
            .syntax("reset quest <quest:text> for <who:target>")
            .syntax("reset quest <quest:text>")
            .example("reset quest tutorial for player")
            .executes((context, arguments) -> {
                var quests = requireQuests(context);
                for (Entity holder : holders(context, arguments)) {
                    quests.reset(holder, arguments.string("quest"));
                }
            })
            .build());
    }

    // ------------------------------------------------------------------ helpers

    private static GameplayServices gameplay(ExecContext context) {
        return context == null || context.services() == null ? null : context.services().gameplay();
    }

    /** The gameplay services, or a diagnosable failure when the layer is missing. */
    private static GameplayServices require(ExecContext context) throws ActionFailure {
        GameplayServices gameplay = gameplay(context);
        if (gameplay == null) {
            throw ActionFailure.silent("The gameplay services are not available on this server");
        }
        return gameplay;
    }

    private static io.astra.runtime.quest.QuestService requireQuests(ExecContext context) throws ActionFailure {
        GameplayServices gameplay = require(context);
        if (gameplay.quests() == null) throw ActionFailure.silent("Quests are not available on this server");
        return gameplay.quests();
    }

    private static io.astra.runtime.quest.QuestService quests(ExecContext context) {
        GameplayServices gameplay = gameplay(context);
        return gameplay == null ? null : gameplay.quests();
    }

    /** The entity a quest belongs to: the named target, else the actor, else the player. */
    private static Entity holder(ExecContext context) {
        return context.actor() != null ? context.actor() : context.player();
    }

    /** Every entity a quest action applies to. */
    private static List<Entity> holders(ExecContext context, Arguments arguments) {
        if (arguments.has("who")) {
            List<Player> players = arguments.players("who");
            if (!players.isEmpty()) return new ArrayList<>(players);
            Entity entity = Targets.entity(arguments.value("who"), context);
            if (entity != null) return List.of(entity);
        }
        Entity holder = holder(context);
        return holder == null ? List.of() : List.of(holder);
    }

    /** Players named by an argument, falling back to the context's player. */
    private static List<Player> who(ExecContext context, Arguments arguments) {
        if (arguments.has("who")) {
            List<Player> players = arguments.players("who");
            if (!players.isEmpty()) return players;
        }
        if (context.player() != null) return List.of(context.player());
        return List.of();
    }

    /** Entities named by an argument, falling back to the context's actor. */
    private static List<Entity> targets(ExecContext context, Arguments arguments) {
        if (arguments.has("who")) {
            Entity entity = Targets.entity(arguments.value("who"), context);
            if (entity != null) return List.of(entity);
            List<Player> players = arguments.players("who");
            if (!players.isEmpty()) return new ArrayList<>(players);
        }
        if (context.actor() != null) return List.of(context.actor());
        if (context.player() != null) return List.of(context.player());
        return List.of();
    }

    /** Renders text through placeholders and the text service. */
    private static String render(ExecContext context, Arguments arguments, String key) {
        return context.services().text().render(arguments.string(key),
            text -> context.services().placeholders().resolve(text, context));
    }

    /** A boss bar progress: accepts {@code 0.5} as well as {@code 50}. */
    private static double progress(ExecContext context, Arguments arguments) {
        double value = arguments.decimal("progress");
        if (value > 1.0) value = value / 100.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** "Did you mean" hints for a mistyped custom item name. */
    private static String suggestions(GameplayServices gameplay, String name) {
        List<String> names = new ArrayList<>();
        for (var item : gameplay.items().all()) names.add(item.name());
        if (names.isEmpty()) return "";
        var nearest = io.astra.util.Strings.nearest(name == null ? "" : name.toLowerCase(Locale.ROOT), names, 3);
        return nearest.isEmpty() ? "" : "; did you mean " + String.join(" or ", nearest) + "?";
    }
}
