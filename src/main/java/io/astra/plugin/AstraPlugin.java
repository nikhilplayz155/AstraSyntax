package io.astra.plugin;

import io.astra.command.AstraCommand;
import io.astra.config.ConfigManager;
import io.astra.data.DataStoreImpl;
import io.astra.data.LibLoader;
import io.astra.data.FileStorage;
import io.astra.data.SqlStorage;
import io.astra.data.Storage;
import io.astra.integration.IntegrationManager;
import io.astra.logging.AstraLogger;
import io.astra.platform.PlatformDetector;
import io.astra.platform.SchedulerService;
import io.astra.platform.Schedulers;
import io.astra.platform.ServerPlatform;
import io.astra.platform.TextService;
import io.astra.profiler.Profiler;
import io.astra.profiler.SimpleProfiler;
import io.astra.runtime.Registries;
import io.astra.runtime.RuntimeServices;
import io.astra.runtime.builtin.MaterialTable;
import io.astra.runtime.event.EventBus;
import io.astra.runtime.expression.PlaceholderService;
import io.astra.runtime.scheduler.TaskRegistry;
import io.astra.runtime.script.DynamicCommands;
import io.astra.runtime.script.RuleExecutor;
import io.astra.runtime.script.ScriptManager;
import io.astra.runtime.vocab.BuiltinVocabulary;
import io.astra.security.SecurityGate;
import io.astra.security.SecurityGateImpl;
import io.astra.util.FileUtil;
import io.astra.util.Strings;

import java.nio.file.Path;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The AstraSyntax plugin entry point and the runtime's service hub.
 *
 * <p>Startup order is deliberate, because everything after it depends on the previous
 * step:</p>
 *
 * <ol>
 *   <li>configuration is read and validated (the supplied nine files, never rewritten);</li>
 *   <li>the scheduler backend is chosen from real capability detection, so Folia support is
 *       a decision made at runtime rather than a claim in a description;</li>
 *   <li>storage is opened and migrated before any script can write data;</li>
 *   <li>registries and the vocabulary are built, which is what teaches the parser what
 *       actions exist;</li>
 *   <li>scripts are compiled and activated, after which listeners, commands and timers
 *       exist for exactly the triggers they use;</li>
 *   <li>modules, packages and integrations extend the vocabulary, then the admin command is
 *       registered.</li>
 * </ol>
 *
 * <p>Shutdown is the exact reverse: scripts first (so nothing schedules new work), then the
 * scheduler, then storage, then the log files.</p>
 */
public final class AstraPlugin extends JavaPlugin implements RuntimeServices {

    private AstraLogger logger;
    private ConfigManager config;
    private ServerPlatform platform;
    private SchedulerService scheduler;
    private TextService text;
    private Registries registries;
    private PlaceholderService placeholders;
    private SecurityGate security;
    private SimpleProfiler profiler;
    private DataStoreImpl data;
    private TaskRegistry tasks;
    private RuleExecutor executor;
    private EventBus eventBus;
    private ScriptManager scripts;
    private DynamicCommands dynamicCommands;
    private IntegrationManager integrations;
    private io.astra.module.ModuleManager modules;
    private io.astra.package_.PackageManager packages;
    private BuiltinVocabulary vocabulary;
    private AstraCommand adminCommand;
    private volatile boolean shuttingDown;
    private LibLoader.Loaded libLoader;
    private volatile io.astra.runtime.net.HttpService httpService;
    private io.astra.runtime.GameplayServices gameplay;

    /** Outbound HTTP timeout. {@code security.yml} caps the size, not the duration. */
    private static final long HTTP_TIMEOUT_MILLIS = 10_000L;

