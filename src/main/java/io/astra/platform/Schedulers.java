package io.astra.platform;

import io.astra.logging.AstraLogger;

import org.bukkit.plugin.Plugin;

/**
 * Chooses the scheduler backend for the running server.
 *
 * <p>The decision is based on real capability detection, not on the server name:
 * if the Folia scheduler entry points exist we use them, otherwise we use the
 * Bukkit main-thread scheduler. That keeps Folia support honest - AstraSyntax only
 * claims region-safe scheduling when it actually schedules region-safely.</p>
 */
public final class Schedulers {

    private Schedulers() {}

    public static SchedulerService create(Plugin plugin, ServerPlatform platform, AstraLogger logger) {
        if (platform.folia()) {
            try {
                return new FoliaSchedulerService(plugin, logger);
            } catch (Throwable error) {
                logger.error("Folia scheduler backend unavailable (" + logger.describe(error)
                    + "); falling back to the Bukkit scheduler");
            }
        }
        return new BukkitSchedulerService(plugin, logger);
    }
}
