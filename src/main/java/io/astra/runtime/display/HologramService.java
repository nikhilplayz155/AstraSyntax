package io.astra.runtime.display;

import io.astra.logging.AstraLogger;
import io.astra.platform.TextService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

/**
 * Floating text holograms built on the vanilla {@link TextDisplay} entity.
 *
 * <p>No packet library and no ProtocolLib: a display entity is a real entity, so holograms
 * survive restarts and work identically on every supported platform (Paper, Purpur, Leaf,
 * Folia and Spigot, all of which expose display entities on the 1.21 API). A hologram is
 * one entity showing several lines, which keeps the entity budget low and updating text a
 * single call.</p>
 *
 * <p>Text is plain, {@code \n} separated. Because the entity is spawned through the world,
 * it is spawned on the thread that owns the location - the caller decides that, exactly as
 * with every other Astra spawn.</p>
 */
public final class HologramService {

    /** Minecraft's own line width for {@link TextDisplay}; 200 is the vanilla default. */
    private static final int DEFAULT_LINE_WIDTH = 200;

    private final Map<String, Entry> holograms = new ConcurrentHashMap<>();
    private final TextService text;
    private final AstraLogger logger;

    private record Entry(String script, TextDisplay entity, List<String> lines) {
    }

    public HologramService(TextService text, AstraLogger logger) {
        this.text = text;
        this.logger = logger;
    }

    /**
     * Creates (or moves) a named hologram.
     *
     * @param lines the text lines, rendered through the text service
     * @return the created entity, or {@code null} when the location has no world
     */
    public TextDisplay create(String script, String name, Location location, List<String> lines) {
        if (location == null || location.getWorld() == null) {
            logger.warn("Hologram '" + name + "' needs a world to spawn in");
            return null;
        }
        remove(name);
        World world = location.getWorld();
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.setText(text.render(join(lines)));
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(false);
            entity.setLineWidth(DEFAULT_LINE_WIDTH);
            entity.setPersistent(true);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
        });
        holograms.put(key(name), new Entry(script == null ? "" : script, display,
            lines == null ? List.of() : List.copyOf(lines)));
        return display;
    }

    /** The entity behind a hologram, or {@code null}. */
    public TextDisplay entity(String name) {
        Entry entry = holograms.get(key(name));
        return entry == null ? null : entry.entity();
    }

    public boolean exists(String name) {
        Entry entry = holograms.get(key(name));
        return entry != null && entry.entity().isValid();
    }

    /** Replaces a hologram's text. */
    public boolean setText(String name, List<String> lines) {
        Entry entry = holograms.get(key(name));
        if (entry == null || !entry.entity().isValid()) return false;
        entry.entity().setText(text.render(join(lines)));
        holograms.put(key(name), new Entry(entry.script(), entry.entity(),
            lines == null ? List.of() : List.copyOf(lines)));
        return true;
    }

    /** Moves a hologram to a new location, spawning a fresh entity if needed. */
    public boolean move(String name, Location location, String script) {
        Entry entry = holograms.get(key(name));
        if (entry == null) return false;
        List<String> lines = entry.lines();
        String owner = script == null ? entry.script() : script;
        TextDisplay moved = create(owner, name, location, lines);
        return moved != null;
    }

    /** Removes a hologram and its entity. */
    public boolean remove(String name) {
        Entry entry = holograms.remove(key(name));
        if (entry == null) return false;
        try {
            entry.entity().remove();
        } catch (RuntimeException error) {
            logger.debug("Could not remove hologram entity '" + name + "': " + logger.describe(error));
        }
        return true;
    }

    /** Removes every hologram owned by a script (used on unload and reload). */
    public void unregisterAll(String script) {
        if (script == null) return;
        List<String> owned = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : holograms.entrySet()) {
            if (script.equals(entry.getValue().script())) owned.add(entry.getKey());
        }
        for (String name : owned) remove(name);
    }

    public Collection<String> names() {
        List<String> names = new ArrayList<>(holograms.keySet());
        names.sort(String::compareTo);
        return names;
    }

    public int size() {
        return holograms.size();
    }

    /** Deletes every hologram entity the plugin spawned (called on shutdown). */
    public void shutdown() {
        for (Entry entry : holograms.values()) {
            try {
                entry.entity().remove();
            } catch (RuntimeException ignored) {
                // Shutting down; a missing world is not worth a log line.
            }
        }
        holograms.clear();
    }

    private static String join(List<String> lines) {
        return lines == null ? "" : String.join("\n", lines);
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
