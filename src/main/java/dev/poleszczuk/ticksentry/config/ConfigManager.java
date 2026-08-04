package dev.poleszczuk.ticksentry.config;

import dev.poleszczuk.ticksentry.monitor.CostWeights;
import dev.poleszczuk.ticksentry.remedy.RemedySettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads and exposes the settings from {@code config.yml}.
 *
 * <p>Fields are {@code volatile} because they are read both by the main server thread
 * (monitor, commands) and by the webhook delivery thread.</p>
 */
public final class ConfigManager {

    /** Where a complete, commented copy of the defaults is put when the live file is behind. */
    public static final String REFERENCE_FILE = "config-latest.yml";

    private final Plugin plugin;

    private volatile double msptThresholdMs;
    private volatile int sustainedSeconds;
    private volatile int scanCooldownSeconds;
    private volatile int rollingAverageTicks;

    private volatile boolean discordEnabled;
    private volatile String webhookUrl;
    private volatile String mentionRoleId;

    private volatile Set<String> ignoredWorlds;
    private volatile int topChunksCount;

    private volatile boolean recoveryAlert;
    private volatile int recoverySeconds;
    private volatile boolean inGameAlerts;
    private volatile boolean storageEnabled;
    private volatile int storageKeepDays;
    private volatile int offenderDays;

    private volatile boolean updateCheck;
    private volatile boolean bstatsEnabled;

    private volatile boolean profilerEnabled;
    private volatile int profilerWindowSeconds;

    private volatile boolean dashboardEnabled;
    private volatile String dashboardBind;
    private volatile int dashboardPort;
    private volatile String dashboardToken;
    private volatile boolean dashboardMetrics;
    private volatile CostWeights costWeights;
    private volatile RemedySettings remedySettings = RemedySettings.disabled();
    private volatile AdaptiveSettings adaptiveSettings = AdaptiveSettings.disabled();

    /**
     * Creates the manager and immediately loads the configuration from disk.
     *
     * @param plugin plugin instance providing the {@link FileConfiguration}
     */
    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Re-reads {@code config.yml} from disk and refreshes every setting.
     * Values outside a sensible range are clamped, so a broken config cannot wedge the monitor.
     */
    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        this.msptThresholdMs = Math.max(1.0D, cfg.getDouble("monitor.mspt-threshold-ms", 50.0D));
        this.sustainedSeconds = Math.max(1, cfg.getInt("monitor.sustained-seconds", 10));
        this.scanCooldownSeconds = Math.max(0, cfg.getInt("monitor.scan-cooldown-seconds", 300));
        this.rollingAverageTicks = Math.min(6000, Math.max(20, cfg.getInt("monitor.rolling-average-ticks", 100)));
        this.recoveryAlert = cfg.getBoolean("monitor.recovery-alert", true);
        this.recoverySeconds = Math.max(1, cfg.getInt("monitor.recovery-seconds", 15));
        this.inGameAlerts = cfg.getBoolean("monitor.in-game-alerts", true);

        this.adaptiveSettings = new AdaptiveSettings(
                cfg.getBoolean("monitor.adaptive-threshold.enabled", false),
                cfg.getDouble("monitor.adaptive-threshold.multiplier", 2.0D),
                cfg.getDouble("monitor.adaptive-threshold.minimum-ms", 25.0D),
                cfg.getDouble("monitor.adaptive-threshold.maximum-ms", 100.0D),
                cfg.getInt("monitor.adaptive-threshold.baseline-minutes", 60));

        this.storageEnabled = cfg.getBoolean("storage.enabled", true);
        this.storageKeepDays = Math.max(0, cfg.getInt("storage.keep-days", 30));
        this.offenderDays = Math.min(365, Math.max(1, cfg.getInt("storage.offender-days", 7)));

        this.updateCheck = cfg.getBoolean("updates.check", true);
        this.bstatsEnabled = cfg.getBoolean("updates.bstats", true);

        this.profilerEnabled = cfg.getBoolean("profiler.enabled", true);
        // Below ~5 s a window holds too few samples to mean anything; above 300 s the profiler
        // would be reporting on lag that has long since passed.
        this.profilerWindowSeconds = Math.min(300, Math.max(5, cfg.getInt("profiler.window-seconds", 30)));

        this.dashboardEnabled = cfg.getBoolean("dashboard.enabled", false);
        this.dashboardBind = cfg.getString("dashboard.bind", "127.0.0.1").trim();
        this.dashboardPort = Math.min(65535, Math.max(1, cfg.getInt("dashboard.port", 8080)));
        this.dashboardToken = cfg.getString("dashboard.token", "").trim();
        this.dashboardMetrics = cfg.getBoolean("dashboard.metrics", true);

