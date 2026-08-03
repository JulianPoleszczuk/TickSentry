package dev.poleszczuk.ticksentry;

import dev.poleszczuk.ticksentry.commands.LagWatchCommand;
import dev.poleszczuk.ticksentry.config.ConfigManager;
import dev.poleszczuk.ticksentry.discord.DiscordWebhookClient;
import dev.poleszczuk.ticksentry.monitor.ChunkHotspotScanner;
import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.monitor.SparkBridge;
import dev.poleszczuk.ticksentry.monitor.TickMonitor;
import dev.poleszczuk.ticksentry.storage.AlertHistory;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Punkt wejscia pluginu - spina konfiguracje, monitor tickow i alerty.
 */
public final class TickSentryPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private TickMonitor tickMonitor;
    private ChunkHotspotScanner scanner;
    private DiscordWebhookClient webhook;
    private AlertHistory alertHistory;
    private SparkBridge sparkBridge;

    /** Ile ostatnich incydentow pamieta plugin do {@code /lagwatch history}. */
    private static final int HISTORY_CAPACITY = 25;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.alertHistory = new AlertHistory(HISTORY_CAPACITY);
        this.sparkBridge = new SparkBridge(this);
        this.scanner = new ChunkHotspotScanner(this, configManager, sparkBridge);
        this.webhook = new DiscordWebhookClient(this, configManager);
        this.tickMonitor = new TickMonitor(this, configManager, this::handleSustainedLag);
        this.tickMonitor.start();

        PluginCommand command = getCommand("lagwatch");
        if (command != null) {
            LagWatchCommand handler = new LagWatchCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        getLogger().info("TickSentry aktywny - prog " + configManager.msptThresholdMs()
                + " ms przez " + configManager.sustainedSeconds() + " s.");
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
    }

    /** Reakcja na trwale przekroczenie progu MSPT - skanuje chunki i raportuje incydent. */
    private void handleSustainedLag() {
        LagEvent event = runScan(false);
        alertHistory.record(event);
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

    /** @return klient webhooka Discorda */
    public DiscordWebhookClient webhook() {
        return webhook;
    }

    /**
     * Wykonuje skan chunkow z aktualnymi odczytami monitora.
     *
     * @param manual czy skan zostal wywolany recznie komenda
     * @return zebrany incydent
     */
    public LagEvent runScan(boolean manual) {
        return scanner.scan(tickMonitor.tps(), tickMonitor.averageMspt(), tickMonitor.peakIntervalMs(), manual);
    }

    /** @return manager konfiguracji pluginu */
    public ConfigManager configManager() {
        return configManager;
    }

    /** @return dzialajacy monitor tickow */
    public TickMonitor tickMonitor() {
        return tickMonitor;
    }

    /** @return historia incydentow z biezacej sesji serwera */
    public AlertHistory alertHistory() {
        return alertHistory;
    }

    /** @return miekkie podpiecie pod spark (moze byc niedostepne) */
    public SparkBridge sparkBridge() {
        return sparkBridge;
    }
}
