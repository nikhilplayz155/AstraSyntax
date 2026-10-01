package io.astra.platform;

import java.util.List;

/**
 * Immutable description of the server AstraSyntax is running on plus the
 * capabilities that were actually detected at runtime.
 *
 * <p>Every platform difference in AstraSyntax is expressed as a capability flag or
 * through {@link SchedulerService}, never as a hard reference to a fork-only class.
 * That is what allows one jar to run on Folia, Paper, Purpur, Spigot and Leaf while
 * still taking advantage of the faster APIs when they exist.</p>
 *
 * @param name          the server implementation name ("Paper", "Folia", "Spigot", ...)
 * @param version       the full server version string
 * @param minecraft     the Minecraft version derived from the version string, or "unknown"
 * @param folia         true when the Folia regionised scheduler API is present
 * @param paperFamily   true for Paper/Purpur/Leaf/Folia style servers
 * @param adventure     true when net.kyori.adventure components are available
 * @param classVersion  the class file version the server runs on (0 when unknown)
 */
public record ServerPlatform(String name, String version, String minecraft, boolean folia,
                             boolean paperFamily, boolean adventure, int classVersion) {

    /** The reported software name, normalised ("Folia" when in doubt but region-threaded). */
    public String software() {
        if (folia) return "Folia";
        if (name == null || name.isBlank()) return "unknown";
        return name;
    }

    /** True when the server runs regionised threading (Folia semantics). */
    public boolean regionised() {
        return folia;
    }

    /** A short human readable summary for the startup banner. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(software());
        if (minecraft != null && !minecraft.equals("unknown")) sb.append(" (MC ").append(minecraft).append(')');
        List<String> notes = new java.util.ArrayList<>();
        if (folia) notes.add("regionised scheduling");
        if (adventure) notes.add("adventure text");
        if (!notes.isEmpty()) sb.append(" [").append(String.join(", ", notes)).append(']');
        return sb.toString();
    }

    /** Minimal platform description used when the server API cannot be inspected. */
    public static ServerPlatform unknown() {
        return new ServerPlatform("unknown", "unknown", "unknown", false, false, false, 0);
    }
}
