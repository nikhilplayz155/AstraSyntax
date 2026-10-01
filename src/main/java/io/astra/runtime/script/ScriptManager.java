package io.astra.runtime.script;

import io.astra.language.compiler.AstraCompiler;
import io.astra.language.compiler.CompilationResult;
import io.astra.language.diagnostics.Diagnostic;
import io.astra.language.diagnostics.DiagnosticCollector;
import io.astra.language.diagnostics.Severity;
import io.astra.language.parser.AstraParser;
import io.astra.language.parser.ParseResult;
import io.astra.language.parser.Vocabulary;
import io.astra.logging.AstraLogger;
import io.astra.platform.TaskHandle;
import io.astra.profiler.BasicTraceSession;
import io.astra.profiler.TraceSession;
import io.astra.runtime.ActionFailure;
import io.astra.runtime.ExecContext;
import io.astra.runtime.Registries;
import io.astra.runtime.RuntimeServices;
import io.astra.runtime.event.EventBus;
import io.astra.runtime.event.EventDefinition;
import io.astra.runtime.scheduler.TaskRegistry;
import io.astra.util.FileUtil;
import io.astra.util.Hash;
import io.astra.util.Strings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

/**
 * Owns the lifecycle of every loaded {@code .ar} script.
 *
 * <p>The contract that matters most here is the hot-reload rule: a script that fails to
 * compile keeps its previous, working version. Compilation happens into a staging result,
 * activation is a single field swap on {@link Script}, and every failure path leaves the
 * live {@link CompiledScript} untouched.</p>
 *
 * <p>Resources are released the same way they are acquired: event listeners through
 * {@link EventBus#refresh}, script-owned tasks through {@link TaskRegistry#cancelAll} and
 * commands through {@link DynamicCommands#unregisterScript}. There is no code path that
 * unloads a script without calling all three, which is what makes reloads leak-free.</p>
 */
public final class ScriptManager implements EventBus.Dispatcher {

    /** Outcome of a load/reload/unload request. */
    public enum LoadStatus {
        /** A new script was compiled and activated. */
        LOADED,
        /** An existing script was replaced by a newer version. */
        RELOADED,
        /** The source was unchanged and the compile cache was used or skipped. */
        UNCHANGED,
        /** Compilation or activation failed; a previous version (if any) stays active. */
        FAILED,
        /** The script was disabled and its resources released. */
        UNLOADED,
        /** The request did not apply (no such script, limit reached, ...). */
        SKIPPED
    }

    /** A load/reload/unload result, ready for console output. */
    public record LoadResult(LoadStatus status, String name, CompiledScript script, List<Diagnostic> diagnostics,
                             String message) {

        public boolean failed() {
            return status == LoadStatus.FAILED;
        }

        public boolean changed() {
            return status == LoadStatus.LOADED || status == LoadStatus.RELOADED;
        }
    }

    /** A rule plus the script that owns it. */
    private record RuleRef(Script script, CompiledRule rule) { }

    private final RuntimeServices services;
    private final Registries registries;
    private final TaskRegistry tasks;
    private final RuleExecutor executor;
    private final AstraLogger logger;
    private final Vocabulary vocabulary;
    private final AstraCompiler compiler;

