package io.astra.config;

import io.astra.config.AstraSettings.*;
import io.astra.logging.AstraLogger;
import io.astra.logging.LogLevel;
import io.astra.util.Durations;
import io.astra.util.FileUtil;
import io.astra.util.Strings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads, validates and exposes the nine configuration files that ship with AstraSyntax.
 *
 * <p>The file list, key names and semantics come straight from the supplied project
 * files - this class only reads them. Validation never aborts startup: impossible
 * values are reported as {@link ConfigIssue}s and replaced by the documented default,
 * which keeps a stray typo in {@code storage.yml} from taking the whole plugin down.</p>
 */
public final class ConfigManager {

    /** The authoritative configuration file set. */
    public static final List<String> FILES = List.of(
        "config.yml", "storage.yml", "performance.yml", "security.yml", "language.yml",
        "modules.yml", "packages.yml", "integrations.yml", "logging.yml");

    private static final List<String> STORAGE_TYPES = List.of("sqlite", "mysql", "mariadb", "file");

    private final ClassLoader loader;
    private final Path dataFolder;
    private final AstraLogger logger;
    private final ConfigUpdater updater;
    private final Map<String, ConfigFile> files = new LinkedHashMap<>();

    private Main main;
    private StorageSettings storageSettings;
    private Performance performance;
    private Security security;
    private Language language;
    private Modules modules;
    private Packages packages;
    private Integrations integrations;
    private Logging logging;

    private boolean installDefaultsOnLoad = true;

    public ConfigManager(ClassLoader loader, Path dataFolder, AstraLogger logger) {
        this.loader = loader;
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.updater = new ConfigUpdater(loader, dataFolder, logger);
    }

    /** Disable first-run default installation (used by tests). */
    public void setInstallDefaultsOnLoad(boolean value) {
        this.installDefaultsOnLoad = value;
    }

    /**
     * Install defaults (first run only), append newly shipped keys to existing files,
     * then read and validate everything.
     */
    public void load() {
        try {
            FileUtil.ensureDirectory(dataFolder);
        } catch (IOException e) {
            logger.error("Could not create the AstraSyntax data folder: " + e.getMessage());
        }
        for (String name : FILES) {
            try {
                if (installDefaultsOnLoad) updater.installDefault(name);
                updater.injectMissingKeys(name);
            } catch (IOException e) {
                logger.warn("Could not prepare " + name + ": " + e.getMessage());
            }
        }
        readAll();
    }

    /** Re-read every configuration file from disk (used by {@code /astra reload}). */
    public void reload() {
        readAll();
    }

    private void readAll() {
        files.clear();
        for (String name : FILES) {
            files.put(name, ConfigFile.load(name, dataFolder.resolve(name)));
        }
        for (String name : FILES) {
            ConfigFile file = files.get(name);
            if (!file.loadedFromDisk()) {
                logger.warn(name + " was not found on disk; built-in defaults are in use");
            }
            for (ConfigIssue issue : file.issues()) {
                log(issue);
            }
        }
        buildViews();
    }

    private void log(ConfigIssue issue) {
        String text = issue.describe();
        switch (issue.severity()) {
            case ERROR -> logger.error(text);
            case WARNING -> logger.warn(text);
            case MISSING -> logger.debug(text);
        }
    }