        this.discordEnabled = cfg.getBoolean("discord.enabled", true);
        this.webhookUrl = cfg.getString("discord.webhook-url", "").trim();
        this.mentionRoleId = cfg.getString("discord.mention-role-id", "").trim();

        List<String> worlds = cfg.getStringList("scan.ignored-worlds");
        this.ignoredWorlds = worlds.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.topChunksCount = Math.min(25, Math.max(1, cfg.getInt("scan.top-chunks-count", 5)));

        this.remedySettings = new RemedySettings(
                cfg.getBoolean("remediation.enabled", false),
                cfg.getBoolean("remediation.dry-run", true),
                cfg.getInt("remediation.warning-seconds", 30),
                cfg.getInt("remediation.cooldown-seconds", 600),
                cfg.getBoolean("remediation.clear-items.enabled", true),
                cfg.getInt("remediation.clear-items.threshold", 300),
                cfg.getBoolean("remediation.cap-mobs.enabled", false),
                cfg.getInt("remediation.cap-mobs.threshold", 300),
                cfg.getInt("remediation.cap-mobs.keep", 50),
                cfg.getStringList("remediation.cap-mobs.protected-types"));

        this.costWeights = CostWeights.withOverrides(
                readWeights(cfg.getConfigurationSection("weights.entities")),
                readWeights(cfg.getConfigurationSection("weights.block-entities")));
    }

    /**
     * Tells the admin about settings their {@code config.yml} predates.
     *
     * <p>Bukkit only writes the default file when none exists, so upgrading leaves an older file
     * in place and every setting added since is simply absent. The plugin still works - each
     * lookup here carries its own default - but the options are invisible, which is how somebody
     * runs for months without knowing a feature exists.</p>
     *
     * <p>Their file is deliberately <b>not</b> rewritten. Saving a {@code YamlConfiguration}
     * strips every comment, and this config is explained almost entirely in comments; silently
     * trading that away for a few new lines would be a bad bargain. Instead the missing keys are
     * named in the log and a complete, commented copy is written next to it to copy from.</p>
     *
     * @return keys present in the bundled config but missing from the file on disk
     */
    public List<String> reportOutdatedConfig() {
        FileConfiguration current = plugin.getConfig();
        YamlConfiguration bundled = bundledConfig();
        if (bundled == null) {
            return List.of();
        }

        List<String> missing = missingKeys(current, bundled);
        if (missing.isEmpty()) {
            return missing;
        }

        plugin.getLogger().warning("Your config.yml predates " + missing.size()
                + " setting(s), which are running on their built-in defaults: " + String.join(", ", missing));
        if (writeReferenceConfig()) {
            plugin.getLogger().warning("A complete, commented copy has been written to "
                    + REFERENCE_FILE + " - copy what you want across, then run /lagwatch reload.");
        }
        return missing;
    }

    /**
     * Lists the settings the bundled config has and the live one does not.
     *
     * <p>Uses {@code isSet} rather than {@code contains} for a reason worth spelling out.
     * {@link org.bukkit.plugin.java.JavaPlugin#reloadConfig()} installs the bundled file as the
     * configuration's <em>defaults</em>, and {@code contains} counts a default as present - so
     * every missing key looks like it is already there and nothing is ever reported.
     * {@code isSet} answers the question actually being asked: is this written in the file on
     * disk.</p>
     *
     * @param current  the live configuration, defaults and all
     * @param bundled  the copy shipped inside the jar
     * @return missing keys, in the order the bundled file declares them
     */
    static List<String> missingKeys(ConfigurationSection current, ConfigurationSection bundled) {
        List<String> missing = new ArrayList<>();
        for (String key : bundled.getKeys(true)) {
            // Only leaves matter: a missing section already shows through the keys inside it,
            // and reporting both would bury the useful line under its own parents.
            if (!bundled.isConfigurationSection(key) && !current.isSet(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** Reads the copy of {@code config.yml} inside the jar. */
    private YamlConfiguration bundledConfig() {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Could not read the bundled config.yml: " + ex);
            return null;
        }
    }

    /**
     * Copies the bundled config next to the live one, comments and all.
     *
     * @return whether the file was written
     */
    private boolean writeReferenceConfig() {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                return false;
            }
            File target = new File(plugin.getDataFolder(), REFERENCE_FILE);
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Could not write " + REFERENCE_FILE + ": " + ex);
            return false;
        }
    }

    /**
     * Reads a weights section, ignoring anything that is not a number.
     *
     * @param section config section, may be {@code null} when absent
     * @return type name to weight, empty when the section is missing
     */
    private Map<String, Double> readWeights(ConfigurationSection section) {
        Map<String, Double> weights = new HashMap<>();
        if (section == null) {
            return weights;
        }
        for (String key : section.getKeys(false)) {
            if (section.isDouble(key) || section.isInt(key)) {
                weights.put(key, section.getDouble(key));
            } else {
                plugin.getLogger().warning("Ignoring weight '" + key + "' - it is not a number.");
            }
        }
        return weights;
    }

    /** @return MSPT threshold in milliseconds above which the server counts as overloaded */
    public double msptThresholdMs() {
        return msptThresholdMs;
    }

    /** @return seconds of uninterrupted breach required before an alert fires */
    public int sustainedSeconds() {
        return sustainedSeconds;
    }

    /** @return minimum gap between alerts, in seconds */
    public int scanCooldownSeconds() {
        return scanCooldownSeconds;
    }

    /** @return size of the rolling MSPT average window, in ticks */
    public int rollingAverageTicks() {
        return rollingAverageTicks;
    }

    /** @return whether Discord alerts are enabled and properly configured */
    public boolean discordEnabled() {
        return discordEnabled && !webhookUrl.isEmpty();
    }

    /** @return Discord webhook address (may be empty when unconfigured) */
    public String webhookUrl() {
        return webhookUrl;
    }

    /** @return role id to mention on alert, or an empty string when unset */
    public String mentionRoleId() {
        return mentionRoleId;
    }

    /**
     * Checks whether a world should be skipped while scanning.
     *
     * @param worldName world name
     * @return {@code true} if the world is on the exclusion list
     */
    public boolean isWorldIgnored(String worldName) {
        return ignoredWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    /** @return how many suspicious chunks the scanner should return */
    public int topChunksCount() {
        return topChunksCount;
    }

    /** @return whether to send a separate message once the server recovers */
    public boolean recoveryAlert() {
        return recoveryAlert;
    }

    /** @return how many seconds below the threshold end an incident */
    public int recoverySeconds() {
        return recoverySeconds;
    }

    /** @return whether admins in the game should be told about incidents */
    public boolean inGameAlerts() {
        return inGameAlerts;
    }

    /** @return whether incidents should be written to disk */
    public boolean storageEnabled() {
        return storageEnabled;
    }

    /** @return after how many days old incidents are deleted (0 = never) */
    public int storageKeepDays() {
        return storageKeepDays;
    }

    /** @return how many days back to look when deciding which chunks keep coming back */
    public int offenderDays() {
        return offenderDays;
    }

    /** @return whether to ask GitHub at startup for a newer release */
    public boolean updateCheck() {
        return updateCheck;
    }

    /** @return whether anonymous usage statistics may be sent to bStats */
    public boolean bstatsEnabled() {
        return bstatsEnabled;
    }

    /** @return whether other plugins' event handlers should be timed */
    public boolean profilerEnabled() {
        return profilerEnabled;
    }

    /** @return how many seconds of handler timings a report covers */
    public int profilerWindowSeconds() {
        return profilerWindowSeconds;
    }

    /** @return whether the web panel should be started */
    public boolean dashboardEnabled() {
        return dashboardEnabled;
    }

    /** @return panel listen address (local only by default) */
    public String dashboardBind() {
        return dashboardBind;
    }

    /** @return web panel port */
    public int dashboardPort() {
        return dashboardPort;
    }

    /** @return whether the panel should also serve {@code /metrics} for Prometheus */
    public boolean dashboardMetrics() {
        return dashboardMetrics;
    }

    /** @return panel access token; empty means one has to be generated */
    public String dashboardToken() {
        return dashboardToken;
    }

    /** @return entity and block entity cost weights, with any config overrides applied */
    public CostWeights costWeights() {
        return costWeights;
    }

    /** @return how the alert threshold should adapt to this server's normal tick time */
    public AdaptiveSettings adaptiveSettings() {
        return adaptiveSettings;
    }

    /** @return what, if anything, the plugin is allowed to remove on its own */
    public RemedySettings remedySettings() {
        return remedySettings;
    }

    /**
     * Writes a generated panel token into {@code config.yml} so it survives a restart.
     *
     * @param token new access token
     */
    public void saveDashboardToken(String token) {
        this.dashboardToken = token;
        plugin.getConfig().set("dashboard.token", token);
        plugin.saveConfig();
    }
}