    @Override
    public void onEnable() {
        long started = System.currentTimeMillis();
        logger = new AstraLogger(message -> getLogger().info(message));

        config = new ConfigManager(getClassLoader(), getDataFolder().toPath(), logger);
        config.load();
        var logging = config.logging();
        logger.configure(logging.level(), logging.console(), logging.file(),
            config.resolve(logging.filePath()), logging.separateErrorFile(), config.resolve(logging.errorFilePath()),
            logging.includeStackTraces(), logging.includeScriptSource());

        platform = PlatformDetector.detect();
        scheduler = Schedulers.create(this, platform, logger);
        text = new TextService(config.main().developer().debug());

        registries = Registries.builtins();
        placeholders = registries.placeholders();
        vocabulary = new BuiltinVocabulary(registries, MaterialTable.shared(),
            config.main().naturalLanguage().enabled(), config.main().naturalLanguage().allowMixedMode());
        io.astra.language.parser.VocabularyProvider.setDefaultVocabulary(vocabulary);
        io.astra.runtime.vocab.BuiltinVocabulary.setShared(vocabulary);

        // Bundled JDBC drivers (astra/lib/*.jar) are extracted and registered before
        // storage is opened, so storage.yml can select sqlite/mysql without the
        // server administrator installing a driver by hand.
        libLoader = LibLoader.install(getClassLoader(), getDataFolder().toPath(), logger);
        if (libLoader.usable()) {
            logger.info("Storage drivers ready: " + libLoader.describe());
        }

        security = new SecurityGateImpl(config, logger, getDataFolder().toPath());
        profiler = new SimpleProfiler(
            config.performance().profiler().enabled() || config.main().developer().profiler(),
            config.performance().events().slowRuleThresholdMicros(),
            config.performance().profiler().historySize());

        data = new DataStoreImpl(openStorage(), logger, scheduler, config.storage().cache().enabled());
        data.storage().migrate();
        installCorePlaceholders();

        // The gameplay services own everything scripts put into the world. They are built
        // after storage (quests live in the data store) and before the executor, because
        // the menu listener runs button bodies through that executor.
        gameplay = createGameplay();
        tasks = new TaskRegistry(logger);
        executor = new RuleExecutor(this, tasks);
        gameplay.menus().wire(executor, name -> scripts == null ? null : scripts.script(name));
        gameplay.regionTracker().start(this);
        gameplay.menus().start();
        eventBus = new EventBus(getServer().getPluginManager(), this, logger, (event, definitions) -> {
            ScriptManager manager = scripts;
            if (manager != null) manager.dispatch(event, definitions);
        });
        scripts = new ScriptManager(this, registries, tasks, executor, logger, vocabulary);
        dynamicCommands = new DynamicCommands(logger, "astra");
        dynamicCommands.setManager(scripts);
        scripts.setCommands(dynamicCommands);

        getServer().getPluginManager().registerEvents(new PlayerDataListener(), this);

        publishExamples();
        integrations = new IntegrationManager(this, config, logger, placeholders);
        integrations.install();
        modules = new io.astra.module.ModuleManager(this, config, logger, (SecurityGateImpl) security, registries,
            vocabulary);
        packages = new io.astra.package_.PackageManager(this, config, logger, scripts, (SecurityGateImpl) security);

        registerAdminCommand();
        startScripts();
        if (config.modules().autoLoad()) modules.loadAll();
        if (config.packages().autoLoad()) packages.loadAll();
        startAutosave();

        logger.info("AstraSyntax " + pluginVersion() + " enabled on " + platform.describe()
            + " using " + scheduler.implementationName());
        logger.info("Loaded " + scripts.activeCount() + " script(s) with " + scripts.ruleCount() + " active rule(s); "
            + data.storage().health());
        for (var issue : config.issues()) {
            if (issue.severity() == io.astra.config.ConfigIssue.Severity.ERROR) {
                logger.warn("Configuration: " + issue.describe());
            }
        }
        logger.debug("Startup took " + (System.currentTimeMillis() - started) + "ms");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (scripts != null) scripts.shutdown();
        if (gameplay != null) gameplay.shutdown();
        if (dynamicCommands != null) dynamicCommands.unregisterAll();
        if (modules != null) modules.shutdown();
        if (integrations != null) integrations.shutdown();
        if (tasks != null) tasks.cancelEverything();
        if (scheduler != null) scheduler.cancelAll();
        if (data != null) data.shutdown();
        if (libLoader != null && libLoader.loader() instanceof java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (Exception error) {
                if (logger != null) logger.debug("Could not close the driver class loader: " + error);
            }
        }
        if (adminCommand != null) adminCommand.shutdown();
        if (logger != null) {
            logger.info("AstraSyntax disabled");
            logger.shutdown();
        }
    }

