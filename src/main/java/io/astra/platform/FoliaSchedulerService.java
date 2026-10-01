package io.astra.platform;

import io.astra.logging.AstraLogger;
import io.astra.util.Reflect;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Folia scheduler backend.
 *
 * <p>Folia has no global main thread: work has to be handed to the scheduler that
 * owns the region, entity or global tick loop it touches. Because the plugin is
 * compiled against the shared Bukkit API, those schedulers are reached through
 * cached reflective lookups - the lookups happen once, at construction, and are
 * never part of a hot path.</p>
 *
 * <p>If Folia is present but one of the entry points is unavailable (older Folia
 * builds), the call degrades to the closest safe equivalent (typically the global
 * region scheduler) and records a one-time debug note instead of failing.</p>
 */
public final class FoliaSchedulerService implements SchedulerService {

    private final Plugin plugin;
    private final AstraLogger logger;
    private final Set<TaskHandle> tasks = ConcurrentHashMap.newKeySet();

    private final Object globalScheduler;
    private final Object asyncScheduler;
    private final Object regionScheduler;

    private final Method globalRun;
    private final Method globalRunDelayed;
    private final Method globalRunAtFixedRate;
    private final Method globalExecute;

    private final Method asyncRunNow;
    private final Method asyncRunDelayed;
    private final Method asyncRunAtFixedRate;

    private final Method regionRun;
    private final Method regionRunDelayed;
    private final Method regionRunAtFixedRate;

    private final Method cancelTasks;
    private final Set<String> notedFallbacks = ConcurrentHashMap.newKeySet();

    public FoliaSchedulerService(Plugin plugin, AstraLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
        Object global = null;
        Object async = null;
        Object region = null;
        try {
            Class<?> bukkit = Bukkit.getServer().getClass();
            global = Reflect.invokeQuietly(Bukkit.getServer(), "getGlobalRegionScheduler", 0);
            async = Reflect.invokeQuietly(Bukkit.getServer(), "getAsyncScheduler", 0);
            region = Reflect.invokeQuietly(Bukkit.getServer(), "getRegionScheduler", 0);
            if (global == null && bukkit != null) {
                Method method = Reflect.findMethod(bukkit, "getGlobalRegionScheduler").orElse(null);
                global = Reflect.invokeQuietly(method, Bukkit.getServer());
            }
        } catch (Throwable error) {
            logger.error("Folia scheduler lookup failed: " + logger.describe(error));
        }
        this.globalScheduler = global;
        this.asyncScheduler = async;
        this.regionScheduler = region;

        this.globalRun = method(global, "run", 2);
        this.globalRunDelayed = method(global, "runDelayed", 3);
        this.globalRunAtFixedRate = method(global, "runAtFixedRate", 4);
        this.globalExecute = method(global, "execute", 2);

        this.asyncRunNow = method(async, "runNow", 2);
        this.asyncRunDelayed = method(async, "runDelayed", 4);
        this.asyncRunAtFixedRate = method(async, "runAtFixedRate", 5);

        this.regionRun = method(region, "run", 5);
        this.regionRunDelayed = method(region, "runDelayed", 6);
        this.regionRunAtFixedRate = method(region, "runAtFixedRate", 7);

        this.cancelTasks = method(global, "cancelTasks", 1);
    }

    private static Method method(Object target, String name, int arity) {
        if (target == null) return null;
        return Reflect.findMethodByArity(target.getClass(), name, arity).orElse(null);
    }

    @Override
    public boolean isFolia() {
        return true;
    }

    @Override
    public String implementationName() {
        return "Folia regionised scheduler";
    }

    @Override
    public TaskHandle runGlobal(Runnable task) {
        if (globalRun == null) return unsupported("global run");
        return schedule(globalRun, true, "global", task);
    }

