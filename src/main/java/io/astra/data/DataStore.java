package io.astra.data;

import io.astra.runtime.Value;

import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Entity;

/**
 * Automatic per-player (and per-entity/global) data without needing variable syntax.
 *
 * <p>Scripts write {@code add 5 coins to player} and AstraSyntax takes care of the UUID
 * association, typing, defaults, caching, dirty tracking and persistence. This
 * interface is the view the runtime uses; {@code DataStoreImpl} owns the caching and
 * the storage hand-off.</p>
 */
public interface DataStore {

    /** Current value of a key, or the declared default when nothing is stored yet. */
    Value get(Entity holder, String key);

    /** True when the holder has an explicit stored value for the key. */
    boolean has(Entity holder, String key);

    /** Store a value (marks the holder dirty for the next autosave). */
    void set(Entity holder, String key, Value value);

    /** Remove a stored value, restoring the declared default. */
    void remove(Entity holder, String key);

    /** Every key currently known for a holder. */
    Set<String> keys(Entity holder);

    /** A value that is not attached to any player (world/server scoped). */
    Value getGlobal(String key);

    void setGlobal(String key, Value value);

    /** True when a global value exists. */
    boolean hasGlobal(String key);

    /** Declare a key (name, default, persistence) before scripts use it. */
    void declare(String key, Value defaultValue, io.astra.runtime.ValueType type, boolean persistent);

    /** True when the key has been declared by a script or a package. */
    boolean isDeclared(String key);

    /** True when the declared key is persisted to storage. */
    boolean isPersistent(String key);

    /** Flush pending writes for a specific player (used on quit). */
    void flush(UUID uuid);

    /** Flush everything (used on shutdown and by the autosave task). */
    void flushAll();

    /** Number of players currently cached. */
    int cachedCount();
}
