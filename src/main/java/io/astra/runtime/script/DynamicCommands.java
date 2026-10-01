package io.astra.runtime.script;

import io.astra.logging.AstraLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Registers the commands that scripts declare with {@code command /heal: ...}.
 *
 * <p>Script commands are dynamic - they appear and disappear with a reload - so they cannot
 * live in {@code plugin.yml}. They are registered into the server's {@link CommandMap}
 * through the same accessor every plugin uses, under the {@code astra} fallback prefix so a
 * name clash with another plugin never silently breaks either side.</p>
 *
 * <p>Unregistering is as important as registering: {@link #unregisterScript(String)}
 * removes exactly the commands of one script, which is why a reload cannot leave a stale
 * {@code /heal} behind pointing at a rule that no longer exists.</p>
 */
public final class DynamicCommands {

    /** Everything known about one registered script command. */
    private record Registered(String scriptName, CompiledCommand command, Command bukkitCommand) { }

    private final AstraLogger logger;
    private final Map<String, Registered> byLabel = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final String fallbackPrefix;

    private ScriptManager manager;
    private volatile CommandMap commandMap;
    private volatile boolean unavailableLogged;

    public DynamicCommands(AstraLogger logger, String fallbackPrefix) {
        this.logger = logger;
        this.fallbackPrefix = fallbackPrefix == null || fallbackPrefix.isBlank() ? "astra" : fallbackPrefix;
    }

    /** Link the manager that executes the command bodies. */
    public void setManager(ScriptManager manager) {
        this.manager = manager;
    }

    /** Every label currently registered. */
    public Set<String> labels() {
        return Set.copyOf(byLabel.keySet());
    }

    /** Labels belonging to one script. */
    public List<String> labelsOf(String scriptName) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Registered> entry : byLabel.entrySet()) {
            if (entry.getValue().scriptName().equals(scriptName)) out.add(entry.getKey());
        }
        return out;
    }

    /** Register every command of one script, replacing any previous registration. */
    public void registerAll(Script script, List<CompiledCommand> commands) {
        unregisterScript(script.name());
        for (CompiledCommand command : commands) {
            register(script, command);
        }
    }

    private void register(Script script, CompiledCommand command) {
        CommandMap map = commandMap();
        if (map == null) {
            if (!unavailableLogged) {
                unavailableLogged = true;
                logger.warn("Script commands are unavailable on this server: the command map could not be "
                    + "reached. Everything else (events, timers, actions) keeps working.");
            }
            return;
        }
        ScriptCommand bukkitCommand = new ScriptCommand(command, script.name());
        try {
            map.register(fallbackPrefix, bukkitCommand);
        } catch (Throwable error) {
            logger.warn("Could not register /" + command.name() + " from '" + script.name() + "': "
                + logger.describe(error));
            return;
        }
        List<String> names = new ArrayList<>();
        names.add(command.name().toLowerCase(Locale.ROOT));
        for (String alias : command.aliases()) {
            if (alias != null && !alias.isBlank()) names.add(alias.toLowerCase(Locale.ROOT));
        }
        for (String name : names) {
            Registered previous = byLabel.put(name, new Registered(script.name(), command, bukkitCommand));
            if (previous != null && !previous.scriptName().equals(script.name())) {
                logger.warn("Script command /" + name + " of '" + script.name() + "' replaced the one from '"
                    + previous.scriptName() + "'");
            }
        }
        logger.debug(() -> "Registered command /" + command.name() + " from " + script.name());
    }

    /** Remove every command a script registered. */
    public void unregisterScript(String scriptName) {
        List<String> labels = labelsOf(scriptName);
        if (labels.isEmpty()) return;
        CommandMap map = commandMap();
        for (String label : labels) {
            Registered registered = byLabel.remove(label);
            if (registered == null) continue;
            if (map != null) {
                try {
                    Command existing = map.getCommand(label);
                    if (existing instanceof ScriptCommand scriptCommand && scriptCommand.owner().equals(scriptName)) {
                        existing.unregister(map);
                    }
                } catch (Throwable ignored) {
                    // Some implementations refuse to unregister; the label map above is the
                    // source of truth either way and stops dispatching to the script.
                }
            }
        }
        cooldowns.keySet().removeIf(key -> key.startsWith(scriptName + ":"));
        logger.debug(() -> "Unregistered " + labels.size() + " command(s) of " + scriptName);
    }

    /** Remove every script command (shutdown). */
    public void unregisterAll() {
        for (String script : Set.copyOf(scriptNames())) {
            unregisterScript(script);
        }
    }

    private Set<String> scriptNames() {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Registered registered : byLabel.values()) names.add(registered.scriptName());
        return names;
    }

    /** The compiled command behind a label, or {@code null}. */
    CompiledCommand command(String label) {
        Registered registered = byLabel.get(label == null ? "" : label.toLowerCase(Locale.ROOT));
        return registered == null ? null : registered.command();
    }

    /** True when the label is owned by a script. */
    public boolean handles(String label) {
        return label != null && byLabel.containsKey(label.toLowerCase(Locale.ROOT));
    }

    /**
     * Run a script command.
     *
     * @return true when a script command handled the request
     */
    public boolean dispatch(CommandSender sender, String label, String[] args) {
        Registered registered = byLabel.get(label == null ? "" : label.toLowerCase(Locale.ROOT));
        if (registered == null || manager == null) return false;
        Script script = manager.script(registered.scriptName());
        if (script == null) {
            unregisterScript(registered.scriptName());
            return false;
        }
        if (!checkCooldown(registered, sender)) return true;
        manager.runCommand(registered.command(), script, sender, label, args == null ? new String[0] : args);
        return true;
    }

    /** Enforce the declared cooldown; returns false when the sender must wait. */
    private boolean checkCooldown(Registered registered, CommandSender sender) {
        long cooldown = registered.command().cooldownTicks();
        if (cooldown <= 0) return true;
        String bypass = registered.command().cooldownBypass();
        if (bypass != null && !bypass.isBlank() && sender.hasPermission(bypass)) return true;
        String key = registered.scriptName() + ":" + registered.command().name().toLowerCase(Locale.ROOT);
        UUID uuid = sender instanceof Player player ? player.getUniqueId()
            : UUID.nameUUIDFromBytes("console".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(key + ":" + uuid, 0L);
        if (now < readyAt) {
            long seconds = Math.max(1L, (readyAt - now + 999L) / 1000L);
            sender.sendMessage(io.astra.util.Strings.trimToEmpty("\u00a7cPlease wait " + seconds + "s"));
            return false;
        }
        cooldowns.put(key + ":" + uuid, now + cooldown * 50L);
        return true;
    }

    /** The Bukkit command map, resolved once. */
    private CommandMap commandMap() {
        CommandMap cached = commandMap;
        if (cached != null) return cached;
        try {
            Object server = Bukkit.getServer();
            if (server == null) return null;
            Object map = io.astra.util.Reflect.invokeQuietly(server, "getCommandMap", 0);
            if (map instanceof CommandMap resolved) {
                commandMap = resolved;
                return resolved;
            }
        } catch (Throwable error) {
            logger.debug("Could not reach the command map: " + error.getMessage());
        }
        return null;
    }

    /** Suggestions for tab completion: labels, then declared argument names. */
    List<String> suggestions(CommandSender sender, String label, String[] args) {
        Registered registered = byLabel.get(label == null ? "" : label.toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        if (registered == null) return out;
        if (args == null || args.length <= 1) {
            return out;
        }
        int index = args.length - 2;
        List<io.astra.language.ast.Declaration.CommandArgument> declared = registered.command().arguments();
        if (index >= 0 && index < declared.size()) {
            String type = declared.get(index).type() == null ? "" : declared.get(index).type().toLowerCase(Locale.ROOT);
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (type.contains("player") || type.contains("target")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(player.getName());
                }
            } else if (type.contains("world")) {
                for (org.bukkit.World world : Bukkit.getWorlds()) {
                    if (world.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(world.getName());
                }
            }
        }
        return out;
    }

    /** Diagnostic summary for {@code /astra info}. */
    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Registered> entry : byLabel.entrySet()) {
            out.put(entry.getKey(), entry.getValue().scriptName() + " (" + entry.getValue().command().name() + ")");
        }
        return out;
    }

    /** The Bukkit {@link Command} wrapper around one compiled script command. */
    private final class ScriptCommand extends Command {

        private final String owner;

        private ScriptCommand(CompiledCommand compiled, String owner) {
            super(compiled.name(), io.astra.util.Strings.trimToEmpty(compiled.description()),
                io.astra.util.Strings.trimToEmpty(compiled.usage()),
                new ArrayList<>(compiled.aliases()));
            this.owner = owner;
        }

        String owner() {
            return owner;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return dispatch(sender, commandLabel, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return suggestions(sender, alias, args);
        }
    }
}
