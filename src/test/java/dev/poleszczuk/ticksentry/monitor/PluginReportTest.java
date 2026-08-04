package dev.poleszczuk.ticksentry.monitor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginReportTest {

    private static final long TEN_SECONDS = 10L * 1_000_000_000L;

    private static PluginTiming timing(String name, double millis) {
        long nanos = (long) (millis * 1_000_000.0D);
        return new PluginTiming(name, nanos, 100L, "PlayerMoveEvent", nanos);
    }

    @Test
    void sortsPluginsByTimeSpent() {
        PluginReport report = PluginReport.of(TEN_SECONDS,
                List.of(timing("Cheap", 10.0D), timing("Expensive", 500.0D), timing("Middling", 120.0D)));

        assertEquals(List.of("Expensive", "Middling", "Cheap"),
                report.timings().stream().map(PluginTiming::pluginName).collect(java.util.stream.Collectors.toList()));
        assertEquals("Expensive", report.worst().pluginName());
    }

    @Test
    void breaksTiesOnNameSoReportsAreReproducible() {
        PluginReport first = PluginReport.of(TEN_SECONDS, List.of(timing("Bravo", 50.0D), timing("Alpha", 50.0D)));
        PluginReport second = PluginReport.of(TEN_SECONDS, List.of(timing("Alpha", 50.0D), timing("Bravo", 50.0D)));

        assertEquals("Alpha", first.worst().pluginName());
        assertEquals("Alpha", second.worst().pluginName());
    }

    @Test
    void sharesAreRelativeToTheWindowNotToTheMeasuredTotal() {
        // 2.5 s out of a 10 s window is a quarter of the window, even though it is
        // also the only measurement in the report.
        PluginReport report = PluginReport.of(TEN_SECONDS, List.of(timing("Heavy", 2500.0D)));

        assertEquals(0.25D, report.worstShare(), 0.0001D);
    }

    @Test
    void aQuietPluginExplainsNothing() {
        PluginReport report = PluginReport.of(TEN_SECONDS, List.of(timing("Quiet", 200.0D)));

        assertFalse(report.explainsLag());
        assertFalse(report.dominatesLag());
        assertNull(report.message());
        assertNull(report.suggestion());
    }

    @Test
    void aPluginTakingAQuarterOfTheWindowIsWorthNaming() {
        PluginReport report = PluginReport.of(TEN_SECONDS, List.of(timing("Noisy", 3000.0D)));

        assertTrue(report.explainsLag());
        assertFalse(report.dominatesLag());
        assertTrue(report.message().contains("Noisy"));
        assertTrue(report.message().contains("PlayerMoveEvent"));
        assertTrue(report.suggestion().contains("Noisy"));
    }

    @Test
    void aPluginTakingHalfTheWindowOutranksTheChunkScan() {
        PluginReport report = PluginReport.of(TEN_SECONDS, List.of(timing("Hog", 6000.0D)));

        assertTrue(report.dominatesLag());
    }

    @Test
    void emptyReportIsSafeToAskAnything() {
        PluginReport report = PluginReport.empty();

        assertTrue(report.isEmpty());
        assertNull(report.worst());
        assertEquals(0.0D, report.worstShare());
        assertFalse(report.explainsLag());
        assertTrue(report.top(5).isEmpty());
    }

    @Test
    void aZeroLengthWindowCannotProduceShares() {
        assertTrue(PluginReport.of(0L, List.of(timing("Whatever", 100.0D))).isEmpty());
    }

    @Test
    void topClampsToWhatIsAvailable() {
        PluginReport report = PluginReport.of(TEN_SECONDS, List.of(timing("A", 30.0D), timing("B", 20.0D)));

        assertEquals(2, report.top(5).size());
        assertEquals(1, report.top(1).size());
        assertTrue(report.top(0).isEmpty());
    }

    @Test
    void describeMentionsMillisecondsShareAndEvent() {
        String text = timing("Essentials", 431.0D).describe(TEN_SECONDS);

        assertTrue(text.contains("Essentials"));
        assertTrue(text.contains("431 ms"));
        assertTrue(text.contains("4%"));
        assertTrue(text.contains("PlayerMoveEvent"));
    }

    @Test
    void aTimingWithoutEventsStillDescribesItself() {
        PluginTiming timing = new PluginTiming("Silent", 1_000_000L, 0L, null, 0L);

        assertNotNull(timing.describe(TEN_SECONDS));
        assertEquals(0.0D, timing.share(0L));
    }
}
