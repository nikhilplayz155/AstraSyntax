package io.astra.command;

import io.astra.language.diagnostics.Diagnostic;
import io.astra.profiler.BasicTraceSession;
import io.astra.profiler.RuleStats;
import io.astra.runtime.script.Script;
import io.astra.runtime.script.ScriptManager;
import io.astra.util.FileUtil;
import io.astra.util.Strings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The administrative command: {@code /astra ...}.
 *
 * <p>Every subcommand is permission protected and the tab completer only offers what the
 * sender may actually use, so an operator never has to guess a script name or remember the
 * command list. Output is plain text with the configured prefix; errors always say where
 * the problem is, which is what makes this the first stop when a rule misbehaves.</p>
 */
public final class AstraCommand implements CommandExecutor, TabCompleter {

    /** Subcommands, in help order. */
    private static final List<String> SUBCOMMANDS = List.of(
        "reload", "load", "unload", "scripts", "check", "info", "debug", "explain", "trace", "performance",
        "errors", "package");

    /** Package subcommands, in help order. */
    private static final List<String> PACKAGE_SUBCOMMANDS = List.of("list", "install", "load", "unload");

    private final JavaPlugin plugin;
    private final io.astra.plugin.AstraPlugin astra;

    public AstraCommand(io.astra.plugin.AstraPlugin astra) {
        this.astra = astra;
        this.plugin = astra;
    }

