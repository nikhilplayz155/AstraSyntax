package io.astra.integration;

import io.astra.config.ConfigManager;
import io.astra.logging.AstraLogger;
import io.astra.runtime.Value;
import io.astra.runtime.expression.PlaceholderService;
import io.astra.util.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Soft integrations with the plugins servers actually run.
 *
 * <p>Every hook here is optional and reflective. AstraSyntax never compiles against Vault,
 * PlaceholderAPI, Citizens or WorldGuard, so a server without them is a fully supported
 * configuration rather than a degraded one - and a missing integration is reported as
 * "not installed" instead of throwing {@code NoClassDefFoundError} at startup.</p>
 *
 * <p>{@code integrations.yml} decides: {@code enabled: auto} means "use it when
 * installed", {@code true} means "require it and warn when missing" and {@code false}
 * means "leave it alone".</p>
 */
public final class IntegrationManager {

    private final org.bukkit.plugin.Plugin plugin;
    private final ConfigManager config;
    private final AstraLogger logger;
    private final PlaceholderService placeholders;

    private EconomyBridge economy;
    private final List<String> available = new ArrayList<>();
    private final List<String> missing = new ArrayList<>();

    public IntegrationManager(Plugin plugin, ConfigManager config, AstraLogger logger,
                              PlaceholderService placeholders) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.placeholders = placeholders;
    }

    /** Detect and install every enabled integration. */
    public void install() {
        var integrations = config.integrations();
        detect("Vault", integrations.vault().enabled(), integrations.vault().auto(), this::installEconomy);
        detect("PlaceholderAPI", integrations.placeholderApi().enabled(),
            integrations.placeholderApi().auto(), this::installPlaceholders);
        detect("Citizens", integrations.citizens().enabled(), integrations.citizens().auto(), () -> { });
        detect("WorldGuard", integrations.worldGuard().enabled(), integrations.worldGuard().auto(), () -> { });
        if (integrations.redis().enabled()) {
            // Redis is used for cross-server variables. It needs a client library that is
            // not bundled, so instead of pretending, the integration reports what is
            // missing and the cross-server feature stays off.
            if (Reflect.hasClass("redis.clients.jedis.Jedis")) {
                available.add("Redis");
            } else {
                missing.add("Redis (needs a Jedis client on the server)");
                logger.warn("integrations.yml enables Redis but no Redis client is installed; "
                    + "cross-server data is unavailable");
            }
        }
    }

    /** A hook that may fail while being installed. */
    @FunctionalInterface
    private interface Installer {
        void install() throws Exception;
    }

    private void detect(String name, boolean enabled, boolean auto, Installer installer) {
        if (!enabled) return;
        boolean present = isPluginPresent(name);
        if (!present) {
            if (auto) {
                missing.add(name + " (not installed)");
            } else {
                missing.add(name + " (enabled in integrations.yml but not installed)");
                logger.warn("integrations.yml enables " + name + " but the plugin is not installed");
            }
            return;
        }
        try {
            installer.install();
            available.add(name);
            logger.debug(name + " integration is active");
        } catch (Throwable error) {
            missing.add(name + " (" + error.getClass().getSimpleName() + ")");
            logger.warn("The " + name + " integration could not be installed: " + logger.describe(error));
        }
    }

    private boolean isPluginPresent(String name) {
        try {
            for (Plugin installed : Bukkit.getPluginManager().getPlugins()) {
                if (installed.getName().equalsIgnoreCase(name)) return true;
            }
        } catch (Throwable ignored) {
            // Not on a running server (tests); fall back to a class check.
        }
        return switch (name) {
            case "Vault" -> Reflect.hasClass("net.milkbowl.vault.economy.Economy");
            case "PlaceholderAPI" -> Reflect.hasClass("me.clip.placeholderapi.PlaceholderAPI");
            case "Citizens" -> Reflect.hasClass("net.citizensnpcs.api.CitizensAPI");
            case "WorldGuard" -> Reflect.hasClass("com.sk89q.worldguard.WorldGuard");
            default -> false;
        };
    }

    // -------------------------------------------------------------------- economy

    /** Register the Vault economy bridge and the placeholders it enables. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void installEconomy() throws ClassNotFoundException {
        Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy",
            true, plugin.getClass().getClassLoader());
        Object registration = Bukkit.getServicesManager().getRegistration((Class) economyClass);
        if (registration == null) {
            throw new IllegalStateException("Vault is installed but no economy provider is registered");
        }
        Object provider = io.astra.util.Reflect.invokeQuietly(registration, "getProvider", 0);
        if (provider == null) throw new IllegalStateException("Vault returned no economy provider");
        economy = new EconomyBridge(provider, economyClass);
        placeholders.register("balance", (name, context) -> {
            Player player = context.player();
            if (player == null) return Value.NULL;
            double balance = economy.balance(player);
            return balance < 0 ? Value.NULL : Value.dec(balance);
        });
        logger.info("Vault economy detected: " + economy.name());
    }

    /** Reflection bridge to a Vault economy provider. */
    public static final class EconomyBridge implements io.astra.runtime.economy.Economy {

        private final Object provider;
        private final Method getBalance;
        private final Method depositPlayer;
        private final Method withdrawPlayer;
        private final Method format;
        private final Method currencyName;
        private final boolean decimals;

        private EconomyBridge(Object provider, Class<?> economyClass) {
            this.provider = provider;
            this.getBalance = Reflect.findMethod(economyClass, "getBalance", OfflinePlayer.class).orElse(null);
            this.depositPlayer = Reflect.findMethod(economyClass, "depositPlayer", OfflinePlayer.class,
                double.class).orElse(null);
            this.withdrawPlayer = Reflect.findMethod(economyClass, "withdrawPlayer", OfflinePlayer.class,
                double.class).orElse(null);
            this.format = Reflect.findMethod(economyClass, "format", double.class).orElse(null);
            this.currencyName = Reflect.findMethod(economyClass, "currencyNamePlural").orElse(null);
            Object supportsDecimals = Reflect.invokeQuietly(provider, "hasBankSupport", 0);
            this.decimals = supportsDecimals == null || Boolean.TRUE.equals(supportsDecimals);
        }

        @Override
        public boolean available() {
            return getBalance != null && depositPlayer != null && withdrawPlayer != null;
        }

        /** Generic helper: Vault exposes this as {@code isEnabled()}. */
        public boolean enabled() {
            return available() && !Boolean.FALSE.equals(Reflect.invokeQuietly(provider, "isEnabled", 0));
        }

        public Object provider() {
            return provider;
        }

        /** Resolves an id to a player the provider understands. */
        private static OfflinePlayer resolve(java.util.UUID id) {
            if (id == null) return null;
            Player online = Bukkit.getPlayer(id);
            return online != null ? online : Bukkit.getOfflinePlayer(id);
        }

        @Override
        public double balance(java.util.UUID player) {
            OfflinePlayer target = resolve(player);
            return target == null ? -1 : balance(target);
        }

        @Override
        public boolean deposit(java.util.UUID player, double amount) {
            OfflinePlayer target = resolve(player);
            return target != null && deposit(target, amount);
        }

        @Override
        public boolean withdraw(java.util.UUID player, double amount) {
            OfflinePlayer target = resolve(player);
            return target != null && withdraw(target, amount);
        }

        /** Vault's own text for an amount. */
        @Override
        public String format(double amount) {
            return formatAmount(amount);
        }

        @Override
        public String currency() {
            return currencyNameText();
        }

        /** The provider's own name ("Essentials Economy", "CMI", ...). */
        @Override
        public String name() {
            Object value = Reflect.invokeQuietly(provider, "getName", 0);
            return value == null ? "Vault economy" : String.valueOf(value);
        }

        public double balance(OfflinePlayer player) {
            if (getBalance == null) return -1;
            Object result = Reflect.invokeQuietly(getBalance, provider, player);
            return result instanceof Number number ? number.doubleValue() : -1;
        }

        public boolean deposit(OfflinePlayer player, double amount) {
            if (depositPlayer == null) return false;
            Object result = Reflect.invokeQuietly(depositPlayer, provider, player, amount);
            return result == null || Boolean.TRUE.equals(readSuccess(result));
        }

        public boolean withdraw(OfflinePlayer player, double amount) {
            if (withdrawPlayer == null) return false;
            Object result = Reflect.invokeQuietly(withdrawPlayer, provider, player, amount);
            return result == null || Boolean.TRUE.equals(readSuccess(result));
        }

        /** Vault's own text for an amount. */
        public String formatAmount(double amount) {
            if (format == null) return String.valueOf(amount);
            Object result = Reflect.invokeQuietly(format, provider, amount);
            return result == null ? String.valueOf(amount) : String.valueOf(result);
        }

        /** Plural currency name, or an empty string. */
        public String currencyNameText() {
            if (currencyName == null) return "";
            Object result = Reflect.invokeQuietly(currencyName, provider);
            return result == null ? "" : String.valueOf(result);
        }

        /** Vault returns a {@code Response} object with a {@code transactionSuccess()} method. */
        private static Object readSuccess(Object response) {
            Object success = Reflect.invokeQuietly(response, "transactionSuccess", 0);
            return success == null ? Boolean.TRUE : success;
        }
    }

    // --------------------------------------------------------------- placeholders

    /** Route {@code %placeholder%} through PlaceholderAPI when it is installed. */
    private void installPlaceholders() throws ClassNotFoundException {
        Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI",
            true, plugin.getClass().getClassLoader());
        Method setPlaceholders = Reflect.findMethod(api, "setPlaceholders", OfflinePlayer.class, String.class)
            .orElse(null);
        if (setPlaceholders == null) throw new IllegalStateException("PlaceholderAPI has no setPlaceholders method");
        placeholders.setExternalBridge((placeholder, context) -> {
            Player player = context == null ? null : context.player();
            if (player == null) return null;
            Object result = Reflect.invokeQuietly(setPlaceholders, null, player, "%" + placeholder + "%");
            return result == null ? null : String.valueOf(result);
        });
        placeholders.register("papi", (name, context) -> context.player() == null ? Value.NULL
            : Value.str(context.player().getName()));
    }

    // ------------------------------------------------------------------- reporting

    /** Names of the integrations that installed successfully. */
    public List<String> active() {
        return List.copyOf(available);
    }

    /** One line per integration state, used by {@code /astra info}. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (String name : available) lines.add(name);
        for (String name : missing) lines.add(name);
        return lines;
    }

    /** The economy bridge, or empty when Vault is not in use. */
    public Optional<EconomyBridge> economy() {
        return Optional.ofNullable(economy);
    }

    /** True when an economy is available for economy actions. */
    public boolean hasEconomy() {
        return economy != null;
    }

    /** Nothing to release: hooks are method handles held by this object. */
    public void shutdown() {
        available.clear();
        missing.clear();
        economy = null;
    }

    /** The plugin name, for the {@code /astra info} line. */
    public String pluginName() {
        return plugin == null ? "AstraSyntax" : plugin.getName();
    }
}
