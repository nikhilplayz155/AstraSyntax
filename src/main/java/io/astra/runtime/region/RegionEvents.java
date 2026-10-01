package io.astra.runtime.region;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * The two Bukkit events Astra fires when a player crosses an Astra region boundary.
 *
 * <p>They are real events rather than a private callback so that the trigger pipeline stays
 * exactly the same for regions as for vanilla events: {@code on region enter} is an
 * ordinary {@code EventDefinition} whose event class is {@link Enter}, which means the
 * event bus attaches its listener only while a loaded script actually uses the trigger, and
 * {@code {region}} resolves through the standard event-property resolver.</p>
 *
 * <p>Both classes share {@link Transition}, so one adapter and one property case cover
 * entering and leaving.</p>
 */
public final class RegionEvents {

    private RegionEvents() {
    }

    /** Shared shape of a boundary crossing. */
    public abstract static class Transition extends Event {

        private final Player player;
        private final RegionDefinition region;
        private final RegionDefinition previous;

        protected Transition(Player player, RegionDefinition region, RegionDefinition previous) {
            this.player = player;
            this.region = region;
            this.previous = previous;
        }

        /** The player crossing the boundary. */
        public Player player() {
            return player;
        }

        /** The region that was entered or left. */
        public RegionDefinition region() {
            return region;
        }

        /** The region that contained the player before this crossing, or {@code null}. */
        public RegionDefinition previous() {
            return previous;
        }

        /** The region name, which is what {@code {region}} resolves to. */
        public String regionName() {
            return region == null ? "" : region.name();
        }
    }

    /** Fired when a player crosses into a region. */
    public static final class Enter extends Transition {

        private static final HandlerList HANDLERS = new HandlerList();

        public Enter(Player player, RegionDefinition region, RegionDefinition previous) {
            super(player, region, previous);
        }

        @Override public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** Fired when a player crosses out of a region. */
    public static final class Leave extends Transition {

        private static final HandlerList HANDLERS = new HandlerList();

        public Leave(Player player, RegionDefinition region, RegionDefinition previous) {
            super(player, region, previous);
        }

        @Override public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
