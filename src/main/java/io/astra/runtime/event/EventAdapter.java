package io.astra.runtime.event;

import io.astra.runtime.ExecContext;
import io.astra.runtime.Value;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

/**
 * Knows how to pull the actors and properties out of one Bukkit event type.
 *
 * <p>Adapters are registered data, not hard-coded listener classes: the event bus
 * registers a single dynamic listener per event type and asks the adapter who the
 * actors are. Adding support for a new event therefore never touches the parser, the
 * compiler or the bus.</p>
 */
public interface EventAdapter {

    /** The primary entity of the event (player or mob), or {@code null}. */
    default Entity actor(Event event) {
        return null;
    }

    /** The primary player of the event when there is one. */
    default Player player(Event event) {
        Entity actor = actor(event);
        return actor instanceof Player p ? p : null;
    }

    /** A secondary entity such as the killer, attacker or shooter. */
    default Entity secondary(Event event) {
        return null;
    }

    /** True when the event can be cancelled by scripts. */
    default boolean cancellable(Event event) {
        return event instanceof org.bukkit.event.Cancellable;
    }

    /** Extra context wiring performed before the rule body runs. */
    default void prepare(ExecContext context, Event event) {
    }

    /** An adapter that only ever exposes a player. */
    static EventAdapter ofPlayer() {
        return new EventAdapter() {
            @Override public Entity actor(Event event) {
                return event instanceof org.bukkit.event.player.PlayerEvent playerEvent
                    ? playerEvent.getPlayer() : null;
            }
        };
    }

    /** An adapter that only ever exposes an entity. */
    static EventAdapter ofEntity() {
        return new EventAdapter() {
            @Override public Entity actor(Event event) {
                return event instanceof org.bukkit.event.entity.EntityEvent entityEvent
                    ? entityEvent.getEntity() : null;
            }
        };
    }

    /**
     * Entity + killer, plus the usual damage-cause wiring. Used by death, damage and
     * kill triggers.
     */
    static EventAdapter ofEntityAndDamager() {
        return new EventAdapter() {
            @Override public Entity actor(Event event) {
                return event instanceof org.bukkit.event.entity.EntityEvent entityEvent
                    ? entityEvent.getEntity() : null;
            }

            @Override public Entity secondary(Event event) {
                if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity) {
                    return byEntity.getDamager();
                }
                if (event instanceof org.bukkit.event.entity.EntityDeathEvent death) {
                    return death.getEntity().getKiller();
                }
                return null;
            }
        };
    }

    /** An adapter for events that involve only a block. */
    static EventAdapter ofBlock() {
        return new EventAdapter() {
            @Override public Player player(Event event) {
                return null;
            }
        };
    }

    /** An adapter that pulls the player out of an inventory holder when possible. */
    static EventAdapter ofInventory() {
        return new EventAdapter() {
            @Override public Entity actor(Event event) {
                if (event instanceof org.bukkit.event.inventory.InventoryEvent inventoryEvent) {
                    return inventoryEvent.getView().getPlayer();
                }
                return null;
            }
        };
    }

    /**
     * Build an adapter from lightweight extractor functions.
     *
     * <p>Most built-in triggers do not need a class of their own: this factory keeps the
     * registration table declarative while still expressing "the player here is the
     * breaker, the mob here is the victim".</p>
     *
     * @param player    extracts the player actor, or {@code null} when unused
     * @param entity    extracts the entity actor, or {@code null} when unused
     * @param secondary extracts the killer/attacker/target, or {@code null} when unused
     */
    static EventAdapter of(java.util.function.Function<Event, Player> player,
                           java.util.function.Function<Event, Entity> entity,
                           java.util.function.Function<Event, Entity> secondary) {
        return new EventAdapter() {
            @Override public Player player(Event event) {
                if (player != null) {
                    Player result = player.apply(event);
                    if (result != null) return result;
                }
                return EventAdapter.super.player(event);
            }

            @Override public Entity actor(Event event) {
                if (entity != null) {
                    Entity result = entity.apply(event);
                    if (result != null) return result;
                }
                return EventAdapter.super.actor(event);
            }

            @Override public Entity secondary(Event event) {
                return secondary == null ? null : secondary.apply(event);
            }
        };
    }

    /** Compose several adapters; the first non-null result wins. */
    static EventAdapter or(EventAdapter first, EventAdapter second) {
        return new EventAdapter() {
            @Override public Entity actor(Event event) {
                Entity result = first.actor(event);
                return result != null ? result : second.actor(event);
            }

            @Override public Player player(Event event) {
                Player result = first.player(event);
                return result != null ? result : second.player(event);
            }

            @Override public Entity secondary(Event event) {
                Entity result = first.secondary(event);
                return result != null ? result : second.secondary(event);
            }

            @Override public void prepare(ExecContext context, Event event) {
                first.prepare(context, event);
                second.prepare(context, event);
            }
        };
    }
}
