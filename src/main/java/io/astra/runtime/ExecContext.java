package io.astra.runtime;

import io.astra.data.DataStore;
import io.astra.profiler.TraceSession;
import io.astra.runtime.script.CompiledRule;
import io.astra.runtime.script.Script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

/**
 * The state of one rule execution.
 *
 * <p>One context per invocation, never shared: it carries who the rule is running for,
 * the values bound by the trigger, the variables of the current block, and the
 * cancellation request that an action may raise. Reusing a context across invocations is
 * the classic source of "the script used the wrong player" bugs, so the runtime creates a
 * fresh one for every event, command and timer tick.</p>
 */
public final class ExecContext {

    private final RuntimeServices services;
    private final Event event;
    private final String triggerId;
    private final Script script;
    private final CompiledRule rule;
    private final Entity actor;
    private final Player player;
    private final Entity secondary;
    private final World world;
    private final Location location;
    private final CommandSender sender;
    private final TraceSession tracer;

    private final Map<String, Value> arguments = new LinkedHashMap<>();
    private final Map<String, Value> variables = new LinkedHashMap<>();
    private final long startedNanos = System.nanoTime();
    private boolean cancelRequested;
    private boolean taskCancelRequested;
    private boolean failed;

    public ExecContext(RuntimeServices services, Event event, String triggerId, Script script, CompiledRule rule,
                       Entity actor, Player player, Entity secondary, World world, Location location,
                       CommandSender sender, TraceSession tracer) {
        this.services = services;
        this.event = event;
        this.triggerId = triggerId;
        this.script = script;
        this.rule = rule;
        this.actor = actor;
        this.player = player != null ? player : (actor instanceof Player p ? p : null);
        this.secondary = secondary;
        this.world = world != null ? world : (location != null && location.getWorld() != null
            ? location.getWorld() : (actor != null ? actor.getWorld() : null));
        this.location = location != null ? location : (actor != null ? actor.getLocation() : null);
        this.sender = sender;
        this.tracer = tracer;
    }

    /** A context for an event trigger. */
    public static ExecContext forEvent(RuntimeServices services, Event event, String triggerId, Script script,
                                       CompiledRule rule, Entity actor, Player player, Entity secondary,
                                       World world, Location location, TraceSession tracer) {
        return new ExecContext(services, event, triggerId, script, rule, actor, player, secondary, world, location,
            null, tracer);
    }

    /** A context for a command, console or manual run. */
    public static ExecContext forSender(RuntimeServices services, String triggerId, Script script, CompiledRule rule,
                                        CommandSender sender, Player player, Entity actor, TraceSession tracer) {
        return new ExecContext(services, null, triggerId, script, rule, actor, player, null, null, null, sender,
            tracer);
    }

    /** A context for a timer tick. */
    public static ExecContext forTimer(RuntimeServices services, String triggerId, Script script, CompiledRule rule,
                                       TraceSession tracer) {
        return new ExecContext(services, null, triggerId, script, rule, null, null, null, null, null, null, tracer);
    }

    // ------------------------------------------------------------- accessors

    public RuntimeServices services() {
        return services;
    }

    public DataStore data() {
        return services.data();
    }

    public Event event() {
        return event;
    }

    public String triggerId() {
        return triggerId;
    }

    public Script script() {
        return script;
    }

    public CompiledRule rule() {
        return rule;
    }

    public Entity actor() {
        return actor;
    }

    public Player player() {
        return player;
    }

    public Entity secondary() {
        return secondary;
    }

    public World world() {
        return world;
    }

    public Location location() {
        return location;
    }

    public CommandSender sender() {
        return sender;
    }

    public TraceSession tracer() {
        return tracer;
    }

    /** Alias used by the executor: the active trace session, or {@code null}. */
    public TraceSession trace() {
        return tracer;
    }

    public long elapsedMicros() {
        return (System.nanoTime() - startedNanos) / 1000L;
    }

    public boolean failed() {
        return failed;
    }

    public void markFailed() {
        this.failed = true;
    }

    /** True when there is a player to act on. */
    public boolean hasPlayer() {
        return player != null;
    }

    /**
     * The player, or a clean rule failure.
     *
     * <p>Used by actions that only make sense for players: a console-run rule that says
     * {@code heal player} fails with "there is no player here" instead of throwing a
     * NullPointerException into the server log.</p>
     */
    public Player requirePlayer() {
        if (player == null) {
            throw ActionFailure.silent("This rule needs a player, but none is available here");
        }
        return player;
    }

