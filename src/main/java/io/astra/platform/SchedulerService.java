package io.astra.platform;

import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * Scheduler abstraction that hides the difference between the legacy Bukkit
 * main-thread scheduler and Folia's regionised schedulers.
 *
 * <p>Scripts never talk to a scheduler directly; the runtime asks for an execution
 * anchor (a location or an entity) and this interface picks the correct
 * region-owning scheduler on Folia, or the main thread everywhere else.</p>
 */
public interface SchedulerService {

    /** True when the Folia regionised scheduler API is in use. */
    boolean isFolia();

    /** Human readable backend name for diagnostics. */
    String implementationName();

    TaskHandle runGlobal(Runnable task);

    TaskHandle runGlobalLater(Runnable task, long delayTicks);

    TaskHandle runGlobalRepeating(Runnable task, long delayTicks, long periodTicks);

    TaskHandle runAsync(Runnable task);

    TaskHandle runAsyncLater(Runnable task, long delayTicks);

    TaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks);

    /** Run on the thread that owns the given world position. */
    TaskHandle runAtLocation(World world, double x, double y, double z, Runnable task);

    TaskHandle runAtLocationLater(World world, double x, double y, double z, Runnable task, long delayTicks);

    TaskHandle runAtLocationRepeating(World world, double x, double y, double z, Runnable task,
                                      long delayTicks, long periodTicks);

    /** Run on the thread that owns the given entity (falls back to a global task). */
    TaskHandle runOnEntity(Entity entity, Runnable task);

    TaskHandle runOnEntityLater(Entity entity, Runnable task, long delayTicks);

    /** Cancel every task AstraSyntax has scheduled. Called during shutdown. */
    void cancelAll();
}
