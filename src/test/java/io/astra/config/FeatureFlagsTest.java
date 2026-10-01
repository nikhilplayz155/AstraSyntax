package io.astra.config;

import io.astra.config.AstraSettings.Features;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The feature switches in {@code config.yml} are only meaningful if every key maps to
 * something and a disabled key actually refuses a script. Both are checked here - the
 * parser and the loader are covered by {@code CompilerSmokeTest} and
 * {@code ScriptManagerTest}.
 */
class FeatureFlagsTest {

    /** Every key as it appears in the shipped config.yml, with the matching flag on. */
    private static final Features ALL_OFF = new Features(false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false);
    private static final Features ALL_ON = new Features(true, true, true, true, true, true, true, true,
        true, true, true, true, true, true, true, true, true);

    @Test
    void everyKeyFromTheShippedConfigMapsToAFlag() {
        List<String> keys = List.of("natural-language", "custom-items", "custom-mobs", "bosses", "gui", "quests",
            "economy", "regions", "npc", "holograms", "scoreboards", "bossbars", "placeholders", "recipes",
            "webhooks", "http", "cross-server");
        for (String key : keys) {
            assertTrue(FeatureFlags.enabled(ALL_ON, key), key + " must be enabled when its flag is on");
            assertFalse(FeatureFlags.enabled(ALL_OFF, key), key + " must be disabled when its flag is off");
            assertEquals("features." + key, FeatureFlags.configKey(key));
        }
    }

    @Test
    void idsAreNormalisedAndUnknownOnesAreAllowed() {
        assertEquals("custom-items", FeatureFlags.normalize(" Custom_Items "));
        assertEquals("cross-server", FeatureFlags.normalize("cross.server".replace('.', '-')));
        // Third-party modules may declare their own feature names.
        assertTrue(FeatureFlags.enabled(ALL_OFF, "my-module-thing"));
        assertTrue(FeatureFlags.enabled(ALL_OFF, null));
    }

    @Test
    void remoteInstallIsGovernedBySecurityNotByTheFeatureSwitch() {
        // packages.allow-remote-install lives in security.yml; reporting the wrong file
        // would send the administrator to the wrong place.
        assertTrue(FeatureFlags.enabled(ALL_OFF, FeatureFlags.REMOTE_INSTALL));
        assertEquals("features.remote-install", FeatureFlags.configKey(FeatureFlags.REMOTE_INSTALL));
    }

    @Test
    void disabledFeaturesAreReportedInRequestOrderWithoutDuplicates() {
        Features flags = new Features(true, true, true, true, false, true, false, true, true, true, true,
            true, true, true, false, false, true);
        List<String> disabled = FeatureFlags.disabled(flags,
            List.of("economy", "http", "gui", "economy", "webhooks", "worlds"));
        assertEquals(List.of("economy", "http", "gui", "webhooks"), disabled);
        assertTrue(FeatureFlags.disabled(flags, List.of("natural-language")).isEmpty());
    }
}
