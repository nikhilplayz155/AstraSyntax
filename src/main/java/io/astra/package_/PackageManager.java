package io.astra.package_;

import io.astra.config.ConfigManager;
import io.astra.logging.AstraLogger;
import io.astra.runtime.script.ScriptManager;
import io.astra.security.SecurityGateImpl;
import io.astra.util.FileUtil;
import io.astra.util.Hash;
import io.astra.util.Strings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads script packages from {@code packages/}.
 *
 * <p>A package is a folder with a {@code package.yml} manifest and one or more
 * {@code .ar} scripts:</p>
 *
 * <pre>
 * packages/essentials-like/
 *   package.yml          # name, version, author, description, dependencies
 *   scripts/kit.ar
 *   scripts/spawn.ar
 * </pre>
 *
 * <p>Dependencies are resolved by package name; a missing required dependency stops the
 * package (when {@code dependencies.fail-on-missing-required} is true) while a missing
 * optional one is a warning. Package scripts are loaded through the same
 * {@link ScriptManager} as ordinary scripts, so they get the same compile cache, the same
 * diagnostics and the same leak-free unload - a package is a way to distribute scripts,
 * never a second engine.</p>
 *
 * <p>Remote installation is implemented and gated twice, because it downloads code: both
 * {@code packages.allow-remote-install} in {@code packages.yml} and
 * {@code security.packages.allow-remote-install} in {@code security.yml} must be true, the
 * URL must pass the security gate, the download is size-capped, and
 * {@code security.packages.require-signatures} demands a {@code sha256} that the archive has
 * to match. Archives are extracted with a zip-slip guard, so a hostile package cannot write
 * outside its own folder.</p>
 */
public final class PackageManager {

    /** One package that is currently loaded. */
    public record LoadedPackage(String name, String version, String author, String description,
                                List<String> dependencies, Path folder, List<String> scripts) { }

