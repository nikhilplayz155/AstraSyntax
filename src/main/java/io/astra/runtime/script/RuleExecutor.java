package io.astra.runtime.script;

import io.astra.language.ast.Declaration;
import io.astra.platform.TaskHandle;
import io.astra.profiler.TraceSession;
import io.astra.runtime.scheduler.TaskRegistry;
import io.astra.runtime.scheduler.TaskRegistry;
import io.astra.runtime.ActionFailure;
import io.astra.runtime.ExecContext;
import io.astra.runtime.RuntimeServices;
import io.astra.runtime.Value;
import io.astra.runtime.action.ActionDefinition;
import io.astra.runtime.expression.ExpressionEvaluator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Executes compiled rules.
 *
 * <p>This is the only interpreter in AstraSyntax, and it interprets an already compiled
 * structure: no lexing, no parsing and no name lookups happen here. The order of work is
 * deliberate and fixed:</p>
 * <ol>
 *   <li>cheap event filters (material, entity type) - reject most events immediately</li>
 *   <li>declared conditions (permissions, cooldowns, data comparisons)</li>
 *   <li>actions, in order, with failures stopping the rule cleanly</li>
 * </ol>
 *
 * <p>Profiling and tracing are single boolean checks on the hot path so that both can be
 * disabled without a cost.</p>
 */
public final class RuleExecutor {

    private static final int MAX_FUNCTION_DEPTH = 16;

    private final RuntimeServices services;
    private final ExpressionEvaluator evaluator;
    private final TaskRegistry taskRegistry;

    public RuleExecutor(RuntimeServices services, TaskRegistry taskRegistry) {
        this.services = services;
        this.evaluator = new ExpressionEvaluator(services);
        this.taskRegistry = taskRegistry;
    }

    /** The registry that tracks script-owned scheduled tasks. */
    public TaskRegistry taskRegistry() {
        return taskRegistry;
    }

    public ExpressionEvaluator evaluator() {
        return evaluator;
    }

    public RuntimeServices services() {
        return services;
    }

    /** Result of one rule invocation. */
    public enum Result {
        /** The rule ran to completion. */
        EXECUTED,
        /** A filter or condition rejected the event. */
        SKIPPED,
        /** The rule failed; details were logged or traced. */
        FAILED
    }

    /**
     * Run a rule: filters, conditions then body.
     *
     * @param rule    the compiled rule
     * @param context execution context (already populated with the event actors)
     * @return what happened, for tracing and statistics
     */
    public Result execute(CompiledRule rule, ExecContext context) {
        boolean profiled = services.profiler() != null && services.profiler().enabled();
        long start = System.nanoTime();
        boolean failed = false;
        try {
            for (CompiledCondition filter : rule.filters()) {
                if (!filter.test(context, evaluator)) {
                    trace(context, "Filtered out before the body ran");
                    return Result.SKIPPED;
                }
            }
            for (CompiledCondition condition : rule.conditions()) {
                if (!condition.test(context, evaluator)) {
                    trace(context, "Condition failed: " + rule.description());
                    return Result.SKIPPED;
                }
            }
            trace(context, "Matched " + rule.span().toString());
            runBlock(rule.body(), context);
            return Result.EXECUTED;
        } catch (ActionFailure failure) {
            if (!failure.isSilent()) {
                services.logger().debug(() -> "Rule " + rule.id() + " stopped: " + failure.getMessage());
            }
            trace(context, "Stopped: " + failure.getMessage());
            return Result.SKIPPED;
        } catch (CompiledStmt.StopSignal stop) {
            trace(context, "Stopped early: " + stop.getMessage());
            return Result.EXECUTED;
        } catch (CompiledStmt.ReturnSignal signal) {
            trace(context, "Returned from the rule");
            return Result.EXECUTED;
        } catch (Throwable error) {
            failed = true;
            reportError(rule, context, error);
            return Result.FAILED;
        } finally {
            long micros = (System.nanoTime() - start) / 1000L;
            if (profiled) {
                services.profiler().record(rule.id(), rule.scriptName(), micros, failed);
                if (services.profiler().isSlow(rule.id())) {
                    services.logger().warnThrottled("slow:" + rule.id(),
                        "Slow rule " + rule.id() + " took " + micros + "us (threshold "
                            + services.profiler().slowThresholdMicros() + "us)");
                }
            }
            if (context.script() != null) context.script().stats().record(micros, failed);
            TraceSession session = context.trace();
            if (session != null && session.active()) session.finish(failed ? "failed" : "completed", micros);
        }
    }

    /** Run a block of compiled statements. */
    public void runBlock(List<CompiledStmt> statements, ExecContext context) {
        for (CompiledStmt statement : statements) {
            statement.run(context, this);
        }
    }

    /** Execute a single action with security enforcement and tracing. */
    public void invokeAction(CompiledStmt.Action action, ExecContext context) {
        services.security().checkAction(action.definition().id(), context);
        trace(context, "Action: " + action.definition().id());
        try {
            action.definition().executor().execute(context,
                io.astra.runtime.Arguments.of(context, action.arguments(), evaluator));
        } catch (ActionFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw new ActionFailure("Action '" + action.definition().id() + "' failed: " + error, false);
        }
    }

