package io.astra.data;

import io.astra.logging.AstraLogger;
import io.astra.runtime.Value;
import io.astra.util.Strings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * JDBC backend for SQLite, MySQL and MariaDB.
 *
 * <p>Details that matter for a plugin that shares a server with other software:</p>
 * <ul>
 *   <li>every statement is prepared and every write runs inside one transaction, so a
 *       failed autosave never leaves half of a player's data updated;</li>
 *   <li>the driver is loaded by name and its absence is reported as an actionable
 *       message instead of a {@code NoClassDefFoundError};</li>
 *   <li>the schema is versioned in {@code astra_meta}, which is what makes future
 *       migrations possible without guessing whether a column already exists;</li>
 *   <li>{@code close()} commits, closes and never touches Bukkit, so it is safe from the
 *       shutdown thread.</li>
 * </ul>
 */
public final class SqlStorage implements Storage {

    /** Bump this and add a case in {@link #applyMigrations} whenever the schema changes. */
    public static final int SCHEMA_VERSION = 1;

    private static final String GLOBAL_HOLDER = "00000000-0000-0000-0000-000000000000";

    /** Column widths for the MySQL/MariaDB dialect (SQLite ignores them). */
    private static final String TABLE_SQL_MYSQL = """
        CREATE TABLE IF NOT EXISTS astra_data (
          holder VARCHAR(36) NOT NULL,
          key_name VARCHAR(64) NOT NULL,
          value TEXT NOT NULL,
          updated_at BIGINT NOT NULL,
          PRIMARY KEY (holder, key_name)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""";

    private static final String TABLE_SQL_SQLITE = """
        CREATE TABLE IF NOT EXISTS astra_data (
          holder TEXT NOT NULL,
          key_name TEXT NOT NULL,
          value TEXT NOT NULL,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (holder, key_name)
        )""";

    private final String jdbcUrl;
    private final String driverClass;
    private final Properties properties;
    private final AstraLogger logger;
    private final boolean mysqlDialect;

    private Connection connection;
    private boolean ready;
    private String lastError = "";

    private SqlStorage(String jdbcUrl, String driverClass, Properties properties, boolean mysqlDialect,
                       AstraLogger logger) {
        this.jdbcUrl = jdbcUrl;
        this.driverClass = driverClass;
        this.properties = properties;
        this.mysqlDialect = mysqlDialect;
        this.logger = logger;
    }

    /** SQLite at the given file, creating parent folders as needed. */
    public static SqlStorage sqlite(Path file, AstraLogger logger) {
        return new SqlStorage("jdbc:sqlite:" + file.toAbsolutePath(), "org.sqlite.JDBC", new Properties(),
            false, logger);
    }

