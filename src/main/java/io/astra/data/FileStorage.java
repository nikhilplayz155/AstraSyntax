package io.astra.data;

import io.astra.logging.AstraLogger;
import io.astra.runtime.Value;
import io.astra.util.FileUtil;
import io.astra.util.Strings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The dependency-free backend used by {@code storage.type: file}.
 *
 * <p>It exists for two reasons: a server can run AstraSyntax without any JDBC driver, and
 * the automated tests can exercise the whole data path (dirty tracking, defaults,
 * autosave, reload) without a database. The format is one tab-separated record per line -
 * {@code holder \t key \t encoded value} - written atomically through
 * {@link FileUtil#writeAtomic}, so a crash mid-save cannot leave a half-written file.</p>
 *
 * <p>It is not meant to be edited by hand; {@code storage.type: sqlite} is the default and
 * the recommended option for real servers.</p>
 */
public final class FileStorage implements Storage {

    private static final String GLOBAL_HOLDER = "*global*";

    private final Path file;
    private final AstraLogger logger;
    private final Map<UUID, Map<String, Value>> holders = new LinkedHashMap<>();
    private final Map<String, Value> globals = new LinkedHashMap<>();
    private boolean ready;
    private boolean dirty;

    public FileStorage(Path file, AstraLogger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public String describe() {
        return "file (" + file + ")";
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public synchronized void migrate() {
        try {
            if (file.getParent() != null) FileUtil.ensureDirectory(file.getParent());
            if (!Files.exists(file)) {
                FileUtil.writeAtomic(file, "# AstraSyntax file storage\n".getBytes(StandardCharsets.UTF_8));
            }
            read();
            ready = true;
        } catch (IOException e) {
            ready = false;
            logger.error("Could not open file storage at " + file + ": " + e.getMessage());
        }
    }

    private void read() throws IOException {
        holders.clear();
        globals.clear();
        if (!Files.exists(file)) return;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            String holder = parts[0];
            String key = parts[1];
            Value value = ValueCodec.decode(parts[2]);
            if (value.isNull()) continue;
            if (GLOBAL_HOLDER.equals(holder)) {
                globals.put(key, value);
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(holder);
            } catch (IllegalArgumentException broken) {
                continue;
            }
            holders.computeIfAbsent(uuid, key2 -> new LinkedHashMap<>()).put(key, value);
        }
    }

    @Override
    public synchronized Map<String, Value> load(UUID holder) {
        Map<String, Value> stored = holders.get(holder);
        return stored == null ? Map.of() : new LinkedHashMap<>(stored);
    }

    @Override
    public synchronized void save(UUID holder, Map<String, Value> values) {
        if (holder == null || values == null || values.isEmpty()) return;
        Map<String, Value> stored = holders.computeIfAbsent(holder, key -> new LinkedHashMap<>());
        for (Map.Entry<String, Value> entry : values.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isNull()) {
                stored.remove(entry.getKey());
            } else {
                stored.put(entry.getKey(), entry.getValue());
            }
        }
        if (stored.isEmpty()) holders.remove(holder);
        dirty = true;
        write();
    }

    @Override
    public synchronized void delete(UUID holder) {
        if (holders.remove(holder) != null) {
            flushToDisk();
        }
    }

    @Override
    public synchronized Map<String, Value> loadGlobal() {
        return new LinkedHashMap<>(globals);
    }

    @Override
    public synchronized void saveGlobal(Map<String, Value> values) {
        if (values == null || values.isEmpty()) return;
        for (Map.Entry<String, Value> entry : values.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isNull()) {
                globals.remove(entry.getKey());
            } else {
                globals.put(entry.getKey(), entry.getValue());
            }
        }
        dirty = true;
        write();
    }

    private void write() {
        try {
            flushToDisk();
        } catch (RuntimeException error) {
            logger.error("Could not write " + file + ": " + error.getMessage());
        }
    }

    private void flushToDisk() {
        StringBuilder out = new StringBuilder();
        out.append("# AstraSyntax file storage - one record per line: holder<TAB>key<TAB>value\n");
        for (Map.Entry<String, Value> entry : globals.entrySet()) {
            String encoded = ValueCodec.encode(entry.getValue());
            if (!encoded.isEmpty()) out.append(GLOBAL_HOLDER).append('\t').append(entry.getKey()).append('\t')
                .append(encoded).append('\n');
        }
        for (Map.Entry<UUID, Map<String, Value>> holder : holders.entrySet()) {
            for (Map.Entry<String, Value> entry : holder.getValue().entrySet()) {
                String encoded = ValueCodec.encode(entry.getValue());
                if (!encoded.isEmpty()) out.append(holder.getKey()).append('\t').append(entry.getKey()).append('\t')
                    .append(encoded).append('\n');
            }
        }
        try {
            FileUtil.writeAtomic(file, out.toString().getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        if (dirty) {
            try {
                flushToDisk();
            } catch (RuntimeException error) {
                logger.error("Could not flush file storage during shutdown: " + error.getMessage());
            }
        }
    }

    @Override
    public String health() {
        return describe() + (ready ? " [" + holders.size() + " holder(s), " + globals.size() + " global key(s)]"
            : " [not ready]");
    }

    /** Path of the backing file (used by the tests). */
    public Path file() {
        return file;
    }

    /** True when something has been written since the last flush (tests). */
    public boolean hasPendingWrites() {
        return dirty;
    }

    /** Remove everything (used by {@code /astra} data reset and by the tests). */
    public synchronized void wipe() {
        holders.clear();
        globals.clear();
        try {
            FileUtil.writeAtomic(file, Strings.trimToEmpty("# AstraSyntax file storage\n")
                .getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (IOException e) {
            logger.error("Could not clear file storage: " + e.getMessage());
        }
    }
}
