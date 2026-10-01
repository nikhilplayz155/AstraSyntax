package io.astra.config;

import io.astra.config.AstraSettings.Features;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Turns {@code config.yml -> features} into a decision.
 *
 * <p>The compiler records which feature a script needs ({@code requiredFeatures} on
 * {@link io.astra.language.compiler.CompilationResult}), and this class answers the
 * only question the loader has to ask: is that feature switched on? A script that
 * needs a disabled feature is refused at load time with a diagnostic that names the
 * config key - it is never silently loaded and then failing at runtime, and a
 * previous good version of the script stays active.</p>
 *
 * <p>Feature ids are the keys used in {@code config.yml} and in the compiler's
 * feature map, always lower case with dashes.</p>
 */
public final class FeatureFlags {

    /** Feature id used by the compiler for scripts that install from a remote source. */
    public static final String REMOTE_INSTALL = "remote-install";

    private FeatureFlags() {
    }

    /**
     * True when {@code featureId} is enabled in {@code config.yml}.
     *
     * <p>Unknown ids are allowed: modules and packages may declare their own feature
     * names, and refusing them here would make third-party contributions impossible.</p>
     */
    public static boolean enabled(Features features, String featureId) {
        if (featureId == null || featureId.isBlank() || features == null) return true;
        return switch (normalize(featureId)) {
            case "natural-language" -> features.naturalLanguage();
            case "custom-items" -> features.customItems();
            case "custom-mobs" -> features.customMobs();
            case "bosses" -> features.bosses();
            case "gui" -> features.gui();
            case "quests" -> features.quests();
            case "economy" -> features.economy();
            case "regions" -> features.regions();
            case "npc" -> features.npc();
            case "holograms" -> features.holograms();
            case "scoreboards" -> features.scoreboards();
            case "bossbars" -> features.bossbars();
            case "placeholders" -> features.placeholders();
            case "recipes" -> features.recipes();
            case "webhooks" -> features.webhooks();
            case "http" -> features.http();
            case "cross-server" -> features.crossServer();
            // Not a switch in the shipped config.yml: remote installation is governed
            // by security.yml (packages.allow-remote-install), which the security gate
            // enforces. Treating it as disabled here would report the wrong file.
            case REMOTE_INSTALL -> true;
            default -> true;
        };
    }

    /** Every requested feature that is switched off, in the order it was requested. */
    public static List<String> disabled(Features features, Collection<String> requiredFeatures) {
        List<String> disabled = new ArrayList<>();
        if (requiredFeatures == null) return disabled;
        for (String feature : requiredFeatures) {
            if (!enabled(features, feature) && !disabled.contains(feature)) {
                disabled.add(feature);
            }
        }
        return disabled;
    }

    /** The exact {@code config.yml} key for a feature id ({@code features.<key>}). */
    public static String configKey(String featureId) {
        return "features." + normalize(featureId);
    }

    /** Lower case, dashes only - the spelling used by both the config and the compiler. */
    public static String normalize(String featureId) {
        return featureId == null ? "" : featureId.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
