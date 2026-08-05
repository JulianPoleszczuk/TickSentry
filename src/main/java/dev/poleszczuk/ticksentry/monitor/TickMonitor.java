package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.MonitorSettings;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Measures server health every tick and reports sustained overload.
 *
 * <p>MSPT comes from {@link Server#getAverageTickTime()} - the actual tick execution time
 * (a healthy server sits at 5-25 ms). The gap between task invocations, measured with
 * {@code System.nanoTime()}, is tracked separately to catch momentary freezes: on a healthy
 * server that gap is always ~50 ms, so it makes a useless alert threshold, but its peak value
 * shows nicely how bad the worst stall in the window was.</p>
 */
public final class TickMonitor implements Runnable {

    /** Target gap between ticks at 20 TPS. */
    private static final double TARGET_TICK_MS = 50.0D;

    private final Plugin plugin;
    private final MonitorSettings config;
    private final AdaptiveThreshold adaptive;
    private final Server server;
    private final Runnable onSustainedLag;
    private final LongConsumer onRecovered;

    /**
     * Source of "now" in milliseconds.
     *
     * <p>Every decision this class makes is about elapsed time - has the breach lasted long
     * enough, has the cooldown expired, has the server been calm long enough to call the incident
     * over. Reading the clock through a supplier is what lets a test walk time forward instead of
     * sleeping through it.</p>
     */
    private final LongSupplier clock;

    private double[] msptSamples;
    private double[] intervalSamples;
    private int cursor;
    private int filled;

    private long lastTickNanos;
    private long breachStartMillis = -1L;
    private long lastAlertMillis = 0L;
    private BukkitTask task;

    private boolean inIncident;
    private long incidentStartMillis = -1L;
    private long recoveryStartMillis = -1L;

    /**
     * @param plugin         plugin instance (used for the scheduler)
     * @param config         source of thresholds and time windows
     * @param adaptive       threshold that learns this server's normal tick time
     * @param onSustainedLag action run on the main thread once sustained lag is detected
     * @param onRecovered    action run once the server recovers, with the incident length in seconds
     */
    public TickMonitor(Plugin plugin, MonitorSettings config, AdaptiveThreshold adaptive,
                       Runnable onSustainedLag, LongConsumer onRecovered) {
        this(plugin, config, adaptive, onSustainedLag, onRecovered, System::currentTimeMillis);
    }

    /**
     * @param plugin         plugin instance (used for the scheduler)
     * @param config         source of thresholds and time windows
     * @param adaptive       threshold that learns this server's normal tick time
     * @param onSustainedLag action run on the main thread once sustained lag is detected
     * @param onRecovered    action run once the server recovers, with the incident length in seconds
     * @param clock          source of the current time in milliseconds
     */
    TickMonitor(Plugin plugin, MonitorSettings config, AdaptiveThreshold adaptive,
                Runnable onSustainedLag, LongConsumer onRecovered, LongSupplier clock) {
        this.plugin = plugin;
        this.config = config;
        this.adaptive = adaptive;
        this.server = plugin.getServer();
        this.onSustainedLag = onSustainedLag;
        this.onRecovered = onRecovered;
        this.clock = clock;
        resizeWindow();
    }

    /** Starts measuring - a synchronous task running every tick. */
    public void start() {
        if (task == null) {
            task = server.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
        }
    }

    /** Stops measuring and releases the scheduler task. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** @return {@code true} if the monitor is currently running */
    public boolean isRunning() {
        return task != null;
    }

    /**
     * Clears collected samples and resets detection state.
     * Called after a config reload, because the averaging window may have changed.
     */
    public void reset() {
        resizeWindow();
        lastTickNanos = 0L;
        breachStartMillis = -1L;
        recoveryStartMillis = -1L;
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

        // Until the window is full the average means little - for example right after startup.
        if (filled < msptSamples.length) {
            return;
        }
        evaluate();
    }

    /** Checks whether the threshold has been exceeded long enough and fires an alert if so. */
    private void evaluate() {
        long nowMillis = clock.getAsLong();
        if (averageMspt() <= thresholdMs()) {
            breachStartMillis = -1L;
            checkRecovery(nowMillis);
            return;
        }

        // Back above the threshold - any countdown towards "recovered" starts from scratch.
        recoveryStartMillis = -1L;

        if (breachStartMillis < 0L) {
            breachStartMillis = nowMillis;
            return;
        }

        if (nowMillis - breachStartMillis < config.sustainedSeconds() * 1000L) {
            return;
        }

        // An incident starts when the threshold was first crossed, not when the alert goes out.
        if (!inIncident) {
            inIncident = true;
            incidentStartMillis = breachStartMillis;
        }

        // The threshold held long enough - restart the window regardless of the cooldown, so that
        // once the cooldown expires an alert still requires a full period of overload.
        breachStartMillis = nowMillis;

        if (nowMillis - lastAlertMillis < config.scanCooldownSeconds() * 1000L) {
            return;
        }
        lastAlertMillis = nowMillis;

        try {
            onSustainedLag.run();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Error while handling detected lag: " + ex);
        }
    }

    /**
     * Watches whether an ongoing incident has ended.
     * A full quiet window is required, so a momentary breather is not announced as a recovery.
     */
    private void checkRecovery(long nowMillis) {
        if (!inIncident) {
            return;
        }
        if (recoveryStartMillis < 0L) {
            recoveryStartMillis = nowMillis;
            return;
        }
        if (nowMillis - recoveryStartMillis < config.recoverySeconds() * 1000L) {
            return;
        }

        long durationSeconds = Math.max(0L, (nowMillis - incidentStartMillis) / 1000L);
        inIncident = false;
        recoveryStartMillis = -1L;
        incidentStartMillis = -1L;

        try {
            onRecovered.accept(durationSeconds);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Error while handling recovery: " + ex);
        }
    }

    /**
     * @return the tick time above which the server counts as overloaded right now - either the
     *         fixed value from the configuration or the one the adaptive threshold worked out
     */
    public double thresholdMs() {
        return adaptive.threshold(config.msptThresholdMs());
    }

    /** @return {@code true} if an unfinished lag incident is in progress */
    public boolean isInIncident() {
        return inIncident;
    }

    /** @return rolling average MSPT over the sample window, in milliseconds */
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

    /** @return longest gap between ticks in the sample window, in milliseconds */
    public double peakIntervalMs() {
        double peak = 0.0D;
        for (int i = 0; i < filled; i++) {
            peak = Math.max(peak, intervalSamples[i]);
        }
        return peak;
    }

    /** @return one-minute TPS, capped at 20.0 */
    public double tps() {
        double[] tps = server.getTPS();
        return tps.length == 0 ? 20.0D : Math.min(20.0D, tps[0]);
    }

    /** @return how long (in seconds) the MSPT threshold has been continuously exceeded; 0 when healthy */
    public long currentBreachSeconds() {
        return breachStartMillis < 0L ? 0L : (clock.getAsLong() - breachStartMillis) / 1000L;
    }

    /** @return seconds left of the alert cooldown; 0 when an alert can fire immediately */
    public long alertCooldownRemainingSeconds() {
        long elapsed = clock.getAsLong() - lastAlertMillis;
        long cooldown = config.scanCooldownSeconds() * 1000L;
        return elapsed >= cooldown ? 0L : (cooldown - elapsed) / 1000L;
    }

    /** Records that an alert has just been sent - resets the cooldown (used for manual alerts). */
    public void markAlertSent() {
        lastAlertMillis = clock.getAsLong();
    }

    private void resizeWindow() {
        int size = config.rollingAverageTicks();
        this.msptSamples = new double[size];
        this.intervalSamples = new double[size];
        this.cursor = 0;
        this.filled = 0;
    }
}
