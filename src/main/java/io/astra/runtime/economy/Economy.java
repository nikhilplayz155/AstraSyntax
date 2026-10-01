package io.astra.runtime.economy;

import java.util.UUID;

/**
 * The economy a script can reach, expressed in AstraSyntax's own terms.
 *
 * <p>The interface is keyed by {@link UUID} rather than by a platform type so the
 * runtime, the built-in vocabulary and the tests never depend on Vault or on a
 * server being present. {@code IntegrationManager.EconomyBridge} implements it over
 * Vault reflection; a server without Vault simply has no economy and the built-in
 * actions fail with a message that names the missing integration instead of a
 * {@code NullPointerException}.</p>
 *
 * <p>Implementations must be safe to call from the thread that owns the entity;
 * they never block on the database or the network.</p>
 */
public interface Economy {

    /** True when an economy provider is installed and usable. */
    boolean available();

    /** The provider's display name ("Essentials Economy", "CMI", ...). */
    String name();

    /** The plural currency name, or an empty string when the provider has none. */
    String currency();

    /** Formats an amount the way the provider does ("$1,200.00"). */
    String format(double amount);

    /** The balance of a player, or {@code -1} when it cannot be read. */
    double balance(UUID player);

    /** True when the player can afford {@code amount}. */
    default boolean has(UUID player, double amount) {
        double balance = balance(player);
        return balance >= 0 && balance >= amount;
    }

    /** Adds money. Returns false when the provider rejected the transaction. */
    boolean deposit(UUID player, double amount);

    /** Removes money. Returns false when the provider rejected the transaction. */
    boolean withdraw(UUID player, double amount);

    /** Sets the balance to an exact amount, reporting whether it worked. */
    default boolean set(UUID player, double amount) {
        double current = balance(player);
        if (current < 0) return false;
        double delta = amount - current;
        if (Math.abs(delta) < 1.0e-6) return true;
        return delta > 0 ? deposit(player, delta) : withdraw(player, -delta);
    }
}
