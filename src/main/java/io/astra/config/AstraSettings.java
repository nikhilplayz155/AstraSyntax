package io.astra.config;

import io.astra.logging.LogLevel;

import java.util.List;
import java.util.Map;

/**
 * Strongly typed views over the nine supplied configuration files.
 *
 * <p>Field names mirror the YAML keys exactly - the shapes below are a 1:1 mapping
 * of the files that ship with the project, so a configuration change and a code
 * change always have to touch the same place.</p>
 */
public final class AstraSettings {

    private AstraSettings() {}

    // ---------------------------------------------------------------- config.yml

    /** {@code general:} section of config.yml. */
    public record General(String language, boolean checkUpdates, boolean metrics) {}

    /** {@code scripts:} section of config.yml. */
    public record Scripts(String folder, String fileExtension, boolean loadOnStartup,
                          boolean autoReload, boolean continueOnScriptError) {}

    /** {@code natural-language:} section of config.yml. */
    public record NaturalLanguage(boolean enabled, boolean allowMixedMode, boolean strictMode) {}

    /** {@code developer:} section of config.yml. */
    public record Developer(boolean debug, boolean detailedErrors, boolean suggestions, boolean profiler) {}

    /** {@code examples:} section of config.yml. */
    public record Examples(boolean generate) {}

    /** {@code features:} section of config.yml - each toggle gates a subsystem. */
    public record Features(boolean naturalLanguage, boolean customItems, boolean customMobs, boolean bosses,
                           boolean gui, boolean quests, boolean economy, boolean regions, boolean npc,
                           boolean holograms, boolean scoreboards, boolean bossbars, boolean placeholders,
                           boolean recipes, boolean webhooks, boolean http, boolean crossServer) {}

    /** Whole config.yml. */
    public record Main(General general, Scripts scripts, NaturalLanguage naturalLanguage,
                       Developer developer, Examples examples, Features features) {}

    // --------------------------------------------------------------- storage.yml

    /** {@code storage.sqlite:} section. */
    public record Sqlite(String file) {}

    /** {@code storage.mysql:} section. */
    public record Mysql(boolean enabled, String host, int port, String database, String username,
                        String password, boolean ssl) {}

    /** {@code storage:} section. */
    public record Storage(String type, Sqlite sqlite, Mysql mysql) {}

    /** {@code autosave:} section. */
    public record Autosave(boolean enabled, long intervalTicks, String rawInterval) {}

    /** {@code cache:} section. */
    public record Cache(boolean enabled, boolean playerData) {}

    /** Whole storage.yml. */
    public record StorageSettings(Storage storage, Autosave autosave, Cache cache) {}

    // ----------------------------------------------------------- performance.yml

    /** {@code scripts:} section of performance.yml. */
    public record PerformanceScripts(boolean compileCache, boolean parallelLoading) {}

    /** {@code events:} section of performance.yml. */
    public record Events(boolean warnSlowRules, long slowRuleThresholdMicros) {}

    /** {@code scheduler:} section of performance.yml. */
    public record Scheduler(boolean asyncSafeActions) {}

    /** {@code profiler:} section of performance.yml. */
    public record ProfilerSettings(boolean enabled, int historySize) {}

    /** {@code limits:} section of performance.yml. */
    public record Limits(int maxLoadedScripts, int maxScheduledTasks) {}

    /** Whole performance.yml. */
    public record Performance(PerformanceScripts scripts, Events events, Scheduler scheduler,
                              ProfilerSettings profiler, Limits limits) {}

    // -------------------------------------------------------------- security.yml

    /** {@code scripts:} section of security.yml. */
    public record SecurityScripts(boolean allowConsoleCommands, boolean allowFileAccess, boolean allowHttpRequests) {}

    /** {@code webhooks:} section of security.yml. */
    public record Webhooks(boolean enabled, List<String> allowedDomains) {}

    /** {@code packages:} section of security.yml. */
    public record SecurityPackages(boolean allowRemoteInstall, boolean requireSignatures) {}

    /** {@code modules:} section of security.yml. */
    public record SecurityModules(boolean requireTrustedModules) {}

    /** {@code limits:} section of security.yml. */
    public record SecurityLimits(int maxLoopIterations, int maxTasksPerScript, long maxHttpResponseSize) {}

    /** Whole security.yml. */
    public record Security(SecurityScripts scripts, List<String> httpAllowedDomains, Webhooks webhooks,
                           SecurityPackages packages, SecurityModules modules, SecurityLimits limits) {}

    // -------------------------------------------------------------- language.yml

    /** Whole language.yml: a MiniMessage-style prefix plus named message templates. */
    public record Language(String prefix, Map<String, String> messages) {

        /** A message template, or {@code fallback} when the key is not defined. */
        public String message(String key, String fallback) {
            String value = messages.get(key);
            return value == null ? fallback : value;
        }
    }

    // --------------------------------------------------------------- modules.yml

    /** Whole modules.yml. */
    public record Modules(boolean autoLoad, String folder, boolean checkCompatibility, boolean isolateFailures,
                          boolean continueOnModuleError) {}

    // -------------------------------------------------------------- packages.yml

    /** Whole packages.yml. */
    public record Packages(String folder, boolean autoLoad, boolean resolveDependencies, boolean allowRemoteInstall,
                           boolean checkUpdates, boolean failOnMissingRequired, boolean warnOnMissingOptional) {}

    // ---------------------------------------------------------- integrations.yml

    /** A single {@code enabled: auto|true|false} integration toggle. */
    public record IntegrationToggle(String raw, boolean enabled) {

        /** True when the integration is not explicitly disabled. */
        public boolean auto() {
            return "auto".equalsIgnoreCase(raw);
        }
    }

    /** {@code redis:} section of integrations.yml. */
    public record Redis(boolean enabled, String host, int port, String password, int database) {}

    /** Whole integrations.yml. */
    public record Integrations(IntegrationToggle vault, IntegrationToggle placeholderApi,
                               IntegrationToggle citizens, IntegrationToggle worldGuard, Redis redis) {}

    // --------------------------------------------------------------- logging.yml

    /** Whole logging.yml. */
    public record Logging(LogLevel level, boolean console, boolean file, String filePath,
                          boolean separateErrorFile, String errorFilePath, boolean includeStackTraces,
                          boolean includeScriptSource) {}
}
