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
 * Punkt wejscia pluginu - spina konfiguracje, monitor tickow, sklad incydentow i alerty.
 */
public final class TickSentryPlugin extends JavaPlugin {

    /** Ile ostatnich incydentow pamieta zapasowy sklad w pamieci. */
    private static final int MEMORY_CAPACITY = 50;

    /** Jak czesto (w tickach) odswiezany jest licznik incydentow na potrzeby placeholderow. */
    private static final long COUNTER_REFRESH_TICKS = 20L * 60L;

    /** Co ile tickow zbierana jest probka do wykresu na dashboardzie (5 sekund). */
    private static final long SAMPLE_TICKS = 20L * 5L;

    /** Ile probek trzyma wykres - 720 probek co 5 s daje godzine historii. */
    private static final int SAMPLE_CAPACITY = 720;

    /** Co ile tickow odswiezana jest lista incydentow w panelu (30 sekund). */
    private static final long DASHBOARD_INCIDENTS_TICKS = 20L * 30L;

    /** Ile incydentow pokazuje panel webowy. */
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
        // Pierwszy odczyt po sekundzie, zeby panel i placeholdery nie pokazywaly zera przez cala minute.
        getServer().getScheduler().runTaskTimer(this, this::refreshCounters, 20L, COUNTER_REFRESH_TICKS);

