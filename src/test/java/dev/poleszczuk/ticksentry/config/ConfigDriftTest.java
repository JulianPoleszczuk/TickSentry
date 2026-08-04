package dev.poleszczuk.ticksentry.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection of settings an upgraded server's {@code config.yml} predates.
 *
 * <p>There is one trap here big enough to make the whole feature silently do nothing, and it is
 * pinned down by {@link #defaultsMustNotCountAsPresent()}: Bukkit installs the bundled file as
 * the live configuration's defaults, so asking whether a key is "contained" always answers yes,
 * and the list of missing settings comes back empty on a config that is plainly years behind.</p>
 */
class ConfigDriftTest {

    private static YamlConfiguration bundled() {
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("monitor.mspt-threshold-ms", 50);
        bundled.set("monitor.sustained-seconds", 10);
        bundled.set("profiler.enabled", true);
        bundled.set("profiler.window-seconds", 30);
        bundled.set("remediation.enabled", false);
        return bundled;
    }

    @Test
    void aConfigThatPredatesSettingsReportsExactlyThose() {
        YamlConfiguration old = new YamlConfiguration();
        old.set("monitor.mspt-threshold-ms", 40);
        old.set("monitor.sustained-seconds", 3);

        List<String> missing = ConfigManager.missingKeys(old, bundled());

        assertEquals(List.of("profiler.enabled", "profiler.window-seconds", "remediation.enabled"), missing);
    }

    @Test
    void defaultsMustNotCountAsPresent() {
        // The bug this whole test file exists for. JavaPlugin.reloadConfig() installs the
        // bundled file as the live config's defaults; contains() then answers true for every
        // key, and nothing is ever reported. isSet() asks what we actually mean.
        YamlConfiguration old = new YamlConfiguration();
        old.set("monitor.mspt-threshold-ms", 40);
        old.setDefaults(bundled());

        assertTrue(old.contains("profiler.enabled"), "precondition: contains() is fooled by defaults");
        assertFalse(old.isSet("profiler.enabled"), "precondition: isSet() is not");

        assertTrue(ConfigManager.missingKeys(old, bundled()).contains("profiler.enabled"));
    }

    @Test
    void anUpToDateConfigReportsNothing() {
        YamlConfiguration current = bundled();

        assertTrue(ConfigManager.missingKeys(current, bundled()).isEmpty());
    }

    @Test
    void sectionsThemselvesAreNotListed() {
        YamlConfiguration old = new YamlConfiguration();

        List<String> missing = ConfigManager.missingKeys(old, bundled());

        // "profiler" and "monitor" are sections; naming them alongside their own children
        // would bury the useful lines.
        assertFalse(missing.contains("profiler"));
        assertFalse(missing.contains("monitor"));
        assertTrue(missing.contains("profiler.enabled"));
    }

    @Test
    void settingsTheAdminAddedThemselvesAreNotMissing() {
        YamlConfiguration old = bundled();
        old.set("weights.entities.COW", 2.5);

        assertTrue(ConfigManager.missingKeys(old, bundled()).isEmpty());
    }

    @Test
    void aKeySetToFalseOrZeroStillCountsAsPresent() {
        // A falsey value is a decision the admin made, not an absence.
        YamlConfiguration old = bundled();
        old.set("profiler.enabled", false);
        old.set("profiler.window-seconds", 0);

        assertTrue(ConfigManager.missingKeys(old, bundled()).isEmpty());
    }
}
