package io.astra.platform;

import io.astra.util.Reflect;
import io.astra.util.Strings;

import java.util.Locale;

import org.bukkit.Bukkit;

/**
 * Detects the running server software and its capabilities.
 *
 * <p>Detection is deliberately conservative: a capability is only reported when the
 * corresponding class or method really exists, because the whole point is that the
 * runtime must degrade to a documented fallback instead of throwing
 * {@code NoSuchMethodError} on a server that does not implement it.</p>
 */
public final class PlatformDetector {

    private PlatformDetector() {}

    /** Inspect the running server. Never throws. */
    public static ServerPlatform detect() {
        String name;
        String version;
        try {
            name = Bukkit.getServer() == null ? "unknown" : Bukkit.getServer().getName();
            version = Bukkit.getVersion();
        } catch (Throwable ignored) {
            return ServerPlatform.unknown();
        }
        name = Strings.trimToEmpty(name).replace(",", "").trim();
        version = Strings.trimToEmpty(version);

        boolean foliaClasses = Reflect.hasClass("io.papermc.paper.threadedregions.RegionizedServer")
            || Reflect.hasClass("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler")
            || Reflect.hasClass("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
        boolean foliaApi = allowsFoliaSchedulers();
        boolean folia = foliaClasses || foliaApi || name.toLowerCase(Locale.ROOT).contains("folia");

        boolean paperFamily = folia
            || name.toLowerCase(Locale.ROOT).contains("paper")
            || name.toLowerCase(Locale.ROOT).contains("purpur")
            || name.toLowerCase(Locale.ROOT).contains("leaf")
            || Reflect.hasClass("io.papermc.paper.configuration.Configuration")
            || Reflect.hasClass("com.destroystokyo.paper.PaperConfig");

        boolean adventure = Reflect.hasClass("net.kyori.adventure.text.Component");

        String minecraft = detectMinecraftVersion(version);
        int classVersion = detectClassVersion();

        return new ServerPlatform(name.isEmpty() ? "unknown" : name, version, minecraft, folia, paperFamily,
            adventure, classVersion);
    }

    /** True when the Folia scheduler entry points exist on the server instance. */
    private static boolean allowsFoliaSchedulers() {
        try {
            var server = Bukkit.getServer();
            if (server == null) return false;
            return Reflect.findMethod(server.getClass(), "getRegionScheduler").isPresent()
                || Reflect.findMethod(server.getClass(), "getGlobalRegionScheduler").isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Extract the Minecraft version from a Bukkit version string such as
     * {@code "git-Paper-196 (MC: 1.21.11)"}.
     */
    public static String detectMinecraftVersion(String bukkitVersion) {
        if (Strings.isBlank(bukkitVersion)) return "unknown";
        int marker = bukkitVersion.indexOf("MC:");
        if (marker >= 0) {
            String tail = bukkitVersion.substring(marker + 3).trim();
            int end = 0;
            while (end < tail.length() && (Character.isDigit(tail.charAt(end)) || tail.charAt(end) == '.')) end++;
            String candidate = tail.substring(0, end);
            if (!candidate.isEmpty()) return candidate;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(\\d+\\.\\d+(?:\\.\\d+)?)")
            .matcher(bukkitVersion);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    /** The class file version the server itself runs on (used for diagnostics). */
    private static int detectClassVersion() {
        try {
            Class<?> serverClass = Bukkit.getServer().getClass();
            java.io.DataInputStream in = new java.io.DataInputStream(
                serverClass.getResourceAsStream("/" + serverClass.getName().replace('.', '/') + ".class"));
            try (in) {
                if (in.readInt() != 0xCAFEBABE) return 0;
                in.readUnsignedShort(); // minor
                return in.readUnsignedShort();
            }
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
