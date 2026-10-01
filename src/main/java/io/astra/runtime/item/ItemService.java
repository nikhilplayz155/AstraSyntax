package io.astra.runtime.item;

import io.astra.logging.AstraLogger;
import io.astra.platform.TextService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Custom items declared in scripts ({@code item legendary_sword: ...}).
 *
 * <p>Definitions are registered when a script is activated and dropped when it unloads, so
 * an item exists exactly as long as the script that declares it. Creating a stack is
 * deliberately cheap: the definition is immutable and shared, the {@link ItemStack} is
 * built per call because stacks are mutable.</p>
 *
 * <p>Materials, enchantments and flags are all resolved defensively: an unknown material is
 * reported (the parser already rejects unknown material names, so this is a second line of
 * defence for module-registered items) and an unknown enchantment is skipped with a warning
 * instead of failing the whole item.</p>
 */
public final class ItemService {

    private final Map<String, Entry> items = new ConcurrentHashMap<>();
    private final AstraLogger logger;
    private final TextService text;

    private record Entry(String script, ItemDefinition definition) {
    }

    public ItemService(AstraLogger logger, TextService text) {
        this.logger = logger;
        this.text = text;
    }

    /** Registers (or replaces) one item declaration for a script. */
    public void register(String scriptName, ItemDefinition definition) {
        if (definition == null) return;
        items.put(key(definition.name()), new Entry(scriptName == null ? "" : scriptName, definition));
    }

    /** Registers every item a script declares. */
    public void registerAll(String scriptName, Collection<ItemDefinition> definitions) {
        if (definitions == null) return;
        for (ItemDefinition definition : definitions) register(scriptName, definition);
    }

    /** Removes every item owned by a script (called when it unloads or reloads). */
    public void unregisterAll(String scriptName) {
        if (scriptName == null) return;
        items.entrySet().removeIf(entry -> scriptName.equals(entry.getValue().script()));
    }

    /** The definition of a custom item, or {@code null}. */
    public ItemDefinition definition(String name) {
        if (name == null) return null;
        Entry entry = items.get(key(name));
        return entry == null ? null : entry.definition();
    }

    /** True when a script declared an item with this name. */
    public boolean isCustom(String name) {
        return definition(name) != null;
    }

    /** Every declared item, in registration order of the underlying map. */
    public Collection<ItemDefinition> all() {
        List<ItemDefinition> out = new ArrayList<>();
        for (Entry entry : items.values()) out.add(entry.definition());
        return out;
    }

    public int size() {
        return items.size();
    }

    /** The script that declared an item, or an empty string. */
    public String owner(String name) {
        Entry entry = items.get(key(name));
        return entry == null ? "" : entry.script();
    }

    /**
     * Builds a stack for a declared item.
     *
     * @return the stack, or {@code null} when no item has that name
     */
    public ItemStack create(String name, int amount) {
        ItemDefinition definition = definition(name);
        if (definition == null) return null;
        return create(definition, amount);
    }

    /** Builds a stack from a definition, applying name, lore, enchantments and flags. */
    public ItemStack create(ItemDefinition definition, int amount) {
        Material material = Material.matchMaterial(definition.material().toUpperCase(Locale.ROOT));
        if (material == null || material.isAir()) {
            logger.warn("Custom item '" + definition.name() + "' has unknown material '"
                + definition.material() + "'; using stone instead");
            material = Material.STONE;
        }
        int size = Math.max(1, Math.min(material.getMaxStackSize(), amount <= 0 ? definition.amount() : amount));
        ItemStack stack = new ItemStack(material, size);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            applyMeta(meta, definition);
            try {
                stack.setItemMeta(meta);
            } catch (RuntimeException error) {
                logger.warn("Could not apply the custom item data of '" + definition.name() + "': "
                    + logger.describe(error));
            }
        }
        return stack;
    }

    private void applyMeta(ItemMeta meta, ItemDefinition definition) {
        if (definition.displayName() != null && !definition.displayName().isBlank()) {
            meta.setDisplayName(text.render(definition.displayName()));
        }
        if (!definition.lore().isEmpty()) {
            List<String> lore = new ArrayList<>(definition.lore().size());
            for (String line : definition.lore()) lore.add(text.render(line));
            meta.setLore(lore);
        }
        for (ItemDefinition.Enchant enchant : definition.enchants()) {
            Enchantment resolved = resolveEnchantment(enchant.name());
            if (resolved == null) {
                logger.warn("Unknown enchantment '" + enchant.name() + "' on item '"
                    + definition.name() + "'; it is skipped");
                continue;
            }
            // Deprecated in newer APIs, but it is the only call present across the whole
            // 1.21 line without depending on the registry API of a specific fork.
            meta.addEnchant(resolved, Math.max(1, enchant.level()), true);
        }
        if (definition.unbreakable()) {
            meta.setUnbreakable(true);
        }
        for (String flag : definition.flags()) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase(Locale.ROOT).replace('-', '_')));
            } catch (IllegalArgumentException unknown) {
                logger.warn("Unknown item flag '" + flag + "' on item '" + definition.name()
                    + "'; it is skipped");
            }
        }
    }

    /** Enchantment lookup that works on every supported platform. */
    public static Enchantment resolveEnchantment(String name) {
        if (name == null || name.isBlank()) return null;
        String normalised = name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        Object value = io.astra.util.Reflect.invokeQuietly(Enchantment.class, "getByName", 1, normalised);
        return value instanceof Enchantment enchantment ? enchantment : null;
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
