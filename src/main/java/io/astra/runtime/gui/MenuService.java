package io.astra.runtime.gui;

import io.astra.logging.AstraLogger;
import io.astra.platform.TextService;
import io.astra.runtime.ExecContext;
import io.astra.runtime.RuntimeServices;
import io.astra.runtime.script.Script;
import io.astra.runtime.item.ItemService;
import io.astra.runtime.script.RuleExecutor;

import java.util.function.Function;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Chest menus declared in scripts, plus the listener that runs their buttons.
 *
 * <p>Opening a menu builds a real {@link Inventory} whose holder is the menu name; the
 * listener only reacts to inventories this plugin created, so it never interferes with a
 * chest a player opened normally. Clicking a bound slot runs that slot's compiled
 * statements through the shared {@link RuleExecutor}, which means a button obeys exactly the
 * same action, condition and {@code security.yml} rules as any other Astra statement.</p>
 *
 * <p>The listener is registered once and unregistered on shutdown, never per script.</p>
 */
public final class MenuService implements Listener {

    /** Marks an inventory as belonging to Astra and remembers which menu it shows. */
    private static final class Holder implements InventoryHolder {
        private final String menu;
        private Inventory inventory;

        private Holder(String menu) {
            this.menu = menu;
        }

        @Override public Inventory getInventory() {
            return inventory;
        }
    }

    /** One open menu: which menu, and who owns the script that declared it. */
    private record Open(String menu, String script) {
    }

    private final Map<String, Entry> menus = new ConcurrentHashMap<>();
    /** Inventory identity -> what is open, so a click can be routed. */
    private final Map<Inventory, Open> open = new ConcurrentHashMap<>();
    /** Player -> inventory, so a script can close the menu it opened. */
    private final Map<UUID, Inventory> viewing = new ConcurrentHashMap<>();

    private final ItemService items;
    private final TextService text;
    private final AstraLogger logger;
    private final RuntimeServices services;
    private RuleExecutor executor;
    private Function<String, Script> scriptLookup = name -> null;
    private boolean registered;

    private record Entry(String script, MenuDefinition definition) {
    }

    public MenuService(RuntimeServices services, ItemService items, TextService text, AstraLogger logger) {
        this.services = services;
        this.items = items;
        this.text = text;
        this.logger = logger;
    }

    /** Wires the executor and the script lookup; both are built later than the service. */
    public void wire(RuleExecutor executor, Function<String, Script> scriptLookup) {
        this.executor = executor;
        this.scriptLookup = scriptLookup;
    }

    /** Registers the click listener; called once when the plugin enables. */
    public void start() {
        if (registered) return;
        if (services.plugin() instanceof org.bukkit.plugin.Plugin plugin) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    // ------------------------------------------------------------------ registry

    public void registerAll(String script, Collection<MenuDefinition> definitions) {
        if (definitions == null) return;
        for (MenuDefinition definition : definitions) {
            menus.put(key(definition.name()), new Entry(script == null ? "" : script, definition));
        }
    }

    public void unregisterAll(String script) {
        if (script == null) return;
        List<String> owned = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : menus.entrySet()) {
            if (script.equals(entry.getValue().script())) owned.add(entry.getKey());
        }
        for (String name : owned) menus.remove(name);
        // Close anyone who is looking at a menu that no longer exists.
        for (Map.Entry<UUID, Inventory> viewer : new ArrayList<>(viewing.entrySet())) {
            Open entry = open.get(viewer.getValue());
            if (entry == null || !script.equals(entry.script()) || !owned.contains(entry.menu())) continue;
            Player player = Bukkit.getPlayer(viewer.getKey());
            if (player != null) player.closeInventory();
            viewing.remove(viewer.getKey());
            open.remove(viewer.getValue());
        }
    }

    public MenuDefinition definition(String name) {
        Entry entry = menus.get(key(name));
        return entry == null ? null : entry.definition();
    }

    public String scriptOf(String name) {
        Entry entry = menus.get(key(name));
        return entry == null ? "" : entry.script();
    }

    public Collection<MenuDefinition> all() {
        List<MenuDefinition> out = new ArrayList<>();
        for (Entry entry : menus.values()) out.add(entry.definition());
        return out;
    }

    public int size() {
        return menus.size();
    }

    // ------------------------------------------------------------------ opening

