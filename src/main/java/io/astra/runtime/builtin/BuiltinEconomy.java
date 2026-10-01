package io.astra.runtime.builtin;

import io.astra.runtime.ActionFailure;
import io.astra.runtime.Arguments;
import io.astra.runtime.ExecContext;
import io.astra.runtime.Value;
import io.astra.runtime.action.ActionDefinition;
import io.astra.runtime.action.ActionRegistry;
import io.astra.runtime.condition.ConditionDefinition;
import io.astra.runtime.condition.ConditionRegistry;
import io.astra.runtime.economy.Economy;
import io.astra.runtime.expression.ExpressionDefinition;
import io.astra.runtime.expression.ExpressionRegistry;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Economy vocabulary: paying, charging, checking and reading balances.
 *
 * <p>Every entry resolves the economy through {@code context.services().economy()} and
 * fails with a clear message when no provider is installed (or when
 * {@code features.economy} is switched off, which the loader already refuses at
 * compile time). Vault is never a hard dependency: without it the actions report
 * "no economy provider is available" instead of breaking script loading.</p>
 */
public final class BuiltinEconomy {

    private BuiltinEconomy() {
    }

    /** Register the economy actions, conditions and expressions. */
    public static void registerAll(ActionRegistry actions, ConditionRegistry conditions,
                                   ExpressionRegistry expressions) {
        registerActions(actions);
        registerConditions(conditions);
        registerExpressions(expressions);
    }

    private static void registerActions(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("give-money", "Pays money to players")
            .syntax("pay <amount:number> to <who:target>")
            .syntax("give money <amount:number> to <who:target>")
            .syntax("deposit <amount:number> for <who:target>")
            .syntax("give <who:target> <amount:number> money")
            .example("pay 250 to player")
            .executes((context, arguments) -> {
                Economy economy = require(context);
                double amount = Math.max(0, arguments.number("amount"));
                List<Player> players = recipients(context, arguments);
                if (players.isEmpty()) throw new ActionFailure("No player to pay");
                for (Player player : players) {
                    if (!economy.deposit(player.getUniqueId(), amount)) {
                        throw new ActionFailure("The economy provider refused to pay "
                            + economy.format(amount) + " to " + player.getName());
                    }
                }
            })
            .build());

        registry.register(ActionDefinition.builder("take-money", "Charges players")
            .syntax("charge <amount:number> to <who:target>")
            .syntax("withdraw <amount:number> from <who:target>")
            .syntax("take money <amount:number> from <who:target>")
            .example("charge 100 to player")
            .executes((context, arguments) -> {
                Economy economy = require(context);
                double amount = Math.max(0, arguments.number("amount"));
                List<Player> players = recipients(context, arguments);
                if (players.isEmpty()) throw new ActionFailure("No player to charge");
                for (Player player : players) {
                    if (!economy.withdraw(player.getUniqueId(), amount)) {
                        throw new ActionFailure("The economy provider refused to charge "
                            + economy.format(amount) + " to " + player.getName());
                    }
                }
            })
            .build());

        registry.register(ActionDefinition.builder("set-balance", "Sets a player's balance")
            .syntax("set balance of <who:target> to <amount:number>")
            .syntax("set <who:target> balance to <amount:number>")
            .example("set balance of player to 1000")
            .executes((context, arguments) -> {
                Economy economy = require(context);
                double amount = Math.max(0, arguments.number("amount"));
                List<Player> players = recipients(context, arguments);
                if (players.isEmpty()) throw new ActionFailure("No player whose balance could be set");
                for (Player player : players) {
                    if (!economy.set(player.getUniqueId(), amount)) {
                        throw new ActionFailure("The economy provider refused to set the balance of "
                            + player.getName());
                    }
                }
            })
            .build());
    }

    private static void registerConditions(ConditionRegistry registry) {
        registry.register(ConditionDefinition.builder("has-money", "Checks whether players can afford something")
            .syntax("has money <amount:number>")
            .syntax("has <amount:number> money")
            .syntax("<who:target> has money <amount:number>")
            .syntax("<who:target> can afford <amount:number>")
            .example("if player can afford 500:")
            .tests((context, arguments) -> {
                Economy economy = context.services().economy();
                if (economy == null || !economy.available()) return false;
                double amount = arguments.number("amount");
                return entities(context, arguments).stream()
                    .anyMatch(entity -> economy.has(entity.getUniqueId(), amount));
            })
            .build());
    }

    private static void registerExpressions(ExpressionRegistry registry) {
        registry.register(ExpressionDefinition.builder("balance-of", "The balance of a player")
            .syntax("balance of <who:target>")
            .syntax("money of <who:target>")
            .syntax("<who:target>'s balance")
            .returns("decimal")
            .example("tell player \"You have %balance of player%\"")
            .evaluates((context, arguments) -> {
                Economy economy = context.services().economy();
                if (economy == null || !economy.available()) return Value.dec(0);
                for (Entity entity : entities(context, arguments)) {
                    double balance = economy.balance(entity.getUniqueId());
                    if (balance >= 0) return Value.dec(balance);
                }
                return Value.dec(0);
            })
            .build());

        registry.register(ExpressionDefinition.builder("balance-formatted", "A player's balance, formatted by the economy provider")
            .syntax("formatted balance of <who:target>")
            .returns("text")
            .example("tell player \"You have %formatted balance of player%\"")
            .evaluates((context, arguments) -> {
                Economy economy = context.services().economy();
                if (economy == null || !economy.available()) return Value.str("");
                for (Entity entity : entities(context, arguments)) {
                    double balance = economy.balance(entity.getUniqueId());
                    if (balance >= 0) return Value.str(economy.format(balance));
                }
                return Value.str("");
            })
            .build());
    }

    // ------------------------------------------------------------------ helpers

    /** The installed economy, or a failure that says exactly what is missing. */
    private static Economy require(ExecContext context) {
        Economy economy = context.services().economy();
        if (economy == null || !economy.available()) {
            throw new ActionFailure("No economy provider is available - install Vault and an economy plugin, "
                + "or enable integrations.vault in integrations.yml");
        }
        return economy;
    }

    /** Players named by the {@code who} argument, falling back to the event's player. */
    private static List<Player> recipients(ExecContext context, Arguments arguments) {
        List<Player> players = new ArrayList<>();
        for (Entity entity : entities(context, arguments)) {
            if (entity instanceof Player player) players.add(player);
        }
        return players;
    }

    private static List<Entity> entities(ExecContext context, Arguments arguments) {
        if (arguments != null && arguments.has("who")) {
            List<Player> named = io.astra.runtime.Targets.players(arguments.value("who"), context);
            if (!named.isEmpty()) return List.copyOf(named);
            Entity single = io.astra.runtime.Targets.entity(arguments.value("who"), context);
            if (single != null) return List.of(single);
        }
        Entity actor = context.actor();
        if (actor != null) return List.of(actor);
        Player player = context.player();
        return player == null ? List.of() : List.of(player);
    }
}