    private final Map<String, Script> scripts = new ConcurrentHashMap<>();
    private final Map<String, List<RuleRef>> rulesByTrigger = new ConcurrentHashMap<>();
    private final Map<String, CachedCompile> compileCache = new ConcurrentHashMap<>();
    private final Map<String, Path> external = new ConcurrentHashMap<>();
    private final Set<String> enabled = ConcurrentHashMap.newKeySet();
    private final ExecutorService parallelPool = Executors.newFixedThreadPool(
        Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), runnable -> {
            Thread thread = new Thread(runnable, "AstraSyntax-Compiler");
            thread.setDaemon(true);
            return thread;
        });

    private volatile DynamicCommands commands;
    private volatile BasicTraceSession armedTrace;
    private volatile String traceTarget = "";
    private volatile boolean shuttingDown;

    /** One cached compile, keyed by source hash so a reload of unchanged text is free. */
    private record CachedCompile(String hash, CompilationResult result) { }

    public ScriptManager(RuntimeServices services, Registries registries, TaskRegistry tasks, RuleExecutor executor,
                         AstraLogger logger, Vocabulary vocabulary) {
        this.services = services;
        this.registries = registries;
        this.tasks = tasks;
        this.executor = executor;
        this.logger = logger;
        this.vocabulary = vocabulary;
        this.compiler = new AstraCompiler(registries, services.security() == null ? null
            : services.security().policy());
    }

    /** Install the command registry once the plugin has created it. */
    public void setCommands(DynamicCommands commands) {
        this.commands = commands;
    }

    public RuleExecutor executor() {
        return executor;
    }

    public Vocabulary vocabulary() {
        return vocabulary;
    }

    // ------------------------------------------------------------------ discovery

    /** The folder scripts are read from. */
    public Path scriptsFolder() {
        return services.config().resolve(services.config().main().scripts().folder());
    }

    /** Every {@code .ar} file currently on disk, sorted by name. */
    public List<Path> discover() {
        String extension = services.config().main().scripts().fileExtension();
        List<Path> files = new ArrayList<>(FileUtil.listFilesRecursive(scriptsFolder(),
            Strings.isBlank(extension) ? ".ar" : extension));
        files.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
        return files;
    }

    // ------------------------------------------------------------------- loading

    /** Load every discovered script. Returns the number of scripts that are now active. */
    public List<LoadResult> loadAll() {
        List<Path> files = discover();
        int limit = services.config().performance().limits().maxLoadedScripts();
        if (files.size() > limit) {
            logger.warn("Found " + files.size() + " scripts but performance.yml allows " + limit
                + "; the extras are skipped");
            files = files.subList(0, limit);
        }
        List<LoadResult> results = new ArrayList<>();
        boolean parallel = services.config().performance().scripts().parallelLoading() && files.size() > 2;
        if (!parallel) {
            for (Path file : files) {
                results.add(load(file));
            }
            return results;
        }
        // Compilation is pure CPU work with no Bukkit calls, so it is safe off-thread; the
        // activation step below still happens on the calling thread, in file order.
        List<CompletableFuture<LoadResult>> futures = new ArrayList<>();
        for (Path file : files) {
            futures.add(CompletableFuture.supplyAsync(() -> load(file), parallelPool));
        }
        for (CompletableFuture<LoadResult> future : futures) {
            try {
                results.add(future.join());
            } catch (RuntimeException error) {
                logger.error("A script could not be loaded: " + logger.describe(error));
            }
        }
        return results;
    }

    /**
     * Load a script that does not live in the scripts folder (a package contribution).
     *
     * <p>The path is remembered so {@code /astra reload} reloads it exactly like an
     * ordinary script, and so unloading the package can forget it again.</p>
     */
    public LoadResult loadExternal(Path file, String owner) {
        if (file != null) external.put(FileUtil.baseName(file), file);
        return load(file);
    }

    /** Forget an externally contributed script (used when a package unloads). */
    public void forgetExternal(String name) {
        if (name != null) external.remove(name);
    }

    /** Scripts contributed by packages, by name. */
    public java.util.Set<String> externalScripts() {
        return java.util.Set.copyOf(external.keySet());
    }

    /** Load or reload a script from a file path. */
    public LoadResult load(Path file) {
        String name = FileUtil.baseName(file);
        if (shuttingDown) {
            return new LoadResult(LoadStatus.SKIPPED, name, null, List.of(), "The plugin is shutting down");
        }
        if (!Files.exists(file)) {
            return new LoadResult(LoadStatus.FAILED, name, null, List.of(), "No such file: " + file);
        }
        Script script = scripts.computeIfAbsent(name, key -> new Script(key, file));
        script.setPathIfAbsent(file);
        ScriptState previousState = script.state();
        script.setState(ScriptState.PARSING);

        String source;
        try {
            source = FileUtil.read(file);
        } catch (IOException e) {
            script.setState(ScriptState.ERROR);
            script.setLastError("Could not read the file: " + e.getMessage());
            return new LoadResult(LoadStatus.FAILED, name, null, List.of(),
                "Could not read " + file.getFileName() + ": " + e.getMessage());
        }
        script.setSource(source);

        CompilationResult result = compile(name, source);
        script.setDiagnostics(result.errors().isEmpty() ? result.warnings() : result.errors());
        if (!result.success()) {
            script.setState(previousState.holdsResources() ? previousState : ScriptState.ERROR);
            String message = "Script '" + name + "' has " + result.errors().size() + " error(s); "
                + (script.active() == null ? "it was not loaded" : "the previous version stays active");
            script.setLastError(message);
            return new LoadResult(LoadStatus.FAILED, name, script.active(), result.errors(), message);
        }

        boolean wasActive = previousState.holdsResources();
        if (wasActive && script.active() != null && script.active().sourceHash().equals(result.script().sourceHash())) {
            script.setState(ScriptState.ENABLED);
            return new LoadResult(LoadStatus.UNCHANGED, name, script.active(), result.warnings(),
                "Script '" + name + "' is already up to date");
        }

        activate(script, result);
        LoadStatus status = wasActive ? LoadStatus.RELOADED : LoadStatus.LOADED;
        boolean natural = script.ast() != null && script.ast().hasNaturalLanguage();
        String message = (wasActive ? "Reloaded " : "Loaded ") + name + ": " + script.active().ruleCount()
            + " rule(s)" + (natural ? " (natural language)" : "");
        if (wasActive) logger.info(message); else logger.debug(message);
        return new LoadResult(status, name, script.active(), result.warnings(), message);
    }

    /** Load a script by name from the scripts folder. */
    public LoadResult load(String name) {
        Optional<Path> file = findFile(name);
        if (file.isEmpty()) {
            return new LoadResult(LoadStatus.SKIPPED, name, null, List.of(), "No script named '" + name + "'");
        }
        return load(file.get());
    }

    /** Compile a script without activating it ({@code /astra check}). */
    public CompilationResult check(Path file) {
        String name = FileUtil.baseName(file);
        try {
            String source = FileUtil.read(file);
            return compile(name, source);
        } catch (IOException e) {
            DiagnosticCollector collector = new DiagnosticCollector();
            collector.setFile(file.getFileName().toString());
            collector.error("Could not read the file: " + e.getMessage(), 1, 1, "The file could not be read.");
            return CompilationResult.failure(collector, "");
        }
    }

    /** Compile source text into the runtime IR, using the cache when the text is unchanged. */
    public CompilationResult compile(String name, String source) {
        String hash = Hash.sha256(source);
        boolean cacheEnabled = services.config().performance().scripts().compileCache();
        if (cacheEnabled) {
            CachedCompile cached = compileCache.get(name);
            if (cached != null && cached.hash().equals(hash)) return cached.result();
        }
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        diagnostics.setFile(fileNameFor(name));
        ParseResult parsed = new AstraParser(diagnostics, vocabulary).parse(source, fileNameFor(name));
        CompilationResult result;
        if (diagnostics.hasErrors()) {
            // The parser already reported the problem; there is nothing to compile.
            result = CompilationResult.failure(diagnostics, hash);
        } else {
            CompilationResult compiled = compiler.compile(parsed);
            if (!compiled.success()) {
                result = compiled;
            } else {
                // Parser warnings plus compiler warnings, in that order.
                result = new CompilationResult(compiled.script(), List.of(),
                    mergeWarnings(diagnostics, compiled), diagnostics, hash);
            }
        }
        if (cacheEnabled) compileCache.put(name, new CachedCompile(hash, result));
        return result;
    }

    private static List<Diagnostic> mergeWarnings(DiagnosticCollector parserDiagnostics, CompilationResult compiled) {
        List<Diagnostic> warnings = new ArrayList<>();
        for (Diagnostic diagnostic : parserDiagnostics.diagnostics()) {
            if (diagnostic.severity() == Severity.WARNING) warnings.add(diagnostic);
        }
        warnings.addAll(compiled.warnings());
        return warnings;
    }

    private String fileNameFor(String name) {
        String extension = services.config().main().scripts().fileExtension();
        return name + (Strings.isBlank(extension) ? ".ar" : extension);
    }

    /** Register everything a freshly compiled script needs. */
    private void activate(Script script, CompilationResult result) {
        release(script);
        CompiledScript compiled = result.script();
        script.setAst(compiled.ast());
        script.activate(compiled);
        script.markReloaded();
        script.setLastError("");
        script.setDiagnostics(result.warnings());

        // Data keys the script declares become known to the store before any rule runs.
        for (io.astra.language.ast.Declaration.Data declared : compiled.data()) {
            services.data().declare(declared.key(), declared.defaultValue(), declared.type(), declared.persistent());
        }
        // Script-defined commands are registered before the state flips to ENABLED so a
        // command can never run against a half-registered script.
        if (commands != null && !compiled.commands().isEmpty()) {
            commands.registerAll(script, compiled.commands());
        }
        scheduleTimers(script, compiled);
        enabled.add(script.name());
        script.setState(ScriptState.ENABLED);
        refreshEventBus();
    }

    /** Register the repeating tasks a script declares. */
    private void scheduleTimers(Script script, CompiledScript compiled) {
        int maxTasks = services.security().policy().maxTasksPerScript();
        for (CompiledRule rule : compiled.rules()) {
            if (rule.kind() != RuleKind.TIMER) continue;
            long period = Math.max(1L, rule.periodTicks());
            Runnable task = () -> {
                if (!script.state().isActive() || shuttingDown) return;
                ExecContext context = ExecContext.forTimer(services, rule.triggerId(), script, rule, traceFor(script));
                try {
                    executor.execute(rule, context);
                } catch (Throwable error) {
                    logger.error("Timer rule " + rule.id() + " failed: " + logger.describe(error));
                }
            };
            TaskHandle handle = services.scheduler().runGlobalRepeating(task, period, period);
            TaskHandle registered = tasks.register(script.name(), rule.id(), handle, maxTasks);
            if (registered == null) {
                logger.warn("Timer " + rule.id() + " was not scheduled: the script reached the task limit");
            }
        }
    }

    // ----------------------------------------------------------------- unloading

    /** Disable a script and release every resource it owns. */
    public LoadResult unload(String name) {
        Script script = scripts.get(name);
        if (script == null) {
            return new LoadResult(LoadStatus.SKIPPED, name, null, List.of(), "No script named '" + name + "'");
        }
        release(script);
        script.setState(ScriptState.UNLOADED);
        enabled.remove(name);
        scripts.remove(name);
        compileCache.remove(name);
        refreshEventBus();
        logger.info("Unloaded script '" + name + "'");
        return new LoadResult(LoadStatus.UNLOADED, name, null, List.of(), "Unloaded " + name);
    }

    /** Cancel tasks, unregister commands and clear the rule index for a script. */
    private void release(Script script) {
        int cancelled = tasks.cancelAll(script.name());
        if (cancelled > 0) logger.debug("Cancelled " + cancelled + " task(s) of '" + script.name() + "'");
        if (commands != null) commands.unregisterScript(script.name());
        enabled.remove(script.name());
    }

    /** Re-read one script from disk. */
    public LoadResult reload(String name) {
        Optional<Path> file = findFile(name);
        if (file.isEmpty()) {
            Script existing = scripts.get(name);
            if (existing == null) {
                return new LoadResult(LoadStatus.SKIPPED, name, null, List.of(), "No script named '" + name + "'");
            }
            return load(existing.path());
        }
        return load(file.get());
    }

    /** Re-read every script and refresh the configuration. */
    public List<LoadResult> reloadAll() {
        compileCache.clear();
        List<LoadResult> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path file : discover()) {
            seen.add(FileUtil.baseName(file));
            results.add(load(file));
        }
        // Package-contributed scripts live outside the scripts folder; they are reloaded
        // from the paths the package manager registered.
        for (Map.Entry<String, Path> entry : external.entrySet()) {
            if (seen.add(entry.getKey())) {
                results.add(load(entry.getValue()));
            }
        }
        // Scripts whose files disappeared are unloaded rather than left running.
        for (String name : Set.copyOf(scripts.keySet())) {
            if (!seen.contains(name)) {
                results.add(unload(name));
            }
        }
        return results;
    }

    private Optional<Path> findFile(String name) {
        if (Strings.isBlank(name)) return Optional.empty();
        for (Path file : discover()) {
            if (FileUtil.baseName(file).equalsIgnoreCase(name)) return Optional.of(file);
        }
        Script existing = scripts.get(name);
        return existing == null ? Optional.empty() : Optional.of(existing.path());
    }

    // ----------------------------------------------------------------- event path

    /** Rebuild the trigger index and tell the event bus what to listen for. */
    public void refreshEventBus() {
        Map<String, List<RuleRef>> index = new LinkedHashMap<>();
        List<EventDefinition> definitions = new ArrayList<>();
        Set<String> wantedTriggers = new LinkedHashSet<>();
        for (Script script : scripts.values()) {
            CompiledScript compiled = script.active();
            if (compiled == null || !script.state().isActive()) continue;
            for (CompiledRule rule : compiled.rules()) {
                if (rule.kind() != RuleKind.EVENT) continue;
                index.computeIfAbsent(rule.triggerId(), key -> new ArrayList<>()).add(new RuleRef(script, rule));
                if (wantedTriggers.add(rule.triggerId())) {
                    EventDefinition definition = registries.events().get(rule.triggerId());
                    if (definition != null) {
                        definitions.add(definition);
                    } else {
                        logger.warn("Script '" + script.name() + "' uses the unknown trigger '"
                            + rule.triggerId() + "'");
                    }
                }
            }
        }
        rulesByTrigger.clear();
        rulesByTrigger.putAll(index);
        if (services.events() != null) {
            services.events().refresh(definitions);
        }
    }

    @Override
    public void dispatch(Event event, List<EventDefinition> definitions) {
        if (shuttingDown) return;
        for (EventDefinition definition : definitions) {
            List<RuleRef> refs = rulesByTrigger.get(definition.id());
            if (refs == null || refs.isEmpty()) continue;
            for (RuleRef ref : refs) {
                Script script = ref.script();
                if (!script.state().isActive()) continue;
                if (ref.rule().eventClass() != null && !ref.rule().eventClass().isInstance(event)) continue;
                ExecContext context = contextFor(definition, ref, event);
                try {
                    executor.execute(ref.rule(), context);
                } catch (Throwable error) {
                    logger.error("Rule " + ref.rule().id() + " failed: " + logger.describe(error));
                    if (script != null) script.setLastError(error.getClass().getSimpleName());
                }
                if (context.isCancelRequested()) {
                    // Cancellation is applied once per event, after every rule has had a
                    // chance to react, so two rules cannot fight over the same event.
                    context.applyCancellation();
                    if (definition.id() != null && !definition.id().isEmpty()) {
                        logger.debug(() -> "Event " + definition.id() + " cancelled by " + script.name());
                    }
                }
            }
        }
    }

    /** Build the execution context for one rule/event pair using the trigger's adapter. */
    private ExecContext contextFor(EventDefinition definition, RuleRef ref, Event event) {
        var adapter = definition.adapter();
        org.bukkit.entity.Entity actor = adapter == null ? null : adapter.actor(event);
        Player player = adapter == null ? null : adapter.player(event);
        org.bukkit.entity.Entity secondary = adapter == null ? null : adapter.secondary(event);
        org.bukkit.Location location = actor != null ? actor.getLocation() : null;
        ExecContext context = ExecContext.forEvent(services, event, definition.id(), ref.script(), ref.rule(),
            actor, player, secondary, actor == null ? null : actor.getWorld(), location, traceFor(ref.script()));
        if (adapter != null) {
            try {
                adapter.prepare(context, event);
            } catch (RuntimeException error) {
                logger.debug(() -> "Adapter for " + definition.id() + " failed to prepare: " + error);
            }
        }
        return context;
    }

    // ------------------------------------------------------------------ commands

    /**
     * Run a compiled script command.
     *
     * @param command the compiled command
     * @param script  the owning script
     * @param sender  whoever ran it
     * @param label   the label used (for messages)
     * @param args    the raw arguments
     * @return true when the command body ran
     */
    public boolean runCommand(CompiledCommand command, Script script, CommandSender sender, String label,
                              String[] args) {
        if (script == null || !script.state().isActive() || command == null) return false;
        if (command.playerOnly() && !(sender instanceof Player)) {
            sender.sendMessage(services.text().render(services.message("player-only",
                "<red>This command can only be used by a player.")));
            return false;
        }
        if (!command.consoleAllowed() && !(sender instanceof Player)) {
            sender.sendMessage(services.text().render(services.message("console-not-allowed",
                "<red>This command cannot be run from the console.")));
            return false;
        }
        if (!Strings.isBlank(command.permission()) && !sender.hasPermission(command.permission())) {
            String message = command.permissionMessage() != null && !command.permissionMessage().isBlank()
                ? command.permissionMessage()
                : services.message("no-permission", "<red>You do not have permission to do that.");
            sender.sendMessage(services.text().render(message));
            return false;
        }

        Player player = sender instanceof Player p ? p : null;
        CompiledRule placeholder = new CompiledRule(script.name() + ":command:" + command.name(), RuleKind.COMMAND,
            "command", "/" + command.name(), List.of(), command.conditions(), command.body(), null, 0L, false,
            "command /" + command.name(), command.span());
        ExecContext context = ExecContext.forSender(services, "command", script, placeholder, sender, player,
            player, traceFor(script));
        bindArguments(command, args, context);
        try {
            for (CompiledCondition condition : command.conditions()) {
                if (!condition.test(context, executor.evaluator())) {
                    String message = services.message("command-condition-failed",
                        "<red>You cannot use this command right now.");
                    sender.sendMessage(services.text().render(message));
                    return false;
                }
            }
            executor.runBlock(command.body(), context);
            return true;
        } catch (ActionFailure failure) {
            if (!failure.isSilent()) {
                sender.sendMessage(services.text().render("<red>" + failure.getMessage()));
            }
            return false;
        } catch (CompiledStmt.StopSignal | CompiledStmt.ReturnSignal signal) {
            return true;
        } catch (Throwable error) {
            logger.error("Command /" + label + " of script '" + script.name() + "' failed: "
                + logger.describe(error));
            script.setLastError(error.getClass().getSimpleName() + ": " + error.getMessage());
            sender.sendMessage(services.text().render(services.message("script-error",
                "<red>That command failed. Check the console for details.")));
            return false;
        } finally {
            if (context.isCancelRequested()) context.applyCancellation();
        }
    }

    /** Bind positional command arguments to the names the script declared. */
    private void bindArguments(CompiledCommand command, String[] args, ExecContext context) {
        List<io.astra.language.ast.Declaration.CommandArgument> declared = command.arguments();
        if (declared.isEmpty()) {
            if (args != null && args.length > 0) {
                context.setArgument("args", io.astra.runtime.Value.list(buildList(args)));
            }
            return;
        }
        int index = 0;
        for (int i = 0; i < declared.size(); i++) {
            io.astra.language.ast.Declaration.CommandArgument argument = declared.get(i);
            if (index >= args.length) {
                if (!argument.optional()) break;
                context.setArgument(argument.name(), io.astra.runtime.Value.NULL);
                continue;
            }
            if (argument.greedy()) {
                StringBuilder rest = new StringBuilder();
                for (int k = index; k < args.length; k++) {
                    if (rest.length() > 0) rest.append(' ');
                    rest.append(args[k]);
                }
                context.setArgument(argument.name(), io.astra.runtime.Value.str(rest.toString()));
                index = args.length;
                continue;
            }
            context.setArgument(argument.name(), coerce(argument.type(), args[index]));
            index++;
        }
        if (index < args.length) {
            context.setArgument("args", io.astra.runtime.Value.list(buildList(
                java.util.Arrays.copyOfRange(args, index, args.length))));
        }
    }

    private static java.util.List<io.astra.runtime.Value> buildList(String[] args) {
        List<io.astra.runtime.Value> values = new ArrayList<>();
        for (String arg : args) values.add(io.astra.runtime.Value.str(arg));
        return values;
    }

    private static io.astra.runtime.Value coerce(String type, String raw) {
        io.astra.runtime.ValueType valueType = io.astra.runtime.ValueType.parse(type, io.astra.runtime.ValueType.STRING);
        return switch (valueType) {
            case INT, DECIMAL -> {
                try {
                    yield valueType == io.astra.runtime.ValueType.INT
                        ? io.astra.runtime.Value.num(Long.parseLong(raw))
                        : io.astra.runtime.Value.dec(Double.parseDouble(raw));
                } catch (NumberFormatException notANumber) {
                    yield io.astra.runtime.Value.str(raw);
                }
            }
            case BOOLEAN -> io.astra.runtime.Value.bool(Boolean.parseBoolean(raw));
            case PLAYER, ENTITY, STRING, MATERIAL, WORLD, UUID, LOCATION, LIST, NULL ->
                io.astra.runtime.Value.str(raw);
        };
    }

    // -------------------------------------------------------------- trace/explain

    /** Arm a trace for one script ({@code /astra trace <script>}). */
    public void armTrace(String scriptName) {
        this.traceTarget = scriptName == null ? "" : scriptName;
        this.armedTrace = new BasicTraceSession(scriptName);
    }

    /** The armed session, or {@code null}. */
    public BasicTraceSession armedTrace() {
        return armedTrace;
    }

    /** Forget the armed trace ({@code /astra trace off}). */
    public void disarmTrace() {
        armedTrace = null;
        traceTarget = "";
    }

    private TraceSession traceFor(Script script) {
        BasicTraceSession session = armedTrace;
        if (session == null || script == null) return TraceSession.NOOP;
        if (!session.active()) return TraceSession.NOOP;
        if (!traceTarget.isEmpty() && !traceTarget.equalsIgnoreCase(script.name())) return TraceSession.NOOP;
        return session;
    }

    /** A human readable explanation of a script, used by {@code /astra explain}. */
    public List<String> explain(String name) {
        Script script = scripts.get(name);
        if (script == null || script.active() == null) return List.of("No compiled script named '" + name + "'");
        CompiledScript compiled = script.active();
        List<String> lines = new ArrayList<>();
        lines.add("Script " + compiled.name() + " - " + compiled.ruleCount() + " rule(s), compiled "
            + compiled.compiledAtMillis());
        if (!compiled.requiredFeatures().isEmpty()) {
            lines.add("Needs features: " + String.join(", ", compiled.requiredFeatures()));
        }
        for (CompiledScript.RuleSummary summary : compiled.summaries()) {
            StringBuilder line = new StringBuilder("  ").append(summary.kind().label()).append(": ")
                .append(summary.title());
            if (summary.natural()) line.append("  [natural language]");
            lines.add(line.toString());
            for (String detail : summary.details()) {
                lines.add("      - " + detail);
            }
        }
        if (compiled.commands() != null) {
            for (CompiledCommand command : compiled.commands()) {
                lines.add("  command: /" + command.name()
                    + (Strings.isBlank(command.permission()) ? "" : " (permission " + command.permission() + ")"));
            }
        }
        return lines;
    }

    // -------------------------------------------------------------------- queries

    public Collection<Script> scripts() {
        List<Script> sorted = new ArrayList<>(scripts.values());
        sorted.sort(Comparator.comparing(Script::name));
        return sorted;
    }

    /** Look a script up by name, ignoring case. */
    public Script script(String name) {
        if (name == null) return null;
        Script direct = scripts.get(name);
        if (direct != null) return direct;
        for (Script script : scripts.values()) {
            if (script.name().equalsIgnoreCase(name)) return script;
        }
        return null;
    }

    public int activeCount() {
        int count = 0;
        for (Script script : scripts.values()) {
            if (script.state().isActive()) count++;
        }
        return count;
    }

    /** Every error currently reported by any script. */
    public List<Diagnostic> errors() {
        List<Diagnostic> out = new ArrayList<>();
        for (Script script : scripts.values()) {
            for (Diagnostic diagnostic : script.diagnostics()) {
                if (diagnostic.isError()) out.add(diagnostic);
            }
        }
        return out;
    }

    /** Every warning currently reported by any script. */
    public List<Diagnostic> warnings() {
        List<Diagnostic> out = new ArrayList<>();
        for (Script script : scripts.values()) {
            for (Diagnostic diagnostic : script.diagnostics()) {
                if (diagnostic.severity() == Severity.WARNING) out.add(diagnostic);
            }
        }
        return out;
    }

    /** Number of rules currently indexed by trigger. */
    public int ruleCount() {
        int count = 0;
        for (List<RuleRef> refs : rulesByTrigger.values()) count += refs.size();
        return count;
    }

    /** Trigger ids currently used by loaded scripts. */
    public Set<String> activeTriggers() {
        return Set.copyOf(rulesByTrigger.keySet());
    }

    /** Stop everything: cancel tasks, unregister commands and drop all rules. */
    public void shutdown() {
        shuttingDown = true;
        for (Script script : scripts.values()) {
            release(script);
            script.setState(ScriptState.UNLOADED);
        }
        rulesByTrigger.clear();
        scripts.clear();
        compileCache.clear();
        parallelPool.shutdownNow();
        try {
            parallelPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (services.events() != null) services.events().unregisterAll();
    }

    public boolean shuttingDown() {
        return shuttingDown;
    }
}
