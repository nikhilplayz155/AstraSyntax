package io.astra.runtime.script;

import io.astra.language.ast.Declaration;
import io.astra.language.ast.Span;

import java.util.List;

/**
 * A compiled script-defined command.
 *
 * @param name                command name without the slash
 * @param aliases             additional names
 * @param permission          required permission, or {@code null}
 * @param permissionMessage   message shown when the permission is missing
 * @param playerOnly          true when the console may not run it
 * @param consoleAllowed      true when the console may run it
 * @param description         shown in {@code /help}
 * @param usage               usage line
 * @param arguments           declared arguments
 * @param cooldownTicks       cooldown between uses (0 when none)
 * @param cooldownBypass      permission that ignores the cooldown
 * @param conditions          guards evaluated before the body
 * @param body                compiled statements
 * @param span                source location
 */
public record CompiledCommand(String name, List<String> aliases, String permission, String permissionMessage,
                              boolean playerOnly, boolean consoleAllowed, String description, String usage,
                              List<Declaration.CommandArgument> arguments, long cooldownTicks, String cooldownBypass,
                              List<CompiledCondition> conditions, List<CompiledStmt> body, Span span) {

    public CompiledCommand {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        body = body == null ? List.of() : List.copyOf(body);
    }
}