        getLogger().info("TickSentry aktywny - prog " + configManager.msptThresholdMs()
                + " ms przez " + configManager.sustainedSeconds() + " s. Historia: " + alertStore.describe() + ".");
        if (!configManager.discordEnabled()) {
            getLogger().info("Alerty na Discord sa wylaczone lub brak webhook-url w config.yml.");
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
     * Otwiera sklad incydentow zgodnie z konfiguracja.
     * Gdy zapis na dysk jest wylaczony albo baza nie da sie otworzyc, wracamy do historii w pamieci.
     */
    private AlertStore openStore() {
        if (!configManager.storageEnabled()) {
            return new MemoryAlertStore(MEMORY_CAPACITY);
        }
        AlertStore store = SqliteAlertStore.open(this,
                new File(getDataFolder(), "incidents.db"), configManager.storageKeepDays());
        return store != null ? store : new MemoryAlertStore(MEMORY_CAPACITY);
    }

    /** Rejestruje placeholdery, jesli PlaceholderAPI jest na serwerze. */
    private void registerPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new TickSentryExpansion(this).register();
            getLogger().info("Zarejestrowano placeholdery %ticksentry_...%.");
        } catch (RuntimeException | NoClassDefFoundError ex) {
            getLogger().warning("Nie udalo sie zarejestrowac placeholderow: " + ex);
        }
    }

    /** Odswieza licznik incydentow z ostatniej doby - placeholdery nie moga pytac bazy same. */
    private void refreshCounters() {
        alertStore.stats(1, stats -> this.incidentsLast24h = stats.total());
    }

    /**
     * Uruchamia panel webowy, jesli jest wlaczony w konfiguracji.
     * Token generujemy przy pierwszym starcie i zapisujemy, zeby adres panelu byl staly.
     */
    private void startDashboard() {
        if (!configManager.dashboardEnabled()) {
            return;
        }
        String token = configManager.dashboardToken();
        if (token.isEmpty()) {
            token = DashboardServer.generateToken();
            configManager.saveDashboardToken(token);
            getLogger().info("Wygenerowano token panelu i zapisano go w config.yml.");
        }

        MsptHistory history = new MsptHistory(SAMPLE_CAPACITY);
        DashboardServer server = new DashboardServer(this, token, history);
        if (!server.start(configManager.dashboardBind(), configManager.dashboardPort())) {
            return;
        }
        this.dashboard = server;

        if (!"127.0.0.1".equals(configManager.dashboardBind()) && !"localhost".equals(configManager.dashboardBind())) {
            getLogger().warning("Panel nasluchuje na " + configManager.dashboardBind()
                    + " bez szyfrowania - token leci otwartym tekstem. Rozwaz tunel SSH albo proxy z HTTPS.");
        }

        // Migawki i probki zbiera glowny watek; handlery HTTP tylko je odczytuja.
        getServer().getScheduler().runTaskTimer(this, () -> {
            LiveSnapshot snapshot = collectSnapshot();
            history.add(snapshot.generatedAt(), snapshot.mspt(), snapshot.tps());
            server.update(snapshot);
        }, SAMPLE_TICKS, SAMPLE_TICKS);

        getServer().getScheduler().runTaskTimer(this, () -> alertStore.recent(DASHBOARD_INCIDENTS_SHOWN,
                        incidents -> server.updateIncidents(StoredIncident.toJsonArray(incidents))),
                20L, DASHBOARD_INCIDENTS_TICKS);
    }

    /** Sklada migawke stanu serwera - wolno to robic wylacznie na glownym watku. */
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

    /** Reakcja na trwale przekroczenie progu MSPT - zleca skan chunkow. */
    private void handleSustainedLag() {
        runScan(false, this::reportIncident);
    }

    /** Reakcja na powrot serwera do normy po incydencie. */
    private void handleRecovery(long durationSeconds) {
        getLogger().info("Serwer wrocil do normy po " + durationSeconds + " s.");
        if (configManager.recoveryAlert()) {
            webhook.sendRecovery(durationSeconds, tickMonitor.tps(), tickMonitor.averageMspt());
        }
    }

    /** Zapisuje incydent, wypisuje go do logu serwera i wysyla na Discorda. */
    private void reportIncident(LagEvent event) {
        alertStore.record(event);
        lastCategory = event.category();
        incidentsLast24h++;

        getLogger().warning(String.format(
                "Wykryto trwaly lag: MSPT %.1f ms, TPS %.2f, najwiekszy skok %.0f ms. Przyczyna: %s.",
                event.averageMspt(), event.tps(), event.peakMs(), event.category().title()));
        for (ChunkStat stat : event.topChunks()) {
            getLogger().warning(" - " + stat.prettyLocation()
                    + " (encje: " + stat.entityCount() + ", block-entity: " + stat.tileEntityCount() + ")");
        }
        getLogger().warning("Sugestia: " + event.suggestedAction());
        webhook.sendLagAlert(event);
    }

    /**
     * Zleca skan chunkow z aktualnymi odczytami monitora.
     *
     * <p>Skan jest rozlozony na kolejne ticki, wiec wynik przychodzi callbackiem, a nie zwrotka.</p>
     *
     * @param manual   czy skan zostal wywolany recznie komenda
     * @param callback odbiorca gotowego incydentu (glowny watek)
     * @return {@code false}, jesli inny skan juz trwa i zlecenie zostalo pominiete
     */
    public boolean runScan(boolean manual, Consumer<LagEvent> callback) {
        return scanner.startScan(tickMonitor.tps(), tickMonitor.averageMspt(),
                tickMonitor.peakIntervalMs(), manual, callback);
    }

    /**
     * Zapisuje reczny raport w historii i aktualizuje liczniki.
     *
     * @param event incydent z recznego skanu
     */
    public void recordManual(LagEvent event) {
        alertStore.record(event);
        lastCategory = event.category();
    }

    /** @return klient webhooka Discorda */
    public DiscordWebhookClient webhook() {
        return webhook;
    }

    /** @return manager konfiguracji pluginu */
    public ConfigManager configManager() {
        return configManager;
    }

    /** @return dzialajacy monitor tickow */
    public TickMonitor tickMonitor() {
        return tickMonitor;
    }

    /** @return sklad incydentow (SQLite albo pamiec) */
    public AlertStore alertStore() {
        return alertStore;
    }

    /** @return miekkie podpiecie pod spark (moze byc niedostepne) */
    public SparkBridge sparkBridge() {
        return sparkBridge;
    }

    /** @return liczba incydentow z ostatniej doby, odswiezana co minute */
    public int incidentsLast24h() {
        return incidentsLast24h;
    }

    /** @return przyczyna ostatniego incydentu albo {@code null} */
    public LagCategory lastCategory() {
        return lastCategory;
    }
}
