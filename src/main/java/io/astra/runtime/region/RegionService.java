package io.astra.runtime.region;

import io.astra.logging.AstraLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named cuboid regions declared in scripts.
 *
 * <p>No Bukkit types are involved, which keeps containment logic unit testable and lets the
 * {@code inside-region} condition run on any platform. WorldGuard, when installed, is a
 * separate integration (the plugin only detects it); Astra's own regions are self-contained
 * and need no other plugin.</p>
 */
public final class RegionService {

    private final Map<String, Entry> regions = new ConcurrentHashMap<>();
    private final AstraLogger logger;

    private record Entry(String script, RegionDefinition definition) {
    }

    public RegionService(AstraLogger logger) {
        this.logger = logger;
    }

    /** Registers (or replaces) one region for a script. */
    public void register(String scriptName, RegionDefinition definition) {
        if (definition == null) return;
        regions.put(key(definition.name()), new Entry(scriptName == null ? "" : scriptName, definition));
    }

    public void registerAll(String scriptName, Collection<RegionDefinition> definitions) {
        if (definitions == null) return;
        for (RegionDefinition definition : definitions) register(scriptName, definition);
    }

    /** Drops every region owned by a script. */
    public void unregisterAll(String scriptName) {
        if (scriptName == null) return;
        regions.entrySet().removeIf(entry -> scriptName.equals(entry.getValue().script()));
    }

    /** The region with this name, or {@code null}. */
    public RegionDefinition get(String name) {
        if (name == null) return null;
        Entry entry = regions.get(key(name));
        return entry == null ? null : entry.definition();
    }

    public boolean contains(String name, String world, double x, double y, double z) {
        RegionDefinition region = get(name);
        return region != null && region.contains(world, x, y, z);
    }

    /** True when any declared region contains the point. */
    public boolean insideAny(String world, double x, double y, double z) {
        for (Entry entry : regions.values()) {
            if (entry.definition().contains(world, x, y, z)) return true;
        }
        return false;
    }

    /** Every region containing the point, innermost (smallest volume) first. */
    public List<RegionDefinition> regionsAt(String world, double x, double y, double z) {
        List<RegionDefinition> found = new ArrayList<>();
        for (Entry entry : regions.values()) {
            if (entry.definition().contains(world, x, y, z)) found.add(entry.definition());
        }
        found.sort((a, b) -> Double.compare(a.volume(), b.volume()));
        return found;
    }

    /** The smallest declared region containing the point, or {@code null}. */
    public RegionDefinition regionAt(String world, double x, double y, double z) {
        List<RegionDefinition> found = regionsAt(world, x, y, z);
        return found.isEmpty() ? null : found.get(0);
    }

    public Collection<RegionDefinition> all() {
        List<RegionDefinition> out = new ArrayList<>();
        for (Entry entry : regions.values()) out.add(entry.definition());
        return out;
    }

    public int size() {
        return regions.size();
    }

    /** Names of every declared region, sorted, for {@code /astra info}. */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Entry entry : regions.values()) names.add(entry.definition().name());
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /** One line per region for {@code /astra info} and {@code /astra explain}. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (Entry entry : regions.values()) lines.add(entry.definition().describe());
        lines.sort(String::compareToIgnoreCase);
        return lines;
    }

    /** Warns when two different scripts declare the same region name. */
    public void warnAboutClashes() {
        Map<String, String> owners = new ConcurrentHashMap<>();
        for (Map.Entry<String, Entry> entry : regions.entrySet()) {
            String previous = owners.put(entry.getKey(), entry.getValue().script());
            if (previous != null && !previous.equals(entry.getValue().script())) {
                logger.warn("Region '" + entry.getValue().definition().name()
                    + "' is declared by both '" + previous + "' and '" + entry.getValue().script()
                    + "'; the last one loaded wins");
            }
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
