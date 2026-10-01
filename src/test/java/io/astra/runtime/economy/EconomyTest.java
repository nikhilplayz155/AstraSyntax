package io.astra.runtime.economy;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the economy contract the built-in actions rely on, independent of Vault:
 * affordability checks, the deposit/withdraw maths behind {@code set}, and the
 * "no provider" behaviour that must never look like a zero balance.
 */
class EconomyTest {

    private final UUID alex = UUID.randomUUID();

    @Test
    void balanceAndAffordabilityFollowTheProvider() {
        FakeEconomy economy = new FakeEconomy(120.5);
        assertEquals(120.5, economy.balance(alex));
        assertTrue(economy.has(alex, 120.0));
        assertTrue(economy.has(alex, 120.5));
        assertFalse(economy.has(alex, 120.6));
    }

    @Test
    void unavailableBalancesNeverCountAsAffordable() {
        FakeEconomy economy = new FakeEconomy(-1);
        assertFalse(economy.has(alex, 0));
        assertFalse(economy.has(alex, 10));
    }

    @Test
    void setAdjustsByDepositingOrWithdrawingTheDifference() {
        FakeEconomy economy = new FakeEconomy(100);
        assertTrue(economy.set(alex, 250));
        assertEquals(250, economy.balance(alex));
        assertEquals(1, economy.deposits);
        assertEquals(0, economy.withdrawals);

        assertTrue(economy.set(alex, 40));
        assertEquals(40, economy.balance(alex));
        assertEquals(1, economy.deposits);
        assertEquals(1, economy.withdrawals);

        // Setting the same balance again must not touch the provider at all.
        assertTrue(economy.set(alex, 40));
        assertEquals(1, economy.deposits);
        assertEquals(1, economy.withdrawals);
    }

    @Test
    void setOnAnUnreadableBalanceFailsInsteadOfInventingMoney() {
        FakeEconomy economy = new FakeEconomy(-1);
        assertFalse(economy.set(alex, 10));
        assertEquals(0, economy.deposits);
    }

    @Test
    void rejectedTransactionsAreReported() {
        FakeEconomy economy = new FakeEconomy(10);
        economy.acceptTransactions = false;
        assertFalse(economy.deposit(alex, 5));
        assertFalse(economy.withdraw(alex, 5));
        assertEquals(10, economy.balance(alex));
    }

    /** A plain in-memory economy; no server, no Vault, no reflection. */
    private final class FakeEconomy implements Economy {

        private final Map<UUID, Double> balances = new HashMap<>();
        private boolean acceptTransactions = true;
        private int deposits;
        private int withdrawals;

        FakeEconomy(double initial) {
            if (initial >= 0) balances.put(alex, initial);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String name() {
            return "Test Economy";
        }

        @Override
        public String currency() {
            return "coins";
        }

        @Override
        public String format(double amount) {
            return "$" + amount;
        }

        @Override
        public double balance(UUID player) {
            return balances.getOrDefault(player, -1.0);
        }

        @Override
        public boolean deposit(UUID player, double amount) {
            if (!acceptTransactions) return false;
            deposits++;
            balances.merge(player, amount, Double::sum);
            return true;
        }

        @Override
        public boolean withdraw(UUID player, double amount) {
            if (!acceptTransactions) return false;
            withdrawals++;
            balances.merge(player, -amount, Double::sum);
            return true;
        }
    }
}
