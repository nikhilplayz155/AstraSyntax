package io.astra.data;

import io.astra.logging.AstraLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers the bundled-driver pipeline: nested jar extraction (with hash-based
 * reuse) and the isolated driver registration that makes
 * {@code storage.yml} work on a stock server without a manual driver install.
 */
class LibLoaderTest {

    private final AstraLogger logger = new AstraLogger(message -> { });

    @Test
    void extractsNestedJarsAndReusesUnchangedCopies(@TempDir Path temp) throws Exception {
        Path pluginJar = temp.resolve("AstraSyntax.jar");
        byte[] payload = zipBytes("payload.txt", "astra");
        writeJar(pluginJar, "astra/lib/sqlite-jdbc-3.47.1.0.jar", payload,
            "astra/lib/mysql-connector.jar", payload);

        Path libDirectory = temp.resolve("lib");
        List<Path> first = LibLoader.extractNestedJars(pluginJar, libDirectory, logger);
        assertEquals(2, first.size(), "both nested driver jars must be extracted");
        assertTrue(Files.isRegularFile(libDirectory.resolve("sqlite-jdbc-3.47.1.0.jar")));
        assertEquals(payload.length, Files.readAllBytes(libDirectory.resolve("mysql-connector.jar")).length);

        // A second pass over unchanged files must keep them byte-identical
        // (this is what avoids rewriting 15 MB of driver jar on every restart).
        List<Path> second = LibLoader.extractNestedJars(pluginJar, libDirectory, logger);
        assertEquals(first, second);

        // A tampered extraction is detected by the hash and rewritten.
        Path tampered = libDirectory.resolve("mysql-connector.jar");
        Files.write(tampered, "corrupted".getBytes(StandardCharsets.UTF_8));
        LibLoader.extractNestedJars(pluginJar, libDirectory, logger);
        assertFalse(java.util.Arrays.equals("corrupted".getBytes(StandardCharsets.UTF_8),
            Files.readAllBytes(tampered)), "a modified driver jar must be replaced by the packaged copy");
    }

    @Test
    void ignoresEntriesThatEscapeTheLibFolder(@TempDir Path temp) {
        Path libDirectory = temp.resolve("lib");
        assertTrue(LibLoader.targetFor(libDirectory, "astra/lib/../escaped.jar").isEmpty());
        assertTrue(LibLoader.targetFor(libDirectory, "astra/lib/sub/other.jar").isEmpty());
        assertTrue(LibLoader.targetFor(libDirectory, "/astra/lib/absolute.jar").isEmpty());
        assertTrue(LibLoader.targetFor(libDirectory, "other/folder/driver.jar").isEmpty());
        assertEquals(libDirectory.resolve("kept.jar"),
            LibLoader.targetFor(libDirectory, "astra/lib/kept.jar").orElseThrow());
    }

    @Test
    void extractsOnlyValidNestedEntries(@TempDir Path temp) throws Exception {
        Path pluginJar = temp.resolve("AstraSyntax.jar");
        byte[] payload = zipBytes("payload.txt", "astra");
        writeJar(pluginJar, "astra/lib/kept.jar", payload, "astra/not-a-driver.txt", payload);

        List<Path> extracted = LibLoader.extractNestedJars(pluginJar, temp.resolve("lib"), logger);
        assertEquals(1, extracted.size());
        assertEquals("kept.jar", extracted.get(0).getFileName().toString());
    }

    @Test
    void registersBundledDriverAndOpensAConnection(@TempDir Path temp) throws Exception {
        Path sqlite = Path.of("libs", "sqlite-jdbc-3.47.1.0.jar");
        assumeTrue(Files.isRegularFile(sqlite), "bundled sqlite-jdbc jar is not present in this checkout");

        LibLoader.Loaded loaded = LibLoader.installFrom(List.of(sqlite), logger, getClass().getClassLoader());
        assertTrue(loaded.drivers() >= 1, "at least one JDBC driver must be registered from the jar");

        Path database = temp.resolve("astra-test.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE astra_probe (probe_key TEXT PRIMARY KEY, probe_value TEXT NOT NULL)");
            statement.execute("INSERT INTO astra_probe VALUES ('language', 'astra')");
            try (ResultSet rows = statement.executeQuery("SELECT probe_value FROM astra_probe WHERE probe_key = 'language'")) {
                assertTrue(rows.next());
                assertEquals("astra", rows.getString(1));
            }
        } finally {
            if (loaded.loader() instanceof AutoCloseable closeable) closeable.close();
        }
    }

    private static void writeJar(Path target, String firstName, byte[] first, String secondName, byte[] second)
        throws Exception {
        Files.createDirectories(target.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new JarEntry(firstName));
            out.write(first);
            out.closeEntry();
            out.putNextEntry(new JarEntry(secondName));
            out.write(second);
            out.closeEntry();
        }
    }

    private static byte[] zipBytes(String entryName, String content) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }
}
