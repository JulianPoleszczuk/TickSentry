package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.AdaptiveSettings;
import dev.poleszczuk.ticksentry.config.MonitorSettings;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision the whole plugin rests on: when a slow patch counts as an incident.
 *
 * <p>Every other test here covers a pure class. This one covers the state machine - breach starts,
 * breach is sustained, alert fires, cooldown holds the next one back, calm ends the incident - and
 * it can, because the monitor reads the clock through a supplier. Time is walked forward by hand,
 * so the suite stays instant and the awkward cases (a breach that ends one second early, a second
 * incident inside the cooldown) are reachable at all.</p>
 *
 * <p>The server is a proxy rather than a mock: {@code Server} is an interface, and the monitor only
 * ever asks it for the tick time and the TPS.</p>
 */
class TickMonitorTest {

    /** Window size used throughout - the smallest the configuration allows. */
    private static final int WINDOW = 20;

    @Test
    void nothingIsJudgedUntilTheWindowIsFull() {
        Harness harness = new Harness();
        harness.mspt(200.0D);

        harness.tick(WINDOW - 1);

        // A part-filled window says nothing about the server - right after startup most of all.
        assertEquals(0, harness.alerts);
        assertFalse(harness.monitor.isInIncident());
        assertEquals(0L, harness.monitor.currentBreachSeconds(), "no breach has been opened yet");
    }

    @Test
    void aBreachShorterThanSustainedSecondsIsIgnored() {
        Harness harness = new Harness();
        harness.lag();

        harness.advance(9_000L);
        harness.tick();

        assertEquals(0, harness.alerts);
        assertTrue(harness.monitor.currentBreachSeconds() >= 9L);
    }

    @Test
    void aSustainedBreachFiresOnce() {
        Harness harness = new Harness();
        harness.lag();

        harness.advance(10_000L);
        harness.tick();

        assertEquals(1, harness.alerts);
        assertTrue(harness.monitor.isInIncident());
    }

    @Test
    void theIncidentStartsWhenTheThresholdWasCrossedNotWhenTheAlertWentOut() {
        Harness harness = new Harness();
        harness.lag();
        harness.advance(10_000L);
        harness.tick();

        // Calm again: 10 s of breach before the alert plus 15 s of quiet is a 25 s incident, and
        // the admin is told about the whole thing, not just the part after the alert went out.
        harness.calm();
        harness.advance(15_000L);
        harness.tick();

        assertEquals(List.of(25L), harness.recoveries);
    }

    @Test
    void aSecondAlertWaitsOutTheCooldown() {
        Harness harness = new Harness();
        harness.lag();
        harness.advance(10_000L);
        harness.tick();
        assertEquals(1, harness.alerts);

        // Still lagging, and another full sustained period has passed - but not the 300 s cooldown.
        harness.advance(60_000L);
        harness.tick();
        assertEquals(1, harness.alerts);
        assertTrue(harness.monitor.alertCooldownRemainingSeconds() > 0L);

        harness.advance(241_000L);
        harness.tick();
        assertEquals(2, harness.alerts);
    }

    @Test
    void theCooldownExpiringIsNotItselfAnAlert() {
        // The trap the production code guards against: the breach window restarts whether or not
        // the alert was allowed through, so the moment the cooldown lifts still requires a fresh
        // full period of overload rather than firing on the strength of an old one.
        Harness harness = new Harness();
        harness.lag();
        harness.advance(10_000L);
        harness.tick();

        harness.calm();
        harness.advance(400_000L);
        harness.lag();

        assertEquals(1, harness.alerts, "a fresh breach has only just started");

        harness.advance(10_000L);
        harness.tick();
        assertEquals(2, harness.alerts);
    }