    /** Opens a menu for a player. Returns false when the menu does not exist. */
    public boolean open(Player player, String name) {
        if (player == null) return false;
        MenuDefinition definition = definition(name);
        if (definition == null) return false;
        Entry entry = menus.get(key(name));
        Inventory inventory = Bukkit.createInventory(new Holder(key(name)), definition.size(),
            text.render(definition.title()));
        for (MenuDefinition.Slot slot : definition.slots()) {
            ItemStack stack = button(slot);
            if (stack != null && slot.index() < inventory.getSize()) inventory.setItem(slot.index(), stack);
        }
        open.put(inventory, new Open(key(name), entry == null ? "" : entry.script()));
        viewing.put(player.getUniqueId(), inventory);
        player.openInventory(inventory);
        return true;
    }

    /** Closes the menu a player has open, when this plugin opened it. */
    public boolean close(Player player) {
        if (player == null) return false;
        Inventory inventory = viewing.get(player.getUniqueId());
        if (inventory == null) return false;
        player.closeInventory();
        return true;
    }

    /** True when the player currently has an Astra menu open. */
    public boolean isViewing(Player player) {
        return player != null && viewing.containsKey(player.getUniqueId());
    }

    /** The name of the menu the player is viewing, or {@code null}. */
    public String viewing(Player player) {
        if (player == null) return null;
        Inventory inventory = viewing.get(player.getUniqueId());
        Open entry = inventory == null ? null : open.get(inventory);
        return entry == null ? null : entry.menu();
    }

    private ItemStack button(MenuDefinition.Slot slot) {
        if (slot.material() != null && items.isCustom(slot.material())) {
            return items.create(slot.material(), 1);
        }
        Material material = Material.matchMaterial(slot.material() == null ? "STONE"
            : slot.material().toUpperCase(Locale.ROOT));
        if (material == null) material = Material.STONE;
        ItemStack stack = new ItemStack(material, 1);
        if (slot.displayName() != null || !slot.lore().isEmpty()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (slot.displayName() != null) meta.setDisplayName(text.render(slot.displayName()));
                if (!slot.lore().isEmpty()) {
                    List<String> lore = new ArrayList<>(slot.lore().size());
                    for (String line : slot.lore()) lore.add(text.render(line));
                    meta.setLore(lore);
                }
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    // ------------------------------------------------------------------ events

    /** Runs a button's actions; cancellation stops item theft from a GUI. */
    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView() == null ? null : event.getView().getTopInventory();
        if (top == null) return;
        Open entry = open.get(top);
        if (entry == null) return;
        // Every click inside one of our menus is cancelled: buttons are not storage.
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        MenuDefinition definition = definition(entry.menu());
        if (definition == null) {
            // The script was unloaded between opening and clicking; close cleanly.
            player.closeInventory();
            return;
        }
        MenuDefinition.Slot slot = definition.slotAt(event.getRawSlot());
        if (slot == null || executor == null) return;
        run(player, entry, slot);
    }

    /** Dragging inside a menu is cancelled for the same reason clicks are. */
    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView() == null ? null : event.getView().getTopInventory();
        if (top != null && open.containsKey(top)) event.setCancelled(true);
    }

    /** Forgets a player's view when they quit, so a stale mapping never runs a button. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Inventory inventory = viewing.remove(event.getPlayer().getUniqueId());
        if (inventory != null) open.remove(inventory);
    }

    /** Runs one menu button. */
    private void run(Player player, Open entry, MenuDefinition.Slot slot) {
        Script script = scriptLookup.apply(entry.script());
        if (script == null || script.active() == null) return;
        ExecContext context = ExecContext.forEvent(services, null, "gui-click", script, null,
            player, player, null, player.getWorld(), player.getLocation(),
            io.astra.profiler.TraceSession.NOOP);
        context.setArgument("menu", io.astra.runtime.Value.str(entry.menu()));
        context.setArgument("slot", io.astra.runtime.Value.num(slot.index()));
        try {
            executor.runBlock(slot.body(), context);
        } catch (RuntimeException error) {
            logger.warn("Menu '" + entry.menu() + "' slot " + slot.index() + " failed for "
                + player.getName() + ": " + logger.describe(error));
        }
    }

    /** Unregisters the listener and closes every open menu. */
    public void shutdown() {
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
        for (UUID id : new ArrayList<>(viewing.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                try {
                    player.closeInventory();
                } catch (RuntimeException ignored) {
                    // Already gone.
                }
            }
        }
        open.clear();
        viewing.clear();
        menus.clear();
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
