package io.astra.runtime.event;

import io.astra.logging.AstraLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * The single dynamic event listener.
 *
 * <p>Instead of shipping one listener class per Bukkit event, AstraSyntax registers one
 * listener per <em>event class that the currently loaded scripts actually use</em>. That
 * has three consequences the project cares about:</p>
 *
 * <ul>
 *   <li>a reload that removes the last rule using {@code PlayerMoveEvent} also removes the
 *       listener, so no residual work is left behind;</li>
 *   <li>events nobody listens to cost literally nothing;</li>
 *   <li>adding a trigger is a registration call, never a new class plus a plugin.yml
 *       change - which is also what lets modules contribute triggers.</li>
 * </ul>
 *
 * <p>Dispatch is delegated to a {@link Dispatcher} so the bus stays independent of the
 * script manager, and so the compiler/executor can be tested with a stub dispatcher.</p>
 */
public final class EventBus implements Listener {

    /** What to do when an event fires: find the rules and run them. */
    public interface Dispatcher {
        void dispatch(Event event, EventDefinition definition);
    }

    private final PluginManager pluginManager;
    private final AstraLogger logger;
    private final Dispatcher dispatcher;
    private final Plugin plugin;
    private final Map<Class<? extends Event>, EventDefinition> registered = new HashMap<>();
    private final Map<Class<? extends Event>, Listener> listeners = new HashMap<>();
    private final Map<Class<? extends Event>, AtomicLong> counters = new HashMap<>();

    public EventBus(PluginManager pluginManager, Plugin plugin, AstraLogger logger, Dispatcher dispatcher) {
        this.pluginManager = pluginManager;
        this.plugin = plugin;
        this.logger = logger;
        this.dispatcher = dispatcher;
    }

    /**
     * Make the set of registered listeners match the given definitions.
     *
     * <p>Called after every load/unload/reload. Definitions that are no longer needed are
     * unregistered through {@link HandlerList#unregisterAll(Listener)} which, because each
     * event class gets its own marker listener instance, only affects that one event.</p>
     */
    public synchronized void refresh(List<EventDefinition> definitions) {
        Set<Class<? extends Event>> wanted = new HashSet<>();
        Map<Class<? extends Event>, EventDefinition> wantedByClass = new HashMap<>();
        for (EventDefinition definition : definitions) {
            wanted.add(definition.eventClass());
            // The first definition registered for a class defines the listener priority;
            // rules carry their own priority and are ordered by the dispatcher.
            wantedByClass.putIfAbsent(definition.eventClass(), definition);
        }

        List<Class<? extends Event>> removed = new ArrayList<>();
        for (Class<? extends Event> existing : registered.keySet()) {
            if (!wanted.contains(existing)) removed.add(existing);
        }
        for (Class<? extends Event> eventClass : removed) {
            Listener listener = listeners.remove(eventClass);
            if (listener != null) HandlerList.unregisterAll(listener);
            registered.remove(eventClass);
            counters.remove(eventClass);
            logger.debug(() -> "Stopped listening for " + eventClass.getSimpleName());
        }

        for (EventDefinition definition : wantedByClass.values()) {
            Class<? extends Event> eventClass = definition.eventClass();
            if (registered.containsKey(eventClass)) continue;
            Listener marker = new Listener() { };
            try {
                pluginManager.registerEvent(eventClass, marker, EventPriority.NORMAL,
                    (listener, event) -> dispatch(event), plugin);
                registered.put(eventClass, definition);
                listeners.put(eventClass, marker);
                counters.put(eventClass, new AtomicLong());
                logger.debug(() -> "Listening for " + eventClass.getSimpleName());
            } catch (Throwable error) {
                logger.warn("Could not register a listener for " + eventClass.getSimpleName() + ": "
                    + logger.describe(error));
            }
        }
    }

    private void dispatch(Event event) {
        EventDefinition definition = registered.get(event.getClass());
        if (definition == null) {
            // A subclass of a registered event type (for example a plugin's own subclass).
            for (Map.Entry<Class<? extends Event>, EventDefinition> entry : registered.entrySet()) {
                if (entry.getKey().isAssignableFrom(event.getClass())) {
                    definition = entry.getValue();
                    break;
                }
            }
        }
        if (definition == null) return;
        AtomicLong counter = counters.get(definition.eventClass());
        if (counter != null) counter.incrementAndGet();
        try {
            dispatcher.dispatch(event, definition);
        } catch (Throwable error) {
            // A failing rule must never break the server's event pipeline.
            logger.error("Trigger '" + definition.id() + "' failed: " + logger.describe(error));
        }
    }

    /** How many events have been seen for an event class, for {@code /astra performance}. */
    public long countFor(Class<? extends Event> eventClass) {
        AtomicLong counter = counters.get(eventClass);
        return counter == null ? 0L : counter.get();
    }

    /** Currently registered event classes. */
    public synchronized Set<Class<? extends Event>> eventClasses() {
        return Set.copyOf(registered.keySet());
    }

    /** Total number of seen events. */
    public long totalEvents() {
        long total = 0;
        for (AtomicLong counter : counters.values()) total += counter.get();
        return total;
    }
}
