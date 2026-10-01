package io.astra.runtime.scheduler;

import io.astra.logging.AstraLogger;
import io.astra.platform.TaskHandle;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the scheduled tasks a script owns.
 *
 * <p>Script-owned task lifecycle is the reason this exists: when a script is disabled or
 * reloaded, every task it created is cancelled here, which is what prevents the
 * "reloaded script keeps running in the background" class of leaks. The per-script cap
 * from {@code security.yml} ({@code limits.max-tasks-per-script}) is enforced at
 * registration time.</p>
 */
public final class TaskRegistry {

    private final AstraLogger logger;
    private final Map<String, Map<String, TaskHandle>> tasksByScript = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public TaskRegistry(AstraLogger logger) {
        this.logger = logger;
    }

    /**
     * Register a task for a script.
     *
     * @return the handle, or {@code null} when the per-script limit is reached
     */
    public TaskHandle register(String scriptName, String description, TaskHandle handle, int maxPerScript) {
        AtomicInteger counter = counters.computeIfAbsent(scriptName, key -> new AtomicInteger());
        int current = counter.incrementAndGet();
        if (current > maxPerScript) {
            counter.decrementAndGet();
            handle.cancel();
            logger.warnThrottled("task-limit:" + scriptName,
                "Script '" + scriptName + "' reached the task limit (" + maxPerScript + "); the task was not scheduled");
            return null;
        }
        tasksByScript.computeIfAbsent(scriptName, key -> new ConcurrentHashMap<>()).put(description, handle);
        return handle;
    }

    /** Remove a finished task from the registry. */
    public void unregister(String scriptName, String description) {
        Map<String, TaskHandle> tasks = tasksByScript.get(scriptName);
        if (tasks != null && tasks.remove(description) != null) {
            AtomicInteger counter = counters.get(scriptName);
            if (counter != null) counter.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    /** Cancel and forget every task a script owns. */
    public int cancelAll(String scriptName) {
        Map<String, TaskHandle> tasks = tasksByScript.remove(scriptName);
        counters.remove(scriptName);
        if (tasks == null) return 0;
        int cancelled = 0;
        for (TaskHandle handle : tasks.values()) {
            handle.cancel();
            cancelled++;
        }
        return cancelled;
    }

    /** Cancel every task of every script (shutdown). */
    public int cancelEverything() {
        int cancelled = 0;
        for (String scriptName : Set.copyOf(tasksByScript.keySet())) {
            cancelled += cancelAll(scriptName);
        }
        return cancelled;
    }

    /** Number of tasks a script currently owns. */
    public int count(String scriptName) {
        Map<String, TaskHandle> tasks = tasksByScript.get(scriptName);
        return tasks == null ? 0 : tasks.size();
    }

    /** Total number of tracked tasks. */
    public int total() {
        int total = 0;
        for (Map<String, TaskHandle> tasks : tasksByScript.values()) total += tasks.size();
        return total;
    }

    /** Scripts that currently own tasks. */
    public Set<String> scripts() {
        return Set.copyOf(tasksByScript.keySet());
    }
}
