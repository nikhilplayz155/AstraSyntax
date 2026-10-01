package io.astra.runtime.npc;

import io.astra.logging.AstraLogger;
import io.astra.platform.TextService;
import io.astra.runtime.builtin.MaterialTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Script-spawned NPCs that need no other plugin.
 *
 * <p>An NPC is a persistent, silent, invulnerable, AI-less living entity with a name tag -
 * which is all a shopkeeper or a guide needs. It carries the payload tag {@code astra_npc}
 * in its persistent data container, so an NPC is recognisable after a server restart rather
 * than becoming an anonymous mob.</p>
 *
 * <p>Citizens is not required: the NPC is a plain vanilla entity, so it works on every
 * supported platform. The {@code integrations.yml} switches are honoured elsewhere in the
 * runtime when those plugins are present.</p>
 */
public final class NpcService {

    /** Persistent data tag marking an entity as an Astra NPC. */
    public static final String TAG = "astra_npc";

    private final Map<String, Entry> npcs = new ConcurrentHashMap<>();
    private final TextService text;
    private final AstraLogger logger;
    private final NamespacedKey tagKey;

    private record Entry(String script, Entity entity) {
    }

    public NpcService(TextService text, AstraLogger logger) {
        this.text = text;
        this.logger = logger;
        this.tagKey = namespacedKey(TAG);
    }

    /**
     * Spawns (or replaces) a named NPC.
     *
     * @param name    the unique NPC name scripts use
     * @param type    a vanilla entity type name or alias such as {@code villager}
     * @param display the name tag, or {@code null} to reuse the NPC name
     * @return the spawned entity, or {@code null} when the location has no world
     */
    public LivingEntity spawn(String script, String name, String type, Location location, String display) {
        if (location == null || location.getWorld() == null) {
            logger.warn("NPC '" + name + "' needs a world to spawn in");
            return null;
        }
        EntityType entityType = MaterialTable.shared().entityType(type);
        if (entityType == null) {
            logger.warn("Unknown NPC type '" + type + "'; using villager instead");
            entityType = EntityType.VILLAGER;
        }
        remove(name);
        try {
            Entity spawned = location.getWorld().spawnEntity(location, entityType);
            prepare(spawned, name, display);
            npcs.put(key(name), new Entry(script == null ? "" : script, spawned));
            return spawned instanceof LivingEntity living ? living : null;
        } catch (IllegalArgumentException error) {
            logger.warn("Could not spawn NPC '" + name + "' (" + type + "): " + logger.describe(error));
            return null;
        }
    }

    /** Applies the NPC traits: name tag, no AI, no gravity, no death, no despawn. */
    private void prepare(Entity entity, String name, String display) {
        entity.setPersistent(true);
        entity.setSilent(true);
        entity.setInvulnerable(true);
        entity.setGravity(false);
        String label = display == null || display.isBlank() ? name : display;
        if (label != null && !label.isBlank()) {
            entity.setCustomName(text.render(label));
            entity.setCustomNameVisible(true);
        }
        if (entity instanceof LivingEntity living) {
            living.setRemoveWhenFarAway(false);
            living.setCanPickupItems(false);
        }
        if (entity instanceof org.bukkit.entity.Mob mob) {
            // No AI: the NPC stays put and never fights or wanders.
            try {
                mob.setAware(false);
            } catch (RuntimeException ignored) {
                // Some entity types reject it; the NPC is still functional.
            }
        }
        if (tagKey != null && name != null && !name.isBlank()) {
            entity.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, name);
        }
    }

    /**
     * Adopts an entity a script created elsewhere (a boss, a custom mob).
     *
     * <p>Tracking is what makes the entity script-owned: it is removed again when the
     * script unloads or reloads, which is the difference between a boss fight and a boss
     * that keeps walking around after its script is gone.</p>
     */
    public void track(String script, String name, Entity entity) {
        if (entity == null || name == null || name.isBlank()) return;
        npcs.put(key(name), new Entry(script == null ? "" : script, entity));
    }

    /** The entity behind a named NPC, or {@code null}. */
    public Entity entity(String name) {
        Entry entry = npcs.get(key(name));
        return entry == null ? null : entry.entity();
    }

    /** True when a named NPC exists and its entity is still alive. */
    public boolean exists(String name) {
        Entity entity = entity(name);
        return entity != null && entity.isValid();
    }

    /** Names of every NPC this service knows about. */
    public Collection<String> names() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : npcs.entrySet()) {
            if (entry.getValue().entity().isValid()) names.add(entry.getKey());
        }
        names.sort(String::compareTo);
        return names;
    }

    /** The NPC's name scripts use, read back from its persistent data tag. */
    public String nameOf(Entity entity) {
        if (entity == null || tagKey == null) return null;
        return entity.getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
    }

    /** Makes the NPC's name tag read like speech. */
    public boolean say(String name, String message) {
        Entity entity = entity(name);
        if (!(entity instanceof LivingEntity living) || message == null) return false;
        String current = living.getCustomName();
        living.setCustomName(text.render("<gold>" + (current == null ? name : current)
            + " <dark_gray>» <white>" + message));
        living.setCustomNameVisible(true);
        return true;
    }

    /** Points the NPC at a player by rotating it towards them. */
    public boolean lookAt(String name, Player player) {
        Entity entity = entity(name);
        if (entity == null || player == null) return false;
        try {
            Location target = player.getLocation();
            Location from = entity.getLocation();
            double dx = target.getX() - from.getX();
            double dz = target.getZ() - from.getZ();
            double dy = target.getY() + 1.6 - (from.getY() + 1.6);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            double flat = Math.sqrt(dx * dx + dz * dz);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
            entity.setRotation(yaw, pitch);
        } catch (RuntimeException error) {
            logger.debug("Could not aim NPC '" + name + "': " + logger.describe(error));
        }
        return true;
    }

    /** Removes a named NPC. */
    public boolean remove(String name) {
        Entry entry = npcs.remove(key(name));
        if (entry == null) return false;
        try {
            entry.entity().remove();
        } catch (RuntimeException error) {
            logger.debug("Could not remove NPC '" + name + "': " + logger.describe(error));
        }
        return true;
    }

    /** Removes every NPC owned by a script (used on unload and reload). */
    public void unregisterAll(String script) {
        if (script == null) return;
        List<String> owned = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : npcs.entrySet()) {
            if (script.equals(entry.getValue().script())) owned.add(entry.getKey());
        }
        for (String name : owned) remove(name);
    }

    public int size() {
        return npcs.size();
    }

    /** Removes every NPC the plugin spawned (called on shutdown). */
    public void shutdown() {
        for (Entry entry : npcs.values()) {
            try {
                entry.entity().remove();
            } catch (RuntimeException ignored) {
                // The world is already gone.
            }
        }
        npcs.clear();
    }

    /** Keys are built from the plugin's own name, so a reload keeps tagging correctly. */
    private static NamespacedKey namespacedKey(String name) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("AstraSyntax");
            return plugin == null ? null : new NamespacedKey(plugin, name);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
