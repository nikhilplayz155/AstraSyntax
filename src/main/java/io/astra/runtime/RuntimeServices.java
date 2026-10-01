package io.astra.runtime;

import io.astra.config.ConfigManager;
import io.astra.data.DataStore;
import io.astra.logging.AstraLogger;
import io.astra.platform.SchedulerService;
import io.astra.platform.TextService;
import io.astra.profiler.Profiler;
import io.astra.runtime.economy.Economy;
import io.astra.runtime.event.EventBus;
import io.astra.runtime.net.HttpService;
import io.astra.runtime.expression.PlaceholderService;
import io.astra.runtime.scheduler.TaskRegistry;
import io.astra.security.SecurityGate;

/**
 * Everything a running rule may reach.
 *
 * <p>The runtime depends on this interface rather than on the plugin class, which keeps
 * the execution engine free of Bukkit bootstrap concerns and lets the compiler and
 * executor be unit tested with a small fake implementation.</p>
 */
public interface RuntimeServices {

    AstraLogger logger();

    /** Parsed configuration (all nine supplied files). */
    ConfigManager config();

    SchedulerService scheduler();

    TextService text();

    DataStore data();

    Registries registries();

    EventBus events();

    PlaceholderService placeholders();

    SecurityGate security();

    Profiler profiler();

    /** Script-owned scheduled tasks, cancelled when a script unloads. */
    TaskRegistry tasks();

    /** The plugin instance, for integrations that need one. */
    Object plugin();

    /**
     * The installed economy (Vault bridge), or {@code null} when there is none.
     * Never a hard dependency: the built-in economy actions report the missing
     * integration instead of failing to load.
     */
    default Economy economy() {
        return null;
    }

    /** Outbound HTTP with {@code security.yml} enforced, or {@code null} when disabled. */
    default HttpService http() {
        return null;
    }

    /** A message from {@code language.yml}, with placeholders already substituted. */
    String message(String key, Object... replacements);

    /** True when the server is shutting down (rules stop being scheduled). */
    boolean shuttingDown();
}