    private final org.bukkit.plugin.Plugin plugin;
    private final ConfigManager config;
    private final AstraLogger logger;
    private final ScriptManager scripts;
    private final SecurityGateImpl security;
    private final Map<String, LoadedPackage> loaded = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    public PackageManager(org.bukkit.plugin.Plugin plugin, ConfigManager config, AstraLogger logger,
                          ScriptManager scripts, SecurityGateImpl security) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.scripts = scripts;
        this.security = security;
    }

    /** What happened during an install, for the command output. */
    public record InstallResult(boolean ok, String name, String message) {
    }

    /**
     * Installs a package from a URL or a local archive.
     *
     * <p>Runs the whole download and extraction on the calling thread, which the admin
     * command keeps off the server thread; only the final load is scheduled back onto it.
     * Every failure returns a reason instead of throwing, because "it did not install" is
     * normal operation and deserves a message, not a stack trace.</p>
     *
     * @param source   an {@code https://} URL, or a path to a local {@code .zip}/{@code .ar}
     * @param sha256   the expected SHA-256 of the archive, or {@code null}
     * @param http     the HTTP service, or {@code null} when only local archives may be used
     */
    public InstallResult install(String source, String sha256, io.astra.runtime.net.HttpService http,
                                 io.astra.runtime.ExecContext context) {
        if (source == null || source.isBlank()) return new InstallResult(false, "", "No package source given");
        boolean remote = source.startsWith("http://") || source.startsWith("https://");
        if (remote && !config.packages().allowRemoteInstall()) {
            return new InstallResult(false, "", "Remote installation is off: set packages.allow-remote-install to"
                + " true in packages.yml");
        }
        if (remote && !security.policy().allowRemoteInstall()) {
            return new InstallResult(false, "", "Remote installation is off: set packages.allow-remote-install to"
                + " true in security.yml");
        }
        if (remote && security.policy().requireSignatures() && (sha256 == null || sha256.isBlank())) {
            return new InstallResult(false, "", "security.packages.require-signatures is on, so the expected"
                + " sha256 of the archive is required: /astra package install <url> <sha256>");
        }

        Path staging = null;
        try {
            Path archive;
            String origin = "local file";
            if (remote) {
                if (http == null) {
                    return new InstallResult(false, "", "HTTP is not available, so nothing can be downloaded");
                }
                staging = java.nio.file.Files.createTempDirectory("astra-package-");
                archive = staging.resolve("package.zip");
                var response = http.download(source, archive, context);
                if (!response.ok()) {
                    return new InstallResult(false, "", "Download failed: " + response.error());
                }
                origin = io.astra.runtime.net.HttpService.redact(source);
            } else {
                archive = config.resolve(source);
                if (!java.nio.file.Files.isRegularFile(archive)) {
                    return new InstallResult(false, "", "No archive at " + source
                        + " (give a path inside the plugin data folder, or an https URL)");
                }
            }

            if (sha256 != null && !sha256.isBlank()) {
                String actual = io.astra.util.Hash.sha256(archive);
                if (!actual.equalsIgnoreCase(sha256.trim())) {
                    return new InstallResult(false, "", "Checksum mismatch: expected " + sha256.trim()
                        + " but the archive is " + actual);
                }
            }

            Path extracted = extract(archive, staging);
            if (extracted == null) return new InstallResult(false, "", "The archive is not a readable zip file");
            Path manifestFile = findManifest(extracted);
            if (manifestFile == null) {
                return new InstallResult(false, "", "The archive has no package.yml");
            }
            Manifest manifest = readManifest(manifestFile);
            if (manifest == null || manifest.name().isBlank()) {
                return new InstallResult(false, "", "package.yml has no name");
            }
            Path destination = folder().resolve(safeName(manifest.name()));
            if (java.nio.file.Files.exists(destination)) {
                return new InstallResult(false, manifest.name(),
                    "Package '" + manifest.name() + "' is already installed in " + destination.getFileName());
            }
            Path root = manifestFile.getParent();
            java.nio.file.Files.createDirectories(folder());
            copyTree(root, destination);
            logger.info("Installed package '" + manifest.name() + "' from " + origin);

            if (!load(manifest.withFolder(destination))) {
                return new InstallResult(false, manifest.name(),
                    "Package '" + manifest.name() + "' was installed but did not load; see the log");
            }
            LoadedPackage loadedPackage = loaded.get(manifest.name().toLowerCase(java.util.Locale.ROOT));
            int count = loadedPackage == null ? 0 : loadedPackage.scripts().size();
            return new InstallResult(true, manifest.name(),
                "Installed and loaded '" + manifest.name() + "' (" + count + " script(s))");
        } catch (Exception error) {
            logger.warn("Package install failed: " + logger.describe(error));
            return new InstallResult(false, "", "Install failed: " + error.getClass().getSimpleName());
        } finally {
            if (staging != null) deleteTree(staging);
        }
    }

    /** Extracts an archive into a fresh folder next to it and returns that folder. */
    static Path extract(Path archive, Path staging) throws java.io.IOException {
        Path root = (staging == null ? archive.getParent() : staging).resolve("contents");
        java.nio.file.Files.createDirectories(root);
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
            java.nio.file.Files.newInputStream(archive))) {
            java.nio.file.Path canonicalRoot = root.toRealPath();
            java.util.zip.ZipEntry entry;
            boolean any = false;
            while ((entry = zip.getNextEntry()) != null) {
                any = true;
                Path target = root.resolve(entry.getName()).normalize();
                // A zip entry may claim "../../server.properties": refuse anything that
                // would land outside the package folder.
                if (!target.startsWith(root) || !target.toAbsolutePath().startsWith(canonicalRoot.getParent())) {
                    throw new java.io.IOException("archive entry escapes the package folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    java.nio.file.Files.createDirectories(target);
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) java.nio.file.Files.createDirectories(parent);
                java.nio.file.Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (!any) return null;
        } catch (java.util.zip.ZipException notAZip) {
            return null;
        }
        return root;
    }

    /** The package.yml inside an extracted archive, at any depth (a zip often has one root). */
    private Path findManifest(Path root) throws java.io.IOException {
        Path direct = root.resolve("package.yml");
        if (java.nio.file.Files.isRegularFile(direct)) return direct;
        try (var stream = java.nio.file.Files.walk(root, 3)) {
            return stream.filter(path -> path.getFileName().toString().equals("package.yml"))
                .filter(java.nio.file.Files::isRegularFile)
                .findFirst().orElse(null);
        }
    }

    /** Reads a package.yml without going through the script loader. */
    private Manifest readManifest(Path file) {
        try {
            var yaml = new org.bukkit.configuration.file.YamlConfiguration();
            yaml.loadFromString(java.nio.file.Files.readString(file));
            return new Manifest(yaml.getString("name", ""), yaml.getString("version", "1.0"),
                yaml.getString("author", "unknown"), yaml.getString("description", ""),
                yaml.getStringList("dependencies"), yaml.getStringList("optional-dependencies"),
                file.getParent());
        } catch (Exception error) {
            logger.warn("Could not read " + file + ": " + logger.describe(error));
            return null;
        }
    }

    /** Copies an extracted package into its final folder. */
    private void copyTree(Path from, Path to) throws java.io.IOException {
        try (var stream = java.nio.file.Files.walk(from)) {
            for (Path path : stream.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (java.nio.file.Files.isDirectory(path)) {
                    java.nio.file.Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) java.nio.file.Files.createDirectories(parent);
                    java.nio.file.Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) {
        try (var stream = java.nio.file.Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        } catch (java.io.IOException ignored) {
            // A leftover temporary folder is harmless.
        }
    }

    /**
     * Package names become folder names, so they may not contain separators, and leading
     * dots are removed so that a package called {@code ..} cannot point at its parent.
     */
    static String safeName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        return cleaned.isBlank() ? "package" : cleaned;
    }

    /** The packages folder ({@code packages.folder} in packages.yml). */
    public Path folder() {
        return config.resolve(config.packages().folder());
    }

    /** Load every package in the folder, resolving dependencies as far as possible. */
    public List<String> loadAll() {
        Path folder = folder();
        try {
            FileUtil.ensureDirectory(folder);
        } catch (IOException e) {
            logger.error("Could not create the packages folder: " + e.getMessage());
            return List.of();
        }
        Map<String, Manifest> manifests = readManifests(folder);
        List<String> order = resolveOrder(manifests);
        List<String> names = new ArrayList<>();
        for (String name : order) {
            Manifest manifest = manifests.get(name);
            if (manifest == null) continue;
            try {
                if (load(manifest)) names.add(name);
            } catch (Throwable error) {
                String message = name + ": " + logger.describe(error);
                failures.add(message);
                logger.warn("Package " + message);
            }
        }
        if (!names.isEmpty()) {
            logger.info("Loaded " + names.size() + " package(s): " + String.join(", ", names));
        }
        return names;
    }

    /**
     * Loads one installed package by its folder name (or its manifest name).
     *
     * @return true when the package compiled and its scripts started
     */
    public boolean load(String name) {
        if (Strings.isBlank(name)) return false;
        Path candidate = folder().resolve(safeName(name));
        if (!Files.isDirectory(candidate)) candidate = folder().resolve(name);
        Path manifestFile = candidate.resolve("package.yml");
        if (!Files.isRegularFile(manifestFile)) return false;
        Manifest manifest = readManifest(manifestFile);
        return manifest != null && load(manifest.withFolder(candidate));
    }

    private Map<String, Manifest> readManifests(Path folder) {
        Map<String, Manifest> manifests = new LinkedHashMap<>();
        if (!Files.isDirectory(folder)) return manifests;
        try (var stream = Files.list(folder)) {
            for (Path candidate : stream.sorted().toList()) {
                if (!Files.isDirectory(candidate)) continue;
                Path manifestFile = candidate.resolve("package.yml");
                if (!Files.exists(manifestFile)) continue;
                try {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(manifestFile.toFile());
                    String name = yaml.getString("name", candidate.getFileName().toString());
                    Manifest manifest = new Manifest(name,
                        yaml.getString("version", "1.0.0"),
                        yaml.getString("author", ""),
                        yaml.getString("description", ""),
                        yaml.getStringList("dependencies"),
                        yaml.getStringList("optional-dependencies"),
                        candidate);
                    manifests.put(name.toLowerCase(Locale.ROOT), manifest);
                } catch (Exception error) {
                    failures.add(candidate.getFileName() + ": unreadable package.yml (" + error.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            logger.warn("Could not list the packages folder: " + e.getMessage());
        }
        return manifests;
    }

    /** Order packages so that dependencies come first. */
    private List<String> resolveOrder(Map<String, Manifest> manifests) {
        List<String> order = new ArrayList<>();
        if (!config.packages().resolveDependencies()) {
            order.addAll(manifests.keySet());
            return order;
        }
        List<String> visiting = new ArrayList<>();
        for (String name : manifests.keySet()) {
            visit(name, manifests, order, visiting);
        }
        return order;
    }

    private void visit(String name, Map<String, Manifest> manifests, List<String> order, List<String> visiting) {
        if (order.contains(name) || visiting.contains(name)) return;
        visiting.add(name);
        Manifest manifest = manifests.get(name);
        if (manifest != null) {
            for (String dependency : manifest.dependencies()) {
                String key = dependency.toLowerCase(Locale.ROOT);
                if (manifests.containsKey(key)) {
                    visit(key, manifests, order, visiting);
                } else if (config.packages().failOnMissingRequired()) {
                    throw new IllegalStateException("package '" + name + "' requires '" + dependency + "', "
                        + "which is not installed");
                }
            }
        }
        visiting.remove(name);
        order.add(name);
    }

    /** Load one package's scripts. */
    public boolean load(Manifest manifest) {
        if (loaded.containsKey(manifest.name().toLowerCase(Locale.ROOT))) return false;

        for (String dependency : manifest.dependencies()) {
            if (!loaded.containsKey(dependency.toLowerCase(Locale.ROOT))) {
                if (config.packages().failOnMissingRequired()) {
                    failures.add(manifest.name() + ": missing required dependency '" + dependency + "'");
                    logger.warn("Package '" + manifest.name() + "' was not loaded: it needs '" + dependency + "'");
                    return false;
                }
            }
        }
        for (String optional : manifest.optionalDependencies()) {
            if (!loaded.containsKey(optional.toLowerCase(Locale.ROOT)) && config.packages().warnOnMissingOptional()) {
                logger.debug("Package '" + manifest.name() + "' is missing the optional '" + optional + "'");
            }
        }

        if (config.packages().allowRemoteInstall() && !security.policy().allowRemoteInstall()) {
            logger.warn("packages.allow-remote-install is on but security.packages.allow-remote-install is off; "
                + "only local packages are loaded");
        }

        List<Path> scriptFiles = localScripts(manifest.folder());
        List<String> names = new ArrayList<>();
        for (Path file : scriptFiles) {
            ScriptManager.LoadResult result = scripts.loadExternal(file, manifest.name());
            if (result.failed()) {
                failures.add(manifest.name() + "/" + file.getFileName() + ": " + result.message());
                logger.warn("Package script " + file.getFileName() + " of '" + manifest.name() + "' did not compile");
                for (var diagnostic : result.diagnostics()) {
                    logger.warn("  " + diagnostic.position() + ": " + diagnostic.message());
                }
                if (config.packages().failOnMissingRequired()) {
                    // A package that does not compile is not considered loaded, so its
                    // dependants wait for a fixed version instead of failing at runtime.
                    unloadScripts(names);
                    return false;
                }
                continue;
            }
            names.add(result.name());
        }
        loaded.put(manifest.name().toLowerCase(Locale.ROOT), new LoadedPackage(manifest.name(), manifest.version(),
            manifest.author(), manifest.description(), manifest.dependencies(), manifest.folder(), names));
        logger.debug("Package '" + manifest.name() + "' loaded " + names.size() + " script(s)");
        return true;
    }

    /** Every {@code .ar} file a package ships, including a nested {@code scripts} folder. */
    private List<Path> localScripts(Path packageFolder) {
        List<Path> files = new ArrayList<>();
        Path scriptsFolder = packageFolder.resolve("scripts");
        Path root = Files.isDirectory(scriptsFolder) ? scriptsFolder : packageFolder;
        String extension = config.main().scripts().fileExtension();
        files.addAll(FileUtil.listFilesRecursive(root, Strings.isBlank(extension) ? ".ar" : extension));
        files.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    /** Unload one package and every script it contributed. */
    public boolean unload(String name) {
        LoadedPackage removed = loaded.remove(name.toLowerCase(Locale.ROOT));
        if (removed == null) return false;
        unloadScripts(removed.scripts());
        // The package folder stays untouched: user files are never deleted by a reload.
        logger.info("Unloaded package '" + removed.name() + "'");
        return true;
    }

    private void unloadScripts(List<String> names) {
        for (String script : names) {
            scripts.unload(script);
            scripts.forgetExternal(script);
        }
    }

    /** Loaded package names. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (LoadedPackage pkg : loaded.values()) out.add(pkg.name());
        return out;
    }

    /** One line per package, for {@code /astra info} and the startup log. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (LoadedPackage pkg : loaded.values()) {
            lines.add(pkg.name() + " " + pkg.version()
                + (Strings.isBlank(pkg.author()) ? "" : " by " + pkg.author())
                + " [" + pkg.scripts().size() + " script(s)]");
        }
        lines.addAll(failures);
        return lines;
    }

    /** Summary used by the startup log. */
    public String summary() {
        if (loaded.isEmpty() && failures.isEmpty()) return "none";
        return loaded.size() + " loaded" + (failures.isEmpty() ? "" : ", " + failures.size() + " failed");
    }

    /** Packages currently loaded (used by tests and diagnostics). */
    public List<LoadedPackage> loaded() {
        return List.copyOf(loaded.values());
    }

    /** Unload every package (shutdown). */
    public void shutdown() {
        for (String name : List.copyOf(loaded.keySet())) {
            unload(name);
        }
    }

    /** A parsed package.yml. */
    public record Manifest(String name, String version, String author, String description,
                           List<String> dependencies, List<String> optionalDependencies, Path folder) {

        public Manifest {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            optionalDependencies = optionalDependencies == null ? List.of() : List.copyOf(optionalDependencies);
        }

        /** The same manifest pointing at the folder the package actually lives in. */
        public Manifest withFolder(Path newFolder) {
            return new Manifest(name, version, author, description, dependencies, optionalDependencies, newFolder);
        }

        /** A stable identity for update checks: name + version. */
        public String identity() {
            return name + "@" + version;
        }

        /** A short fingerprint of the manifest, used by {@code check-updates} bookkeeping. */
        public String fingerprint() {
            return Hash.shortHash(identity() + "|" + folder.getFileName());
        }
    }
}
