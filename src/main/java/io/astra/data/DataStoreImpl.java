package io.astra.data;

import io.astra.logging.AstraLogger;
import io.astra.platform.SchedulerService;
import io.astra.runtime.Value;
import io.astra.runtime.ValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.entity.Entity;

/**
 * Caching layer in front of a {@link Storage} backend.
 *
 * <p>Threading rules the rest of the runtime relies on:</p>
 * <ul>
 *   <li>{@code get}/{@code set}/{@code has} only touch an in-memory map - they never do
 *       I/O, so they are safe to call from a region thread on Folia;</li>
 *   <li>loading and saving happen through {@link SchedulerService} async tasks, started by
 *       {@link #preload(Entity)} (join), {@link #flushAsync(UUID)} (quit) and the autosave
 *       task, never from a rule body;</li>
 *   <li>a value read before its row has arrived returns the declared default and the row is
 *       requested once, which keeps a slow database from stalling the server.</li>
 * </ul>
 */
public final class DataStoreImpl implements DataStore {

    /** The declared shape of one key. */
    public record Declaration(Value defaultValue, ValueType type, boolean persistent) { }

    private final Storage storage;
    private final AstraLogger logger;
    private final SchedulerService scheduler;
    private final boolean cacheEnabled;

    private final Map<String, Declaration> declarations = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Value>> cache = new ConcurrentHashMap<>();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean globalLoaded = new AtomicBoolean();
    private final Map<String, Value> globals = new ConcurrentHashMap<>();
    private final Set<String> globalDirty = ConcurrentHashMap.newKeySet();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong mutations = new AtomicLong();
    private volatile boolean closed;

    public DataStoreImpl(Storage storage, AstraLogger logger, SchedulerService scheduler, boolean cacheEnabled) {
        this.storage = storage;
        this.logger = logger;
        this.scheduler = scheduler;
        this.cacheEnabled = cacheEnabled;
    }

    public Storage storage() {
        return storage;
    }

    // ------------------------------------------------------------- declarations

    @Override
    public void declare(String key, Value defaultValue, ValueType type, boolean persistent) {
        if (key == null || key.isBlank()) return;
        String normalised = key.toLowerCase(Locale.ROOT);
        declarations.put(normalised, new Declaration(defaultValue == null ? Value.NULL : defaultValue,
            type == null ? ValueType.NULL : type, persistent));
    }

