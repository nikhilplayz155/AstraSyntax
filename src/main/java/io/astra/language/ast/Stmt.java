package io.astra.language.ast;

import java.util.List;
import java.util.Map;

/**
 * A statement in a rule body.
 *
 * <p>Control flow is explicit in the AST - {@link If}, {@link Repeat}, {@link Delay},
 * {@link Cancel}, {@link Call}, {@link Return}, {@link Stop} - so the runtime never has to
 * re-inspect text to decide what to do. Everything else is an {@link Action} whose id
 * resolves to a registered behaviour.</p>
 */
public sealed interface Stmt extends Node {

    /** A sequence of statements. */
    record Block(List<Stmt> statements, Span span) implements Stmt {
        public Block {
            statements = statements == null ? List.of() : List.copyOf(statements);
        }

        public boolean isEmpty() {
            return statements.isEmpty();
        }
    }

    /** A registered action, for example {@code give}. */
    record Action(String id, Map<String, Expr> arguments, Span span) implements Stmt { }

    /** Conditional execution. */
    record If(List<Cond> conditions, Block then, Block otherwise, Span span) implements Stmt {
        public If {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }

    /** A bounded loop: {@code repeat 5 times} / {@code repeat 10 times as i}. */
    record Repeat(Expr count, String variable, Block body, long limit, Span span) implements Stmt { }

    /** A pause with a continuation: {@code wait 5 seconds: ...}. */
    record Delay(long ticks, String raw, Block body, Span span) implements Stmt { }

    /** {@code cancel event} / {@code cancel task}. */
    record Cancel(String scope, Span span) implements Stmt { }

    /** A call to a reusable action declared in the same script. */
    record Call(String function, Map<String, Expr> arguments, Span span) implements Stmt { }

    /** {@code return [value]}. */
    record Return(Expr value, Span span) implements Stmt { }

    /** {@code stop}. */
    record Stop(String reason, Span span) implements Stmt { }
}
