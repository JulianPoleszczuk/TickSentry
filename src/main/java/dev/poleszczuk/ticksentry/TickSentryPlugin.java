package dev.poleszczuk.ticksentry;

import dev.poleszczuk.ticksentry.commands.LagWatchCommand;
import dev.poleszczuk.ticksentry.config.ConfigManager;
import dev.poleszczuk.ticksentry.config.MessageBundle;
import dev.poleszczuk.ticksentry.discord.DiscordWebhookClient;
import dev.poleszczuk.ticksentry.monitor.AdaptiveThreshold;
import dev.poleszczuk.ticksentry.monitor.ChunkAttribution;
import dev.poleszczuk.ticksentry.monitor.ChunkHotspotScanner;
import dev.poleszczuk.ticksentry.monitor.ChunkLoadRate;
import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.ChunkVisitors;
import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.monitor.MemoryProbe;
import dev.poleszczuk.ticksentry.monitor.MemoryWatcher;
import dev.poleszczuk.ticksentry.monitor.PluginProfiler;
import dev.poleszczuk.ticksentry.monitor.PluginTiming;
import dev.poleszczuk.ticksentry.monitor.RegionLookup;
import dev.poleszczuk.ticksentry.monitor.SparkBridge;
import dev.poleszczuk.ticksentry.monitor.TickMonitor;
import dev.poleszczuk.ticksentry.placeholders.TickSentryExpansion;
import dev.poleszczuk.ticksentry.remedy.AutoRemediation;
import dev.poleszczuk.ticksentry.storage.AlertStore;
import dev.poleszczuk.ticksentry.storage.MemoryAlertStore;
import dev.poleszczuk.ticksentry.storage.OffenderIndex;
import dev.poleszczuk.ticksentry.storage.SqliteAlertStore;
import dev.poleszczuk.ticksentry.storage.StoredIncident;
import dev.poleszczuk.ticksentry.util.BStatsReporter;
import dev.poleszczuk.ticksentry.util.FoliaSupport;
import dev.poleszczuk.ticksentry.util.UpdateChecker;
import dev.poleszczuk.ticksentry.web.DashboardServer;
import dev.poleszczuk.ticksentry.web.LiveSnapshot;
import dev.poleszczuk.ticksentry.web.MetricsSnapshot;
import dev.poleszczuk.ticksentry.web.MsptHistory;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** How many repeat offenders are kept in the cached ranking. */
    private static final int OFFENDERS_TRACKED = 25;

    /** How many plugins get their own Prometheus time series. */
    private static final int METRICS_PLUGINS = 10;

    /**
     * How often (in ticks) old incidents are swept (24 hours).
     *
     * <p>Pruning only at startup meant a server that stays up for months never applied its own
     * {@code keep-days} setting - exactly the servers whose history grows enough to matter.</p>
     */
    private static final long PRUNE_TICKS = 20L * 60L * 60L * 24L;

    /** GitHub repository the update check reads releases from. */
    private static final String REPOSITORY = "JulianPoleszczuk/TickSentry";

    /** How often (in ticks) memory and the garbage collector are read (5 seconds). */
    private static final long MEMORY_POLL_TICKS = 20L * 5L;

    /** How often (in ticks) the plugin profiler closes a measurement bucket (5 seconds). */
    private static final long PROFILER_ROTATE_TICKS = 20L * 5L;

    /**
     * How often (in ticks) the profiler looks for listeners it has not wrapped yet (60 seconds).
     * Plugins can register listeners at any time, and one loaded after us starts out unmeasured.
     */
    private static final long PROFILER_INSTALL_TICKS = 20L * 60L;

    private ConfigManager configManager;
    private MessageBundle messages;
    private TickMonitor tickMonitor;
    private ChunkHotspotScanner scanner;
    private DiscordWebhookClient webhook;
    private AlertStore alertStore;
    private SparkBridge sparkBridge;
    private MemoryWatcher memoryWatcher;
    private PluginProfiler pluginProfiler;
    private ChunkVisitors chunkVisitors;
    private ChunkLoadRate chunkLoadRate;
    private RegionLookup regionLookup;
    private AutoRemediation remediation;
    private AdaptiveThreshold adaptiveThreshold;
    private DashboardServer dashboard;

    private volatile int incidentsLast24h;
    private volatile LagCategory lastCategory;
    private volatile OffenderIndex offenderIndex = OffenderIndex.empty();

    @Override
    public void onEnable() {
        if (FoliaSupport.isFolia()) {
            // Bail out before touching the scheduler - on Folia the very first runTaskTimer
            // throws, and an admin deserves a sentence rather than a stack trace.
            getLogger().severe(FoliaSupport.explanation());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.configManager.reportOutdatedConfig();
        this.messages = new MessageBundle(this);
        this.alertStore = openStore();
        this.sparkBridge = new SparkBridge(this);
        this.memoryWatcher = new MemoryWatcher(MEMORY_POLL_TICKS * 50L, messages);
        this.pluginProfiler = new PluginProfiler(this);
        this.chunkVisitors = new ChunkVisitors();
        this.regionLookup = new RegionLookup(this);
        this.chunkLoadRate = new ChunkLoadRate((int) (MEMORY_POLL_TICKS / 20L));
        this.scanner = new ChunkHotspotScanner(this, configManager, sparkBridge, memoryWatcher, pluginProfiler,
                new ChunkAttribution(this, chunkVisitors, regionLookup, this::offenderIndex),
                chunkLoadRate, messages);
        getServer().getPluginManager().registerEvents(chunkVisitors, this);
        getServer().getPluginManager().registerEvents(chunkLoadRate, this);
        this.webhook = new DiscordWebhookClient(this, configManager, messages, this::effectiveThresholdMs);
        this.remediation = new AutoRemediation(this, configManager::remedySettings, this::reportRemediation, messages);
        this.adaptiveThreshold = new AdaptiveThreshold(configManager.adaptiveSettings(),
                (int) (MEMORY_POLL_TICKS / 20L));
        this.tickMonitor = new TickMonitor(this, configManager, adaptiveThreshold,
                this::handleSustainedLag, this::handleRecovery);
        this.tickMonitor.start();

        PluginCommand command = getCommand("lagwatch");
        if (command != null) {
            LagWatchCommand handler = new LagWatchCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        registerPlaceholders();
        startUpdateCheck();
        startStatistics();
        startDashboard();
        startPluginProfiler();
        getServer().getScheduler().runTaskTimer(this, this::pollHealth, MEMORY_POLL_TICKS, MEMORY_POLL_TICKS);
        getServer().getScheduler().runTaskTimer(this,
                () -> alertStore.prune(configManager.storageKeepDays()), PRUNE_TICKS, PRUNE_TICKS);
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
        // Every field below is null when onEnable bailed out early, so each is guarded.
        if (tickMonitor != null) {
            tickMonitor.stop();
        }
        if (pluginProfiler != null) {
            // Puts every wrapped listener back, so a /reload leaves the server as we found it.
            pluginProfiler.stop();
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

    /**
     * Starts timing other plugins' event handlers.
     *
     * <p>Installing is deferred to the first tick on purpose: at {@code onEnable} time the
     * plugins loaded after us have not registered their listeners yet, and those are exactly
     * the ones worth measuring.</p>
     */
    private void startPluginProfiler() {
        if (!configManager.profilerEnabled()) {
            getLogger().info("Plugin profiling is off - alerts will not be able to name a plugin.");
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> {
            pluginProfiler.start();
            getLogger().info("Profiling " + pluginProfiler.wrappedListeners() + " event handlers from other plugins.");
        }, 1L);

        getServer().getScheduler().runTaskTimer(this, pluginProfiler::rotate,
                PROFILER_ROTATE_TICKS, PROFILER_ROTATE_TICKS);
        getServer().getScheduler().runTaskTimer(this, pluginProfiler::install,
                PROFILER_INSTALL_TICKS, PROFILER_INSTALL_TICKS);
    }

    /**
     * Asks GitHub whether a newer release exists.
     *
     * <p>A monitoring plugin quietly running a version with a fixed detection bug is worse than
     * useless, and nobody re-reads a release page for a plugin that has been working.</p>
     */
    private void startUpdateCheck() {
        if (!configManager.updateCheck()) {
            return;
        }
        UpdateChecker checker = new UpdateChecker(this, REPOSITORY, messages);
        getServer().getPluginManager().registerEvents(checker, this);
        checker.checkAsync();
    }

    /**
     * Starts sending anonymous usage statistics, if both this config and bStats' own allow it.
     *
     * <p>The charts are chosen to answer questions that change what gets built next: which
     * Minecraft versions still need supporting, and whether anyone actually turns the optional
     * features on.</p>
     */
    private void startStatistics() {
        if (!configManager.bstatsEnabled()) {
            return;
        }
        BStatsReporter reporter = new BStatsReporter(this);
        reporter.chart("minecraft_version", getServer().getBukkitVersion().split("-")[0]);
        reporter.chart("discord_configured", String.valueOf(configManager.discordEnabled()));
        reporter.chart("profiler_enabled", String.valueOf(configManager.profilerEnabled()));
        reporter.chart("dashboard_enabled", String.valueOf(configManager.dashboardEnabled()));
        reporter.chart("storage_backend", alertStore.describe().startsWith("SQLite") ? "sqlite" : "memory");
        reporter.chart("remediation_mode", !configManager.remedySettings().enabled() ? "off"
                : configManager.remedySettings().dryRun() ? "dry-run" : "active");
        reporter.start();
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

    /**
     * Refreshes the numbers an alert has to have ready the instant it fires.
     *
     * <p>Both come from the database, and an alert is assembled on the main thread, which must
     * never wait on a read. So they are fetched in the background and cached.</p>
     */
    private void refreshCounters() {
        alertStore.stats(1, stats -> this.incidentsLast24h = stats.total());
        alertStore.offenders(configManager.offenderDays(), OFFENDERS_TRACKED,
                offenders -> this.offenderIndex = OffenderIndex.of(offenders));
    }

    /** @return the current repeat offender ranking, never {@code null} */
    public OffenderIndex offenderIndex() {
        return offenderIndex;
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
        DashboardServer server = new DashboardServer(this, token, history, configManager.dashboardMetrics());
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
            if (configManager.dashboardMetrics()) {
                server.updateMetrics(collectMetrics());
            }
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
                tickMonitor.p95Mspt(),
                tickMonitor.p99Mspt(),
                tickMonitor.peakIntervalMs(),
                tickMonitor.thresholdMs(),
                getServer().getOnlinePlayers().size(),
                tickMonitor.isRunning(),
                tickMonitor.isInIncident(),
                incidentsLast24h,
                lastCategory == null ? null : lastCategory.title(),
                sparkBridge.summary(),
                System.currentTimeMillis());
    }

    /**
     * Assembles the Prometheus snapshot - only ever valid on the main thread.
     *
     * <p>Plugin timings are capped at {@value #METRICS_PLUGINS} entries. Every distinct label
     * value becomes its own time series in Prometheus, and a server with eighty plugins would
     * otherwise quietly multiply the storage cost of the whole endpoint.</p>
     */
    private MetricsSnapshot collectMetrics() {
        MemoryProbe.MemorySample memory = memoryWatcher.sample();

        int loadedChunks = 0;
        for (World world : getServer().getWorlds()) {
            loadedChunks += world.getLoadedChunks().length;
        }

        Map<String, Double> pluginSeconds = new LinkedHashMap<>();
        for (PluginTiming timing : pluginProfiler.report(configManager.profilerWindowSeconds())
                .top(METRICS_PLUGINS)) {
            pluginSeconds.put(timing.pluginName(), timing.totalNanos() / 1_000_000_000.0D);
        }

        return new MetricsSnapshot(
                tickMonitor.tps(),
                tickMonitor.averageMspt(),
                tickMonitor.p95Mspt(),
                tickMonitor.p99Mspt(),
                tickMonitor.peakIntervalMs(),
                tickMonitor.thresholdMs(),
                getServer().getOnlinePlayers().size(),
                tickMonitor.isRunning(),
                tickMonitor.isInIncident(),
                incidentsLast24h,
                memory == null ? 0L : memory.usedBytes(),
                memory == null ? -1L : memory.maxBytes(),
                memory == null ? 0L : memory.collections(),
                memory == null ? 0L : memory.collectionMs(),
                loadedChunks,
                offenderIndex.ranked().size(),
                pluginSeconds,
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
                    + " (entities: " + stat.entityCount() + ", block entities: " + stat.tileEntityCount() + ")"
                    + (stat.attribution() == null ? "" : " - " + stat.attribution()));
            if (stat.historyNote() != null) {
                getLogger().warning("   This chunk is a repeat offender: " + stat.historyNote() + ".");
            }
        }
        if (event.memoryNote() != null) {
            getLogger().warning("Memory: " + event.memoryNote());
        }
        if (event.pluginNote() != null) {
            getLogger().warning("Plugin: " + event.pluginNote());
        }
        if (event.chunkLoadNote() != null) {
            getLogger().warning("Chunk loading: " + event.chunkLoadNote());
        }
        getLogger().warning("Suggestion: " + event.suggestedAction());
        webhook.sendLagAlert(event);
        announceInGame(event);
        remediation.consider(event);
    }

    /**
     * Passes on what the automatic clean-up did, or would have done in dry-run.
     *
     * <p>Deleting things players own is not something to do quietly, so it reaches the console,
     * the admins in game and Discord alike.</p>
     *
     * @param summary multi-line description of the actions
     */
    private void reportRemediation(String summary) {
        getLogger().warning(summary);
        String line = messages.get("remedy.summary", "summary", summary);
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.hasPermission("ticksentry.alerts")) {
                player.sendMessage(line);
            }
        }
        webhook.sendRemediation(summary);
    }

    /** @return the automatic clean-up, which does nothing unless an admin enabled it */
    public AutoRemediation remediation() {
        return remediation;
    }

    /**
     * Tells admins who are online right now, so they do not have to be watching Discord.
     * Only players holding {@code ticksentry.alerts} get the message.
     */
    private void announceInGame(LagEvent event) {
        if (!configManager.inGameAlerts()) {
            return;
        }
        ChunkStat primary = event.primaryChunk();
        String cause = messages.categoryTitle(event.category());

        String headline = primary == null
                ? messages.get("alert.lagging-no-location", "cause", cause)
                : messages.get("alert.lagging",
                        "cause", cause,
                        "location", primary.prettyLocation(),
                        "x", String.valueOf(primary.blockX()),
                        "z", String.valueOf(primary.blockZ()));

        String whose = primary == null || primary.attribution() == null
                ? null
                : messages.get("alert.attribution", "attribution", primary.attribution());
        String record = primary == null || primary.historyNote() == null
                ? null
                : messages.get("alert.repeat-offender", "history", primary.historyNote());

        for (Player player : getServer().getOnlinePlayers()) {
            if (player.hasPermission("ticksentry.alerts")) {
                player.sendMessage(headline);
                if (whose != null) {
                    player.sendMessage(whose);
                }
                if (record != null) {
                    player.sendMessage(record);
                }
            }
        }
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
                tickMonitor.peakIntervalMs(), manual,
                // Percentiles are attached here rather than inside the scan: the analysis code has
                // no business knowing about them, and a scan spread over a few ticks reads the same
                // hundred-tick window either way.
                event -> callback.accept(event.withPercentiles(
                        tickMonitor.p95Mspt(), tickMonitor.p99Mspt())));
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

    /** @return the translatable text shown to players */
    public MessageBundle messages() {
        return messages;
    }

    /** @return the running tick monitor */
    public TickMonitor tickMonitor() {
        return tickMonitor;
    }

    /** @return the incident store (SQLite or in-memory) */
    public AlertStore alertStore() {
        return alertStore;
    }

    /**
     * Takes the periodic health readings: memory, and a sample for the adaptive baseline.
     *
     * <p>Nothing is fed to the baseline during an incident. The whole point of it is what the
     * server looks like when it is behaving - teaching it that a bad hour is normal would raise
     * the threshold exactly when it should not move.</p>
     */
    private void pollHealth() {
        memoryWatcher.poll();
        chunkLoadRate.rotate();
        if (!tickMonitor.isInIncident()) {
            // The reading the threshold is compared against, not the average: a baseline learned
            // from means and then compared against a p99 would sit permanently too low.
            adaptiveThreshold.record(tickMonitor.triggerMspt(), configManager.msptThresholdMs());
        }
    }

    /** @return the tick time above which the server currently counts as overloaded */
    public double effectiveThresholdMs() {
        return tickMonitor.thresholdMs();
    }

    /** @return the threshold that learns this server's normal tick time */
    public AdaptiveThreshold adaptiveThreshold() {
        return adaptiveThreshold;
    }

    /** @return the memory and garbage collector watcher */
    public MemoryWatcher memoryWatcher() {
        return memoryWatcher;
    }

    /** @return the profiler timing other plugins' event handlers */
    public PluginProfiler pluginProfiler() {
        return pluginProfiler;
    }

    /** @return the tracker of who was last seen in which chunk */
    public ChunkVisitors chunkVisitors() {
        return chunkVisitors;
    }

    /** @return the soft hooks into land protection plugins */
    public RegionLookup regionLookup() {
        return regionLookup;
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
