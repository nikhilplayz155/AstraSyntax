package io.astra.module;

import io.astra.config.ConfigManager;
import io.astra.language.parser.Vocabulary;
import io.astra.logging.AstraLogger;
import io.astra.runtime.Registries;
import io.astra.runtime.RuntimeServices;
import io.astra.security.SecurityGateImpl;
import io.astra.util.FileUtil;
import io.astra.util.Strings;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads compiled module jars from {@code plugins/AstraSyntax/modules}.
 *
 * <p>Each module directory (or jar) carries a {@code module.yml} manifest:</p>
 *
 * <pre>
 * name: ExampleModule
 * version: 1.0.0
 * main: com.example.ExampleModule   # implements io.astra.module.AstraModule
 * api-version: 1.21
 * </pre>
 *
 * <p>Failure isolation is the point of the loader: a module that throws during
 * {@code onLoad} is unloaded and its class loader closed, while the server keeps running
 * and the remaining modules still load. {@code security.yml} is consulted first, so an
 * untrusted module is refused before any of its code is executed.</p>
 */
public final class ModuleManager {

    /** One loaded module. */
    public record LoadedModule(String name, String version, Path source, AstraModule instance,
                              URLClassLoader classLoader) { }

    private final org.bukkit.plugin.Plugin plugin;
    private final ConfigManager config;
    private final AstraLogger logger;
    private final SecurityGateImpl security;
    private final Registries registries;
    private final Vocabulary vocabulary;
    private final Map<String, LoadedModule> modules = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    public ModuleManager(org.bukkit.plugin.Plugin plugin, ConfigManager config, AstraLogger logger,
                         SecurityGateImpl security, Registries registries, Vocabulary vocabulary) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.security = security;
        this.registries = registries;
        this.vocabulary = vocabulary;
    }

    /** The module folder ({@code modules.folder} in modules.yml). */
    public Path folder() {
        return config.resolve(config.modules().folder());
    }

    /** Load every module found in the module folder. */
    public List<String> loadAll() {
        Path folder = folder();
        try {
            FileUtil.ensureDirectory(folder);
        } catch (IOException e) {
            logger.error("Could not create the modules folder: " + e.getMessage());
            return List.of();
        }
        List<String> loadedNames = new ArrayList<>();
        for (Path candidate : candidates(folder)) {
            try {
                String name = load(candidate);
                if (name != null) loadedNames.add(name);
            } catch (Throwable error) {
                String message = candidate.getFileName() + ": " + logger.describe(error);
                failures.add(message);
                if (config.modules().continueOnModuleError()) {
                    logger.warn("Module " + message);
                } else {
                    logger.error("Module " + message);
                }
            }
        }
        if (!loadedNames.isEmpty()) {
            logger.info("Loaded " + loadedNames.size() + " module(s): " + String.join(", ", loadedNames));
        }
        return loadedNames;
    }

    private List<Path> candidates(Path folder) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (var stream = Files.list(folder)) {
            stream.sorted().forEach(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".jar") || name.endsWith(".disabled")) {
                    if (name.endsWith(".jar")) out.add(path);
                    return;
                }
                // A module can also be a folder containing module.yml plus its own libs.
                if (Files.isDirectory(path) && Files.exists(path.resolve("module.yml"))) {
                    out.add(path);
                }
            });
        } catch (IOException e) {
            logger.warn("Could not list the modules folder: " + e.getMessage());
        }
        return out;
    }

    /** Load one module jar/folder; returns its name, or {@code null} when it is skipped. */
    public String load(Path candidate) throws Exception {
        Path manifest = Files.isDirectory(candidate) ? candidate.resolve("module.yml") : candidate;
        Manifest parsed = readManifest(candidate);
        if (parsed == null) {
            logger.warn("Skipping " + candidate.getFileName() + ": no module.yml manifest found next to it");
            return null;
        }
        if (modules.containsKey(parsed.name())) {
            logger.debug("Module " + parsed.name() + " is already loaded");
            return null;
        }
        security.checkModuleLoad(parsed.name(), candidate.toAbsolutePath().toString());
        if (config.modules().checkCompatibility() && !parsed.compatibleWith(plugin.getDescription().getVersion())) {
            logger.warn("Module " + parsed.name() + " targets AstraSyntax " + parsed.apiVersion()
                + " but this server runs " + plugin.getDescription().getVersion());
        }

        URL[] urls = Files.isDirectory(candidate)
            ? new URL[] {candidate.toUri().toURL()}
            : new URL[] {candidate.toUri().toURL()};
        URLClassLoader loader = new URLClassLoader(urls, plugin.getClass().getClassLoader());
        try {
            Class<?> mainClass = Class.forName(parsed.main(), true, loader);
            if (!AstraModule.class.isAssignableFrom(mainClass)) {
                throw new IllegalStateException("The main class does not implement io.astra.module.AstraModule");
            }
            AstraModule instance = (AstraModule) mainClass.getDeclaredConstructor().newInstance();
            Path dataFolder = folder().resolve(parsed.name());
            FileUtil.ensureDirectory(dataFolder);
            instance.onLoad(new Context(parsed.name(), dataFolder));
            modules.put(parsed.name(), new LoadedModule(parsed.name(), parsed.version(), candidate, instance, loader));
            logger.debug("Module '" + parsed.name() + "' " + parsed.version() + " loaded");
            return parsed.name();
        } catch (Throwable error) {
            if (!config.modules().isolateFailures()) throw error;
            try {
                loader.close();
            } catch (IOException ignored) {
                // nothing else to do
            }
            throw error;
        }
    }

    /** Unload one module, calling its hook and closing its class loader. */
    public boolean unload(String name) {
        LoadedModule module = modules.remove(name);
        if (module == null) return false;
        try {
            module.instance().onUnload();
        } catch (Throwable error) {
            logger.warn("Module " + name + " failed while unloading: " + logger.describe(error));
        }
        try {
            module.classLoader().close();
        } catch (IOException ignored) {
            // The JVM keeps file handles only until the server stops.
        }
        return true;
    }

    /** Read a manifest sitting next to (or inside) a module candidate. */
    private Manifest readManifest(Path candidate) {
        Path direct = Files.isDirectory(candidate) ? candidate.resolve("module.yml")
            : candidate.resolveSibling(FileUtil.baseName(candidate) + ".yml");
        try {
            if (Files.exists(direct)) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(direct.toFile());
                String name = yaml.getString("name", FileUtil.baseName(candidate));
                String main = yaml.getString("main", "");
                if (Strings.isBlank(main)) return null;
                return new Manifest(name, yaml.getString("version", "1.0.0"), main,
                    yaml.getString("api-version", ""));
            }
            // Also allow the manifest inside the jar.
            if (Files.isRegularFile(candidate)) {
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(candidate.toFile())) {
                    var entry = jar.getEntry("module.yml");
                    if (entry == null) return null;
                    try (var in = jar.getInputStream(entry)) {
                        YamlConfiguration yaml = new YamlConfiguration();
                        yaml.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                        String main = yaml.getString("main", "");
                        if (Strings.isBlank(main)) return null;
                        return new Manifest(yaml.getString("name", FileUtil.baseName(candidate)),
                            yaml.getString("version", "1.0.0"), main, yaml.getString("api-version", ""));
                    }
                }
            }
        } catch (Exception error) {
            logger.debug("Could not read the manifest of " + candidate.getFileName() + ": " + error.getMessage());
        }
        return null;
    }

    /** A parsed module.yml. */
    private record Manifest(String name, String version, String main, String apiVersion) {

        boolean compatibleWith(String pluginVersion) {
            if (apiVersion == null || apiVersion.isBlank()) return true;
            // "1.21" is compatible with any 1.21.x server; anything else is reported.
            return pluginVersion != null && pluginVersion.startsWith(apiVersion.trim());
        }
    }

    /** Names of the loaded modules. */
    public List<String> names() {
        return List.copyOf(modules.keySet());
    }

    /** Human readable state of every module, including the ones that failed. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (LoadedModule module : modules.values()) {
            lines.add(module.name() + " " + module.version() + " (" + module.source().getFileName() + ")");
        }
        lines.addAll(failures);
        return lines;
    }

    /** One-line summary for the startup log and {@code /astra info}. */
    public String summary() {
        if (modules.isEmpty() && failures.isEmpty()) return "none";
        return modules.size() + " loaded" + (failures.isEmpty() ? "" : ", " + failures.size() + " failed");
    }

    /** Unload everything (shutdown). */
    public void shutdown() {
        for (String name : List.copyOf(modules.keySet())) {
            unload(name);
        }
    }

    /** The per-module context handed to {@link AstraModule#onLoad}. */
    private final class Context implements AstraModule.AstraModuleContext {

        private final String name;
        private final Path dataFolder;
        private final AstraLogger moduleLogger;

        private Context(String name, Path dataFolder) {
            this.name = name;
            this.dataFolder = dataFolder;
            this.moduleLogger = new AstraLogger(message -> logger.info("[" + name + "] " + message));
            this.moduleLogger.configure(logger.level(), true, false, null, false, null,
                logger.includeStackTraces(), logger.includeScriptSource());
        }

        @Override public RuntimeServices services() {
            return (RuntimeServices) plugin;
        }

        @Override public Registries registries() {
            return registries;
        }

        @Override public Vocabulary vocabulary() {
            return vocabulary;
        }

        @Override public Path dataFolder() {
            return dataFolder;
        }

        @Override public AstraLogger logger() {
            return moduleLogger;
        }
    }
}
