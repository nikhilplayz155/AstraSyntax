package io.astra.runtime.recipe;

import io.astra.logging.AstraLogger;
import io.astra.runtime.item.ItemService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.Plugin;

/**
 * Crafting recipes declared in scripts.
 *
 * <p>Recipes are registered on the server when a script activates and removed by
 * {@link NamespacedKey} when it unloads, so a reload never leaves a duplicate recipe
 * behind. Ingredients and results may name either a Minecraft material or a custom item
 * declared in the same script, which is what makes a custom item actually obtainable.</p>
 */
public final class RecipeService {

    private final Map<String, Entry> recipes = new ConcurrentHashMap<>();
    private final ItemService items;
    private final AstraLogger logger;

    private record Entry(String script, RecipeDefinition definition, NamespacedKey key) {
    }

    public RecipeService(ItemService items, AstraLogger logger) {
        this.items = items;
        this.logger = logger;
    }

    /** Registers (or re-registers) one recipe. */
    public boolean register(String script, RecipeDefinition definition) {
        if (definition == null) return false;
        String key = key(definition.name());
        Entry existing = recipes.remove(key);
        if (existing != null) unregister(existing);

        NamespacedKey namespace = namespace(definition.name());
        if (namespace == null) return false;
        ItemStack result = result(definition);
        if (result == null) return false;

        try {
            boolean added;
            if (definition.shaped()) {
                ShapedRecipe recipe = new ShapedRecipe(namespace, result);
                List<String> shape = normaliseShape(definition.shape());
                recipe.shape(shape.toArray(new String[0]));
                boolean complete = true;
                for (Map.Entry<String, String> ingredient : definition.ingredients().entrySet()) {
                    Material material = material(ingredient.getValue());
                    if (material == null) {
                        logger.warn("Recipe '" + definition.name() + "' ingredient '"
                            + ingredient.getValue() + "' is unknown; the recipe is skipped");
                        complete = false;
                        break;
                    }
                    recipe.setIngredient(Character.toUpperCase(ingredient.getKey().charAt(0)), material);
                }
                added = complete && Bukkit.addRecipe(recipe);
            } else {
                ShapelessRecipe recipe = new ShapelessRecipe(namespace, result);
                boolean complete = true;
                for (String ingredient : definition.ingredientMaterials()) {
                    Material material = material(ingredient);
                    if (material == null) {
                        logger.warn("Recipe '" + definition.name() + "' ingredient '" + ingredient
                            + "' is unknown; the recipe is skipped");
                        complete = false;
                        break;
                    }
                    recipe.addIngredient(material);
                }
                added = complete && Bukkit.addRecipe(recipe);
            }
            if (!added) {
                logger.warn("Recipe '" + definition.name() + "' could not be registered");
                return false;
            }
            recipes.put(key, new Entry(script == null ? "" : script, definition, namespace));
            return true;
        } catch (IllegalArgumentException error) {
            logger.warn("Recipe '" + definition.name() + "' is invalid: " + logger.describe(error));
            return false;
        }
    }

    /** Registers every recipe a script declares. */
    public void registerAll(String script, Collection<RecipeDefinition> definitions) {
        if (definitions == null) return;
        for (RecipeDefinition definition : definitions) register(script, definition);
    }

    /** Removes every recipe a script declared. */
    public void unregisterAll(String script) {
        if (script == null) return;
        List<Entry> owned = new ArrayList<>();
        for (Entry entry : recipes.values()) {
            if (script.equals(entry.script())) owned.add(entry);
        }
        for (Entry entry : owned) {
            recipes.remove(key(entry.definition().name()));
            unregister(entry);
        }
    }

    /** True when a recipe with this name is registered. */
    public boolean has(String name) {
        return recipes.containsKey(key(name));
    }

    public Collection<RecipeDefinition> all() {
        List<RecipeDefinition> out = new ArrayList<>();
        for (Entry entry : recipes.values()) out.add(entry.definition());
        return out;
    }

    public int size() {
        return recipes.size();
    }

    private void unregister(Entry entry) {
        if (entry.key() == null) return;
        try {
            Bukkit.removeRecipe(entry.key());
        } catch (RuntimeException error) {
            logger.debug("Could not remove recipe '" + entry.definition().name() + "': "
                + logger.describe(error));
        }
    }

    /** Removes every registered recipe (called on shutdown). */
    public void shutdown() {
        for (Entry entry : new ArrayList<>(recipes.values())) unregister(entry);
        recipes.clear();
    }

    /**
     * Pads every shape row to the same width.
     *
     * <p>A shape written as {@code "A"} {@code "AA"} is legal in a script but not for the
     * Bukkit API, which requires a rectangular grid; the gap becomes an empty slot.</p>
     */
    static List<String> normaliseShape(List<String> shape) {
        int width = 0;
        for (String row : shape) width = Math.max(width, row.length());
        List<String> padded = new ArrayList<>(shape.size());
        for (String row : shape) {
            StringBuilder line = new StringBuilder(row);
            while (line.length() < width) line.append(' ');
            padded.add(line.toString());
        }
        return padded;
    }

    /** A material from a name, honouring custom items declared by the same script. */
    private Material material(String name) {
        if (items.isCustom(name)) {
            ItemStack stack = items.create(name, 1);
            return stack == null ? null : stack.getType();
        }
        return Material.matchMaterial(name == null ? "" : name.toUpperCase(Locale.ROOT));
    }

    /** The result stack, resolving custom items and unknown materials. */
    private ItemStack result(RecipeDefinition definition) {
        if (items.isCustom(definition.resultMaterial())) {
            return items.create(definition.resultMaterial(), definition.resultAmount());
        }
        Material material = material(definition.resultMaterial());
        if (material == null) {
            logger.warn("Recipe '" + definition.name() + "' result '" + definition.resultMaterial()
                + "' is unknown; the recipe is skipped");
            return null;
        }
        return new ItemStack(material, Math.max(1, Math.min(material.getMaxStackSize(), definition.resultAmount())));
    }

    private static NamespacedKey namespace(String name) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("AstraSyntax");
            return plugin == null ? null : new NamespacedKey(plugin, "recipe_" + key(name));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
