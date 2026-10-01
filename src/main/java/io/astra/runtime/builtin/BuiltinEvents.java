package io.astra.runtime.builtin;

import io.astra.runtime.event.EventAdapter;
import io.astra.runtime.event.EventDefinition;
import io.astra.runtime.event.EventRegistry;

import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * The built-in trigger catalogue.
 *
 * <p>Every entry here is data: an event class, an adapter that says who the actors are,
 * and the phrases the parser accepts for it. Because triggers are registered rather than
 * hard-coded, the dynamic listener in {@code io.astra.runtime.event} only has to attach to
 * the event classes that the currently loaded scripts actually use - and adding a trigger
 * never changes the parser.</p>
 *
 * <p>The catalogue covers the events scripts realistically need: players, blocks,
 * entities, inventories, worlds and weather. It intentionally stops short of the exotic
 * corners of the Bukkit API, and everything it registers is documented through
 * {@link EventDefinition#doc()} so {@code /astra info} always tells the truth.</p>
 */
public final class BuiltinEvents {

    private BuiltinEvents() {
    }

    /** Register every built-in trigger. */
    public static void registerAll(EventRegistry registry) {
        registerPlayers(registry);
        registerBlocks(registry);
        registerEntities(registry);
        registerInventories(registry);
        registerWorld(registry);
        registerNaturalLanguagePhrases(registry);
    }

    // ------------------------------------------------------------------ players

    private static void registerPlayers(EventRegistry registry) {
        add(registry, "player join", PlayerJoinEvent.class, EventAdapter.ofPlayer(),
            "A player joins the server",
            "on player join:\n    send \"&aWelcome %player%!\"",
            "player joins", "player joins|connects", "a player joins|connects",
            "someone joins|connects", "when a player joins|connects");

        add(registry, "player quit", PlayerQuitEvent.class, EventAdapter.ofPlayer(),
            "A player leaves the server",
            "on player quit:\n    broadcast \"&7%player% left\"",
            "player quits|leaves|disconnects", "a player quits|leaves|disconnects",
            "someone quits|leaves", "when a player quits|leaves");

        add(registry, "player death", PlayerDeathEvent.class, adapter(
                event -> ((PlayerDeathEvent) event).getEntity() instanceof org.bukkit.entity.Player player
                    ? player : null,
                event -> ((PlayerDeathEvent) event).getEntity(),
                event -> ((PlayerDeathEvent) event).getEntity().getKiller()),
            "A player dies",
            "on player death:\n    broadcast \"&4%player% died\"",
            "player dies|is killed", "player death", "a player dies|is killed",
            "when a player dies|is killed");

        add(registry, "player respawn", PlayerRespawnEvent.class, EventAdapter.ofPlayer(),
            "A player respawns after dying",
            "on player respawn:\n    send \"&7Welcome back!\"",
            "player respawns|revives", "a player respawns", "when a player respawns");

        add(registry, "player kick", PlayerKickEvent.class, EventAdapter.ofPlayer(),
            "A player is kicked",
            "on player kick:\n    log \"kicked %player%\"",
            "player is kicked|gets kicked", "a player is kicked|gets kicked");

        add(registry, "player move", PlayerMoveEvent.class, EventAdapter.ofPlayer(),
            "A player moves from one block to another (fires every tick, use with care)",
            "on player move:\n    if player is in water:\n        send \"&bSplash!\"",
            "player moves|walks|runs", "a player moves|walks",
            "player changes location|block");

        add(registry, "player chat", AsyncPlayerChatEvent.class, EventAdapter.ofPlayer(),
            "A player sends a chat message",
            "on player chat:\n    if message contains \"hello\":\n        send \"&aHi!\"",
            "player chats|talks|sends a message|says something",
            "a player chats|talks|sends a message", "chat|message",
            "when a player chats|talks|sends a message");

        add(registry, "player command", PlayerCommandPreprocessEvent.class, EventAdapter.ofPlayer(),
            "A player runs a command from chat",
            "on player command:\n    if command contains \"/sell\":\n        cancel event",
            "player runs a command|types a command|uses a command",
            "a player runs a command|types a command", "player commands|command",
            "when a player runs a command|types a command");

        add(registry, "player drop item", PlayerDropItemEvent.class, EventAdapter.ofPlayer(),
            "A player drops an item",
            "on player drop item:\n    cancel event",
            "player drops|drops an item|throws an item", "a player drops|drops an item",
            "item drop|drop item");

        add(registry, "player pickup item", PlayerPickupItemEvent.class, EventAdapter.ofPlayer(),
            "A player picks an item up",
            "on player pickup item:\n    send \"&e+1 %item%\"",
            "player picks up|collects an item|picks up an item",
            "a player picks up|collects an item", "item pickup|pickup item");

        add(registry, "player level change", PlayerLevelChangeEvent.class, EventAdapter.ofPlayer(),
            "A player level changes",
            "on player level change:\n    send \"&aLevel %level%!\"",
            "player levels up|gains a level|loses a level", "player level change",
            "a player levels up|gains a level");

        add(registry, "player exp change", PlayerExpChangeEvent.class, EventAdapter.ofPlayer(),
            "A player gains or loses experience",
            "on player exp change:\n    multiply exp by 2",
            "player gains experience|gains xp|gains exp", "player exp change|xp change",
            "a player gains experience|gains xp");

        add(registry, "player advancement", PlayerAdvancementDoneEvent.class, EventAdapter.ofPlayer(),
            "A player completes an advancement",
            "on player advancement:\n    broadcast \"&d%player% made progress!\"",
            "player completes an advancement|gets an advancement|unlocks an advancement",
            "player advancement|advancement done",
            "a player completes an advancement|gets an advancement");

        add(registry, "player eat", PlayerItemConsumeEvent.class, EventAdapter.ofPlayer(),
            "A player eats or drinks something",
            "on player eat:\n    if item is golden apple:\n        send \"&6Tasty\"",
            "player eats|drinks|consumes something", "player consumes an item",
            "a player eats|drinks|consumes something");

        add(registry, "player fish", PlayerFishEvent.class, EventAdapter.ofPlayer(),
            "A player uses a fishing rod (including a successful catch)",
            "on player fish:\n    if state is caught:\n        give player 1 cod",
            "player fishes|catches a fish|uses a fishing rod",
            "a player fishes|catches a fish", "fishing|fish");

        add(registry, "player teleport", PlayerTeleportEvent.class, EventAdapter.ofPlayer(),
            "A player teleports",
            "on player teleport:\n    send \"&dWhoosh!\"",
            "player teleports|is teleported", "a player teleports|is teleported",
            "when a player teleports|is teleported");

        add(registry, "player change world", PlayerChangedWorldEvent.class, EventAdapter.ofPlayer(),
            "A player moves to another world",
            "on player change world:\n    send \"&aEntering %world%\"",
            "player changes world|switches world|enters a world",
            "a player changes world|switches world",
            "when a player changes world|switches world");

        add(registry, "player change gamemode", PlayerGameModeChangeEvent.class, EventAdapter.ofPlayer(),
            "A player game mode changes",
            "on player change gamemode:\n    send \"&7Now playing %gamemode%\"",
            "player changes gamemode|switches gamemode",
            "a player changes gamemode|switches gamemode", "gamemode change",
            "when a player changes gamemode|switches gamemode");

        add(registry, "player sneak", PlayerToggleSneakEvent.class, EventAdapter.ofPlayer(),
            "A player starts or stops sneaking",
            "on player sneak:\n    cancel event",
            "player sneaks|toggles sneak|starts sneaking",
            "a player sneaks|toggles sneak", "sneak|sneaking");

        add(registry, "player sprint", PlayerToggleSprintEvent.class, EventAdapter.ofPlayer(),
            "A player starts or stops sprinting",
            "on player sprint:\n    send \"&bFast!\"",
            "player sprints|toggles sprint|starts sprinting",
            "a player sprints|toggles sprint", "sprint|sprinting");

        add(registry, "player fly", PlayerToggleFlightEvent.class, EventAdapter.ofPlayer(),
            "A player starts or stops flying",
            "on player fly:\n    cancel event",
            "player flies|toggles flight|starts flying",
            "a player flies|toggles flight", "flight|flying");

        add(registry, "player swap hands", PlayerSwapHandItemsEvent.class, EventAdapter.ofPlayer(),
            "A player swaps their main hand and off hand items",
            "on player swap hands:\n    cancel event",
            "player swaps items|swaps hands", "a player swaps items|swaps hands",
            "swap hands|swap items");

        add(registry, "player interacts", PlayerInteractEvent.class, adapter(
                event -> ((PlayerInteractEvent) event).getPlayer(), null, null),
            "A player interacts with a block, item or air",
            "on player interacts:\n    if block is dirt:\n        send \"&7dirt\"",
            "player interacts|right clicks|left clicks",
            "a player interacts|right clicks|left clicks", "interact|interaction");

        add(registry, "player harvest block", PlayerHarvestBlockEvent.class, EventAdapter.ofPlayer(),
            "A player harvests a block with a tool (for example right clicks a crop)",
            "on player harvest block:\n    give player 1 wheat",
            "player harvests a block|harvests a crop",
            "a player harvests a block|harvests a crop", "harvest block|harvest");

        add(registry, "player bucket fill", PlayerBucketFillEvent.class, EventAdapter.ofPlayer(),
            "A player fills a bucket",
            "on player bucket fill:\n    send \"&bFilled a bucket\"",
            "player fills a bucket|uses a bucket on water",
            "a player fills a bucket", "bucket fill");

        add(registry, "player bucket empty", PlayerBucketEmptyEvent.class, EventAdapter.ofPlayer(),
            "A player empties a bucket",
            "on player bucket empty:\n    cancel event",
            "player empties a bucket|pours water|pours lava",
            "a player empties a bucket", "bucket empty");

        add(registry, "player item held", PlayerItemHeldEvent.class, EventAdapter.ofPlayer(),
            "A player selects a different hotbar slot",
            "on player item held:\n    send \"&7Slot %slot%\"",
            "player switches item slot|changes hotbar slot|selects an item",
            "a player switches item slot|changes hotbar slot", "item held|hotbar switch");

        add(registry, "player item break", PlayerItemBreakEvent.class, EventAdapter.ofPlayer(),
            "A player item breaks from use",
            "on player item break:\n    send \"&cYour tool broke!\"",
            "player's item breaks|player breaks an item|an item breaks",
            "a player's item breaks|an item breaks", "item break|tool break");

        add(registry, "player animation", PlayerAnimationEvent.class, EventAdapter.ofPlayer(),
            "A player swings their arm",
            "on player animation:\n    stop",
            "player swings an arm|player animates", "a player swings an arm",
            "animation|arm swing");

        add(registry, "player shear", PlayerShearEntityEvent.class, EventAdapter.ofPlayer(),
            "A player shears an entity",
            "on player shear:\n    give player 1 white wool",
            "player shears|shears an entity|shears a sheep",
            "a player shears|shears an entity", "shear|shearing");

        add(registry, "player sleeps", PlayerBedEnterEvent.class, EventAdapter.ofPlayer(),
            "A player enters a bed",
            "on player sleeps:\n    send \"&7Good night\"",
            "player sleeps|enters a bed|goes to bed",
            "a player sleeps|enters a bed", "bed enter|sleep");

        add(registry, "player respawn point", org.bukkit.event.player.PlayerSpawnChangeEvent.class,
            EventAdapter.ofPlayer(),
            "A player's spawn point changes",
            "on player respawn point:\n    log \"spawn changed\"",
            "player changes spawn|respawn point changes",
            "a player changes spawn", "spawn change");

        add(registry, "player edits sign", SignChangeEvent.class, adapter(
                event -> ((SignChangeEvent) event).getPlayer(), null, null),
            "A player edits a sign",
            "on player edits sign:\n    cancel event",
            "player edits a sign|writes on a sign",
            "a player edits a sign|writes on a sign", "sign change|edit sign");

        add(registry, "food level change", FoodLevelChangeEvent.class, adapter(
                event -> null, event -> ((FoodLevelChangeEvent) event).getEntity(), null),
            "An entity's food level changes",
            "on food level change:\n    cancel event",
            "food level changes|player gets hungry|player becomes full",
            "a food level changes|player gets hungry", "food level|hunger");
    }

    // ------------------------------------------------------------------- blocks

    private static void registerBlocks(EventRegistry registry) {
        addWithFilters(registry, "block break", BlockBreakEvent.class, adapter(
                event -> ((BlockBreakEvent) event).getPlayer(), null, null),
            "A player breaks a block",
            "on player breaks diamond ore:\n    give player 3 diamonds",
            new String[] {"material:block"},
            "player breaks|mines|destroys <block:material>", "block break",
            "a block is broken|player breaks a block",
            "when a player breaks|mines <block:material>");

        addWithFilters(registry, "block place", BlockPlaceEvent.class, adapter(
                event -> ((BlockPlaceEvent) event).getPlayer(), null, null),
            "A player places a block",
            "on player places tnt:\n    cancel event",
            new String[] {"material:block"},
            "player places|places down <block:material>", "block place",
            "a block is placed|player places a block",
            "when a player places <block:material>");

        addWithFilters(registry, "block grow", BlockGrowEvent.class, EventAdapter.ofBlock(),
            "A block grows (crops, vines, ...)",
            "on block grows:\n    stop",
            new String[] {"material:block"},
            "<block:material> grows|block grows", "a block grows", "when <block:material> grows");

        addWithFilters(registry, "block fade", BlockFadeEvent.class, EventAdapter.ofBlock(),
            "A block fades away (melting snow, drying farmland, ...)",
            "on snow fades:\n    cancel event",
            new String[] {"material:block"},
            "<block:material> fades|block fades", "a block fades", "when <block:material> fades");

        addWithFilters(registry, "block burn", BlockBurnEvent.class, EventAdapter.ofBlock(),
            "A block burns away in fire",
            "on block burns:\n    cancel event",
            new String[] {"material:block"},
            "<block:material> burns|block burns", "a block burns", "when <block:material> burns");

        addWithFilters(registry, "block ignite", BlockIgniteEvent.class, EventAdapter.ofBlock(),
            "A block catches fire",
            "on block ignites:\n    cancel event",
            new String[] {"material:block"},
            "<block:material> ignites|catches fire|block ignites",
            "a block ignites|catches fire", "when <block:material> ignites");

        add(registry, "block explode", BlockExplodeEvent.class, EventAdapter.ofBlock(),
            "A block explodes",
            "on block explodes:\n    log \"boom\"",
            "block explodes|a block explodes", "when a block explodes");

        addWithFilters(registry, "block flow", BlockFromToEvent.class, EventAdapter.ofBlock(),
            "A liquid or falling block spreads to another block",
            "on water flows:\n    cancel event",
            new String[] {"material:block"},
            "<block:material> flows|spreads|block flows", "liquid flows", "when <block:material> flows");

        add(registry, "structure grow", StructureGrowEvent.class, EventAdapter.ofBlock(),
            "A tree or other structure grows",
            "on structure grows:\n    log \"a tree grew\"",
            "structure grows|a tree grows|tree grows", "when a tree grows|structure grows");
    }

    // ----------------------------------------------------------------- entities

    private static void registerEntities(EventRegistry registry) {
        add(registry, "entity death", EntityDeathEvent.class, EventAdapter.ofEntityAndDamager(),
            "A non-player entity dies",
            "on zombie dies:\n    if killer is player:\n        give killer 5 coins",
            "entity dies|mob dies|<mob:entity> dies", "a mob dies|<mob:entity> dies",
            "when <mob:entity> dies|is killed");

        // "when player kills zombie" needs the *killer* as the primary actor, so the
        // coins go to the player, not to the mob that died.
        addWithFilters(registry, "player kills entity", EntityDeathEvent.class, adapter(
                event -> ((EntityDeathEvent) event).getEntity().getKiller(),
                null,
                event -> ((EntityDeathEvent) event).getEntity()),
            "A player kills a mob",
            "when player kills zombie:\n    add 5 coins to player",
            new String[] {"entity type:mob", "killer is player:true"},
            "player kills|defeats|slays|murders <mob:entity>", "kills <mob:entity>");

        add(registry, "entity damaged", EntityDamageEvent.class, EventAdapter.ofEntity(),
            "An entity takes damage",
            "on entity damaged:\n    if damage > 10:\n        log \"big hit\"",
            "entity takes damage|is damaged|entity is hurt", "a entity takes damage|is damaged",
            "when an entity takes damage|is damaged");

        add(registry, "entity attacked", EntityDamageByEntityEvent.class, EventAdapter.ofEntityAndDamager(),
            "An entity is attacked by another entity",
            "on player attacks zombie:\n    send \"&cHit!\"",
            "entity is attacked|is hit by another entity|entity is damaged by an entity",
            "a mob is attacked|is hit", "when an entity is attacked|is hit");

        add(registry, "player damaged", EntityDamageEvent.class, adapter(
                event -> ((EntityDamageEvent) event).getEntity() instanceof org.bukkit.entity.Player p ? p : null,
                event -> ((EntityDamageEvent) event).getEntity(), null),
            "A player takes damage",
            "on player damaged:\n    if damage > 6:\n        send \"&cOuch!\"",
            "player takes damage|is hurt|is damaged|player gets hurt",
            "a player takes damage|is hurt", "when a player takes damage|is hurt");

        add(registry, "player attacked", EntityDamageByEntityEvent.class, adapter(
                event -> ((EntityDamageByEntityEvent) event).getEntity() instanceof org.bukkit.entity.Player p ? p : null,
                event -> ((EntityDamageByEntityEvent) event).getEntity(),
                event -> ((EntityDamageByEntityEvent) event).getDamager()),
            "A player is attacked by an entity",
            "on player attacked:\n    if attacker is zombie:\n        send \"&4Zombie!\"",
            "player is attacked|is hit by a mob|player is attacked by an entity",
            "a player is attacked|is hit by a mob", "when a player is attacked|is hit by a mob");

        add(registry, "entity explodes", EntityExplodeEvent.class, EventAdapter.ofEntity(),
            "An entity (creeper, TNT, ...) explodes",
            "on creeper explodes:\n    cancel event",
            "entity explodes|<mob:entity> explodes|creeper explodes|tnt explodes",
            "a creeper explodes|an entity explodes", "when <mob:entity> explodes");

        add(registry, "entity spawns", CreatureSpawnEvent.class, EventAdapter.ofEntity(),
            "A creature spawns",
            "on zombie spawns:\n    cancel event",
            "entity spawns|mob spawns|<mob:entity> spawns",
            "a mob spawns|<mob:entity> spawns", "when <mob:entity> spawns");

        add(registry, "entity tamed", EntityTameEvent.class, EventAdapter.ofEntity(),
            "An entity is tamed",
            "on entity is tamed:\n    send \"&aA new friend!\"",
            "entity is tamed|<mob:entity> is tamed|animal is tamed",
            "a mob is tamed|an animal is tamed", "when <mob:entity> is tamed");

        add(registry, "entity breeds", EntityBreedEvent.class, adapter(
                event -> null,
                event -> ((EntityBreedEvent) event).getEntity(),
                event -> ((EntityBreedEvent) event).getBreeder()),
            "Two animals breed",
            "on entity breeds:\n    log \"baby animal\"",
            "entity breeds|<mob:entity> breeds|animals breed",
            "a mob breeds|animals breed", "when <mob:entity> breeds");

        add(registry, "entity targets", EntityTargetLivingEntityEvent.class, adapter(
                event -> null,
                event -> ((EntityTargetLivingEntityEvent) event).getEntity(),
                event -> ((EntityTargetLivingEntityEvent) event).getTarget()),
            "A mob targets an entity",
            "on zombie targets a player:\n    send \"&4The zombie sees you\"",
            "entity targets|<mob:entity> targets a player|mob targets a player",
            "a mob targets a player", "when <mob:entity> targets a player");

        add(registry, "entity resurrects", EntityResurrectEvent.class, EventAdapter.ofEntity(),
            "A totem of undying saves an entity",
            "on entity resurrects:\n    broadcast \"&6Saved by a totem\"",
            "entity resurrects|entity is resurrected|totem saves an entity",
            "a totem saves an entity", "when an entity resurrects|is resurrected");

        add(registry, "entity shoots", EntityShootBowEvent.class, EventAdapter.ofEntity(),
            "An entity shoots a bow",
            "on player shoots a bow:\n    log \"arrow fired\"",
            "entity shoots|shoots a bow|fires an arrow",
            "a player shoots a bow|an entity shoots", "when a player shoots a bow|fires an arrow");

        add(registry, "projectile launches", ProjectileLaunchEvent.class, EventAdapter.ofEntity(),
            "A projectile is launched",
            "on projectile launches:\n    log \"projectile\"",
            "projectile launches|an arrow is shot|a projectile is thrown",
            "an arrow is shot|a projectile is thrown", "when a projectile launches");

        add(registry, "projectile hits", ProjectileHitEvent.class, EventAdapter.ofEntity(),
            "A projectile hits something",
            "on arrow hits:\n    log \"hit\"",
            "projectile hits|arrow hits|a projectile lands",
            "an arrow hits|a projectile lands", "when a projectile hits|lands");

        add(registry, "spawner spawns", SpawnerSpawnEvent.class, EventAdapter.ofEntity(),
            "A mob spawner spawns a mob",
            "on spawner spawns:\n    cancel event",
            "spawner spawns a mob|spawner spawns", "a spawner spawns a mob",
            "when a spawner spawns a mob");

        add(registry, "vehicle destroyed", VehicleDestroyEvent.class, adapter(
                event -> null,
                event -> ((VehicleDestroyEvent) event).getVehicle(),
                event -> ((VehicleDestroyEvent) event).getAttacker()),
            "A vehicle (boat, minecart, ...) is destroyed",
            "on vehicle destroyed:\n    log \"vehicle gone\"",
            "vehicle is destroyed|vehicle destroyed|boat is destroyed",
            "a vehicle is destroyed", "when a vehicle is destroyed");
    }

    // ------------------------------------------------------------- inventories

    private static void registerInventories(EventRegistry registry) {
        add(registry, "inventory click", InventoryClickEvent.class, EventAdapter.ofInventory(),
            "A player clicks in an open inventory",
            "on inventory click:\n    if slot is 13:\n        cancel event",
            "player clicks in a menu|clicks a slot|clicks in an inventory",
            "player clicks in an inventory|clicks a slot", "inventory click|menu click");

        add(registry, "inventory open", InventoryOpenEvent.class, EventAdapter.ofInventory(),
            "A player opens an inventory",
            "on inventory open:\n    send \"&7Opened %menu%\"",
            "player opens a menu|opens an inventory", "a player opens an inventory",
            "inventory open|menu open");

        add(registry, "inventory close", InventoryCloseEvent.class, EventAdapter.ofInventory(),
            "A player closes an inventory",
            "on inventory close:\n    log \"closed\"",
            "player closes a menu|closes an inventory", "a player closes an inventory",
            "inventory close|menu close");

        add(registry, "player crafts", CraftItemEvent.class, EventAdapter.ofInventory(),
            "A player crafts an item",
            "on player crafts:\n    send \"&aCrafted %item%\"",
            "player crafts|crafts an item", "a player crafts|crafts an item", "craft|crafting");

        add(registry, "player smelts", FurnaceExtractEvent.class, EventAdapter.ofPlayer(),
            "A player takes an item out of a furnace",
            "on player smelts:\n    send \"&6Smelted %item%\"",
            "player smelts|takes from a furnace", "a player smelts|takes from a furnace", "smelt");

        add(registry, "player trades", TradeSelectEvent.class, EventAdapter.ofInventory(),
            "A player selects a villager trade",
            "on player trades:\n    send \"&eTrade selected\"",
            "player trades|selects a trade|trades with a villager",
            "a player trades|selects a trade", "trade|villager trade");

        add(registry, "player enchants", EnchantItemEvent.class, adapter(
                event -> ((EnchantItemEvent) event).getEnchanter(), null, null),
            "A player enchants an item",
            "on player enchants:\n    send \"&dEnchanted!\"",
            "player enchants|enchants an item", "a player enchants|enchants an item",
            "enchant|enchanting");
    }

    // ------------------------------------------------------------------- world

    private static void registerWorld(EventRegistry registry) {
        add(registry, "weather changes", WeatherChangeEvent.class, EventAdapter.ofEntity(),
            "The weather changes",
            "on weather changes:\n    broadcast \"&7The weather turned\"",
            "weather changes|it starts raining|it stops raining",
            "the weather changes|it starts raining", "when the weather changes");

        add(registry, "thunder changes", ThunderChangeEvent.class, EventAdapter.ofEntity(),
            "A thunderstorm starts or stops",
            "on thunder changes:\n    broadcast \"&9Thunder!\"",
            "thunder changes|a storm starts|a storm stops",
            "the thunder changes|a storm starts", "when thunder changes|a storm starts");

        add(registry, "time skip", TimeSkipEvent.class, EventAdapter.ofEntity(),
            "Time skips forward (sleeping, /time set, ...)",
            "on night falls:\n    broadcast \"&7Night fell\"",
            "time skips|night falls|day breaks|a new day starts",
            "time skips|night falls", "when time skips|night falls");

        add(registry, "world loads", WorldLoadEvent.class, EventAdapter.ofEntity(),
            "A world is loaded",
            "on world loads:\n    log \"world loaded\"",
            "world loads|a world loads", "when a world loads");

        add(registry, "world unloads", WorldUnloadEvent.class, EventAdapter.ofEntity(),
            "A world is unloaded",
            "on world unloads:\n    log \"world unloaded\"",
            "world unloads|a world unloads", "when a world unloads");

        add(registry, "world saves", WorldSaveEvent.class, EventAdapter.ofEntity(),
            "A world is saved",
            "on world saves:\n    log \"world saved\"",
            "world saves|a world saves", "when a world saves");

        add(registry, "chunk loads", ChunkLoadEvent.class, EventAdapter.ofEntity(),
            "A chunk is loaded",
            "on chunk loads:\n    stop",
            "chunk loads|a chunk loads", "when a chunk loads");

        add(registry, "chunk unloads", ChunkUnloadEvent.class, EventAdapter.ofEntity(),
            "A chunk is unloaded",
            "on chunk unloads:\n    stop",
            "chunk unloads|a chunk unloads", "when a chunk unloads");

        add(registry, "console command", ServerCommandEvent.class, EventAdapter.ofEntity(),
            "The console runs a command",
            "on console command:\n    log \"%command%\"",
            "console runs a command|server runs a command",
            "the console runs a command", "when the console runs a command");
    }

    /**
     * Natural-language phrasings.
     *
     * <p>The natural language front end compiles sentences to the same trigger ids as the
     * structured mode; these extra patterns exist so that both modes accept the words a
     * player would actually type ("whenever", "every time", "if a player...").</p>
     */
    private static void registerNaturalLanguagePhrases(EventRegistry registry) {
        // Registered by id so the phrases stay next to the triggers they belong to.
        addExtra(registry, "player join",
            "whenever a player joins|connects", "every time a player joins|connects",
            "if a player joins|connects");
        addExtra(registry, "player quit",
            "whenever a player quits|leaves", "every time a player quits|leaves");
        addExtra(registry, "player death",
            "whenever a player dies|is killed", "every time a player dies|is killed");
        addExtra(registry, "block break",
            "whenever a player breaks|mines <block:material>",
            "every time a player breaks|mines <block:material>",
            "if a player breaks|mines <block:material>");
        addExtra(registry, "entity death",
            "whenever <mob:entity> dies|is killed", "every time <mob:entity> dies|is killed",
            "if <mob:entity> dies|is killed");
        addExtra(registry, "player chat",
            "whenever a player chats|talks", "if a player chats|talks");
        addExtra(registry, "weather changes",
            "whenever the weather changes|it starts raining",
            "if it starts raining|the weather changes");
        addExtra(registry, "time skip",
            "whenever night falls|time skips", "every time night falls|time skips");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Register a trigger.
     *
     * @param registry    target registry
     * @param id          stable trigger id
     * @param type        Bukkit event class
     * @param adapter     actor extraction
     * @param description one-line documentation
     * @param example     a complete example rule shown by {@code /astra info}
     * @param patterns    accepted phrases (first one is the canonical form)
     */
    private static void add(EventRegistry registry, String id, Class<? extends Event> type, EventAdapter adapter,
                            String description, String example, String... patterns) {
        addWithFilters(registry, id, type, adapter, description, example, new String[0], patterns);
    }

    /**
     * Register a trigger whose phrases carry slots bound to event properties.
     *
     * @param filterSpecs {@code property:slot} bindings evaluated before the rule body runs
     */
    private static void addWithFilters(EventRegistry registry, String id, Class<? extends Event> type,
                                       EventAdapter adapter, String description, String example,
                                       String[] filterSpecs, String... patterns) {
        EventDefinition.Builder builder = EventDefinition.builder(id, type, description).adapter(adapter);
        for (String pattern : patterns) {
            if (filterSpecs.length == 0) {
                builder.trigger(pattern);
            } else {
                builder.trigger(pattern, filterSpecs);
            }
        }
        if (example != null && !example.isBlank()) builder.example(example);
        registry.register(builder.build());
    }

    /** Add phrases to an already registered trigger. */
    private static void addExtra(EventRegistry registry, String id, String... patterns) {
        EventDefinition existing = registry.get(id);
        if (existing == null) return;
        java.util.List<EventDefinition.TriggerPattern> all = new java.util.ArrayList<>(existing.patterns());
        for (String pattern : patterns) {
            io.astra.language.parser.SyntaxTemplate template = io.astra.language.parser.SyntaxTemplate.compile(pattern);
            java.util.List<String> specs = template.hasSlot("block")
                ? java.util.List.of("material:block") : java.util.List.of();
            all.add(new EventDefinition.TriggerPattern(template, specs));
        }
        registry.register(new EventDefinition(existing.id(), existing.eventClass(), existing.adapter(), all,
            existing.priority(), existing.ignoreCancelled(), existing.doc()));
    }

    /** Convenience for the extractor-based adapters. */
    private static EventAdapter adapter(java.util.function.Function<Event, org.bukkit.entity.Player> player,
                                        java.util.function.Function<Event, org.bukkit.entity.Entity> entity,
                                        java.util.function.Function<Event, org.bukkit.entity.Entity> secondary) {
        return EventAdapter.of(player, entity, secondary);
    }
}
