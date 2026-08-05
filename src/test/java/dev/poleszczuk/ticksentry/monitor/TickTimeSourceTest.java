package dev.poleszczuk.ticksentry.monitor;

import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading tick times off the server, on both kinds of server.
 *
 * <p>The plugin is built against the Paper API but has to keep running on Spigot, which has no
 * {@code getTickTimes()}. That makes two code paths, and the interesting one is Paper's: the array
 * is a ring whose newest slot depends on the server's own tick counter, so this class works by
 * diffing snapshots instead of assuming an index.</p>
 */
class TickTimeSourceTest {

    private static final Method RAW = rawMethod();

    @Test
    void spigotFallsBackToThePreAveragedReading() {
        AtomicReference<long[]> unused = new AtomicReference<>();
        TickSamples samples = new TickSamples(10);
        TickTimeSource source = new TickTimeSource(server(unused, 42.0D), null);

        assertFalse(source.isRaw());
        assertTrue(source.describe().contains("Spigot"));

        source.drainInto(samples);
        source.drainInto(samples);

        // One reading per call, and no priming - there is no history to accidentally drain.
        assertEquals(2, samples.size());
        assertEquals(42.0D, samples.mean());
    }

    @Test
    void theFirstDrainOnlyPrimesAndRecordsNothing() {
        // Paper's array holds ticks from before the plugin was enabled, and startup is the slowest
        // the server ever is. Draining that in would fill the window with the worst moment on
        // record and alert on it within seconds of a restart.
        AtomicReference<long[]> ticks = new AtomicReference<>(nanos(300.0D, 280.0D, 290.0D));
        TickSamples samples = new TickSamples(10);
        TickTimeSource source = new TickTimeSource(server(ticks, 0.0D), RAW);

        assertTrue(source.isRaw());
        source.drainInto(samples);

        assertEquals(0, samples.size());
    }

    @Test
    void onlyTheChangedSlotIsRecorded() {
        AtomicReference<long[]> ticks = new AtomicReference<>(nanos(10.0D, 10.0D, 10.0D));
        TickSamples samples = new TickSamples(10);
        TickTimeSource source = new TickTimeSource(server(ticks, 0.0D), RAW);
        source.drainInto(samples);

        ticks.set(nanos(10.0D, 250.0D, 10.0D));
        source.drainInto(samples);

        assertEquals(1, samples.size());
        assertEquals(250.0D, samples.mean(), 0.001D);
    }

    @Test
    void everySlotThatMovedIsRecorded() {
        // Our own task can be delayed - which is exactly when the numbers matter - and several
        // ticks then land between two drains. Losing them would understate the incident.
        AtomicReference<long[]> ticks = new AtomicReference<>(nanos(10.0D, 10.0D, 10.0D, 10.0D));
        TickSamples samples = new TickSamples(10);
        TickTimeSource source = new TickTimeSource(server(ticks, 0.0D), RAW);
        source.drainInto(samples);

        ticks.set(nanos(100.0D, 200.0D, 300.0D, 10.0D));
        source.drainInto(samples);

        assertEquals(3, samples.size());
        assertEquals(300.0D, samples.max(), 0.001D);
    }

    @Test
    void untouchedSlotsOnAFreshServerAreNotCountedAsZeroMillisecondTicks() {
        // A server up for ten ticks has ninety zeroes in that array. Treating them as measurements
        // would report a mean of 3 ms on a server sitting at 30 ms.
        AtomicReference<long[]> ticks = new AtomicReference<>(new long[] {0L, 0L, 0L, 0L});
        TickSamples samples = new TickSamples(10);
        TickTimeSource source = new TickTimeSource(server(ticks, 0.0D), RAW);
        source.drainInto(samples);

        ticks.set(new long[] {nano(30.0D), 0L, 0L, 0L});
        source.drainInto(samples);

        assertEquals(1, samples.size());
        assertEquals(30.0D, samples.mean(), 0.001D);
    }

    @Test
    void nanosecondsBecomeMilliseconds() {
        AtomicReference<long[]> ticks = new AtomicReference<>(new long[] {1L});
        TickSamples samples = new TickSamples(4);
        TickTimeSource source = new TickTimeSource(server(ticks, 0.0D), RAW);
        source.drainInto(samples);

        ticks.set(new long[] {47_500_000L});
        source.drainInto(samples);

        assertEquals(47.5D, samples.mean(), 0.0001D);
    }

