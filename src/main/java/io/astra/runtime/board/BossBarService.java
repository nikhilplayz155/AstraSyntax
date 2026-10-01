package io.astra.runtime.board;

import io.astra.logging.AstraLogger;
import io.astra.platform.TextService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

/**
 * Named boss bars driven from scripts.
 *
 * <p>A bar is created once by name and then updated in place - title, progress, colour,
 * style and visibility - which is what a boss fight or a countdown needs. Bars are owned by
 * the script that created them and are removed (and hidden from every player) when that
 * script unloads or reloads, so a reload never leaves a ghost bar on screen.</p>
 *
 * <p>The bars use the platform's own {@link BossBar}, so they render natively on all five
 * supported servers; the service is the only place that knows about them, which keeps the
 * vocabulary thin.</p>
 */
public final class BossBarService {

    private final Map<String, Entry> bars = new ConcurrentHashMap<>();
    private final TextService text;
    private final AstraLogger logger;

    private record Entry(String script, BossBar bar) {
    }

    public BossBarService(TextService text, AstraLogger logger) {
        this.text = text;
        this.logger = logger;
    }

    /** Creates (or replaces) a bar. */
    public BossBar create(String script, String name, String title, String color, String style, double progress,
                          boolean visible) {
        if (name == null || name.isBlank()) return null;
        BossBar bar = Bukkit.createBossBar(text.render(title == null ? name : title),
            barColor(color), barStyle(style));
        bar.setProgress(clamp(progress));
        bar.setVisible(visible);
        Entry previous = bars.put(key(name), new Entry(script == null ? "" : script, bar));
        if (previous != null) previous.bar().removeAll();
        return bar;
    }

    /** The bar with this name, or {@code null}. */
    public BossBar bar(String name) {
        Entry entry = bars.get(key(name));
        return entry == null ? null : entry.bar();
    }

    /** True when a bar with this name exists. */
    public boolean exists(String name) {
        return bars.containsKey(key(name));
    }

    /** Updates whichever properties were supplied; {@code null} means "leave alone". */
    public boolean update(String name, String title, Double progress, String color, String style, Boolean visible) {
        BossBar bar = bar(name);
        if (bar == null) return false;
        if (title != null) bar.setTitle(text.render(title));
        if (progress != null) bar.setProgress(clamp(progress));
        if (color != null) bar.setColor(barColor(color));
        if (style != null) bar.setStyle(barStyle(style));
        if (visible != null) bar.setVisible(visible);
        return true;
    }

    /** Adds a player to the bar's audience. */
    public boolean showTo(String name, Player player) {
        BossBar bar = bar(name);
        if (bar == null || player == null) return false;
        bar.addPlayer(player);
        return true;
    }

    /** Shows a bar to every online player. */
    public boolean showToEveryone(String name) {
        BossBar bar = bar(name);
        if (bar == null) return false;
        for (Player player : Bukkit.getOnlinePlayers()) bar.addPlayer(player);
        return true;
    }

    /** Removes a player from the bar's audience. */
    public boolean hideFrom(String name, Player player) {
        BossBar bar = bar(name);
        if (bar == null || player == null) return false;
        bar.removePlayer(player);
        return true;
    }

    /** Removes the bar entirely, dropping its whole audience. */
    public boolean remove(String name) {
        Entry entry = bars.remove(key(name));
        if (entry == null) return false;
        entry.bar().removeAll();
        entry.bar().setVisible(false);
        return true;
    }

    /** Removes every bar owned by a script (used on unload and reload). */
    public void unregisterAll(String script) {
        if (script == null) return;
        List<String> owned = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : bars.entrySet()) {
            if (script.equals(entry.getValue().script())) owned.add(entry.getKey());
        }
        for (String name : owned) remove(name);
    }

    /** Player count currently seeing a bar, or 0 when it does not exist. */
    public int audience(String name) {
        BossBar bar = bar(name);
        return bar == null ? 0 : bar.getPlayers().size();
    }

    public Collection<String> names() {
        List<String> names = new ArrayList<>(bars.keySet());
        names.sort(String::compareTo);
        return names;
    }

    public int size() {
        return bars.size();
    }

    /** Hides every bar the plugin owns (called on shutdown). */
    public void shutdown() {
        for (Entry entry : bars.values()) {
            entry.bar().removeAll();
            entry.bar().setVisible(false);
        }
        bars.clear();
    }

    /** {@code red}, {@code RED} and {@code red-boss} all resolve. */
    public static BarColor barColor(String name) {
        if (name == null || name.isBlank()) return BarColor.PURPLE;
        try {
            return BarColor.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            return BarColor.PURPLE;
        }
    }

    /** {@code solid}, {@code segmented-10}, {@code notched-20} all resolve. */
    public static BarStyle barStyle(String name) {
        if (name == null || name.isBlank()) return BarStyle.SOLID;
        String normalised = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return BarStyle.valueOf(normalised);
        } catch (IllegalArgumentException unknown) {
            return BarStyle.SOLID;
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static double clamp(double progress) {
        if (Double.isNaN(progress)) return 1.0;
        return Math.max(0.0, Math.min(1.0, progress));
    }
}