    @Override
    public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
        if (globalRunDelayed == null) return runGlobal(task);
        return schedule(globalRunDelayed, true, "global+" + delayTicks + "t", task, Math.max(0L, delayTicks));
    }

    @Override
    public TaskHandle runGlobalRepeating(Runnable task, long delayTicks, long periodTicks) {
        if (globalRunAtFixedRate == null) return runGlobal(task);
        return schedule(globalRunAtFixedRate, true, "global-repeating/" + periodTicks + "t",
            task, Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    @Override
    public TaskHandle runAsync(Runnable task) {
        if (asyncRunNow == null) return runGlobal(task);
        return schedule(asyncRunNow, false, "async", task);
    }

    @Override
    public TaskHandle runAsyncLater(Runnable task, long delayTicks) {
        if (asyncRunDelayed == null) return runAsync(task);
        return schedule(asyncRunDelayed, false, "async+" + delayTicks + "t", task,
            Math.max(0L, delayTicks) * 50L, TimeUnit.MILLISECONDS);
    }

    @Override
    public TaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
        if (asyncRunAtFixedRate == null) return runAsync(task);
        return schedule(asyncRunAtFixedRate, true, "async-repeating/" + periodTicks + "t", task,
            Math.max(0L, delayTicks) * 50L, Math.max(1L, periodTicks) * 50L, TimeUnit.MILLISECONDS);
    }

    @Override
    public TaskHandle runAtLocation(World world, double x, double y, double z, Runnable task) {
        if (world == null || regionRun == null) return runGlobal(task);
        return schedule(regionRun, true, "region", task, world, blockX(x), blockZ(z));
    }

    @Override
    public TaskHandle runAtLocationLater(World world, double x, double y, double z, Runnable task, long delayTicks) {
        if (world == null || regionRunDelayed == null) return runGlobalLater(task, delayTicks);
        return schedule(regionRunDelayed, true, "region+" + delayTicks + "t", task,
            world, blockX(x), blockZ(z), Math.max(0L, delayTicks));
    }

    @Override
    public TaskHandle runAtLocationRepeating(World world, double x, double y, double z, Runnable task,
                                             long delayTicks, long periodTicks) {
        if (world == null || regionRunAtFixedRate == null) return runGlobalRepeating(task, delayTicks, periodTicks);
        return schedule(regionRunAtFixedRate, true, "region-repeating/" + periodTicks + "t", task,
            world, blockX(x), blockZ(z), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    @Override
    public TaskHandle runOnEntity(Entity entity, Runnable task) {
        Object scheduler = entityScheduler(entity);
        if (scheduler == null) return runGlobal(task);
        Method run = Reflect.findMethodByArity(scheduler.getClass(), "run", 3).orElse(null);
        if (run == null) return runGlobal(task);
        return scheduleEntity(run, scheduler, task, null);
    }

    @Override
    public TaskHandle runOnEntityLater(Entity entity, Runnable task, long delayTicks) {
        Object scheduler = entityScheduler(entity);
        if (scheduler == null) return runGlobalLater(task, delayTicks);
        Method run = Reflect.findMethodByArity(scheduler.getClass(), "runDelayed", 4).orElse(null);
        if (run == null) return runGlobalLater(task, delayTicks);
        return scheduleEntity(run, scheduler, task, Math.max(0L, delayTicks));
    }

    @Override
    public void cancelAll() {
        for (TaskHandle handle : tasks) handle.cancel();
        tasks.clear();
        if (cancelTasks != null && globalScheduler != null) {
            Reflect.invokeQuietly(cancelTasks, globalScheduler, plugin);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int blockX(double x) {
        return ((int) Math.floor(x)) >> 4;
    }

    private static int blockZ(double z) {
        return ((int) Math.floor(z)) >> 4;
    }

    private Object entityScheduler(Entity entity) {
        if (entity == null) return null;
        Method method = Reflect.findMethodByArity(entity.getClass(), "getScheduler", 0).orElse(null);
        return Reflect.invokeQuietly(method, entity);
    }

    private TaskHandle unsupported(String operation) {
        if (notedFallbacks.add(operation)) {
            logger.warn("Folia scheduler does not expose " + operation + "; the task was skipped");
        }
        return TaskHandle.noop("unsupported on this Folia build");
    }

    private TaskHandle schedule(Method method, boolean repeating, String description, Runnable task, Object... extraArgs) {
        Object scheduler = pickScheduler(method, description);
        if (scheduler == null) return unsupported(description);
        SimpleTaskHandle[] slot = new SimpleTaskHandle[1];
        Consumer<Object> consumer = scheduledTask -> {
            SimpleTaskHandle handle = slot[0];
            if (handle != null && handle.isCancelled()) return;
            try {
                task.run();
            } catch (Throwable error) {
                logger.error("Scheduled AstraSyntax task failed: " + logger.describe(error));
            }
        };
        Object[] args = new Object[2 + extraArgs.length];
        args[0] = plugin;
        args[1] = consumer;
        System.arraycopy(extraArgs, 0, args, 2, extraArgs.length);
        Object scheduled = Reflect.invokeQuietly(method, scheduler, args);
        if (scheduled == null) return TaskHandle.noop(description);
        SimpleTaskHandle handle = new SimpleTaskHandle(this, () -> cancelScheduled(scheduled), repeating, description);
        slot[0] = handle;
        tasks.add(handle);
        return handle;
    }

    private TaskHandle scheduleEntity(Method method, Object scheduler, Runnable task, Long delayTicks) {
        SimpleTaskHandle[] slot = new SimpleTaskHandle[1];
        Consumer<Object> consumer = scheduledTask -> {
            SimpleTaskHandle handle = slot[0];
            if (handle != null && handle.isCancelled()) return;
            try {
                task.run();
            } catch (Throwable error) {
                logger.error("Entity task failed: " + logger.describe(error));
            }
        };
        Runnable retired = () -> logger.debug("Entity task skipped: the entity is no longer valid");
        Object scheduled = delayTicks == null
            ? Reflect.invokeQuietly(method, scheduler, plugin, consumer, retired)
            : Reflect.invokeQuietly(method, scheduler, plugin, consumer, retired, delayTicks);
        if (scheduled == null) return TaskHandle.noop("entity scheduler rejected the task");
        SimpleTaskHandle handle = new SimpleTaskHandle(this, () -> cancelScheduled(scheduled), false, "entity");
        slot[0] = handle;
        tasks.add(handle);
        return handle;
    }

    private Object pickScheduler(Method method, String description) {
        String name = method.getName();
        String owner = method.getDeclaringClass().getSimpleName();
        if (owner.contains("Async") || name.startsWith("runNow")) return asyncScheduler;
        if (owner.contains("Region") && !owner.contains("Global")) return regionScheduler;
        return globalScheduler;
    }

    private void cancelScheduled(Object scheduledTask) {
        Method cancel = Reflect.findMethodByArity(scheduledTask.getClass(), "cancel", 0).orElse(null);
        Reflect.invokeQuietly(cancel, scheduledTask);
    }
}
