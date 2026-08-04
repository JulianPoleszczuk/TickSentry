package dev.poleszczuk.ticksentry.monitor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the order in which a cause is chosen when the chunk scan, the profiler and the
 * memory watcher all have something to say.
 */
class LagEventCategoryTest {

    private static final long TEN_SECONDS = 10L * 1_000_000_000L;

    private static PluginReport plugins(double millis) {
        long nanos = (long) (millis * 1_000_000.0D);
        return PluginReport.of(TEN_SECONDS,
                List.of(new PluginTiming("HeavyPlugin", nanos, 500L, "PlayerMoveEvent", nanos)));
    }

    /** A chunk packed with cows - clearly a mob farm to the chunk scan. */
    private static List<ChunkStat> mobFarm() {
        return List.of(ChunkStat.ofEntities("world", 10, 20, Map.of("COW", 400)));
    }

    private static LagEvent build(List<ChunkStat> chunks, PluginReport report, MemoryAnalyzer.Verdict memory) {
        return LagEvent.of(12.0D, 90.0D, 300.0D, 500, 4000, chunks, 20L, false, null, memory,
                CostWeights.defaults(), report);
    }

    @Test
    void aDominantPluginOutranksEvenAnObviousMobFarm() {
        LagEvent event = build(mobFarm(), plugins(6000.0D), null);

        assertEquals(LagCategory.PLUGIN, event.category());
        assertNotNull(event.pluginNote());
        assertTrue(event.suggestedAction().contains("HeavyPlugin"));
    }

    @Test
    void aMerelyExpensivePluginLosesToAMobFarm() {
        LagEvent event = build(mobFarm(), plugins(3000.0D), null);

        assertEquals(LagCategory.MOB_FARM, event.category());
        // The note is still attached - the admin gets to see both findings.
        assertNotNull(event.pluginNote());
        assertTrue(event.suggestedAction().contains("cow"));
    }

    @Test
    void aMerelyExpensivePluginWinsWhenNoChunkStandsOut() {
        LagEvent event = build(List.of(), plugins(3000.0D), null);

        assertEquals(LagCategory.PLUGIN, event.category());
        assertTrue(event.suggestedAction().contains("HeavyPlugin"));
    }

    @Test
    void memoryStillWinsWhenNeitherTheWorldNorAPluginExplainsAnything() {
        MemoryAnalyzer.Verdict memory = new MemoryAnalyzer.Verdict(true, "The garbage collector froze the server.");
        LagEvent event = build(List.of(), plugins(200.0D), memory);

        assertEquals(LagCategory.MEMORY, event.category());
        assertNull(event.pluginNote());
        assertNotNull(event.memoryNote());
    }

    @Test
    void aPluginOutranksMemoryWhenBothLookGuilty() {
        MemoryAnalyzer.Verdict memory = new MemoryAnalyzer.Verdict(true, "The garbage collector froze the server.");
        LagEvent event = build(List.of(), plugins(3000.0D), memory);

        assertEquals(LagCategory.PLUGIN, event.category());
    }

    @Test
    void nothingMeasuredMeansNothingChanges() {
        LagEvent event = build(List.of(), PluginReport.empty(), null);

        assertEquals(LagCategory.UNKNOWN, event.category());
        assertNull(event.pluginNote());
    }

    @Test
    void aNullReportIsTreatedAsNoMeasurements() {
        LagEvent event = LagEvent.of(12.0D, 90.0D, 300.0D, 500, 4000, mobFarm(), 20L, false, null, null,
                CostWeights.defaults(), null);

        assertEquals(LagCategory.MOB_FARM, event.category());
        assertNull(event.pluginNote());
    }
}