    @Test
    void aBriefBreatherDoesNotCountAsRecovery() {
        Harness harness = new Harness();
        harness.lag();
        harness.advance(10_000L);
        harness.tick();

        harness.calm();
        harness.advance(14_000L);
        harness.tick();
        assertTrue(harness.recoveries.isEmpty(), "14 s of calm is not the 15 s the config asks for");

        // Back to lagging, so the countdown starts over rather than resuming where it left off.
        harness.lag();
        harness.calm();
        harness.advance(14_000L);
        harness.tick();
        assertTrue(harness.recoveries.isEmpty());

        harness.advance(1_000L);
        harness.tick();
        assertEquals(1, harness.recoveries.size());
        assertFalse(harness.monitor.isInIncident());
    }

    @Test
    void recoveryIsOnlyAnnouncedForAnIncidentThatHappened() {
        Harness harness = new Harness();
        harness.calm();

        harness.advance(60_000L);
        harness.tick();

        assertTrue(harness.recoveries.isEmpty());
    }

    @Test
    void resetForgetsAnOngoingBreach() {
        Harness harness = new Harness();
        harness.lag();
        harness.advance(9_000L);
        harness.tick();
        assertTrue(harness.monitor.currentBreachSeconds() > 0L);

        harness.monitor.reset();

        assertEquals(0L, harness.monitor.currentBreachSeconds());
        assertEquals(0.0D, harness.monitor.averageMspt(), "the window is empty again");
    }

    @Test
    void aThrowingCallbackDoesNotStopTheMonitor() {
        // A broken alert path must not take the detection down with it - the next incident still
        // has to be noticed.
        Harness harness = new Harness(() -> {
            throw new IllegalStateException("webhook exploded");
        }, true);
        harness.lag();
        harness.advance(10_000L);

        harness.tick();

        assertTrue(harness.monitor.isInIncident());
    }

    @Test
    void aServerWithoutPerTickTimesIsStillWatched() {
        // Spigot has no getTickTimes(). Detection has to keep working there, on the one reading it
        // does offer, rather than silently measuring nothing.
        Harness harness = Harness.spigot();
        harness.lag();

        harness.advance(10_000L);
        harness.tick();

        assertEquals(1, harness.alerts);
        assertFalse(harness.monitor.tickTimeSource().isRaw());
    }

    @Test
    void aFreezeAmongHealthyTicksShowsInThePercentilesAndNotInTheMean() {
        // The whole point of reading raw per-tick times. One 400 ms stall in a window of otherwise
        // healthy ticks leaves the mean far below the threshold - so no alert, correctly, for a
        // single hiccup - but the admin can now see that it happened at all.
        Harness harness = new Harness();
        harness.calm();

        harness.mspt(400.0D);
        harness.tick();

        assertTrue(harness.monitor.averageMspt() < 50.0D, "one bad tick is not a lagging server");
        assertEquals(400.0D, harness.monitor.worstTickMs(), 0.001D);
        assertEquals(400.0D, harness.monitor.p99Mspt(), 0.001D);
        assertEquals(0, harness.alerts);
    }

    @Test
    void markAlertSentPushesTheCooldownBack() {
        Harness harness = new Harness();
        harness.calm();
        assertEquals(0L, harness.monitor.alertCooldownRemainingSeconds());

        harness.monitor.markAlertSent();

        assertEquals(300L, harness.monitor.alertCooldownRemainingSeconds());
    }

    /**
     * A monitor wired to a hand-cranked clock and a stand-in server.
     *
     * <p>The server hands out raw per-tick durations, which is what Paper does and therefore what
     * nearly every server running this plugin does. Each simulated tick writes into the next slot
     * of the ring, exactly as the real one does.</p>
     */
    private static final class Harness {

        private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
        private final TickMonitor monitor;
        private final List<Long> recoveries = new ArrayList<>();

        /** Paper's ring of tick durations in nanoseconds, or {@code null} to act like Spigot. */
        private final long[] ticks;

        private int slot;
        private long sequence;
        private double mspt;
        private int alerts;

        private Harness() {
            this(null, true);
        }

