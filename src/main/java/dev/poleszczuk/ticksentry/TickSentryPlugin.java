package dev.poleszczuk.ticksentry;

import dev.poleszczuk.ticksentry.commands.LagWatchCommand;
import dev.poleszczuk.ticksentry.config.ConfigManager;
import dev.poleszczuk.ticksentry.discord.DiscordWebhookClient;
import dev.poleszczuk.ticksentry.monitor.ChunkHotspotScanner;
import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.monitor.SparkBridge;
import dev.poleszczuk.ticksentry.monitor.TickMonitor;
import dev.poleszczuk.ticksentry.placeholders.TickSentryExpansion;
import dev.poleszczuk.ticksentry.storage.AlertStore;
import dev.poleszczuk.ticksentry.storage.MemoryAlertStore;
import dev.poleszczuk.ticksentry.storage.SqliteAlertStore;
import dev.poleszczuk.ticksentry.storage.StoredIncident;
import dev.poleszczuk.ticksentry.web.DashboardServer;
import dev.poleszczuk.ticksentry.web.LiveSnapshot;
import dev.poleszczuk.ticksentry.web.MsptHistory;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.function.Consumer;

/**
 * Plugin entry point - wires together the config, the tick monitor, the incident store and alerts.
 */
public final class TickSentryPlugin extends JavaPlugin {

    /** How many recent incidents the in-memory fallback store keeps. */
    private static final int MEMORY_CAPACITY = 50;

    /** How often (in ticks) the incident counter behind the placeholders is refreshed. */
    private static final long COUNTER_REFRESH_TICKS = 20L * 60L;

    /** How often (in ticks) a chart sample is taken for the dashboard (5 seconds). */
    private static final long SAMPLE_TICKS = 20L * 5L;

    /** Chart sample capacity - 720 samples every 5 s covers one hour. */
    private static final int SAMPLE_CAPACITY = 720;

    /** How often (in ticks) the panel's incident list is refreshed (30 seconds). */
    private static final long DASHBOARD_INCIDENTS_TICKS = 20L * 30L;

    /** How many incidents the web panel shows. */
    private static final int DASHBOARD_INCIDENTS_SHOWN = 20;

    private ConfigManager configManager;
    private TickMonitor tickMonitor;
    private ChunkHotspotScanner scanner;
    private DiscordWebhookClient webhook;
    private AlertStore alertStore;
    private SparkBridge sparkBridge;
    private DashboardServer dashboard;

    private volatile int incidentsLast24h;
    private volatile LagCategory lastCategory;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.alertStore = openStore();
        this.sparkBridge = new SparkBridge(this);
        this.scanner = new ChunkHotspotScanner(this, configManager, sparkBridge);
        this.webhook = new DiscordWebhookClient(this, configManager);
        this.tickMonitor = new TickMonitor(this, configManager, this::handleSustainedLag, this::handleRecovery);
        this.tickMonitor.start();

        PluginCommand command = getCommand("lagwatch");
        if (command != null) {
            LagWatchCommand handler = new LagWatchCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        registerPlaceholders();
        startDashboard();
        // First read after a second, so the panel and placeholders do not show zero for a whole minute.
        getServer().getScheduler().runTaskTimer(this, this::refreshCounters, 20L, COUNTER_REFRESH_TICKS);

        getLogger().info("TickSentry active - threshold " + configManager.msptThresholdMs()
                + " ms for " + configManager.sustainedSeconds() + " s. History: " + alertStore.describe() + ".");
        if (!configManager.discordEnabled()) {
            getLogger().info("Discord alerts are disabled or webhook-url is missing from config.yml.");
        }
    }

    @Override
    public void onDisable() {
        if (tickMonitor != null) {
            tickMonitor.stop();
        }
        if (webhook != null) {
            webhook.shutdown();
        }
        if (dashboard != null) {
            dashboard.stop();
        }
        if (alertStore != null) {
            alertStore.close();
        }
    }

    /**
     * Opens the incident store according to the configuration.
     * When disk storage is off or the database cannot be opened, falls back to in-memory history.
     */
    private AlertStore openStore() {
        if (!configManager.storageEnabled()) {
            return new MemoryAlertStore(MEMORY_CAPACITY);
        }
        AlertStore store = SqliteAlertStore.open(this,
                new File(getDataFolder(), "incidents.db"), configManager.storageKeepDays());
        return store != null ? store : new MemoryAlertStore(MEMORY_CAPACITY);
    }