    @Override
    public boolean isDeclared(String key) {
        return key != null && declarations.containsKey(key.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean isPersistent(String key) {
        if (key == null) return false;
        Declaration declaration = declarations.get(key.toLowerCase(Locale.ROOT));
        return declaration != null && declaration.persistent();
    }

    private Value defaultFor(String key) {
        if (key == null) return Value.NULL;
        Declaration declaration = declarations.get(key.toLowerCase(Locale.ROOT));
        // An undeclared counter starts at zero: "add 5 coins to player" must work
        // before anyone thought to declare the key.
        if (declaration == null) return Value.num(0L);
        return declaration.defaultValue();
    }

    // -------------------------------------------------------------------- reads

    @Override
    public Value get(Entity holder, String key) {
        if (key == null || key.isBlank()) return Value.NULL;
        UUID uuid = holder == null ? null : holder.getUniqueId();
        if (uuid == null) return getGlobal(key);
        Map<String, Value> values = cache.get(uuid);
        if (values != null) {
            Value stored = values.get(key.toLowerCase(Locale.ROOT));
            if (stored != null) return stored;
        }
        ensureLoaded(uuid);
        return defaultFor(key);
    }

    @Override
    public boolean has(Entity holder, String key) {
        if (key == null || holder == null) return false;
        Map<String, Value> values = cache.get(holder.getUniqueId());
        if (values == null) {
            ensureLoaded(holder.getUniqueId());
            return false;
        }
        return values.containsKey(key.toLowerCase(Locale.ROOT));
    }

    @Override
    public Set<String> keys(Entity holder) {
        if (holder == null) return Set.of();
        Map<String, Value> values = cache.get(holder.getUniqueId());
        return values == null ? Set.of() : Set.copyOf(values.keySet());
    }

    @Override
    public Value getGlobal(String key) {
        if (key == null || key.isBlank()) return Value.NULL;
        ensureGlobalLoaded();
        Value value = globals.get(key.toLowerCase(Locale.ROOT));
        return value == null ? defaultFor(key) : value;
    }

    @Override
    public boolean hasGlobal(String key) {
        if (key == null) return false;
        ensureGlobalLoaded();
        return globals.containsKey(key.toLowerCase(Locale.ROOT));
    }

    private void ensureLoaded(UUID uuid) {
        if (!cacheEnabled || uuid == null || closed) return;
        if (loaded.contains(uuid) || !loading.add(uuid)) return;
        SchedulerService service = scheduler;
        if (service == null) {
            loadNow(uuid);
            return;
        }
        service.runAsync(() -> {
            try {
                Map<String, Value> values = storage.load(uuid);
                cache.put(uuid, new ConcurrentHashMap<>(values));
                loaded.add(uuid);
            } catch (RuntimeException error) {
                logger.debug("Could not load stored data for " + uuid + ": " + error.getMessage());
            } finally {
                loading.remove(uuid);
            }
        });
    }

    private void ensureGlobalLoaded() {
        if (!globalLoaded.compareAndSet(false, true)) return;
        SchedulerService service = scheduler;
        if (service == null) {
            globals.putAll(storage.loadGlobal());
            return;
        }
        service.runAsync(() -> {
            try {
                globals.putAll(storage.loadGlobal());
            } catch (RuntimeException error) {
                globalLoaded.set(false);
                logger.debug("Could not load global data: " + error.getMessage());
            }
        });
    }

    /** Load a holder immediately, on the calling thread (startup, tests, admin commands). */
    public void loadNow(UUID uuid) {
        if (uuid == null) return;
        Map<String, Value> values = storage.load(uuid);
        cache.put(uuid, new ConcurrentHashMap<>(values));
        loaded.add(uuid);
    }

    /** Load a holder asynchronously; used on join and at startup. */
    public void preload(Entity entity) {
        if (entity == null) return;
        ensureLoaded(entity.getUniqueId());
    }

    /** Load every holder the backend knows about; only used by admin tooling and tests. */
    public int preloadAll(List<Entity> entities) {
        int count = 0;
        for (Entity entity : entities) {
            if (entity != null) {
                preload(entity);
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------- writes

    @Override
    public void set(Entity holder, String key, Value value) {
        if (key == null || key.isBlank()) return;
        if (holder == null) {
            setGlobal(key, value);
            return;
        }
        String normalised = key.toLowerCase(Locale.ROOT);
        UUID uuid = holder.getUniqueId();
        Map<String, Value> values = cache.computeIfAbsent(uuid, id -> new ConcurrentHashMap<>());
        if (value == null || value.isNull()) {
            values.remove(normalised);
        } else {
            values.put(normalised, value);
        }
        if (isPersistent(key)) {
            dirty.add(uuid);
        }
        mutations.incrementAndGet();
    }

    @Override
    public void setGlobal(String key, Value value) {
        if (key == null || key.isBlank()) return;
        String normalised = key.toLowerCase(Locale.ROOT);
        ensureGlobalLoaded();
        if (value == null || value.isNull()) {
            globals.remove(normalised);
        } else {
            globals.put(normalised, value);
        }
        if (isPersistent(key)) {
            globalDirty.add(normalised);
        }
    }

    @Override
    public void remove(Entity holder, String key) {
        set(holder, key, Value.NULL);
    }

    // -------------------------------------------------------------------- flush

    @Override
    public void flush(UUID uuid) {
        if (uuid == null) return;
        Map<String, Value> values = cache.get(uuid);
        if (values == null) return;
        Map<String, Value> snapshot = new LinkedHashMap<>(values);
        storage.save(uuid, snapshot);
        dirty.remove(uuid);
        writes.incrementAndGet();
    }

    /** Persist one holder off the main thread. */
    public void flushAsync(UUID uuid) {
        if (uuid == null || scheduler == null) {
            flush(uuid);
            return;
        }
        scheduler.runAsync(() -> flush(uuid));
    }

    @Override
    public void flushAll() {
        List<UUID> holders = new ArrayList<>(dirty);
        for (UUID uuid : holders) {
            flush(uuid);
        }
        flushGlobals();
    }

    private void flushGlobals() {
        if (globalDirty.isEmpty() && !globalLoaded.get()) return;
        Map<String, Value> snapshot = new LinkedHashMap<>();
        for (String key : globalDirty) {
            snapshot.put(key, globals.getOrDefault(key, Value.NULL));
        }
        if (snapshot.isEmpty()) return;
        storage.saveGlobal(snapshot);
        globalDirty.clear();
        writes.incrementAndGet();
    }

    /** Save only what changed, both holders and globals. */
    public void saveDirty() {
        for (UUID uuid : new ArrayList<>(dirty)) {
            flush(uuid);
        }
        flushGlobals();
    }

    @Override
    public int cachedCount() {
        return cache.size();
    }

    /** Number of holders with unsaved changes. */
    public int dirtyCount() {
        return dirty.size();
    }

    /** How many save operations this store has performed (diagnostics). */
    public long writeCount() {
        return writes.get();
    }

    /** How many values scripts have written (diagnostics). */
    public long mutationCount() {
        return mutations.get();
    }

    /** Forget a holder entirely (used on quit after the data was saved). */
    public void unload(UUID uuid) {
        dirty.remove(uuid);
        loaded.remove(uuid);
        cache.remove(uuid);
    }

    /** Drop every cached value; the next read reloads it from storage. */
    public void invalidate() {
        cache.clear();
        loaded.clear();
        globals.clear();
        globalLoaded.set(false);
    }

    /** Close the backend; writes everything first. */
    public void shutdown() {
        closed = true;
        try {
            saveDirty();
        } catch (RuntimeException error) {
            logger.warn("Could not save all data during shutdown: " + error.getMessage());
        }
        storage.close();
    }

    /** The declared keys, for {@code /astra info} and documentation output. */
    public Map<String, Declaration> declarations() {
        return Map.copyOf(declarations);
    }
}