    /** Record hashes/versions of the managed files after a successful startup. */
    public void recordState(String pluginVersion) {
        try {
            updater.recordState(FILES, pluginVersion);
        } catch (IOException e) {
            logger.debug("Could not write configuration state: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------- views

    /** Parse every typed view from the freshly read files. */
    private void buildViews() {
        ConfigFile mainFile = file("config.yml");
        General general = new General(
            mainFile.getString("general.language", "en"),
            mainFile.getBoolean("general.check-updates", true),
            mainFile.getBoolean("general.metrics", true));
        Scripts scripts = new Scripts(
            mainFile.getString("scripts.folder", "scripts"),
            mainFile.getString("scripts.file-extension", ".ar"),
            mainFile.getBoolean("scripts.load-on-startup", true),
            mainFile.getBoolean("scripts.auto-reload", false),
            mainFile.getBoolean("scripts.continue-on-script-error", true));
        NaturalLanguage nl = new NaturalLanguage(
            mainFile.getBoolean("natural-language.enabled", true),
            mainFile.getBoolean("natural-language.allow-mixed-mode", true),
            mainFile.getBoolean("natural-language.strict-mode", true));
        Developer developer = new Developer(
            mainFile.getBoolean("developer.debug", false),
            mainFile.getBoolean("developer.detailed-errors", true),
            mainFile.getBoolean("developer.suggestions", true),
            mainFile.getBoolean("developer.profiler", false));
        Examples examples = new Examples(mainFile.getBoolean("examples.generate", true));
        Features features = new Features(
            mainFile.getBoolean("features.natural-language", true),
            mainFile.getBoolean("features.custom-items", true),
            mainFile.getBoolean("features.custom-mobs", true),
            mainFile.getBoolean("features.bosses", true),
            mainFile.getBoolean("features.gui", true),
            mainFile.getBoolean("features.quests", true),
            mainFile.getBoolean("features.economy", true),
            mainFile.getBoolean("features.regions", true),
            mainFile.getBoolean("features.npc", true),
            mainFile.getBoolean("features.holograms", true),
            mainFile.getBoolean("features.scoreboards", true),
            mainFile.getBoolean("features.bossbars", true),
            mainFile.getBoolean("features.placeholders", true),
            mainFile.getBoolean("features.recipes", true),
            mainFile.getBoolean("features.webhooks", false),
            mainFile.getBoolean("features.http", false),
            mainFile.getBoolean("features.cross-server", false));
        this.main = new Main(general, scripts, nl, developer, examples, features);

        ConfigFile storageFile = file("storage.yml");
        String type = storageFile.getChoice("storage.type", "sqlite", STORAGE_TYPES);
        if (type.equals("mariadb")) {
            logger.debug("storage.type 'mariadb' is handled through the MySQL-compatible driver");
        }
        Sqlite sqlite = new Sqlite(storageFile.getString("storage.sqlite.file", "data/astra.db"));
        Mysql mysql = new Mysql(
            storageFile.getBoolean("storage.mysql.enabled", false),
            storageFile.getString("storage.mysql.host", "localhost"),
            storageFile.getInt("storage.mysql.port", 3306, 1, 65535),
            storageFile.getString("storage.mysql.database", "astra"),
            storageFile.getString("storage.mysql.username", "root"),
            storageFile.getString("storage.mysql.password", ""),
            storageFile.getBoolean("storage.mysql.ssl", false));
        if (!type.equals("sqlite") && !type.equals("file") && !mysql.enabled()) {
            storageFile.addIssue(ConfigIssue.warning("storage.yml", "storage.mysql.enabled",
                "storage.type is '" + type + "' but the mysql section is disabled; falling back to sqlite"));
            type = "sqlite";
        }
        Storage storage = new Storage(type, sqlite, mysql);
        String rawInterval = storageFile.getString("autosave.interval", "5m");
        long intervalTicks = Durations.parseTicksOrDefault(rawInterval, Durations.TICKS_PER_MINUTE * 5L);
        if (intervalTicks < Durations.TICKS_PER_SECOND * 10L) {
            storageFile.addIssue(ConfigIssue.warning("storage.yml", "autosave.interval",
                "intervals below 10 seconds are ignored to protect performance; using 10s"));
            intervalTicks = Durations.TICKS_PER_SECOND * 10L;
        }
        Autosave autosave = new Autosave(storageFile.getBoolean("autosave.enabled", true), intervalTicks, rawInterval);
        Cache cache = new Cache(storageFile.getBoolean("cache.enabled", true),
            storageFile.getBoolean("cache.player-data", true));
        this.storageSettings = new StorageSettings(storage, autosave, cache);

        ConfigFile performanceFile = file("performance.yml");
        PerformanceScripts performanceScripts = new PerformanceScripts(
            performanceFile.getBoolean("scripts.compile-cache", true),
            performanceFile.getBoolean("scripts.parallel-loading", true));
        String slowRuleRaw = performanceFile.getString("events.slow-rule-threshold", "10ms");
        long slowRuleMicros = Durations.parseTicksOrDefault(slowRuleRaw, 0L) * 50L; // ticks -> micros (50ms per tick is not used; see below)
        slowRuleMicros = parseMicros(slowRuleRaw);
        Events events = new Events(performanceFile.getBoolean("events.warn-slow-rules", true), slowRuleMicros);
        Scheduler scheduler = new Scheduler(performanceFile.getBoolean("scheduler.async-safe-actions", true));
        ProfilerSettings profiler = new ProfilerSettings(
            performanceFile.getBoolean("profiler.enabled", false),
            performanceFile.getInt("profiler.history-size", 100, 10, 10000));
        Limits limits = new Limits(
            performanceFile.getInt("limits.max-loaded-scripts", 1000, 1, 100000),
            performanceFile.getInt("limits.max-scheduled-tasks", 10000, 1, 1000000));
        this.performance = new Performance(performanceScripts, events, scheduler, profiler, limits);

        ConfigFile securityFile = file("security.yml");
        SecurityScripts securityScripts = new SecurityScripts(
            securityFile.getBoolean("scripts.allow-console-commands", true),
            securityFile.getBoolean("scripts.allow-file-access", false),
            securityFile.getBoolean("scripts.allow-http-requests", false));
        List<String> httpDomains = normalizeDomains(securityFile.getStringList("http.allowed-domains", List.of()));
        Webhooks webhooks = new Webhooks(securityFile.getBoolean("webhooks.enabled", false),
            normalizeDomains(securityFile.getStringList("webhooks.allowed-domains", List.of())));
        SecurityPackages securityPackages = new SecurityPackages(
            securityFile.getBoolean("packages.allow-remote-install", false),
            securityFile.getBoolean("packages.require-signatures", false));
        SecurityModules securityModules = new SecurityModules(
            securityFile.getBoolean("modules.require-trusted-modules", true));
        SecurityLimits securityLimits = new SecurityLimits(
            securityFile.getInt("limits.max-loop-iterations", 10000, 1, 10_000_000),
            securityFile.getInt("limits.max-tasks-per-script", 1000, 1, 1_000_000),
            parseByteSize(securityFile.getString("limits.max-http-response-size", "2MB")));
        this.security = new Security(securityScripts, httpDomains, webhooks, securityPackages, securityModules, securityLimits);

        ConfigFile languageFile = file("language.yml");
        Map<String, String> messages = new LinkedHashMap<>();
        for (String key : languageFile.yaml().getKeys(false)) {
            if (key.equals("prefix")) continue;
            Object value = languageFile.yaml().get(key);
            if (value != null && !(value instanceof org.bukkit.configuration.ConfigurationSection)) {
                messages.put(key, String.valueOf(value));
            }
        }
        this.language = new Language(languageFile.getString("prefix", "Astra"), Collections.unmodifiableMap(messages));

        ConfigFile modulesFile = file("modules.yml");
        this.modules = new Modules(
            modulesFile.getBoolean("modules.auto-load", true),
            modulesFile.getString("modules.folder", "modules"),
            modulesFile.getBoolean("modules.check-compatibility", true),
            modulesFile.getBoolean("modules.isolate-failures", true),
            modulesFile.getBoolean("loading.continue-on-module-error", true));

        ConfigFile packagesFile = file("packages.yml");
        this.packages = new Packages(
            packagesFile.getString("packages.folder", "packages"),
            packagesFile.getBoolean("packages.auto-load", true),
            packagesFile.getBoolean("packages.resolve-dependencies", true),
            packagesFile.getBoolean("packages.allow-remote-install", false),
            packagesFile.getBoolean("packages.check-updates", true),
            packagesFile.getBoolean("dependencies.fail-on-missing-required", true),
            packagesFile.getBoolean("dependencies.warn-on-missing-optional", true));
        if (this.packages.allowRemoteInstall() && this.security.packages().allowRemoteInstall()
            && this.security.packages().requireSignatures()) {
            logger.warn("packages.allow-remote-install is enabled but security.packages.require-signatures is true; "
                + "unsigned remote packages will be rejected");
        }

        ConfigFile integrationsFile = file("integrations.yml");
        this.integrations = new Integrations(
            toggle(integrationsFile, "vault"),
            toggle(integrationsFile, "placeholderapi"),
            toggle(integrationsFile, "citizens"),
            toggle(integrationsFile, "worldguard"),
            new Redis(
                integrationsFile.getBoolean("redis.enabled", false),
                integrationsFile.getString("redis.host", "localhost"),
                integrationsFile.getInt("redis.port", 6379, 1, 65535),
                integrationsFile.getString("redis.password", ""),
                integrationsFile.getInt("redis.database", 0, 0, 15)));

        ConfigFile loggingFile = file("logging.yml");
        this.logging = new Logging(
            LogLevel.parse(loggingFile.getString("logging.level", "INFO")),
            loggingFile.getBoolean("logging.console", true),
            loggingFile.getBoolean("logging.file", true),
            loggingFile.getString("logging.file-path", "logs/astra.log"),
            loggingFile.getBoolean("errors.separate-file", true),
            loggingFile.getString("errors.file-path", "logs/errors.log"),
            loggingFile.getBoolean("debug.include-stack-traces", false),
            loggingFile.getBoolean("debug.include-script-source", true));
    }

    private static IntegrationToggle toggle(ConfigFile file, String key) {
        String raw = file.getString(key + ".enabled", "auto");
        boolean enabled = !"false".equalsIgnoreCase(raw.trim());
        return new IntegrationToggle(raw.trim(), enabled);
    }

    private static List<String> normalizeDomains(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String entry : raw) {
            if (Strings.isBlank(entry)) continue;
            out.add(entry.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    /** Parse {@code 10ms}/{@code 250us}/{@code 2s} into microseconds. */
    public static long parseMicros(String raw) {
        if (Strings.isBlank(raw)) return 10_000L;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("us") || text.endsWith("\u00b5s")) {
                return Long.parseLong(text.substring(0, text.length() - 2).trim());
            }
            if (text.endsWith("ms")) {
                return Math.round(Double.parseDouble(text.substring(0, text.length() - 2).trim()) * 1000.0);
            }
            if (text.endsWith("s")) {
                return Math.round(Double.parseDouble(text.substring(0, text.length() - 1).trim()) * 1_000_000.0);
            }
            long ticks = Durations.parseTicks(text);
            return ticks * 50_000L;
        } catch (RuntimeException e) {
            return 10_000L;
        }
    }

    /** Parse {@code 2MB}/{@code 512KB}/{@code 4096} into bytes. */
    public static long parseByteSize(String raw) {
        if (Strings.isBlank(raw)) return 2L * 1024 * 1024;
        String text = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        try {
            if (text.endsWith("gb")) return Math.round(Double.parseDouble(text.substring(0, text.length() - 2)) * 1024 * 1024 * 1024);
            if (text.endsWith("mb")) return Math.round(Double.parseDouble(text.substring(0, text.length() - 2)) * 1024 * 1024);
            if (text.endsWith("kb")) return Math.round(Double.parseDouble(text.substring(0, text.length() - 2)) * 1024);
            if (text.endsWith("b")) return Math.round(Double.parseDouble(text.substring(0, text.length() - 1)));
            return Long.parseLong(text);
        } catch (RuntimeException e) {
            return 2L * 1024 * 1024;
        }
    }

    // ---------------------------------------------------------------- accessors

    public ConfigFile file(String name) {
        ConfigFile file = files.get(name);
        if (file == null) throw new IllegalArgumentException("Unknown configuration file: " + name);
        return file;
    }

    public Path dataFolder() {
        return dataFolder;
    }

    public Main main() {
        return main;
    }

    public StorageSettings storage() {
        return storageSettings;
    }

    public Performance performance() {
        return performance;
    }

    public Security security() {
        return security;
    }

    public Language language() {
        return language;
    }

    public Modules modules() {
        return modules;
    }

    public Packages packages() {
        return packages;
    }

    public Integrations integrations() {
        return integrations;
    }

    public Logging logging() {
        return logging;
    }

    /** Every issue recorded while reading all files. */
    public List<ConfigIssue> issues() {
        List<ConfigIssue> out = new ArrayList<>();
        for (ConfigFile file : files.values()) out.addAll(file.issues());
        return out;
    }

    /** Resolve a data-folder relative path from config (never escapes the folder). */
    public Path resolve(String relative) {
        try {
            return FileUtil.resolveSafely(dataFolder, relative);
        } catch (IOException e) {
            logger.warn("Unsafe path in configuration ('" + relative + "'), using the data folder instead");
            return dataFolder;
        }
    }

    /** True when the on-disk file differs from the shipped default. */
    public boolean isUserModified(String name) {
        return updater.isUserModified(name);
    }

    /** True when a bundled default exists for the given file name. */
    public boolean hasBundledDefault(String name) {
        return loader.getResource(name) != null || Files.exists(Path.of("src", "main", "resources", name));
    }
}