    // ------------------------------------------------------------------ wiring

    private Storage openStorage() {
        var storageSettings = config.storage();
        String type = storageSettings.storage().type();
        return switch (type == null ? "sqlite" : type.toLowerCase(java.util.Locale.ROOT)) {
            case "mysql", "mariadb" -> SqlStorage.mysql(
                storageSettings.storage().mysql().host(),
                storageSettings.storage().mysql().port(),
                storageSettings.storage().mysql().database(),
                storageSettings.storage().mysql().username(),
                storageSettings.storage().mysql().password(),
                storageSettings.storage().mysql().ssl(),
                logger);
            case "file" -> new FileStorage(getDataFolder().toPath().resolve("data").resolve("astra-data.txt"), logger);
            default -> SqlStorage.sqlite(getDataFolder().toPath().resolve(storageSettings.storage().sqlite().file()),
                logger);
        };
    }

    @Override
    public io.astra.runtime.GameplayServices gameplay() {
        return gameplay;
    }

    /** Builds the gameplay services. Every one of them is inert until a script uses it. */
    private io.astra.runtime.GameplayServices createGameplay() {
        io.astra.runtime.item.ItemService items = new io.astra.runtime.item.ItemService(logger, text);
        io.astra.runtime.region.RegionService regions = new io.astra.runtime.region.RegionService(logger);
        return new io.astra.runtime.GameplayServices(
            items,
            new io.astra.runtime.recipe.RecipeService(items, logger),
            regions,
            new io.astra.runtime.region.RegionTracker(regions, logger),
            new io.astra.runtime.quest.QuestService(data),
            new io.astra.runtime.board.ScoreboardService(text, logger),
            new io.astra.runtime.board.BossBarService(text, logger),
            new io.astra.runtime.display.HologramService(text, logger),
            new io.astra.runtime.gui.MenuService(this, items, text, logger),
            new io.astra.runtime.npc.NpcService(text, logger));
    }

    @Override
    public io.astra.runtime.economy.Economy economy() {
        io.astra.integration.IntegrationManager integration = integrations;
        if (integration == null) return null;
        return integration.economy()
            .filter(bridge -> bridge.available())
            .orElse(null);
    }

    @Override
    public io.astra.runtime.net.HttpService http() {
        io.astra.runtime.net.HttpService service = httpService;
        if (service != null) return service;
        synchronized (this) {
            if (httpService == null) {
                httpService = new io.astra.runtime.net.HttpService(security, logger, HTTP_TIMEOUT_MILLIS,
                    (int) Math.min(Integer.MAX_VALUE, security.policy().maxHttpResponseSize()));
            }
            return httpService;
        }
    }

    private void registerAdminCommand() {
        adminCommand = new AstraCommand(this);
        var command = getCommand("astra");
        if (command != null) {
            command.setExecutor(adminCommand);
            command.setTabCompleter(adminCommand);
        } else {
            logger.warn("The /astra command is missing from plugin.yml; admin commands are unavailable");
        }
    }

    /** Compile and activate the scripts folder. */
    public void startScripts() {
        if (!config.main().scripts().loadOnStartup()) {
            logger.debug("scripts.load-on-startup is false; no script was loaded");
            return;
        }
        List<ScriptManager.LoadResult> results = scripts.loadAll();
        for (ScriptManager.LoadResult result : results) {
            if (result.failed()) {
                logger.warn(result.message());
                for (var diagnostic : result.diagnostics()) {
                    logger.warn("  " + diagnostic.position() + ": " + diagnostic.message()
                        + (diagnostic.suggestions().isEmpty() ? ""
                            : " -> did you mean " + String.join(", ", diagnostic.suggestions()) + "?"));
                }
            }
        }
    }