    /** Call a reusable action with a fresh variable frame. */
    public void invokeFunction(CompiledStmt.Call call, ExecContext context) {
        CompiledFunction function = call.function();
        Value depthValue = context.local("__astradepth");
        int nextDepth = depthValue == null || depthValue.isNull() ? 1 : (int) depthValue.asLong() + 1;
        if (nextDepth > MAX_FUNCTION_DEPTH) {
            throw ActionFailure.silent("Reusable action '" + function.name() + "' recursed more than "
                + MAX_FUNCTION_DEPTH + " times");
        }
        Map<String, Value> savedLocals = new LinkedHashMap<>();
        for (Declaration.Parameter parameter : function.parameters()) {
            Value provided = call.arguments().containsKey(parameter.name())
                ? evaluator.evaluate(call.arguments().get(parameter.name()), context)
                : Value.of(parameter.defaultValue());
            if (provided.isNull() && parameter.defaultValue() != null) provided = Value.of(parameter.defaultValue());
            if (provided.isNull() && !parameter.optional()) {
                throw ActionFailure.silent("Reusable action '" + function.name() + "' is missing '"
                    + parameter.name() + "'");
            }
            Value existing = context.local(parameter.name());
            savedLocals.put(parameter.name(), existing == null ? Value.NULL : existing);
            context.setLocal(parameter.name(), provided);
        }
        context.setLocal("__astradepth", Value.num(nextDepth));
        trace(context, "Call: " + function.name());
        try {
            runBlock(function.body(), context);
        } catch (CompiledStmt.ReturnSignal signal) {
            context.setLocal(function.name() + "$result", signal.value);
        } finally {
            context.setLocal("__astradepth", Value.num(nextDepth - 1));
            for (Map.Entry<String, Value> entry : savedLocals.entrySet()) {
                if (entry.getValue().isNull()) {
                    context.setLocal(entry.getKey(), Value.NULL);
                } else {
                    context.setLocal(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /**
     * Schedule the tail of a rule after a delay.
     *
     * <p>The continuation is anchored at the location the rule was executing in, so on
     * Folia it lands on the thread that owns that region; when the anchor is an entity
     * the entity scheduler is used instead. Without an anchor the global scheduler is
     * used. This is the mechanism behind {@code after 5 seconds: ...}.</p>
     */
    public void scheduleDelayed(ExecContext context, CompiledStmt.Delay delay) {
        String scriptName = context.script() == null ? "unknown" : context.script().name();
        String description = "delay-" + System.nanoTime();
        Runnable task = () -> {
            Script script = context.script();
            if (script != null && !script.state().isActive()) {
                services.logger().debug(() -> "Delayed continuation of '" + script.name()
                    + "' skipped: the script is no longer active");
                return;
            }
            try {
                runBlock(delay.body(), context);
            } catch (ActionFailure failure) {
                if (!failure.isSilent()) {
                    services.logger().warn("Delayed action failed in " + scriptName + ": " + failure.getMessage());
                }
            } catch (CompiledStmt.StopSignal | CompiledStmt.ReturnSignal signal) {
                // Deliberate early exit inside a delayed block.
            } catch (Throwable error) {
                services.logger().error("Delayed action in " + scriptName + " failed: " + services.logger().describe(error));
            }
        };
        long ticks = Math.max(0L, delay.ticks());
        Entity entity = context.actor();
        Location location = context.location();
        TaskHandle handle;
        if (entity != null) {
            handle = services.scheduler().runOnEntityLater(entity, task, ticks);
        } else if (location != null && location.getWorld() != null) {
            handle = services.scheduler().runAtLocationLater(location.getWorld(), location.getX(), location.getY(),
                location.getZ(), task, ticks);
        } else {
            handle = services.scheduler().runGlobalLater(task, ticks);
        }
        if (taskRegistry != null) {
            taskRegistry.register(scriptName, description, handle, services.security().policy().maxTasksPerScript());
        }
    }

    void warnLoopLimit(ExecContext context, int limit) {
        services.logger().warnThrottled("loop-limit:" + (context.script() == null ? "?" : context.script().name()),
            "A loop hit the configured iteration limit (" + limit + "); the rest was skipped");
    }

    private void trace(ExecContext context, String message) {
        TraceSession session = context.trace();
        if (session != null && session.active()) session.step(message);
    }

    private void reportError(CompiledRule rule, ExecContext context, Throwable error) {
        String where = rule == null ? "unknown rule" : rule.id();
        services.logger().error("Rule " + where + " failed: " + services.logger().describe(error));
        Script script = context.script();
        if (script != null) {
            script.setLastError(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    /** Exposes the evaluator for modules that need to evaluate expressions themselves. */
    public Value evaluate(io.astra.language.ast.Expr expr, ExecContext context) {
        return evaluator.evaluate(expr, context);
    }

    /** Convenience: run a body with tracing already handled by the caller. */
    public void executeBody(List<CompiledStmt> body, ExecContext context) {
        runBlock(body, context);
    }
}
