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
 * <p>Remote installation is deliberately not implemented: {@code security.yml} allows
 * disabling it and {@code packages.yml} defaults it to false, so the honest behaviour is to
 * say so rather than to download code from an unverified URL.</p>
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