    /** Copy the shipped examples into {@code plugins/AstraSyntax/examples} on first run. */
    private void publishExamples() {
        if (!config.main().examples().generate()) return;
        Path folder = getDataFolder().toPath().resolve("examples");
        try {
            FileUtil.ensureDirectory(folder);
            for (String name : List.of("01-welcome.ar", "02-natural-language.ar", "03-command.ar", "04-coins.ar",
                "05-timer.ar")) {
                FileUtil.copyResourceIfMissing(getClassLoader(), "examples/" + name, folder.resolve(name));
            }
        } catch (Exception error) {
            logger.debug("Could not publish the example scripts: " + error.getMessage());
        }
    }

    private void startAutosave() {
        var autosave = config.storage().autosave();
        if (!autosave.enabled()) {
            logger.debug("Autosave is disabled in storage.yml");
            return;
        }
        long interval = Math.max(200L, autosave.intervalTicks());
        scheduler.runAsyncRepeating(() -> {
            if (shuttingDown) return;
            try {
                data.saveDirty();
            } catch (RuntimeException error) {
                logger.warnThrottled("autosave", "Autosave failed: " + error.getMessage());
            }
        }, interval, interval);
        logger.debug("Autosave every " + autosave.rawInterval());
    }

    /** Placeholders every script may use, including the ones backed by stored data. */
    private void installCorePlaceholders() {
        // Paper-only methods are reached reflectively so the same jar keeps working on
        // Spigot, where getTPS()/getCurrentTick() do not exist.
        placeholders.register("tps", (name, context) -> io.astra.runtime.Value.dec(currentTps()));
        placeholders.register("online", (name, context) -> io.astra.runtime.Value.num(Bukkit.getOnlinePlayers().size()));
        placeholders.register("plugin-version", (name, context) -> io.astra.runtime.Value.str(pluginVersion()));
        placeholders.register("tick", (name, context) -> io.astra.runtime.Value.num(currentTick()));
        placeholders.register("world-count", (name, context) -> io.astra.runtime.Value.num(Bukkit.getWorlds().size()));
    }

    /** The plugin version, from the plugin description (works on Spigot and Paper). */
    private String pluginVersion() {
        String version = getDescription() == null ? "" : getDescription().getVersion();
        return Strings.isBlank(version) ? "unknown" : version;
    }

    /** Server TPS when the platform exposes it, otherwise the ideal 20. */
    private static double currentTps() {
        java.lang.reflect.Method method = io.astra.util.Reflect.findMethod(org.bukkit.Bukkit.class, "getTPS")
            .orElse(null);
        Object value = io.astra.util.Reflect.invokeQuietly(method, null);
        if (value instanceof double[] samples && samples.length > 0) {
            return Math.min(20.0, samples[0]);
        }
        return 20.0;
    }

