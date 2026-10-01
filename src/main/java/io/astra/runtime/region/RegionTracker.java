package io.astra.runtime.region;

import io.astra.logging.AstraLogger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * Fires {@link RegionEvents} when a player crosses a region boundary.
 *
 * <p>Only the players whose block position actually changed are examined, and the whole
 * tracker is dormant unless a loaded script listens for {@code region enter} or
 * {@code region leave} - a server with no region rules pays nothing at all for it. That
 * check is a flag the runtime sets when it refreshes its trigger list, not a scan of
 * scripts on every move.</p>
 *
 * <p>Membership is remembered per player as a set of region names, so leaving region A while
 * entering region B fires both events in the same tick, and a player who logs out or
 * changes world drops their membership instead of leaving a stale entry behind.</p>
 */
public final class RegionTracker implements Listener {

    private final RegionService regions;
    private final AstraLogger logger;
    private final Map<UUID, Set<String>> memberships = new ConcurrentHashMap<>();
    /** Set by the runtime: true while at least one rule uses a region trigger. */
    private volatile boolean active;
    private boolean registered;

    public RegionTracker(RegionService regions, AstraLogger logger) {
        this.regions = regions;
        this.logger = logger;
    }

    /** Registers the movement listener once. */
    public void start(Plugin plugin) {
        if (registered || plugin == null) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registered = true;
    }

    /** Turns tracking on or off; the runtime sets this from the active trigger list. */
    public void setActive(boolean value) {
        this.active = value;
    }

    public boolean active() {
        return active;
    }

    /** Current membership of a player, empty when they are in no region. */
    public Set<String> membership(Player player) {
        if (player == null) return Set.of();
        return memberships.getOrDefault(player.getUniqueId(), Set.of());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!active) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        // Only a block change can change region membership.
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        update(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!active || event.getTo() == null) return;
        update(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!active) return;
        update(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!active) return;
        update(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // A different world means every membership is void before anything else happens.
        memberships.remove(event.getPlayer().getUniqueId());
        if (!active) return;
        update(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        memberships.remove(event.getPlayer().getUniqueId());
    }

    /** Recomputes membership and fires the crossing events. */
    public void update(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null) return;
        Set<String> before = memberships.getOrDefault(player.getUniqueId(), Set.of());
        List<RegionDefinition> inside = regions.regionsAt(location.getWorld().getName(),
            location.getX(), location.getY(), location.getZ());
        Set<String> after = new HashSet<>();
        for (RegionDefinition region : inside) after.add(region.name());

        if (before.equals(after)) {
            if (after.isEmpty()) memberships.remove(player.getUniqueId());
            else memberships.put(player.getUniqueId(), after);
            return;
        }

        // Leave: innermost (smallest) first, so a nested region closes before its parent.
        for (RegionDefinition left : regions.regionsAtByName(before)) {
            if (after.contains(left.name())) continue;
            fire(new RegionEvents.Leave(player, left, innermost(after)));
        }
        // Enter: outermost first, so a nested region opens inside its parent.
        List<RegionDefinition> entered = new ArrayList<>(inside);
        entered.sort((first, second) -> Double.compare(second.volume(), first.volume()));
        for (RegionDefinition region : entered) {
            if (before.contains(region.name())) continue;
            fire(new RegionEvents.Enter(player, region, innermost(before)));
        }

        if (after.isEmpty()) memberships.remove(player.getUniqueId());
        else memberships.put(player.getUniqueId(), after);
    }

    /** The smallest region in a set - the one the player was actually standing in. */
    private RegionDefinition innermost(Set<String> names) {
        RegionDefinition found = null;
        for (String name : names) {
            RegionDefinition region = regions.get(name);
            if (region != null && (found == null || region.volume() < found.volume())) found = region;
        }
        return found;
    }

    private void fire(org.bukkit.event.Event event) {
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (Throwable error) {
            logger.warn("A region trigger failed: " + logger.describe(error));
        }
    }

    /** Forgets every membership (called on shutdown and on reload). */
    public void clear() {
        memberships.clear();
    }
}
