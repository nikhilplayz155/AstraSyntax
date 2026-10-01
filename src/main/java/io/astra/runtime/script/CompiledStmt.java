package io.astra.runtime.script;

import io.astra.language.ast.Expr;
import io.astra.language.ast.Span;
import io.astra.runtime.ActionFailure;
import io.astra.runtime.ExecContext;
import io.astra.runtime.Targets;
import io.astra.runtime.Value;
import io.astra.runtime.action.ActionDefinition;
import io.astra.runtime.expression.ExpressionEvaluator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compiled statement.
 *
 * <p>Statements are already resolved: an action statement carries its
 * {@link ActionDefinition}, a call statement its {@link CompiledFunction}. Executing a
 * rule therefore never parses text or looks anything up by name - the only runtime
 * work is evaluating the argument expressions.</p>
 */
public sealed interface CompiledStmt
    permits CompiledStmt.Action, CompiledStmt.If, CompiledStmt.Repeat, CompiledStmt.Delay, CompiledStmt.Cancel,
            CompiledStmt.Call, CompiledStmt.Return, CompiledStmt.Stop {

    /** Execute this statement. */
    void run(ExecContext context, RuleExecutor executor);

    /** A resolved action call. */
    record Action(ActionDefinition definition, Map<String, Expr> arguments, Span span) implements CompiledStmt {
        @Override
        public void run(ExecContext context, RuleExecutor executor) {
            executor.invokeAction(this, context);
        }

        /** Argument names, in declaration order (used by {@code /astra explain}). */
        public List<String> argumentNames() {
            return List.copyOf(new LinkedHashMap<>(arguments).keySet());
        }
    }

    /** A conditional branch. */
    record If(List<CompiledCondition> conditions, List<CompiledStmt> then, List<CompiledStmt> otherwise,
              Span span) implements CompiledStmt {
        @Override
        public void run(ExecContext context, RuleExecutor executor) {
            boolean matched = true;
            for (CompiledCondition condition : conditions) {
                if (!condition.test(context, executor.evaluator())) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                executor.runBlock(then, context);
            } else {
                executor.runBlock(otherwise, context);
            }
        }
    }

    /** A repeat/for-each loop. */
    record Repeat(Expr source, String variable, List<CompiledStmt> body, Span span) implements CompiledStmt {
        @Override
        public void run(ExecContext context, RuleExecutor executor) {
            int maxIterations = context.services().security().policy().maxLoopIterations();
            Value sourceValue = executor.evaluator().evaluate(source, context);
            if (sourceValue.type() == io.astra.runtime.ValueType.LIST) {
                List<Value> values = sourceValue.asList();
                int index = 0;
                for (Value value : values) {
                    if (index++ >= maxIterations) {
                        executor.warnLoopLimit(context, maxIterations);
                        break;
                    }
                    context.setLocal(variable, value);
                    executor.runBlock(body, context);
                    if (context.isTaskCancelled()) break;
                }
                return;
            }
            long count = sourceValue.asLong();
            if (count > maxIterations) {
                executor.warnLoopLimit(context, maxIterations);
                count = maxIterations;
            }
            for (long i = 0; i < count; i++) {
                context.setLocal(variable, Value.num(i));
                executor.runBlock(body, context);
                if (context.isTaskCancelled()) break;
            }
        }
    }

    /** A delayed continuation: the remainder of the rule runs later on the anchor thread. */
    record Delay(long ticks, List<CompiledStmt> body, Span span) implements CompiledStmt {
        @Override
        public void run(ExecContext context, RuleExecutor executor) {
            executor.scheduleDelayed(context, this);
        }
    }

    /** {@code cancel event} / {@code cancel task}. */
    record Cancel(String scope, Span span) implements CompiledStmt {
        @Override
        public void run(ExecContext context, RuleExecutor executor) {
            if ("task".equals(scope)) {
                context.cancelTask();
                return;
            }
            if (!context.cancelEvent()) {
                throw ActionFailure.silent("This event cannot be cancelled");
            }
        }
    }

    /** A call to a reusable action. */
    record Call(CompiledFunction function, Map<String, Expr> arguments, Span span) implements CompiledStmt {
        @Override
        public void run(ExecContext context, RuleExecutor executor) {
            executor.invokeFunction(this, context);
        }
    }

    /** {@code return value} from a reusable action. */
    record Return(Expr value, Span span) implements CompiledStmt {
        @Override
        public void run(ExecContext context, RuleExecutor executor) {
            Value result = value == null ? Value.NULL : executor.evaluator().evaluate(value, context);
            context.setLocal("return", result);
            throw new ReturnSignal(result);
        }
    }

    /** {@code stop} - abort the rest of the body. */
    record Stop(String reason, Span span) implements CompiledStmt {
        @Override
        public void run(ExecContext context, RuleExecutor executor) {
            throw new StopSignal(reason);
        }
    }

    /** Internal control-flow signal for {@code return}. */
    final class ReturnSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final transient Value value;

        ReturnSignal(Value value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    /** Internal control-flow signal for {@code stop}. */
    final class StopSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StopSignal(String reason) {
            super(reason, null, false, false);
        }
    }
}