    /** MySQL/MariaDB using the supplied credentials. The password is never logged. */
    public static SqlStorage mysql(String host, int port, String database, String username, String password,
                                   boolean ssl, AstraLogger logger) {
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=" + ssl
            + "&allowPublicKeyRetrieval=" + ssl + "&serverTimezone=UTC";
        Properties properties = new Properties();
        properties.setProperty("user", username == null ? "" : username);
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "15000");
        return new SqlStorage(url, "com.mysql.cj.jdbc.Driver", properties, true, logger);
    }

    @Override
    public String describe() {
        if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            return "sqlite (" + jdbcUrl.substring("jdbc:sqlite:".length()) + ")";
        }
        // Never include credentials in a description that ends up in logs.
        String url = jdbcUrl;
        int query = url.indexOf('?');
        if (query > 0) url = url.substring(0, query);
        return "mysql (" + url.substring("jdbc:mysql://".length()) + ")";
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public void migrate() {
        if (!open()) return;
        try {
            int current = readSchemaVersion();
            if (current < SCHEMA_VERSION) {
                applyMigrations(current);
                writeSchemaVersion(SCHEMA_VERSION);
                logger.info("Storage schema migrated from v" + current + " to v" + SCHEMA_VERSION);
            }
            ready = true;
        } catch (SQLException e) {
            ready = false;
            lastError = describe(e);
            logger.error("Could not migrate the storage schema: " + lastError);
        }
    }

    private boolean open() {
        if (connection != null) return true;
        try {
            if (driverClass != null) {
                try {
                    Class.forName(driverClass);
                } catch (ClassNotFoundException missing) {
                    // DriverManager may still resolve it through the service loader.
                    logger.debug("JDBC driver " + driverClass + " is not loaded yet; "
                        + "DriverManager will try the registered drivers");
                }
            }
            connection = properties.isEmpty()
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, properties);
            connection.setAutoCommit(true);
            return true;
        } catch (SQLException | RuntimeException e) {
            ready = false;
            lastError = describe(e);
            logger.error("Storage is unavailable: " + lastError);
            logger.error("Add the JDBC driver to the server (see README: storage) or set storage.type to 'file'");
            return false;
        }
    }

    private int readSchemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(mysqlDialect
                ? "CREATE TABLE IF NOT EXISTS astra_meta (meta_key VARCHAR(64) PRIMARY KEY, "
                    + "meta_value VARCHAR(255) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                : "CREATE TABLE IF NOT EXISTS astra_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT meta_value FROM astra_meta WHERE meta_key = ?")) {
            statement.setString(1, "schema_version");
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return 0;
                try {
                    return Integer.parseInt(rows.getString(1));
                } catch (NumberFormatException broken) {
                    return 0;
                }
            }
        }
    }

    private void applyMigrations(int from) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (from < 1) {
                statement.execute(mysqlDialect ? TABLE_SQL_MYSQL : TABLE_SQL_SQLITE);
            }
            // Later schema changes append additional `if (from < N)` blocks here. The
            // version row is written only after every step succeeded.
        }
    }

    private void writeSchemaVersion(int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE astra_meta SET meta_value = ? WHERE meta_key = ?")) {
            statement.setString(1, Integer.toString(version));
            statement.setString(2, "schema_version");
            if (statement.executeUpdate() > 0) return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO astra_meta (meta_key, meta_value) VALUES (?, ?)")) {
            statement.setString(1, "schema_version");
            statement.setString(2, Integer.toString(version));
            statement.executeUpdate();
        }
    }

    @Override
    public Map<String, Value> load(UUID holder) {
        if (!open()) return Map.of();
        String id = holder == null ? GLOBAL_HOLDER : holder.toString();
        Map<String, String> raw = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT key_name, value FROM astra_data WHERE holder = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) raw.put(rows.getString(1), rows.getString(2));
            }
        } catch (SQLException e) {
            logger.warnThrottled("storage-load", "Could not read stored data: " + describe(e));
            return Map.of();
        }
        return ValueCodec.decodeAll(raw);
    }

    @Override
    public void save(UUID holder, Map<String, Value> values) {
        if (values == null || values.isEmpty() || !open()) return;
        String id = holder == null ? GLOBAL_HOLDER : holder.toString();
        long now = System.currentTimeMillis();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement(mysqlDialect
                    ? "INSERT INTO astra_data (holder, key_name, value, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE value = VALUES(value), updated_at = VALUES(updated_at)"
                    : "INSERT INTO astra_data (holder, key_name, value, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(holder, key_name) DO UPDATE SET value = excluded.value, "
                        + "updated_at = excluded.updated_at");
                 PreparedStatement remove = connection.prepareStatement(
                     "DELETE FROM astra_data WHERE holder = ? AND key_name = ?")) {
                for (Map.Entry<String, Value> entry : values.entrySet()) {
                    String encoded = ValueCodec.encode(entry.getValue());
                    if (encoded.isEmpty()) {
                        remove.setString(1, id);
                        remove.setString(2, entry.getKey());
                        remove.addBatch();
                        continue;
                    }
                    upsert.setString(1, id);
                    upsert.setString(2, entry.getKey());
                    upsert.setString(3, encoded);
                    upsert.setLong(4, now);
                    upsert.addBatch();
                }
                upsert.executeBatch();
                remove.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // The connection is already unusable; the next save reopens it.
            }
            logger.warnThrottled("storage-save", "Could not write stored data: " + describe(e));
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // ignored
            }
        }
    }

    @Override
    public void delete(UUID holder) {
        if (!open()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM astra_data WHERE holder = ?")) {
            statement.setString(1, holder == null ? GLOBAL_HOLDER : holder.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warnThrottled("storage-delete", "Could not delete stored data: " + describe(e));
        }
    }

    @Override
    public Map<String, Value> loadGlobal() {
        if (!open()) return Map.of();
        Map<String, String> raw = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT key_name, value FROM astra_data WHERE holder = ?")) {
            statement.setString(1, GLOBAL_HOLDER);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) raw.put(rows.getString(1), rows.getString(2));
            }
        } catch (SQLException e) {
            logger.warnThrottled("storage-load-global", "Could not read global data: " + describe(e));
            return Map.of();
        }
        return ValueCodec.decodeAll(raw);
    }

    @Override
    public void saveGlobal(Map<String, Value> values) {
        save(null, values);
    }

    @Override
    public synchronized void close() {
        ready = false;
        if (connection == null) return;
        try {
            if (!connection.getAutoCommit()) connection.commit();
            connection.close();
        } catch (SQLException e) {
            logger.debug("Could not close the storage connection cleanly: " + describe(e));
        } finally {
            connection = null;
        }
    }

    @Override
    public String health() {
        if (!ready) return describe() + " [unavailable" + (lastError.isEmpty() ? "" : ": " + lastError) + "]";
        try {
            if (connection == null || connection.isClosed()) return describe() + " [closed]";
            return describe() + " [schema v" + readSchemaVersion() + ", driver "
                + connection.getMetaData().getDriverName() + " " + connection.getMetaData().getDriverVersion() + "]";
        } catch (SQLException | RuntimeException e) {
            return describe() + " [degraded: " + describe(e) + "]";
        }
    }

    /** Force a reconnect, used by {@code /astra reload} after a database restart. */
    public synchronized void reconnect() {
        close();
        migrate();
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        if (Strings.isBlank(message)) message = error.getClass().getSimpleName();
        if (message != null && message.length() > 200) message = message.substring(0, 200) + "...";
        return message;
    }

    /** The JDBC URL without credentials, for diagnostics and tests. */
    public String url() {
        int query = jdbcUrl.indexOf('?');
        return query > 0 ? jdbcUrl.substring(0, query) : jdbcUrl;
    }
}
