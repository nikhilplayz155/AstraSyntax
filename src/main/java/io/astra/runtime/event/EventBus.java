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
 * <p>Several definitions can share one Bukkit event class ({@code player death} and
 * {@code entity death} both come from {@code EntityDeathEvent}), so the bus keeps a list
 * per class and hands every definition to the dispatcher. Dispatching only the first one
 * would silently drop half the rules.</p>
 */
public final class EventBus implements Listener {

    /** What to do when an event fires: find the rules and run them. */
    public interface Dispatcher {
        void dispatch(Event event, List<EventDefinition> definitions);
    }

    private final PluginManager pluginManager;
    private final AstraLogger logger;
    private final Dispatcher dispatcher;
    private final Plugin plugin;
    private final Map<Class<? extends Event>, List<EventDefinition>> registered = new HashMap<>();
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
        Map<Class<? extends Event>, List<EventDefinition>> wanted = new HashMap<>();
        for (EventDefinition definition : definitions) {
            wanted.computeIfAbsent(definition.eventClass(), key -> new ArrayList<>()).add(definition);
        }

        List<Class<? extends Event>> removed = new ArrayList<>();
        for (Class<? extends Event> existing : registered.keySet()) {
            if (!wanted.containsKey(existing)) removed.add(existing);
        }
        for (Class<? extends Event> eventClass : removed) {
            Listener listener = listeners.remove(eventClass);
            if (listener != null) HandlerList.unregisterAll(listener);
            registered.remove(eventClass);
            counters.remove(eventClass);
            logger.debug(() -> "Stopped listening for " + eventClass.getSimpleName());
        }

        for (Map.Entry<Class<? extends Event>, List<EventDefinition>> entry : wanted.entrySet()) {
            Class<? extends Event> eventClass = entry.getKey();
            List<EventDefinition> wantedDefinitions = entry.getValue();
            if (registered.containsKey(eventClass)) {
                registered.put(eventClass, List.copyOf(wantedDefinitions));
                continue;
            }
            Listener marker = new Listener() { };
            try {
                pluginManager.registerEvent(eventClass, marker, EventPriority.NORMAL,
                    (listener, event) -> dispatch(event), plugin);
                registered.put(eventClass, List.copyOf(wantedDefinitions));
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
        List<EventDefinition> definitions = registered.get(event.getClass());
        if (definitions == null) {
            // A subclass of a registered event type (for example a plugin's own subclass).
            for (Map.Entry<Class<? extends Event>, List<EventDefinition>> entry : registered.entrySet()) {
                if (entry.getKey().isAssignableFrom(event.getClass())) {
                    definitions = entry.getValue();
                    break;
                }
            }
        }
        if (definitions == null || definitions.isEmpty()) return;
        for (EventDefinition definition : definitions) {
            AtomicLong counter = counters.get(definition.eventClass());
            if (counter != null) counter.incrementAndGet();
        }
        try {
            dispatcher.dispatch(event, definitions);
        } catch (Throwable error) {
            // A failing rule must never break the server's event pipeline.
            logger.error("An event trigger failed: " + logger.describe(error));
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

    /** Every definition currently registered for an event class. */
    public synchronized List<EventDefinition> definitionsFor(Class<? extends Event> eventClass) {
        List<EventDefinition> definitions = registered.get(eventClass);
        return definitions == null ? List.of() : List.copyOf(definitions);
    }

    /** Total number of seen events. */
    public long totalEvents() {
        long total = 0;
        for (AtomicLong counter : counters.values()) total += counter.get();
        return total;
    }

    /** Remove every listener (shutdown). */
    public synchronized void unregisterAll() {
        for (Listener listener : listeners.values()) {
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
        registered.clear();
        counters.clear();
    }

    /** Distinct trigger ids the bus is currently listening for. */
    public synchronized Set<String> triggerIds() {
        Set<String> ids = new HashSet<>();
        for (List<EventDefinition> definitions : registered.values()) {
            for (EventDefinition definition : definitions) ids.add(definition.id());
        }
        return ids;
    }
}
