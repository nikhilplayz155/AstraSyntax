package io.astra.runtime.script;

import io.astra.language.ast.Declaration;
import io.astra.language.ast.Span;

import java.util.List;

/**
 * A reusable action compiled once and called from any rule.
 *
 * @param name       the action name used in scripts
 * @param parameters declared parameters
 * @param body       compiled statements
 * @param span       source location
 */
public record CompiledFunction(String name, List<Declaration.Parameter> parameters, List<CompiledStmt> body,
                              Span span) {

    public CompiledFunction {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        body = body == null ? List.of() : List.copyOf(body);
    }
}
