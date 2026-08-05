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
 * <p>Tick times come from {@link TickTimeSource} - real per-tick durations on Paper, the server's
 * own pre-averaged reading on Spigot, which is all Spigot offers. They land in a rolling window
 * that reports both a mean and percentiles, because the two answer different questions: the mean
 * says whether the server is generally behind, p95 says how bad its bad ticks are. A server
 * averaging 25 ms with a p99 of 400 ms is not healthy, and the mean alone would call it so.</p>
 *
 * <p>The gap between task invocations, measured with {@code System.nanoTime()}, is tracked
 * separately. It cannot tell 5 ms from 45 ms - the server sleeps out the difference - so it makes a
 * useless threshold, but it catches time that passes <em>between</em> ticks and never appears in any
 * tick's duration, which is exactly what a stop-the-world pause looks like.</p>
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

    private final TickTimeSource tickTimeSource;

    /** Tick durations - what the threshold is compared against. */
    private TickSamples tickTimes;

    /** Wall-clock gaps between our own invocations - the freeze gauge. */
    private TickSamples intervals;

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
        this.tickTimeSource = new TickTimeSource(this.server);
        resizeWindow();
    }

    /** @return where tick times are being read from, for {@code /lagwatch status} */
    public TickTimeSource tickTimeSource() {
        return tickTimeSource;
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
        tickTimeSource.reset();
        lastTickNanos = 0L;
        breachStartMillis = -1L;
        recoveryStartMillis = -1L;
    }

    @Override
    public void run() {
        long now = System.nanoTime();
        intervals.add(lastTickNanos == 0L ? TARGET_TICK_MS : (now - lastTickNanos) / 1_000_000.0D);
        lastTickNanos = now;

        tickTimeSource.drainInto(tickTimes);

        // Until the window is full the readings mean little - right after startup most of all.
        if (!tickTimes.isFull()) {
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
        return tickTimes.mean();
    }

    /** @return 95th percentile tick time in the window - how bad this server's bad ticks are */
    public double p95Mspt() {
        return tickTimes.p95();
    }

    /** @return 99th percentile tick time in the window */
    public double p99Mspt() {
        return tickTimes.p99();
    }

    /** @return the single worst tick in the window, in milliseconds */
    public double worstTickMs() {
        return tickTimes.max();
    }

    /** @return longest gap between ticks in the sample window, in milliseconds */
    public double peakIntervalMs() {
        return intervals.max();
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
        this.tickTimes = new TickSamples(size);
        this.intervals = new TickSamples(size);
    }
}