    /** Nothing is cached between commands; kept for a symmetric shutdown path. */
    public void shutdown() {
        // no cached state
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("astra.admin") && !sender.hasPermission("astra.command")) {
            reply(sender, astra.message("no-permission", ""));
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("astra.admin") && !sender.hasPermission("astra.command." + sub)) {
            reply(sender, astra.message("no-permission", ""));
            return true;
        }
        String argument = args.length > 1 ? args[1] : "";
        switch (sub) {
            case "reload" -> reload(sender, argument);
            case "load" -> load(sender, argument);
            case "unload" -> unload(sender, argument);
            case "scripts", "list" -> listScripts(sender);
            case "check" -> check(sender, argument);
            case "info" -> info(sender);
            case "debug" -> debug(sender);
            case "explain" -> explain(sender, argument);
            case "trace" -> trace(sender, argument);
            case "performance", "perf" -> performance(sender, argument);
            case "errors" -> errors(sender);
            case "package", "packages" -> packageCommand(sender, args);
            default -> {
                reply(sender, "<red>Unknown subcommand '" + sub + "'.</red>");
                help(sender, label);
            }
        }
        return true;
    }

    // ------------------------------------------------------------- subcommands

    private void reload(CommandSender sender, String name) {
        long started = System.currentTimeMillis();
        astra.config().reload();
        if (name.isBlank()) {
            List<ScriptManager.LoadResult> results = astra.scripts().reloadAll();
            int loaded = 0;
            int failed = 0;
            for (ScriptManager.LoadResult result : results) {
                if (result.changed()) loaded++;
                if (result.failed()) {
                    failed++;
                    reportFailure(sender, result);
                }
            }
            reply(sender, "<green>Reloaded " + loaded + " script(s)"
                + (failed == 0 ? "" : ", <red>" + failed + " failed</red>") + "<green> in "
                + (System.currentTimeMillis() - started) + "ms.</green>");
            return;
        }
        ScriptManager.LoadResult result = astra.scripts().reload(name);
        report(sender, result, name);
    }

    private void load(CommandSender sender, String argument) {
        if (argument.isBlank()) {
            reply(sender, "<red>Usage: /astra load <file.ar|script></red>");
            return;
        }
        Path file = resolve(argument);
        ScriptManager.LoadResult result = Files.exists(file)
            ? astra.scripts().load(file)
            : astra.scripts().load(argument.replace(".ar", ""));
        report(sender, result, argument);
    }

    private void unload(CommandSender sender, String name) {
        if (name.isBlank()) {
            reply(sender, "<red>Usage: /astra unload <script></red>");
            return;
        }
        ScriptManager.LoadResult result = astra.scripts().unload(name);
        report(sender, result, name);
    }

    private void listScripts(CommandSender sender) {
        reply(sender, "<gray>Scripts (" + astra.scripts().activeCount() + " active, "
            + astra.scripts().ruleCount() + " rules):</gray>");
        for (Script script : astra.scripts().scripts()) {
            String state = script.state().name().toLowerCase(Locale.ROOT);
            String colour = script.state().isActive() ? "<green>" : "<red>";
            reply(sender, "<gray> - " + colour + script.name() + "</" + (script.state().isActive()
                ? "green" : "red") + "> <dark_gray>(" + state + ", " + script.activeTriggers().size()
                + " trigger(s), " + script.stats().invocations() + " invocation(s))</dark_gray>");
        }
        List<Path> onDisk = astra.scripts().discover();
        if (onDisk.size() > astra.scripts().activeCount()) {
            reply(sender, "<gray> - <yellow>" + (onDisk.size() - astra.scripts().activeCount())
                + " file(s) on disk are not active (use /astra errors)</yellow>");
        }
    }

    private void check(CommandSender sender, String name) {
        List<Path> files = new ArrayList<>();
        if (name.isBlank()) {
            files.addAll(astra.scripts().discover());
        } else {
            Path file = resolve(name);
            if (!Files.exists(file)) {
                Script script = astra.scripts().script(name);
                if (script == null) {
                    reply(sender, "<red>No script named '" + name + "'.</red>");
                    return;
                }
                file = script.path();
            }
            files.add(file);
        }
        if (files.isEmpty()) {
            reply(sender, "<gray>No scripts to check.</gray>");
            return;
        }
        int ok = 0;
        for (Path file : files) {
            var result = astra.scripts().check(file);
            String fileName = file.getFileName().toString();
            if (result.success()) {
                ok++;
                reply(sender, "<green>OK</green> <gray>" + fileName + " - " + result.script().ruleCount()
                    + " rule(s), " + result.warnings().size() + " warning(s)</gray>");
            } else {
                reply(sender, "<red>FAILED</red> <gray>" + fileName + "</gray>");
                printDiagnostics(sender, result.errors(), 5);
            }
            if (!result.warnings().isEmpty()) {
                printDiagnostics(sender, result.warnings(), 3);
            }
        }
        reply(sender, "<gray>Checked " + files.size() + " file(s): " + ok + " ok, "
            + (files.size() - ok) + " failed.</gray>");
    }

    private void info(CommandSender sender) {
        reply(sender, "<gray>AstraSyntax <white>" + plugin.getDescription().getVersion()
            + "</white> on <white>" + astra.platform().describe() + "</white></gray>");
        reply(sender, "<gray> - scheduler: <white>" + astra.scheduler().implementationName()
            + (astra.scheduler().isFolia() ? " (regionised)" : "") + "</white></gray>");
        reply(sender, "<gray> - storage: <white>" + astra.dataStore().storage().health() + "</white></gray>");
        reply(sender, "<gray> - scripts: <white>" + astra.scripts().activeCount() + " active, "
            + astra.scripts().ruleCount() + " rules</white>, triggers: <white>"
            + astra.scripts().activeTriggers().size() + "</white></gray>");
        reply(sender, "<gray> - vocabulary: <white>" + astra.vocabulary().actions().size() + " action forms, "
            + astra.vocabulary().conditions().size() + " condition forms, "
            + astra.vocabulary().eventPatterns().size() + " trigger patterns</white></gray>");
        reply(sender, "<gray> - commands: <white>" + astra.commands().labels().size()
            + "</white>, tasks: <white>" + astra.tasks().total() + "</white>, cached holders: <white>"
            + astra.dataStore().cachedCount() + "</white></gray>");
        reply(sender, "<gray> - natural language: <white>"
            + (astra.config().main().naturalLanguage().enabled() ? "enabled" : "disabled")
            + "</white>, profiler: <white>" + (astra.profilerImpl().enabled() ? "on" : "off") + "</white></gray>");
        if (astra.gameplay() != null) {
            reply(sender, "<gray> - gameplay: <white>" + astra.gameplay().describe() + "</white></gray>");
        }
        if (!astra.integrations().describe().isEmpty()) {
            reply(sender, "<gray> - integrations: <white>"
                + String.join(", ", astra.integrations().describe()) + "</white></gray>");
        }
    }

    private void debug(CommandSender sender) {
        boolean now = !astra.profilerImpl().enabled();
        astra.profilerImpl().setEnabled(now);
        reply(sender, "<gray>Profiling is now <white>" + (now ? "on" : "off") + "</white>.</gray>");
        if (now) {
            reply(sender, "<gray>Slow rules are recorded; use <white>/astra performance</white> to inspect them."
                + "</gray>");
        }
    }

    private void explain(CommandSender sender, String name) {
        if (name.isBlank()) {
            reply(sender, "<red>Usage: /astra explain <script></red>");
            return;
        }
        List<String> lines = astra.scripts().explain(name);
        for (String line : lines) {
            reply(sender, "<gray>" + line + "</gray>");
        }
    }

    private void trace(CommandSender sender, String name) {
        if (name.isBlank() || name.equalsIgnoreCase("off") || name.equalsIgnoreCase("stop")) {
            astra.scripts().disarmTrace();
            reply(sender, "<gray>Tracing stopped.</gray>");
            return;
        }
        astra.scripts().armTrace(name);
        reply(sender, "<gray>Tracing <white>" + name + "</white>; the next matching execution will be recorded."
            + "</gray>");
    }

    private void performance(CommandSender sender, String argument) {
        if (argument.equalsIgnoreCase("reset")) {
            astra.profilerImpl().reset();
            reply(sender, "<gray>Profiler statistics were reset.</gray>");
            return;
        }
        if (!astra.profilerImpl().enabled()) {
            reply(sender, "<gray>The profiler is off. Turn it on with <white>/astra debug</white> or "
                + "performance.yml: profiler.enabled.</gray>");
        }
        List<RuleStats> slowest = astra.profilerImpl().slowest(10);
        reply(sender, "<gray>Slowest rules (average):</gray>");
        if (slowest.isEmpty()) {
            reply(sender, "<gray> - nothing recorded yet</gray>");
        }
        for (RuleStats stats : slowest) {
            reply(sender, "<gray> - <white>" + stats.ruleId() + "</white> avg "
                + stats.averageMicros() + "us, max " + stats.maxMicros() + "us, "
                + stats.invocations() + " call(s), " + stats.errors() + " error(s)</gray>");
        }
        BasicTraceSession session = astra.scripts().armedTrace();
        if (session != null && !session.untouched()) {
            reply(sender, "<gray>Last trace (" + session.ruleId() + ", " + session.durationMicros() + "us):</gray>");
            for (String step : session.steps()) {
                reply(sender, "<dark_gray>   " + step + "</dark_gray>");
            }
        }
    }

    private void errors(CommandSender sender) {
        List<Diagnostic> errors = astra.scripts().errors();
        List<Diagnostic> warnings = astra.scripts().warnings();
        reply(sender, "<gray>Problems: <red>" + errors.size() + " error(s)</red>, <yellow>"
            + warnings.size() + " warning(s)</yellow>.</gray>");
        printDiagnostics(sender, errors, 10);
        printDiagnostics(sender, warnings, 5);
    }

    // ------------------------------------------------------------------ helpers

    private void report(CommandSender sender, ScriptManager.LoadResult result, String what) {
        switch (result.status()) {
            case LOADED, RELOADED -> {
                reply(sender, astra.message("reload-success", what));
                for (Diagnostic warning : result.diagnostics()) {
                    reply(sender, "<yellow>" + warning.position() + ": " + warning.message() + "</yellow>");
                }
            }
            case UNCHANGED -> reply(sender, "<gray>'" + what + "' is already up to date.</gray>");
            case UNLOADED -> reply(sender, astra.message("script-unloaded", what));
            case SKIPPED -> reply(sender, "<gray>" + result.message() + "</gray>");
            case FAILED -> reportFailure(sender, result);
        }
    }

    private void reportFailure(CommandSender sender, ScriptManager.LoadResult result) {
        reply(sender, astra.message("reload-failed", result.name()));
        reply(sender, "<gray>" + result.message() + "</gray>");
        printDiagnostics(sender, result.diagnostics(), 6);
    }

    private void printDiagnostics(CommandSender sender, List<Diagnostic> diagnostics, int limit) {
        int shown = 0;
        for (Diagnostic diagnostic : diagnostics) {
            if (shown++ >= limit) {
                reply(sender, "<dark_gray>   ... and " + (diagnostics.size() - limit) + " more (see /astra errors)"
                    + "</dark_gray>");
                break;
            }
            String colour = diagnostic.isError() ? "red" : "yellow";
            reply(sender, "<dark_gray>   " + diagnostic.position() + " [" + diagnostic.category() + "] <" + colour + ">"
                + diagnostic.message() + "</" + colour + ">"
                + (diagnostic.explanation().isEmpty() ? "" : " <gray>- " + diagnostic.explanation() + "</gray>")
                + (diagnostic.suggestions().isEmpty() ? ""
                    : " <gray>(did you mean " + String.join(", ", diagnostic.suggestions()) + "?)</gray>"));
            printSourceExcerpt(sender, diagnostic);
        }
    }

    /**
     * Shows the offending line with a caret, plus the ready-to-copy corrected line.
     *
     * <p>Diagnostics are the main way an author learns the language, so the command output
     * carries the same context the console does: the exact source line, where on it the
     * problem is, and - when the compiler could work it out - the fixed line.</p>
     */
    private void printSourceExcerpt(CommandSender sender, Diagnostic diagnostic) {
        String source = sourceOf(diagnostic);
        String line = io.astra.language.diagnostics.DiagnosticRenderer.sourceLine(source, diagnostic.line());
        if (line != null && !line.isBlank()) {
            reply(sender, "<dark_gray>      | " + escape(line) + "</dark_gray>");
            if (diagnostic.column() > 0) {
                int pad = Math.max(0, diagnostic.column() - 1);
                int width = Math.max(1, Math.min(diagnostic.length(), Math.max(1, line.length() - pad)));
                reply(sender, "<dark_gray>      | " + " ".repeat(Math.min(pad, line.length()))
                    + "<red>" + "^".repeat(width) + "</red></dark_gray>");
            }
        }
        if (!diagnostic.fixedLine().isEmpty()) {
            reply(sender, "<dark_gray>      suggested: <green>" + escape(diagnostic.fixedLine())
                + "</green></dark_gray>");
        }
    }

    /** The source text a diagnostic points at, when that script is loaded. */
    private String sourceOf(Diagnostic diagnostic) {
        for (var script : astra.scripts().scripts()) {
            String fileName = script.path() == null ? "" : script.path().getFileName().toString();
            if (fileName.equals(diagnostic.file()) || script.name().equals(io.astra.util.FileUtil.baseName(
                java.nio.file.Path.of(diagnostic.file())))) {
                return script.source();
            }
        }
        return null;
    }

    /** Keeps MiniMessage from interpreting angle brackets that came from script source. */
    private static String escape(String text) {
        return text.replace("<", "\\<");
    }

    private void help(CommandSender sender, String label) {
        reply(sender, "<gray>AstraSyntax commands:</gray>");
        for (String sub : SUBCOMMANDS) {
            reply(sender, "<gray> /" + label + " <white>" + sub + "</white>"
                + (sub.equals("package") ? " <dark_gray>(" + String.join("|", PACKAGE_SUBCOMMANDS) + ")"
                    + "</dark_gray>" : "") + "</gray>");
        }
    }

    // -------------------------------------------------------------- packages

    /**
     * {@code /astra package list|install <url|file> [sha256]|load <name>|unload <name>}.
     *
     * <p>Installation downloads and unpacks off the server thread and only the final
     * registration runs on it, so a slow package host cannot stall the tick loop.</p>
     */
    private void packageCommand(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list", "status" -> {
                var manager = astra.packages();
                reply(sender, "<gray>Packages in <white>" + manager.folder() + "</white>:</gray>");
                var loaded = manager.loaded();
                if (loaded.isEmpty()) reply(sender, "<gray> - none loaded</gray>");
                for (var loadedPackage : loaded) {
                    reply(sender, "<gray> - <green>" + loadedPackage.name() + "</green> "
                        + loadedPackage.version() + " <dark_gray>by " + loadedPackage.author() + " ("
                        + loadedPackage.scripts().size() + " script(s))</dark_gray>");
                }
            }
            case "install" -> {
                if (args.length < 3) {
                    reply(sender, "<red>Usage: /astra package install <https://url|file.zip> [sha256]</red>");
                    return;
                }
                String source = args[2];
                String checksum = args.length > 3 ? args[3] : null;
                reply(sender, "<gray>Installing <white>" + source + "</white>...</gray>");
                io.astra.runtime.ExecContext context = io.astra.runtime.ExecContext.forSender(
                    astra, "command", null, null, sender, sender instanceof org.bukkit.entity.Player p ? p : null,
                    sender instanceof org.bukkit.entity.Entity e ? e : null, null);
                astra.scheduler().runAsync(() -> {
                    var result = astra.packages().install(source, checksum, astra.http(), context);
                    // Loading registers listeners, so it belongs on the server thread.
                    astra.scheduler().runGlobal(() -> reply(sender, result.ok()
                        ? "<green>" + result.message() + "</green>"
                        : "<red>Package install failed: " + result.message() + "</red>"));
                });
            }
            case "load" -> {
                if (args.length < 3) {
                    reply(sender, "<red>Usage: /astra package load <name></red>");
                    return;
                }
                boolean ok = astra.packages().load(args[2]);
                reply(sender, ok ? "<green>Loaded package '" + args[2] + "'.</green>"
                    : "<red>Package '" + args[2] + "' was not loaded; check the name and the log.</red>");
            }
            case "unload" -> {
                if (args.length < 3) {
                    reply(sender, "<red>Usage: /astra package unload <name></red>");
                    return;
                }
                boolean ok = astra.packages().unload(args[2]);
                reply(sender, ok ? "<green>Unloaded package '" + args[2] + "'.</green>"
                    : "<red>No loaded package named '" + args[2] + "'.</red>");
            }
            default -> reply(sender, "<red>Usage: /astra package list|install <url> [sha256]|load|unload"
                + " <name></red>");
        }
    }

    private Path resolve(String name) {
        if (name.contains("/") || name.contains("\\")) {
            return Path.of(name);
        }
        Path inScripts = astra.scripts().scriptsFolder().resolve(name.endsWith(".ar") ? name : name + ".ar");
        if (Files.exists(inScripts)) return inScripts;
        return astra.scripts().scriptsFolder().resolve(name);
    }

    private void reply(CommandSender sender, String message) {
        String prefix = astra.config().language().prefix();
        String text = Strings.isBlank(prefix) ? message : prefix + " <reset>" + message;
        sender.sendMessage(astra.text().render(text));
    }

    // --------------------------------------------------------------- completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : SUBCOMMANDS) {
                if (!sender.hasPermission("astra.admin") && !sender.hasPermission("astra.command." + sub)) continue;
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(sub);
            }
            return out;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("package") || sub.equals("packages")) {
                for (String action : PACKAGE_SUBCOMMANDS) {
                    if (action.startsWith(prefix)) out.add(action);
                }
                return out;
            }
            if (sub.equals("trace")) out.add("off");
            if (sub.equals("performance") || sub.equals("perf")) out.add("reset");
            for (Script script : astra.scripts().scripts()) {
                if (script.name().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(script.name());
            }
            if (sub.equals("load")) {
                for (Path file : astra.scripts().discover()) {
                    String name = file.getFileName().toString();
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(name);
                }
            }
            return out;
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String prefix = args[2].toLowerCase(Locale.ROOT);
            if (sub.equals("package") || sub.equals("packages")) {
                for (var loadedPackage : astra.packages().loaded()) {
                    if (loadedPackage.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(loadedPackage.name());
                    }
                }
            }
        }
        return out;
    }

    /** Convenience for the console: {@code FileUtil} is used by the loader, referenced here for symmetry. */
    static String fileName(Path path) {
        return FileUtil.baseName(path);
    }
}
