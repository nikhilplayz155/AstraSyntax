package io.astra.runtime.builtin;

import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Friendly-name resolution for Minecraft vocabulary.
 *
 * <p>Script authors write {@code diamonds}, not {@code DIAMOND} - and {@code give player
 * 5 dimonds} has to produce a suggestion pointing at {@code diamonds}. This table is
 * therefore the single source of truth for:</p>
 * <ul>
 *   <li>material names (enum names plus friendly aliases and plurals)</li>
 *   <li>entity types</li>
 *   <li>keyword families such as game modes, difficulty, weather, sounds, particles
 *       and potion effects</li>
 * </ul>
 *
 * <p>Resolution deliberately avoids {@code Bukkit.getUnsafe()} and other server-instance
 * APIs, so it also works in unit tests without a running server.</p>
 */
public final class MaterialTable {

    /** Friendly alias -&gt; canonical material name. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("wood", "OAK_LOG"),
        Map.entry("log", "OAK_LOG"),
        Map.entry("logs", "OAK_LOG"),
        Map.entry("planks", "OAK_PLANKS"),
        Map.entry("steak", "COOKED_BEEF"),
        Map.entry("cooked steak", "COOKED_BEEF"),
        Map.entry("raw beef", "BEEF"),
        Map.entry("cooked pork", "COOKED_PORKCHOP"),
        Map.entry("pork", "PORKCHOP"),
        Map.entry("xp bottle", "EXPERIENCE_BOTTLE"),
        Map.entry("experience bottle", "EXPERIENCE_BOTTLE"),
        Map.entry("bottle o enchanting", "EXPERIENCE_BOTTLE"),
        Map.entry("xp", "EXPERIENCE_BOTTLE"),
        Map.entry("iron", "IRON_INGOT"),
        Map.entry("gold", "GOLD_INGOT"),
        Map.entry("lapis", "LAPIS_LAZULI"),
        Map.entry("redstone dust", "REDSTONE"),
        Map.entry("redstone", "REDSTONE"),
        Map.entry("eye of ender", "ENDER_EYE"),
        Map.entry("ender eye", "ENDER_EYE"),
        Map.entry("sulphur", "GUNPOWDER"),
        Map.entry("slimeball", "SLIME_BALL"),
        Map.entry("glowstone", "GLOWSTONE"),
        Map.entry("nether quartz", "QUARTZ"),
        Map.entry("nether brick", "NETHER_BRICK"),
        Map.entry("soul sand", "SOUL_SAND"),
        Map.entry("end stone", "END_STONE"),
        Map.entry("cobble", "COBBLESTONE"),
        Map.entry("stone brick", "STONE_BRICKS"),
        Map.entry("grass", "GRASS_BLOCK"),
        Map.entry("mycelium", "MYCELIUM"),
        Map.entry("clay ball", "CLAY_BALL"),
        Map.entry("brick block", "BRICKS"),
        Map.entry("tnt block", "TNT"),
        Map.entry("nametag", "NAME_TAG"),
        Map.entry("lead", "LEAD"),
        Map.entry("leash", "LEAD"),
        Map.entry("boat", "OAK_BOAT"),
        Map.entry("minecart", "MINECART"),
        Map.entry("flint and steel", "FLINT_AND_STEEL"),
        Map.entry("water bucket", "WATER_BUCKET"),
        Map.entry("lava bucket", "LAVA_BUCKET"),
        Map.entry("milk", "MILK_BUCKET"),
        Map.entry("hay", "HAY_BLOCK"),
        Map.entry("hay bale", "HAY_BLOCK"),
        Map.entry("workbench", "CRAFTING_TABLE"),
        Map.entry("enchant table", "ENCHANTING_TABLE"),
        Map.entry("snow ball", "SNOWBALL"),
        Map.entry("shulker box", "SHULKER_BOX"),
        Map.entry("totem", "TOTEM_OF_UNDYING"),
        Map.entry("dragon egg", "DRAGON_EGG"),
        Map.entry("heart of the sea", "HEART_OF_THE_SEA"),
        Map.entry("nautilus shell", "NAUTILUS_SHELL"),
        Map.entry("web", "COBWEB"),
        Map.entry("cobweb", "COBWEB"));

    private static final Map<String, String> ENTITY_ALIASES = Map.ofEntries(
        Map.entry("zombie pigman", "ZOMBIFIED_PIGLIN"),
        Map.entry("pigman", "ZOMBIFIED_PIGLIN"),
        Map.entry("wither skeleton", "WITHER_SKELETON"),
        Map.entry("elder guardian", "ELDER_GUARDIAN"),
        Map.entry("magma cube", "MAGMA_CUBE"),
        Map.entry("cave spider", "CAVE_SPIDER"),
        Map.entry("dragon", "ENDER_DRAGON"),
        Map.entry("enderdragon", "ENDER_DRAGON"),
        Map.entry("iron golem", "IRON_GOLEM"),
        Map.entry("snow golem", "SNOW_GOLEM"),
        Map.entry("polar bear", "POLAR_BEAR"),
        Map.entry("mushroom cow", "MUSHROOM_COW"),
        Map.entry("mooshroom", "MUSHROOM_COW"),
        Map.entry("wandering trader", "WANDERING_TRADER"),
        Map.entry("trader llama", "TRADER_LLAMA"),
        Map.entry("zombie villager", "ZOMBIE_VILLAGER"),
        Map.entry("zoglin", "ZOGLIN"));

    private static final Set<String> DIFFICULTIES = Set.of("peaceful", "easy", "normal", "hard");
    private static final Set<String> WEATHER = Set.of("clear", "sun", "rain", "raining", "storm", "thunder", "snow");
    private static final Set<String> GAMEMODES = Set.of("survival", "creative", "adventure", "spectator");

    private final Map<String, String> materialLookup = new ConcurrentHashMap<>();
    private final Map<String, String> entityLookup = new ConcurrentHashMap<>();
    private final List<String> materialNames = new ArrayList<>();
    private final List<String> entityNames = new ArrayList<>();

    private MaterialTable() {
        buildMaterials();
        buildEntities();
    }

    /**
     * The shared table (built once, immutable afterwards).
     *
     * <p>Held in a nested holder rather than a plain static field: the constructor reads
     * the static alias maps, so a plain field declared above them would be observed as
     * {@code null} while the table is being built.</p>
     */
    public static MaterialTable shared() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final MaterialTable INSTANCE = new MaterialTable();
    }

    private void buildMaterials() {
        for (Material material : Material.values()) {
            if (material.isLegacy()) continue;
            String canonical = material.name();
            register(materialLookup, canonical, canonical);
            String friendly = canonical.toLowerCase(Locale.ROOT).replace('_', ' ');
            register(materialLookup, friendly, canonical);
            register(materialLookup, friendly + "s", canonical);
            materialNames.add(friendly);
        }
        for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
            register(materialLookup, alias.getKey(), alias.getValue());
        }
    }

    private void buildEntities() {
        for (EntityType type : EntityType.values()) {
            String canonical = type.name();
            register(entityLookup, canonical, canonical);
            String friendly = canonical.toLowerCase(Locale.ROOT).replace('_', ' ');
            register(entityLookup, friendly, canonical);
            register(entityLookup, friendly + "s", canonical);
            entityNames.add(friendly);
        }
        for (Map.Entry<String, String> alias : ENTITY_ALIASES.entrySet()) {
            register(entityLookup, alias.getKey(), alias.getValue());
        }
    }

    private static void register(Map<String, String> target, String key, String value) {
        if (key == null || key.isBlank()) return;
        target.putIfAbsent(normalise(key), value);
    }

    private static String normalise(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ");
    }

    // -------------------------------------------------------------- materials

    /** True when the text names a Minecraft material. */
    public boolean isMaterial(String text) {
        return resolveMaterial(text) != null;
    }

    /** Resolve a friendly material name to a canonical enum name, or {@code null}. */
    public String resolveMaterial(String text) {
        if (Strings.isBlank(text)) return null;
        return materialLookup.get(normalise(text));
    }

    /** Resolve to a {@link Material}, or {@code null}. */
    public Material material(String text) {
        String canonical = resolveMaterial(text);
        if (canonical == null) return null;
        try {
            return Material.valueOf(canonical);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Up to three suggestions for a mistyped material name.
     *
     * <p>Cached because the parser asks for the same misspelling once per template it
     * probes, and ranking against the whole catalogue is not free. The cache is cleared
     * wholesale when it grows past a few hundred entries, which keeps a hostile script
     * from turning it into a memory leak.</p>
     */
    public List<String> suggestions(String text) {
        if (Strings.isBlank(text)) return List.of();
        String key = normalise(text);
        List<String> cached = SUGGESTIONS.get(key);
        if (cached != null) return cached;
        Set<String> pool = new LinkedHashSet<>(materialLookup.keySet());
        List<String> computed = List.copyOf(Strings.nearest(key, pool, 3));
        if (SUGGESTIONS.size() > 512) SUGGESTIONS.clear();
        SUGGESTIONS.put(key, computed);
        return computed;
    }

    /** Small, bounded memo for {@link #suggestions(String)}. */
    private static final java.util.Map<String, List<String>> SUGGESTIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The same, for entity type names. */
    private static final java.util.Map<String, List<String>> ENTITY_SUGGESTIONS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** All known material names (friendly form), for documentation and editors. */
    public Collection<String> materialNames() {
        return materialNames;
    }

    // ---------------------------------------------------------------- entities

    public boolean isEntityType(String text) {
        return resolveEntity(text) != null;
    }

    public String resolveEntity(String text) {
        if (Strings.isBlank(text)) return null;
        return entityLookup.get(normalise(text));
    }

    public EntityType entityType(String text) {
        String canonical = resolveEntity(text);
        if (canonical == null) return null;
        try {
            return EntityType.valueOf(canonical);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public List<String> entitySuggestions(String text) {
        if (Strings.isBlank(text)) return List.of();
        String key = normalise(text);
        List<String> cached = ENTITY_SUGGESTIONS.get(key);
        if (cached != null) return cached;
        Set<String> pool = new LinkedHashSet<>(entityLookup.keySet());
        List<String> computed = List.copyOf(Strings.nearest(key, pool, 3));
        if (ENTITY_SUGGESTIONS.size() > 256) ENTITY_SUGGESTIONS.clear();
        ENTITY_SUGGESTIONS.put(key, computed);
        return computed;
    }

    public Collection<String> entityNames() {
        return entityNames;
    }

    // ---------------------------------------------------------------- keywords

    /** True when the word belongs to the given keyword family. */
    public boolean isKeyword(String category, String name) {
        if (Strings.isBlank(name)) return false;
        String family = category == null ? "" : category.toLowerCase(Locale.ROOT);
        String value = normalise(name);
        return switch (family) {
            case "gamemode" -> GAMEMODES.contains(value);
            case "difficulty" -> DIFFICULTIES.contains(value);
            case "weather" -> WEATHER.contains(value);
            case "sound" -> resolveSound(name) != null;
            case "particle" -> resolveParticle(name) != null;
            case "effect", "potion" -> resolvePotionEffect(name) != null;
            case "attribute" -> resolveAttribute(name) != null;
            case "enchantment" -> resolveEnchantment(name) != null;
            default -> true;
        };
    }

    /** Names accepted in a keyword family (used by documentation and suggestions). */
    public List<String> keywordNames(String category) {
        String family = category == null ? "" : category.toLowerCase(Locale.ROOT);
        return switch (family) {
            case "gamemode" -> new ArrayList<>(GAMEMODES);
            case "difficulty" -> new ArrayList<>(DIFFICULTIES);
            case "weather" -> new ArrayList<>(WEATHER);
            default -> List.of();
        };
    }

    /** Resolve a sound name through the Bukkit API without compiling against fork classes. */
    public Object resolveSound(String name) {
        String value = Strings.isBlank(name) ? "" : name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        try {
            Class<?> soundClass = Class.forName("org.bukkit.Sound");
            if (soundClass.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object resolved = Enum.valueOf((Class<? extends Enum>) soundClass, value);
                return resolved;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Resolve a particle name. */
    public Object resolveParticle(String name) {
        String value = Strings.isBlank(name) ? "" : name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        try {
            Class<?> particleClass = Class.forName("org.bukkit.Particle");
            if (particleClass.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object resolved = Enum.valueOf((Class<? extends Enum>) particleClass, value);
                return resolved;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Resolve a potion effect name. */
    public Object resolvePotionEffect(String name) {
        String value = Strings.isBlank(name) ? "" : name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        try {
            Class<?> typeClass = Class.forName("org.bukkit.potion.PotionEffectType");
            Object byName = null;
            try {
                byName = typeClass.getMethod("getByName", String.class).invoke(null, value);
            } catch (Throwable ignored) {
                // Newer APIs may not expose getByName; fall through to the enum lookup.
            }
            if (byName != null) return byName;
            if (typeClass.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object resolved = Enum.valueOf((Class<? extends Enum>) typeClass, value);
                return resolved;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Resolve an attribute name. */
    public Object resolveAttribute(String name) {
        String value = Strings.isBlank(name) ? "" : name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!value.startsWith("GENERIC_") && !value.startsWith("PLAYER_") && !value.startsWith("ZOMBIE_")
            && !value.startsWith("HORSE_")) {
            value = "GENERIC_" + value;
        }
        try {
            Class<?> attributeClass = Class.forName("org.bukkit.attribute.Attribute");
            if (attributeClass.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object resolved = Enum.valueOf((Class<? extends Enum>) attributeClass, value);
                return resolved;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Resolve an enchantment name. */
    public Object resolveEnchantment(String name) {
        String value = Strings.isBlank(name) ? "" : name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        try {
            Class<?> enchantmentClass = Class.forName("org.bukkit.enchantments.Enchantment");
            if (enchantmentClass.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object resolved = Enum.valueOf((Class<? extends Enum>) enchantmentClass, value);
                return resolved;
            }
            return enchantmentClass.getMethod("getByName", String.class).invoke(null, value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** A small map of friendly aliases, exposed for documentation generation. */
    public Map<String, String> aliases() {
        return new LinkedHashMap<>(ALIASES);
    }
}