    /** Registers the placeholders if PlaceholderAPI is on the server. */
    private void registerPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new TickSentryExpansion(this).register();
            getLogger().info("Registered the %ticksentry_...% placeholders.");
        } catch (RuntimeException | NoClassDefFoundError ex) {
            getLogger().warning("Could not register the placeholders: " + ex);
        }
    }

    /** Refreshes the 24 h incident counter - placeholders cannot query the database themselves. */
    private void refreshCounters() {
        alertStore.stats(1, stats -> this.incidentsLast24h = stats.total());
    }

    /**
     * Starts the web panel when it is enabled in the configuration.
     * The token is generated on first start and saved, so the panel address stays stable.
     */
    private void startDashboard() {
        if (!configManager.dashboardEnabled()) {
            return;
        }
        String token = configManager.dashboardToken();
        if (token.isEmpty()) {
            token = DashboardServer.generateToken();
            configManager.saveDashboardToken(token);
            getLogger().info("Generated a panel token and saved it to config.yml.");
        }

        MsptHistory history = new MsptHistory(SAMPLE_CAPACITY);
        DashboardServer server = new DashboardServer(this, token, history);
        if (!server.start(configManager.dashboardBind(), configManager.dashboardPort())) {
            return;
        }
        this.dashboard = server;

        if (!"127.0.0.1".equals(configManager.dashboardBind()) && !"localhost".equals(configManager.dashboardBind())) {
            getLogger().warning("The panel listens on " + configManager.dashboardBind()
                    + " without encryption - the token travels in clear text. Consider an SSH tunnel or an HTTPS proxy.");
        }

        // Snapshots and samples are taken by the main thread; HTTP handlers only read them.
        getServer().getScheduler().runTaskTimer(this, () -> {
            LiveSnapshot snapshot = collectSnapshot();
            history.add(snapshot.generatedAt(), snapshot.mspt(), snapshot.tps());
            server.update(snapshot);
        }, SAMPLE_TICKS, SAMPLE_TICKS);

        getServer().getScheduler().runTaskTimer(this, () -> alertStore.recent(DASHBOARD_INCIDENTS_SHOWN,
                        incidents -> server.updateIncidents(StoredIncident.toJsonArray(incidents))),
                20L, DASHBOARD_INCIDENTS_TICKS);
    }

    /** Assembles the server state snapshot - only ever valid on the main thread. */
    private LiveSnapshot collectSnapshot() {
        return new LiveSnapshot(
                tickMonitor.tps(),
                tickMonitor.averageMspt(),
                tickMonitor.peakIntervalMs(),
                configManager.msptThresholdMs(),
                getServer().getOnlinePlayers().size(),
                tickMonitor.isRunning(),
                tickMonitor.isInIncident(),
                incidentsLast24h,
                lastCategory == null ? null : lastCategory.title(),
                sparkBridge.summary(),
                System.currentTimeMillis());
    }

    /** Reaction to a sustained MSPT breach - schedules a chunk scan. */
    private void handleSustainedLag() {
        runScan(false, this::reportIncident);
    }

    /** Reaction to the server recovering after an incident. */
    private void handleRecovery(long durationSeconds) {
        getLogger().info("Server is back to normal after " + durationSeconds + " s.");
        if (configManager.recoveryAlert()) {
            webhook.sendRecovery(durationSeconds, tickMonitor.tps(), tickMonitor.averageMspt());
        }
    }

    /** Stores the incident, prints it to the server log and sends it to Discord. */
    private void reportIncident(LagEvent event) {
        alertStore.record(event);
        lastCategory = event.category();
        incidentsLast24h++;

        getLogger().warning(String.format(
                "Sustained lag detected: MSPT %.1f ms, TPS %.2f, longest freeze %.0f ms. Cause: %s.",
                event.averageMspt(), event.tps(), event.peakMs(), event.category().title()));
        for (ChunkStat stat : event.topChunks()) {
            getLogger().warning(" - " + stat.prettyLocation()
                    + " (entities: " + stat.entityCount() + ", block entities: " + stat.tileEntityCount() + ")");
        }
        getLogger().warning("Suggestion: " + event.suggestedAction());
        webhook.sendLagAlert(event);
    }

    /**
     * Schedules a chunk scan using the monitor's current readings.
     *
     * <p>The scan is spread across upcoming ticks, so the result arrives through a callback
     * rather than as a return value.</p>
     *
     * @param manual   whether the scan was triggered by a command
     * @param callback receiver of the finished incident (main thread)
     * @return {@code false} if another scan is already running and this request was skipped
     */
    public boolean runScan(boolean manual, Consumer<LagEvent> callback) {
        return scanner.startScan(tickMonitor.tps(), tickMonitor.averageMspt(),
                tickMonitor.peakIntervalMs(), manual, callback);
    }

    /**
     * Records a manual report in the history and updates the counters.
     *
     * @param event incident from a manual scan
     */
    public void recordManual(LagEvent event) {
        alertStore.record(event);
        lastCategory = event.category();
    }

    /** @return the Discord webhook client */
    public DiscordWebhookClient webhook() {
        return webhook;
    }

    /** @return the plugin configuration manager */
    public ConfigManager configManager() {
        return configManager;
    }

    /** @return the running tick monitor */
    public TickMonitor tickMonitor() {
        return tickMonitor;
    }

    /** @return the incident store (SQLite or in-memory) */
    public AlertStore alertStore() {
        return alertStore;
    }

    /** @return the soft hook into spark (may be unavailable) */
    public SparkBridge sparkBridge() {
        return sparkBridge;
    }

    /** @return number of incidents in the last 24 hours, refreshed once a minute */
    public int incidentsLast24h() {
        return incidentsLast24h;
    }

    /** @return cause of the last incident, or {@code null} */
    public LagCategory lastCategory() {
        return lastCategory;
    }
}
