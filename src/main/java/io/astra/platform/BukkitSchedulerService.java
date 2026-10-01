package io.astra.platform;

import io.astra.logging.AstraLogger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Scheduler backend for Paper, Purpur, Spigot and Leaf.
 *
 * <p>The legacy scheduler is main-thread oriented, so location and entity anchors
 * simply execute on the main thread; asynchronous work uses the async pool. This is
 * exactly the "documented fallback" the platform contract requires - behaviour is
 * identical on every non-Folia server and no Folia class is ever loaded here.</p>
 */
public final class BukkitSchedulerService implements SchedulerService {

    private final Plugin plugin;
    private final AstraLogger logger;
    private final Set<TaskHandle> tasks = ConcurrentHashMap.newKeySet();

    public BukkitSchedulerService(Plugin plugin, AstraLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public boolean isFolia() {
        return false;
    }

    @Override
    public String implementationName() {
        return "Bukkit main-thread scheduler";
    }

    @Override
    public TaskHandle runGlobal(Runnable task) {
        return wrap(Bukkit.getScheduler().runTask(plugin, guard(task)), false, "global");
    }

    @Override
    public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
        return wrap(Bukkit.getScheduler().runTaskLater(plugin, guard(task), Math.max(0L, delayTicks)), false,
            "delayed+" + delayTicks + "t");
    }

    @Override
    public TaskHandle runGlobalRepeating(Runnable task, long delayTicks, long periodTicks) {
        long period = Math.max(1L, periodTicks);
        return wrap(Bukkit.getScheduler().runTaskTimer(plugin, guard(task), Math.max(0L, delayTicks), period), true,
            "repeating/" + period + "t");
    }

    @Override
    public TaskHandle runAsync(Runnable task) {
        return wrap(Bukkit.getScheduler().runTaskAsynchronously(plugin, guard(task)), false, "async");
    }

    @Override
    public TaskHandle runAsyncLater(Runnable task, long delayTicks) {
        return wrap(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, guard(task), Math.max(0L, delayTicks)),
            false, "async+" + delayTicks + "t");
    }

    @Override
    public TaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
        long period = Math.max(1L, periodTicks);
        return wrap(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, guard(task), Math.max(0L, delayTicks), period),
            true, "async-repeating/" + period + "t");
    }

    @Override
    public TaskHandle runAtLocation(World world, double x, double y, double z, Runnable task) {
        return runGlobal(task);
    }

    @Override
    public TaskHandle runAtLocationLater(World world, double x, double y, double z, Runnable task, long delayTicks) {
        return runGlobalLater(task, delayTicks);
    }

    @Override
    public TaskHandle runAtLocationRepeating(World world, double x, double y, double z, Runnable task,
                                             long delayTicks, long periodTicks) {
        return runGlobalRepeating(task, delayTicks, periodTicks);
    }

    @Override
    public TaskHandle runOnEntity(Entity entity, Runnable task) {
        return runGlobal(task);
    }

    @Override
    public TaskHandle runOnEntityLater(Entity entity, Runnable task, long delayTicks) {
        return runGlobalLater(task, delayTicks);
    }

    @Override
    public void cancelAll() {
        for (TaskHandle handle : tasks) handle.cancel();
        tasks.clear();
        try {
            Bukkit.getScheduler().cancelTasks(plugin);
        } catch (Throwable ignored) {
        }
    }

    /** Wrap the scheduling call so a failing script cannot break the server tick loop. */
    private Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable error) {
                logger.error("Scheduled AstraSyntax task failed: " + logger.describe(error));
            }
        };
    }

    private TaskHandle wrap(BukkitTask task, boolean repeating, String description) {
        if (task == null) return TaskHandle.noop("scheduler rejected the task");
        SimpleTaskHandle handle = new SimpleTaskHandle(this, task::cancel, repeating, description, task::isCancelled);
        tasks.add(handle);
        return handle;
    }
}
