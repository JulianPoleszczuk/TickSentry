package dev.poleszczuk.ticksentry.monitor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLoadVerdictTest {

    @Test
    void aQuietServerSaysNothing() {
        ChunkLoadVerdict verdict = ChunkLoadVerdict.of(0.5D, 0.0D);

        assertFalse(verdict.explainsLag());
        assertFalse(verdict.hasMessage());
        assertNull(verdict.message());
        assertNull(verdict.suggestion());
    }

    @Test
    void generatingNewLandIsTheStory() {
        ChunkLoadVerdict verdict = ChunkLoadVerdict.of(20.0D, 5.0D);

        assertTrue(verdict.explainsLag());
        assertTrue(verdict.message().contains("20 chunks a second"));
        assertTrue(verdict.message().contains("5.0 of them generated"));
        assertTrue(verdict.suggestion().contains("Pregenerating"));
    }

    @Test
    void streamingExploredGroundIsADifferentProblemWithDifferentAdvice() {
        ChunkLoadVerdict verdict = ChunkLoadVerdict.of(60.0D, 0.0D);

        assertTrue(verdict.explainsLag());
        assertTrue(verdict.suggestion().contains("read from disk"));
        assertFalse(verdict.suggestion().contains("Pregenerating"));
    }

    @Test
    void aTrickleOfNewChunksIsWorthMentioningButNotBlaming() {
        ChunkLoadVerdict verdict = ChunkLoadVerdict.of(8.0D, 1.5D);

        assertFalse(verdict.explainsLag());
        assertTrue(verdict.hasMessage());
        assertNotNull(verdict.message());
    }

    @Test
    void negativeRatesCannotHappenButAreClampedAnyway() {
        assertEquals(0.0D, ChunkLoadVerdict.of(-5.0D, -1.0D).loadedPerSecond());
    }

    @Test
    void theQuietVerdictIsSafeToAskAnything() {
        assertFalse(ChunkLoadVerdict.quiet().explainsLag());
        assertNull(ChunkLoadVerdict.quiet().message());
        assertEquals(0.0D, ChunkLoadVerdict.quiet().generatedPerSecond());
    }

    @Test
    void chunkGenerationExplainsLagWhenNothingInTheWorldStandsOut() {
        LagEvent event = LagEvent.of(14.0D, 80.0D, 200.0D, 500, 3000, List.of(), 20L, false, null,
                null, CostWeights.defaults(), PluginReport.empty(), ChunkLoadVerdict.of(30.0D, 6.0D));

        assertEquals(LagCategory.CHUNK_LOADING, event.category());
        assertNotNull(event.chunkLoadNote());
        assertTrue(event.suggestedAction().contains("Pregenerating"));
    }

    @Test
    void anObviousMobFarmStillOutranksChunkGeneration() {
        List<ChunkStat> farm = List.of(ChunkStat.ofEntities("world", 10, 20, Map.of("COW", 400)));
        LagEvent event = LagEvent.of(14.0D, 80.0D, 200.0D, 500, 3000, farm, 20L, false, null,
                null, CostWeights.defaults(), PluginReport.empty(), ChunkLoadVerdict.of(30.0D, 6.0D));

        assertEquals(LagCategory.MOB_FARM, event.category());
        // The note still travels with the alert, so the admin sees both findings.
        assertNotNull(event.chunkLoadNote());
    }

    @Test
    void chunkGenerationOutranksMemoryWhenBothLookGuilty() {
        MemoryAnalyzer.Verdict memory = new MemoryAnalyzer.Verdict(true, "The collector froze the server.");
        LagEvent event = LagEvent.of(14.0D, 80.0D, 200.0D, 500, 3000, List.of(), 20L, false, null,
                memory, CostWeights.defaults(), PluginReport.empty(), ChunkLoadVerdict.of(30.0D, 6.0D));

        assertEquals(LagCategory.CHUNK_LOADING, event.category());
    }

    @Test
    void aNullVerdictIsTreatedAsQuiet() {
        LagEvent event = LagEvent.of(14.0D, 80.0D, 200.0D, 500, 3000, List.of(), 20L, false, null,
                null, CostWeights.defaults(), PluginReport.empty(), null);

        assertEquals(LagCategory.UNKNOWN, event.category());
        assertNull(event.chunkLoadNote());
    }

    @Test
    void spawnersAreToldApartFromHoppers() {
        ChunkStat spawners = new ChunkStat("world", 5, 5, 0, Map.of(),
                Map.of("SPAWNER", 60, "CHEST", 4));
        ChunkStat hoppers = new ChunkStat("world", 5, 5, 0, Map.of(),
                Map.of("HOPPER", 60, "CHEST", 4));

        assertEquals(LagCategory.SPAWNERS, HotspotAnalyzer.categorize(spawners));
        assertEquals(LagCategory.REDSTONE, HotspotAnalyzer.categorize(hoppers));
        assertTrue(HotspotAnalyzer.suggestedAction(spawners, LagCategory.SPAWNERS).contains("spawners"));
    }

    @Test
    void aMinecartLineIsNotCalledAMobFarm() {
        ChunkStat carts = ChunkStat.ofEntities("world", 5, 5, Map.of("HOPPER_MINECART", 120));

        assertEquals(LagCategory.MINECARTS, HotspotAnalyzer.categorize(carts));
        assertTrue(HotspotAnalyzer.suggestedAction(carts, LagCategory.MINECARTS).contains("Hopper carts"));
    }

    @Test
    void theTypeHelpersKnowTheirSpellings() {
        assertTrue(HotspotAnalyzer.isSpawner("SPAWNER"));
        assertTrue(HotspotAnalyzer.isSpawner("mob_spawner"));
        assertTrue(HotspotAnalyzer.isSpawner("TRIAL_SPAWNER"));
        assertFalse(HotspotAnalyzer.isSpawner("HOPPER"));

        assertTrue(HotspotAnalyzer.isMinecart("HOPPER_MINECART"));
        assertTrue(HotspotAnalyzer.isMinecart("MINECART_CHEST"));
        assertFalse(HotspotAnalyzer.isMinecart("COW"));
    }
}
