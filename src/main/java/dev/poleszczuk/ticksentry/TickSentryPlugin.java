package dev.poleszczuk.ticksentry;

import dev.poleszczuk.ticksentry.config.ConfigManager;
import dev.poleszczuk.ticksentry.monitor.TickMonitor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Punkt wejscia pluginu - spina konfiguracje, monitor tickow i alerty.
 */
public final class TickSentryPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private TickMonitor tickMonitor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.tickMonitor = new TickMonitor(this, configManager, this::handleSustainedLag);
        this.tickMonitor.start();

        getLogger().info("TickSentry aktywny - prog " + configManager.msptThresholdMs()
                + " ms przez " + configManager.sustainedSeconds() + " s.");
    }

    @Override
    public void onDisable() {
        if (tickMonitor != null) {
            tickMonitor.stop();
        }
    }

    /** Reakcja na trwale przekroczenie progu MSPT - na razie wpis do logu serwera. */
    private void handleSustainedLag() {
        getLogger().warning(String.format(
                "Wykryto trwaly lag: MSPT %.1f ms, TPS %.2f, najwiekszy skok %.0f ms.",
                tickMonitor.averageMspt(), tickMonitor.tps(), tickMonitor.peakIntervalMs()));
    }

    /** @return manager konfiguracji pluginu */
    public ConfigManager configManager() {
        return configManager;
    }

    /** @return dzialajacy monitor tickow */
    public TickMonitor tickMonitor() {
        return tickMonitor;
    }
}