    /** Ticks the server has been running, when the platform exposes it. */
    private static long currentTick() {
        java.lang.reflect.Method method = io.astra.util.Reflect
            .findMethod(org.bukkit.Bukkit.class, "getCurrentTick").orElse(null);
        Object value = io.astra.util.Reflect.invokeQuietly(method, null);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    // ----------------------------------------------------------- RuntimeServices

    @Override public AstraLogger logger() { return logger; }

    @Override public ConfigManager config() { return config; }

    @Override public SchedulerService scheduler() { return scheduler; }

    @Override public TextService text() { return text; }

    @Override public io.astra.data.DataStore data() { return data; }

    @Override public Registries registries() { return registries; }

    @Override public EventBus events() { return eventBus; }

    @Override public PlaceholderService placeholders() { return placeholders; }

    @Override public SecurityGate security() { return security; }

    @Override public Profiler profiler() { return profiler; }

    @Override public TaskRegistry tasks() { return tasks; }

    @Override public Object plugin() { return this; }

    @Override public boolean shuttingDown() { return shuttingDown; }

    @Override
    public String message(String key, Object... replacements) {
        String template = config.language().message(key, defaultMessage(key));
        String rendered = template;
        if (replacements != null && replacements.length > 0) {
            String[] names = placeholderNames(key);
            for (int i = 0; i < replacements.length; i++) {
                String placeholder = i < names.length ? names[i] : "{" + i + "}";
                rendered = rendered.replace(placeholder, String.valueOf(replacements[i]));
            }
        }
        String prefix = config.language().prefix();
        if (Strings.isBlank(prefix)) return text.render(rendered);
        return text.render(prefix + " <reset>" + rendered);
    }

    /** The placeholder names used by each shipped message, in order. */
    private static String[] placeholderNames(String key) {
        return switch (key) {
            case "reload-success", "reload-failed", "script-loaded", "script-unloaded" -> new String[] {"{file}"};
            case "unknown-item" -> new String[] {"{item}"};
            case "unknown-action" -> new String[] {"{action}"};
            case "script-error" -> new String[] {"{line}"};
            case "suggestion" -> new String[] {"{suggestion}"};
            case "natural-language-error" -> new String[] {"{rule}"};
            default -> new String[] {"{0}", "{1}", "{2}"};
        };
    }

    /** Fallbacks used when a key is missing from language.yml; the file always wins. */
    public static String defaultMessage(String key) {
        return switch (key) {
            case "startup" -> "AstraSyntax is ready.";
            case "reload-success" -> "<green>{file} reloaded successfully.</green>";
            case "reload-failed" -> "<red>Couldn't reload {file}.</red>";
            case "script-loaded" -> "<green>Loaded {file}.</green>";
            case "script-unloaded" -> "<yellow>Unloaded {file}.</yellow>";
            case "unknown-item" -> "<red>Unknown item: {item}</red>";
            case "unknown-action" -> "<red>Unknown action: {action}</red>";
            case "no-permission" -> "<red>You don't have permission.</red>";
            case "script-error" -> "<red>Problem found on line {line}.</red>";
            case "suggestion" -> "<gray>Did you mean: {suggestion}?</gray>";
            case "natural-language-error" -> "<red>I couldn't understand this rule: {rule}</red>";
            case "player-only" -> "<red>This command can only be used by a player.</red>";
            case "console-not-allowed" -> "<red>This command cannot be run from the console.</red>";
            case "command-condition-failed" -> "<red>You cannot use this command right now.</red>";
            default -> key;
        };
    }

    // ----------------------------------------------------------------- accessors

    /** The script manager (used by modules, packages and the admin command). */
    public ScriptManager scripts() {
        return scripts;
    }

    /** Script command registry (used by the admin command). */
    public DynamicCommands commands() {
        return dynamicCommands;
    }

    /** The detected platform. */
    public ServerPlatform platform() {
        return platform;
    }

    /** The data store with its diagnostics surface. */
    public DataStoreImpl dataStore() {
        return data;
    }

    /** The profiler with its runtime switch. */
    public SimpleProfiler profilerImpl() {
        return profiler;
    }

    /** Module manager, or {@code null} before startup finished. */
    public io.astra.module.ModuleManager modules() {
        return modules;
    }

    /** Package manager, or {@code null} before startup finished. */
    public io.astra.package_.PackageManager packages() {
        return packages;
    }

    /** Integration manager, or {@code null} before startup finished. */
    public IntegrationManager integrations() {
        return integrations;
    }

    /** The vocabulary modules and packages extend. */
    public BuiltinVocabulary vocabulary() {
        return vocabulary;
    }

    /** Player data is warmed on join and written back on quit, both off the main thread. */
    private final class PlayerDataListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            data.preload(player);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            Player player = event.getPlayer();
            java.util.UUID uuid = player.getUniqueId();
            data.flushAsync(uuid);
            // The cached row is dropped only after the write has been queued, so a script
            // reading the player during the quit event still sees the live values.
            scheduler.runGlobalLater(() -> data.unload(uuid), 40L);
        }
    }
}
