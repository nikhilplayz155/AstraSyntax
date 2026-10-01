package io.astra.data;

import io.astra.logging.AstraLogger;
import io.astra.util.FileUtil;
import io.astra.util.Strings;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads the JDBC drivers that ship inside the plugin jar as nested jars
 * ({@code astra/lib/*.jar}).
 *
 * <p>Why nested jars instead of shading the drivers into the plugin classes: the
 * server class path is shared by every plugin, and two plugins that bundle
 * different {@code sqlite-jdbc} builds collide in confusing ways (one of them
 * silently wins). Keeping the drivers in their own jar and loading them through
 * a dedicated class loader isolates AstraSyntax completely.</p>
 *
 * <p>Three things happen here:</p>
 * <ol>
 *   <li>the nested jars are extracted to {@code <dataFolder>/lib} - only when the
 *       file is missing or its SHA-256 does not match the copy inside the plugin
 *       jar, so upgrades are picked up but server restarts are not wasted;</li>
 *   <li>the extracted jars are exposed through one {@link URLClassLoader};</li>
 *   <li>every {@link Driver} that the jars declare in
 *       {@code META-INF/services/java.sql.Driver} is wrapped in a
 *       {@link DriverShim} and registered with {@link DriverManager}.</li>
 * </ol>
 *
 * <p>The shim is what makes step three work at all: {@code DriverManager} only
 * hands a connection to a driver whose class is visible to the caller's class
 * loader, and a driver loaded from {@code <dataFolder>/lib} never is. The shim
 * lives in the plugin's own class loader and forwards to the isolated instance,
 * which is the long-standing, safe way to do this.</p>
 *
 * <p>Nothing here is fatal: when extraction or driver discovery fails the caller
 * keeps the plain {@code DriverManager} path and the storage layer reports that
 * the driver is unavailable. Storage never blocks plugin start-up.</p>
 */
public final class LibLoader {

    /** Folder inside the plugin jar that holds the nested driver jars. */
    public static final String NESTED_ROOT = "astra/lib";

    /** Sub-folder of the plugin data folder the drivers are extracted to. */
    public static final String LIB_FOLDER = "lib";

    private static volatile ClassLoader sharedLoader;

    private LibLoader() {
    }

    /** Outcome of an install attempt. */
    public record Loaded(int extracted, int reused, int drivers, ClassLoader loader, List<String> jars) {

        /** True when at least one driver was registered. */
        public boolean usable() {
            return drivers > 0;
        }

        /** A one-line, log-friendly summary. */
        public String describe() {
            if (jars.isEmpty()) return "no bundled JDBC drivers (using the server class path)";
            return jars.size() + " driver jar(s), " + extracted + " extracted, " + reused + " reused, "
                + drivers + " driver(s) registered";
        }
    }

    /** The isolated class loader created by the last install, or {@code null}. */
    public static ClassLoader classLoader() {
        return sharedLoader;
    }

    /**
     * Extracts and registers every nested JDBC jar that is packaged inside this
     * plugin jar (or inside the build output directory during development).
     *
     * @param source     the plugin's class loader, used to locate its own jar
     * @param dataFolder the plugin data folder ({@code plugins/AstraSyntax})
     */
    public static Loaded install(ClassLoader source, Path dataFolder, AstraLogger logger) {
        Path libDirectory = dataFolder.resolve(LIB_FOLDER);
        try {
            Path origin = ownLocation(source);
            if (origin == null) {
                logger.debug("Could not locate the AstraSyntax jar; skipping bundled JDBC drivers");
                return new Loaded(0, 0, 0, null, List.of());
            }
            List<Path> extracted = List.of();
            if (Files.isDirectory(origin)) {
                extracted = scanDirectory(origin.resolve(NESTED_ROOT));
            } else {
                extracted = extractNestedJars(origin, libDirectory, logger);
            }
            if (extracted.isEmpty()) {
                logger.debug("No bundled JDBC jars found in " + origin);
                return new Loaded(0, 0, 0, null, List.of());
            }
            Loaded loaded = installFrom(extracted, logger, source);
            sharedLoader = loaded.loader();
            logger.debug("JDBC drivers: " + loaded.describe());
            return loaded;
        } catch (Exception error) {
            logger.warn("Could not prepare the bundled JDBC drivers: " + logger.describe(error));
            return new Loaded(0, 0, 0, null, List.of());
        }
    }

    /** Extracts nested jars into {@code libDirectory} and returns their paths. */
    public static List<Path> extractNestedJars(Path pluginJar, Path libDirectory, AstraLogger logger)
        throws IOException {
        List<Path> extracted = new ArrayList<>();
        int rewritten = 0;
        int reused = 0;
        try (JarFile jar = new JarFile(pluginJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(NESTED_ROOT + "/") || !name.endsWith(".jar")) {
                    continue;
                }
                Path target = targetFor(libDirectory, name).orElse(null);
                if (target == null) {
                    logger.warn("Ignoring nested jar with an unsafe name: " + name);
                    continue;
                }
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                String expected = sha256(bytes);
                if (Files.isRegularFile(target) && expected.equals(sha256(Files.readAllBytes(target)))) {
                    reused++;
                } else {
                    FileUtil.writeAtomic(target, bytes);
                    rewritten++;
                }
                extracted.add(target);
            }
        }
        if (rewritten > 0) {
            logger.debug("Extracted " + rewritten + " bundled driver jar(s) to " + libDirectory);
        }
        if (reused > 0 && rewritten == 0) {
            logger.debug("Reused " + reused + " already extracted driver jar(s) in " + libDirectory);
        }
        extracted.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return extracted;
    }

    /**
     * Builds one isolated class loader over {@code jars} and registers every JDBC
     * driver those jars declare.
     */
    public static Loaded installFrom(List<Path> jars, AstraLogger logger, ClassLoader parent) {
        List<URL> urls = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Path jar : jars) {
            try {
                urls.add(jar.toUri().toURL());
                names.add(jar.getFileName().toString());
            } catch (Exception error) {
                logger.warn("Skipping driver jar " + jar + ": " + logger.describe(error));
            }
        }
        if (urls.isEmpty()) return new Loaded(0, 0, 0, null, List.of());
        ClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), parent);
        int drivers = registerDrivers(loader, logger);
        return new Loaded(0, 0, drivers, loader, List.copyOf(names));
    }

    /** Registers every {@link Driver} found through the service loader. */
    public static int registerDrivers(ClassLoader loader, AstraLogger logger) {
        int count = 0;
        try {
            ServiceLoader<Driver> service = ServiceLoader.load(Driver.class, loader);
            for (Driver driver : service) {
                try {
                    DriverManager.registerDriver(new DriverShim(driver));
                    count++;
                    logger.debug("Registered JDBC driver " + driver.getClass().getName()
                        + " " + driver.getMajorVersion() + "." + driver.getMinorVersion());
                } catch (SQLException error) {
                    logger.warn("Could not register JDBC driver " + driver.getClass().getName()
                        + ": " + logger.describe(error));
                }
            }
        } catch (Throwable error) {
            // ServiceConfigurationError from a broken driver jar, or a security
            // manager refusing the lookup: report and carry on with what we have.
            logger.warn("JDBC driver discovery failed: " + logger.describe(error));
        }
        return count;
    }

    /** Loads {@code className} through the isolated loader, falling back to the plugin loader. */
    public static Class<?> loadDriverClass(String className, ClassLoader fallback) throws ClassNotFoundException {
        ClassLoader loader = sharedLoader;
        if (loader != null) {
            try {
                return Class.forName(className, true, loader);
            } catch (ClassNotFoundException ignored) {
                // Fall through to the caller's class loader.
            }
        }
        return Class.forName(className, true, fallback);
    }

    /** The plugin's own jar (or build output directory), or {@code null} when unknown. */
    private static Path ownLocation(ClassLoader source) {
        try {
            var codeSource = LibLoader.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) return null;
            return Path.of(codeSource.getLocation().toURI());
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * Maps a nested entry name onto its extraction target.
     *
     * <p>Returns empty for anything that is not a plain {@code astra/lib/<name>.jar}
     * (blank names, sub-directories, or names that would escape the library folder),
     * so a hostile or malformed jar entry can never write outside {@code lib/}.</p>
     */
    public static java.util.Optional<Path> targetFor(Path libDirectory, String entryName) {
        if (entryName == null) return java.util.Optional.empty();
        String normalized = entryName.replace('\\', '/');
        if (normalized.contains("..") || normalized.startsWith("/")) return java.util.Optional.empty();
        if (!normalized.startsWith(NESTED_ROOT + "/") || !normalized.endsWith(".jar")) {
            return java.util.Optional.empty();
        }
        String fileName = normalized.substring(NESTED_ROOT.length() + 1);
        // Exactly one path segment: nested folders would flatten onto the same
        // file name and silently overwrite each other.
        if (Strings.isBlank(fileName) || fileName.indexOf('/') >= 0) return java.util.Optional.empty();
        Path target = libDirectory.resolve(fileName).normalize();
        if (!target.startsWith(libDirectory.normalize())) return java.util.Optional.empty();
        return java.util.Optional.of(target);
    }

    /** Development fallback: nested jars laid out as real files under a build directory. */
    private static List<Path> scanDirectory(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        List<Path> found = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .sorted()
                .forEach(found::add);
        } catch (IOException error) {
            return List.of();
        }
        return found;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            // Every JDK ships SHA-256; without it, fall back to length+hashCode.
            return bytes.length + "-" + java.util.Arrays.hashCode(bytes);
        }
    }

    /**
     * Forwards to a driver that lives in an isolated class loader so
     * {@link DriverManager} will actually use it.
     */
    private static final class DriverShim implements Driver {

        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.sql.Connection connect(String url, java.util.Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info)
            throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            try {
                return delegate.getParentLogger();
            } catch (Exception error) {
                return java.util.logging.Logger.getLogger("io.astra.data");
            }
        }

        @Override
        public String toString() {
            return "AstraSyntaxDriverShim[" + delegate.getClass().getName() + "]";
        }
    }
}
