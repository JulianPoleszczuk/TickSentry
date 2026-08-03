package dev.poleszczuk.ticksentry.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.List;
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
    private volatile boolean storageEnabled;
    private volatile int storageKeepDays;

    private volatile boolean dashboardEnabled;
    private volatile String dashboardBind;
    private volatile int dashboardPort;
    private volatile String dashboardToken;

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

        this.storageEnabled = cfg.getBoolean("storage.enabled", true);
        this.storageKeepDays = Math.max(0, cfg.getInt("storage.keep-days", 30));

        this.dashboardEnabled = cfg.getBoolean("dashboard.enabled", false);
        this.dashboardBind = cfg.getString("dashboard.bind", "127.0.0.1").trim();
        this.dashboardPort = Math.min(65535, Math.max(1, cfg.getInt("dashboard.port", 8080)));
        this.dashboardToken = cfg.getString("dashboard.token", "").trim();

        this.discordEnabled = cfg.getBoolean("discord.enabled", true);
        this.webhookUrl = cfg.getString("discord.webhook-url", "").trim();
        this.mentionRoleId = cfg.getString("discord.mention-role-id", "").trim();

        List<String> worlds = cfg.getStringList("scan.ignored-worlds");
        this.ignoredWorlds = worlds.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.topChunksCount = Math.min(25, Math.max(1, cfg.getInt("scan.top-chunks-count", 5)));
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

    /** @return whether incidents should be written to disk */
    public boolean storageEnabled() {
        return storageEnabled;
    }

    /** @return after how many days old incidents are deleted (0 = never) */
    public int storageKeepDays() {
        return storageKeepDays;
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

    /** @return panel access token; empty means one has to be generated */
    public String dashboardToken() {
        return dashboardToken;
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