    @Test
    void aServerThatThrowsIsDroppedRatherThanRetriedTwentyTimesASecond() {
        Server exploding = (Server) Proxy.newProxyInstance(
                TickTimeSourceTest.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (self, method, args) -> {
                    if ("getTickTimes".equals(method.getName())) {
                        throw new UnsupportedOperationException("fork without tick times");
                    }
                    if ("getAverageTickTime".equals(method.getName())) {
                        return 33.0D;
                    }
                    return fallbackAnswer(self, method, args);
                });
        TickSamples samples = new TickSamples(4);
        TickTimeSource source = new TickTimeSource(exploding, RAW);

        source.drainInto(samples);

        assertFalse(source.isRaw(), "it must stop claiming a precision this server is not giving");
        assertEquals(33.0D, samples.mean(), "and fall back rather than measure nothing at all");
    }

    @Test
    void resetMakesTheNextDrainPrimeAgain() {
        AtomicReference<long[]> ticks = new AtomicReference<>(nanos(10.0D, 10.0D));
        TickSamples samples = new TickSamples(10);
        TickTimeSource source = new TickTimeSource(server(ticks, 0.0D), RAW);
        source.drainInto(samples);

        source.reset();
        ticks.set(nanos(500.0D, 500.0D));
        source.drainInto(samples);

        assertEquals(0, samples.size(), "a reload starts over instead of reporting a jump");

        ticks.set(nanos(500.0D, 20.0D));
        source.drainInto(samples);
        assertEquals(1, samples.size());
    }

    @Test
    void aShrinkingArrayDoesNotWalkOffTheEndOfTheOldSnapshot() {
        AtomicReference<long[]> ticks = new AtomicReference<>(nanos(10.0D, 10.0D));
        TickSamples samples = new TickSamples(10);
        TickTimeSource source = new TickTimeSource(server(ticks, 0.0D), RAW);
        source.drainInto(samples);

        ticks.set(nanos(10.0D, 10.0D, 60.0D, 70.0D));
        source.drainInto(samples);

        assertEquals(2, samples.size(), "slots the previous snapshot never had count as new");
    }

    @Test
    void theLiveArrayIsCopiedSoTheComparisonStillWorks() {
        // Some Paper versions hand back the live array. Holding onto it as "the previous snapshot"
        // would mean comparing an array against itself - always equal, nothing ever measured.
        long[] live = nanos(10.0D, 10.0D);
        AtomicReference<long[]> ticks = new AtomicReference<>(live);
        TickSamples samples = new TickSamples(10);
        TickTimeSource source = new TickTimeSource(server(ticks, 0.0D), RAW);
        source.drainInto(samples);

        live[1] = nano(200.0D);
        source.drainInto(samples);

        assertEquals(1, samples.size());
        assertEquals(200.0D, samples.mean(), 0.001D);
    }

    private static Method rawMethod() {
        try {
            return Server.class.getMethod("getTickTimes");
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("the Paper API on the test classpath should declare it", ex);
        }
    }

    private static long nano(double milliseconds) {
        return (long) (milliseconds * 1_000_000.0D);
    }

    private static long[] nanos(double... milliseconds) {
        long[] values = new long[milliseconds.length];
        for (int i = 0; i < milliseconds.length; i++) {
            values[i] = nano(milliseconds[i]);
        }
        return values;
    }

    /** A server answering only the two readings this class asks for. */
    private static Server server(AtomicReference<long[]> tickTimes, double averageTickTime) {
        return (Server) Proxy.newProxyInstance(
                TickTimeSourceTest.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (self, method, args) -> {
                    switch (method.getName()) {
                        case "getTickTimes":
                            return tickTimes.get();
                        case "getAverageTickTime":
                            return averageTickTime;
                        default:
                            return fallbackAnswer(self, method, args);
                    }
                });
    }

    private static Object fallbackAnswer(Object self, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return self == args[0];
            case "hashCode":
                return System.identityHashCode(self);
            case "toString":
                return "FakeServer";
            default:
                Class<?> type = method.getReturnType();
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
}
