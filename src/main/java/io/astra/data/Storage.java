package io.astra.data;

import io.astra.runtime.Value;

import java.util.Map;
import java.util.UUID;

/**
 * The persistence backend behind {@link DataStore}.
 *
 * <p>Backends only move rows around; caching, defaults, dirty tracking and thread
 * discipline live in the store above them. Every method may be called from an async
 * thread and must therefore be free of Bukkit API calls.</p>
 */
public interface Storage {

    /** A short description used by {@code /astra info} and startup logs. */
    String describe();

    /** True when the backend opened successfully. */
    boolean ready();

    /** Load every stored key for a holder (empty when the holder is unknown). */
    Map<String, Value> load(UUID holder);

    /** Write the given keys for a holder; keys whose value is {@code null} are deleted. */
    void save(UUID holder, Map<String, Value> values);

    /** Delete everything stored for a holder. */
    void delete(UUID holder);

    /** Load the global (server-scoped) values. */
    Map<String, Value> loadGlobal();

    /** Write global values; {@code null} values are deleted. */
    void saveGlobal(Map<String, Value> values);

    /** Apply schema migrations. Called once during startup. */
    void migrate();

    /** Flush any buffered writes and release the connection. */
    void close();

    /** A one-line health report for diagnostics. */
    String health();
}