        private Harness(Runnable onAlert, boolean raw) {
            this.ticks = raw ? new long[WINDOW * 2] : null;
            Server server = fakeServer(() -> mspt, ticks);
            Plugin plugin = fakePlugin(server);
            this.monitor = new TickMonitor(plugin, new Settings(),
                    new AdaptiveThreshold(AdaptiveSettings.disabled(), 5),
                    () -> {
                        alerts++;
                        if (onAlert != null) {
                            onAlert.run();
                        }
                    },
                    recoveries::add,
                    now::get);
        }

        /** A monitor on a server with no per-tick durations, the way Spigot behaves. */
        private static Harness spigot() {
            return new Harness(null, false);
        }

        private void mspt(double value) {
            this.mspt = value;
        }

        private void advance(long millis) {
            now.addAndGet(millis);
        }

        /**
         * Fills the whole window with an overloaded reading.
         *
         * <p>A full window is needed, not one tick: the monitor judges the <em>average</em> of the
         * window, so a single slow tick among nineteen quick ones changes nothing. The clock is
         * deliberately not advanced, which keeps the breach starting at a known instant.</p>
         */
        private void lag() {
            fill(200.0D);
        }

        /** Fills the whole window with a healthy reading - same reasoning as {@link #lag()}. */
        private void calm() {
            fill(5.0D);
        }

        /** One extra tick on the raw path, whose first drain only takes a priming snapshot. */
        private void fill(double milliseconds) {
            mspt(milliseconds);
            tick(ticks == null ? WINDOW : WINDOW + 1);
        }

        private void tick() {
            if (ticks != null) {
                // Every tick lands in the next slot of the ring. The nanosecond count carries a
                // monotonic counter so no two ticks are ever written with the same value: real tick
                // times do not repeat, and the source spots a new measurement by the value having
                // changed. Writing 200 ms twice into the same slot would look like nothing
                // happened - a property of the fake, not of any real server.
                slot = (slot + 1) % ticks.length;
                ticks[slot] = (long) (mspt * 1_000_000.0D) + (++sequence);
            }
            monitor.run();
        }

        private void tick(int times) {
            for (int i = 0; i < times; i++) {
                tick();
            }
        }
    }

    /** The defaults from config.yml, with the smallest window the configuration permits. */
    private static final class Settings implements MonitorSettings {

        @Override
        public double msptThresholdMs() {
            return 50.0D;
        }

        @Override
        public int sustainedSeconds() {
            return 10;
        }

        @Override
        public int scanCooldownSeconds() {
            return 300;
        }

        @Override
        public int recoverySeconds() {
            return 15;
        }

        @Override
        public int rollingAverageTicks() {
            return WINDOW;
        }
    }

    /** A server that answers only what the monitor asks it: the tick times and the TPS. */
    private static Server fakeServer(java.util.function.DoubleSupplier mspt, long[] tickTimes) {
        return (Server) Proxy.newProxyInstance(
                TickMonitorTest.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (self, method, args) -> {
                    switch (method.getName()) {
                        case "getTickTimes":
                            return tickTimes;
                        case "getAverageTickTime":
                            return mspt.getAsDouble();
                        case "getTPS":
                            return new double[] {20.0D, 20.0D, 20.0D};
                        case "equals":
                            return self == args[0];
                        case "hashCode":
                            return System.identityHashCode(self);
                        case "toString":
                            return "FakeServer";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Plugin fakePlugin(Server server) {
        Logger logger = Logger.getLogger("TickMonitorTest");
        return (Plugin) Proxy.newProxyInstance(
                TickMonitorTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (self, method, args) -> {
                    switch (method.getName()) {
                        case "getServer":
                            return server;
                        case "getLogger":
                            return logger;
                        case "isEnabled":
                            return true;
                        case "getName":
                            return "TickSentry";
                        case "equals":
                            return self == args[0];
                        case "hashCode":
                            return System.identityHashCode(self);
                        case "toString":
                            return "FakePlugin";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == double.class) {
            return 0.0D;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }
}