    public Entity requireEntity() {
        if (actor == null) {
            throw ActionFailure.silent("This rule needs an entity, but none is available here");
        }
        return actor;
    }

    // ------------------------------------------------------------- variables

    public void setArgument(String name, Value value) {
        if (name != null) arguments.put(name, value == null ? Value.NULL : value);
    }

    public Value argument(String name) {
        return arguments.getOrDefault(name, Value.NULL);
    }

    public Map<String, Value> arguments() {
        return arguments;
    }

    /** Bind a block-local value ({@code repeat ... as i} creates one). */
    public void setLocal(String name, Value value) {
        if (name != null) variables.put(name, value == null ? Value.NULL : value);
    }

    /** A block-local value, or {@link Value#NULL}. */
    public Value local(String name) {
        return variables.getOrDefault(name, Value.NULL);
    }

    public boolean hasLocal(String name) {
        return variables.containsKey(name);
    }

    public void setVariable(String name, Value value) {
        setLocal(name, value);
    }

    public Value variable(String name) {
        return local(name);
    }

    public boolean hasVariable(String name) {
        return hasLocal(name);
    }

    public Map<String, Value> variables() {
        return variables;
    }

    /**
     * Resolve a bare name mentioned in a script.
     *
     * <p>The aliases exist so that "them", "him" and "her" work in natural-language rules
     * with the same meaning they have in a sentence: whoever the rule is about.</p>
     */
    public Value resolveName(String name) {
        return resolve(name);
    }

    /** Resolve a bare name mentioned in a script. */
    public Value resolve(String name) {
        if (name == null) return Value.NULL;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        if (variables.containsKey(key)) return variables.get(key);
        return switch (key) {
            case "player", "them", "him", "her", "self" -> player == null ? Value.NULL : Value.player(player);
            case "actor", "entity", "target", "mob" -> actor == null ? Value.NULL : Value.entity(actor);
            case "attacker", "damager", "secondary", "killer" -> secondary == null ? Value.NULL
                : Value.entity(secondary);
            case "victim", "killed" -> actor == null ? Value.NULL : Value.entity(actor);
            case "world" -> world == null ? Value.NULL : Value.str(world.getName());
            case "console", "server" -> Value.str("console");
            case "everyone", "all players", "everybody" -> Value.str("everyone");
            case "sender" -> sender == null ? Value.NULL : Value.str(sender.getName());
            case "now" -> Value.num(System.currentTimeMillis());
            default -> Value.NULL;
        };
    }

    // ------------------------------------------------------------ cancellation

    /** Ask for the event to be cancelled; the bus applies it after the body runs. */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /**
     * Request event cancellation.
     *
     * @return true when there is a cancellable event to cancel, so a script can report
     *         "this trigger cannot be cancelled" instead of silently doing nothing
     */
    public boolean cancelEvent() {
        if (event == null) return false;
        this.cancelRequested = true;
        return true;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void cancelTask() {
        this.taskCancelRequested = true;
    }

    public boolean isTaskCancelled() {
        return taskCancelRequested;
    }

    public void requestTaskCancel() {
        cancelTask();
    }

    public boolean isTaskCancelRequested() {
        return isTaskCancelled();
    }

    /** True when the underlying event is already cancelled by another plugin. */
    public boolean isEventCancelled() {
        return event instanceof Cancellable cancellable && cancellable.isCancelled();
    }

    /** Applies a cancellation request to the Bukkit event, if there is one. */
    public void applyCancellation() {
        if (cancelRequested && event instanceof Cancellable cancellable) cancellable.setCancelled(true);
    }

    /** A short description used by traces: "player join for Notch". */
    public String describe() {
        StringBuilder out = new StringBuilder(triggerId == null ? "rule" : triggerId);
        if (player != null) out.append(" for ").append(player.getName());
        else if (actor != null) out.append(" for ").append(actor.getType().name().toLowerCase(java.util.Locale.ROOT));
        return out.toString();
    }

    /** A stable description of an entity, used in logs, traces and entity values. */
    public static String describeEntity(Entity entity) {
        if (entity == null) return "nothing";
        if (entity instanceof Player player) return "player " + player.getName();
        String type = entity.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        String custom = entity.getCustomName();
        return custom == null || custom.isBlank() ? type : type + " named " + custom;
    }

    /** Convenience used by actions that need the surrounding players. */
    public List<Player> worldPlayers() {
        return world == null ? List.of() : world.getPlayers();
    }
}
