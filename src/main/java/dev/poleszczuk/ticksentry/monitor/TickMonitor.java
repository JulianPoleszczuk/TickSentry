package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.ConfigManager;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Mierzy kondycje serwera co tick i zglasza trwale przeciazenie.
 *
 * <p>Jako MSPT uzywany jest {@link Server#getAverageTickTime()} - to faktyczny czas
 * wykonania ticku (zdrowy serwer: 5-25 ms). Odstep miedzy wywolaniami zadania
 * mierzony {@code System.nanoTime()} sluzy osobno do wykrywania chwilowych zwiech:
 * przy zdrowym serwerze wynosi on zawsze ~50 ms, wiec nie nadaje sie na prog alarmowy,
 * ale jego szczytowa wartosc dobrze pokazuje skale najwiekszego zacieca w oknie.</p>
 */
public final class TickMonitor implements Runnable {

    /** Docelowy odstep miedzy tickami przy 20 TPS. */
    private static final double TARGET_TICK_MS = 50.0D;

    private final Plugin plugin;
    private final ConfigManager config;
    private final Server server;
    private final Runnable onSustainedLag;

    private double[] msptSamples;
    private double[] intervalSamples;
    private int cursor;
    private int filled;

    private long lastTickNanos;
    private long breachStartMillis = -1L;
    private long lastAlertMillis = 0L;
    private BukkitTask task;

    /**
     * @param plugin         instancja pluginu (uzywana do schedulera)
     * @param config         zrodlo progow i okien czasowych
     * @param onSustainedLag akcja wywolywana na glownym watku po wykryciu trwalego lagu
     */
    public TickMonitor(Plugin plugin, ConfigManager config, Runnable onSustainedLag) {
        this.plugin = plugin;
        this.config = config;
        this.server = plugin.getServer();
        this.onSustainedLag = onSustainedLag;
        resizeWindow();
    }

    /** Uruchamia pomiar - zadanie synchroniczne wykonywane co tick. */
    public void start() {
        if (task == null) {
            task = server.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
        }
    }

    /** Zatrzymuje pomiar i zwalnia zadanie schedulera. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** @return {@code true} jesli monitor jest aktualnie uruchomiony */
    public boolean isRunning() {
        return task != null;
    }

    /**
     * Czysci zebrane probki i resetuje stan wykrywania.
     * Wywolywane po przeladowaniu konfiguracji, bo moglo zmienic sie okno sredniej.
     */
    public void reset() {
        resizeWindow();
        lastTickNanos = 0L;
        breachStartMillis = -1L;
    }

    @Override
    public void run() {
        long now = System.nanoTime();
        if (lastTickNanos != 0L) {
            intervalSamples[cursor] = (now - lastTickNanos) / 1_000_000.0D;
        } else {
            intervalSamples[cursor] = TARGET_TICK_MS;
        }
        lastTickNanos = now;

        msptSamples[cursor] = server.getAverageTickTime();
        cursor = (cursor + 1) % msptSamples.length;
        if (filled < msptSamples.length) {
            filled++;
        }

        // Dopoki okno nie jest pelne, srednia jest niemiarodajna (np. tuz po starcie serwera).
        if (filled < msptSamples.length) {
            return;
        }
        evaluate();
    }

    /** Sprawdza, czy prog jest przekroczony wystarczajaco dlugo, i ewentualnie odpala alert. */
    private void evaluate() {
        long nowMillis = System.currentTimeMillis();
        if (averageMspt() <= config.msptThresholdMs()) {
            breachStartMillis = -1L;
            return;
        }

        if (breachStartMillis < 0L) {
            breachStartMillis = nowMillis;
            return;
        }

        if (nowMillis - breachStartMillis < config.sustainedSeconds() * 1000L) {
            return;
        }

        // Prog trzyma sie wystarczajaco dlugo - liczymy okno od nowa niezaleznie od cooldownu,
        // zeby po jego wygasnieciu alert wymagal ponownie pelnego okresu przeciazenia.
        breachStartMillis = nowMillis;

        if (nowMillis - lastAlertMillis < config.scanCooldownSeconds() * 1000L) {
            return;
        }
        lastAlertMillis = nowMillis;

        try {
            onSustainedLag.run();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Blad podczas obslugi wykrytego lagu: " + ex);
        }
    }

    /** @return srednia krocząca MSPT z okna pomiarowego, w milisekundach */
    public double averageMspt() {
        if (filled == 0) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (int i = 0; i < filled; i++) {
            sum += msptSamples[i];
        }
        return sum / filled;
    }

    /** @return najdluzszy odstep miedzy tickami w oknie pomiarowym, w milisekundach */
    public double peakIntervalMs() {
        double peak = 0.0D;
        for (int i = 0; i < filled; i++) {
            peak = Math.max(peak, intervalSamples[i]);
        }
        return peak;
    }

    /** @return TPS z ostatniej minuty, przyciete do maksymalnie 20.0 */
    public double tps() {
        double[] tps = server.getTPS();
        return tps.length == 0 ? 20.0D : Math.min(20.0D, tps[0]);
    }

    /** @return jak dlugo (w sekundach) prog MSPT jest nieprzerwanie przekroczony; 0 gdy serwer jest zdrowy */
    public long currentBreachSeconds() {
        return breachStartMillis < 0L ? 0L : (System.currentTimeMillis() - breachStartMillis) / 1000L;
    }

    /** @return liczba sekund pozostalych do konca cooldownu alertow; 0 gdy alert moze polecec od razu */
    public long alertCooldownRemainingSeconds() {
        long elapsed = System.currentTimeMillis() - lastAlertMillis;
        long cooldown = config.scanCooldownSeconds() * 1000L;
        return elapsed >= cooldown ? 0L : (cooldown - elapsed) / 1000L;
    }

    /** Odnotowuje, ze alert wlasnie zostal wyslany - resetuje cooldown (uzywane przy alertach recznych). */
    public void markAlertSent() {
        lastAlertMillis = System.currentTimeMillis();
    }

    private void resizeWindow() {
        int size = config.rollingAverageTicks();
        this.msptSamples = new double[size];
        this.intervalSamples = new double[size];
        this.cursor = 0;
        this.filled = 0;
    }
}
