package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatOffenderTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    private static StoredIncident at(String world, int blockX, int blockZ, double mspt, long minutesAgo) {
        return new StoredIncident(NOW.minus(minutesAgo, ChronoUnit.MINUTES), 15.0D, mspt,
                LagCategory.MOB_FARM, world, blockX, blockZ, 900, "COW", 800, false);
    }

    private static StoredIncident manual(String world, int blockX, int blockZ) {
        return new StoredIncident(NOW, 15.0D, 90.0D, LagCategory.MOB_FARM, world, blockX, blockZ,
                900, "COW", 800, true);
    }

    private static List<StoredIncident> repeat(int times, String world, int blockX, int blockZ) {
        List<StoredIncident> incidents = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            incidents.add(at(world, blockX, blockZ, 80.0D + i, i * 10L));
        }
        return incidents;
    }

    @Test
    void aChunkSeenOnceIsNotAnOffender() {
        List<RepeatOffender> found = RepeatOffender.summarise(repeat(1, "world", 100, 100), 7, 10);

        assertTrue(found.isEmpty());
    }

    @Test
    void aChunkSeenTwiceStartsCounting() {
        List<RepeatOffender> found = RepeatOffender.summarise(repeat(2, "world", 100, 100), 7, 10);

        assertEquals(1, found.size());
        assertEquals(2, found.get(0).hits());
        assertEquals(2, found.get(0).outOf());
        assertEquals("world @ 100, 100", found.get(0).prettyLocation());
    }

    @Test
    void theTotalCountsEveryIncidentNotJustTheOnesThatNamedThisChunk() {
        List<StoredIncident> incidents = new ArrayList<>(repeat(3, "world", 100, 100));
        incidents.addAll(repeat(1, "world", 500, 500));
        incidents.addAll(repeat(1, "world", 900, 900));

        List<RepeatOffender> found = RepeatOffender.summarise(incidents, 7, 10);

        assertEquals(1, found.size());
        assertEquals(3, found.get(0).hits());
        assertEquals(5, found.get(0).outOf());
        assertEquals(0.6D, found.get(0).share(), 0.0001D);
    }

    @Test
    void manualReportsAreIgnoredEntirely() {
        // Running /lagwatch report five times while testing must not invent a chronic problem.
        List<StoredIncident> incidents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            incidents.add(manual("world", 100, 100));
        }

        assertTrue(RepeatOffender.summarise(incidents, 7, 10).isEmpty());
    }

    @Test
    void manualReportsDoNotInflateTheTotalEither() {
        List<StoredIncident> incidents = new ArrayList<>(repeat(2, "world", 100, 100));
        incidents.add(manual("world", 700, 700));

        assertEquals(2, RepeatOffender.summarise(incidents, 7, 10).get(0).outOf());
    }

    @Test
    void incidentsWithoutALocationCountTowardsTheTotalButNameNoChunk() {
        List<StoredIncident> incidents = new ArrayList<>(repeat(2, "world", 100, 100));
        incidents.add(at(null, 0, 0, 95.0D, 5L));

        List<RepeatOffender> found = RepeatOffender.summarise(incidents, 7, 10);

        assertEquals(1, found.size());
        assertEquals(3, found.get(0).outOf());
    }

    @Test
    void chunksInDifferentWorldsAreNeverMerged() {
        List<StoredIncident> incidents = new ArrayList<>(repeat(2, "world", 100, 100));
        incidents.addAll(repeat(2, "world_nether", 100, 100));

        assertEquals(2, RepeatOffender.summarise(incidents, 7, 10).size());
    }

    @Test
    void theRankingPutsTheMostFrequentFirst() {
        List<StoredIncident> incidents = new ArrayList<>(repeat(2, "world", 100, 100));
        incidents.addAll(repeat(5, "world", 500, 500));
        incidents.addAll(repeat(3, "world", 900, 900));

        List<RepeatOffender> found = RepeatOffender.summarise(incidents, 7, 10);

        assertEquals(List.of("world @ 500, 500", "world @ 900, 900", "world @ 100, 100"),
                found.stream().map(RepeatOffender::prettyLocation).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void theWorstTickTimeAndLatestSightingAreKept() {
        List<StoredIncident> incidents = List.of(
                at("world", 100, 100, 80.0D, 600L),
                at("world", 100, 100, 240.0D, 300L),
                at("world", 100, 100, 90.0D, 5L));

        RepeatOffender offender = RepeatOffender.summarise(incidents, 7, 10).get(0);

        assertEquals(240.0D, offender.worstMspt(), 0.0001D);
        assertEquals(NOW.minus(5L, ChronoUnit.MINUTES), offender.lastSeen());
        assertTrue(offender.describe().contains("240 ms"));
        assertTrue(offender.describe().contains("3 of the last 3"));
    }

    @Test
    void twoAppearancesOutOfManyIsNoticedButNotCalledChronic() {
        List<StoredIncident> incidents = new ArrayList<>(repeat(2, "world", 100, 100));
        for (int i = 0; i < 20; i++) {
            incidents.add(at("world", 1000 + i * 16, 1000, 70.0D, i));
        }

        RepeatOffender offender = RepeatOffender.summarise(incidents, 7, 10).get(0);

        assertEquals(2, offender.hits());
        assertFalse(offender.isChronic());
    }

    @Test
    void beingBehindMostIncidentsIsChronic() {
        List<StoredIncident> incidents = new ArrayList<>(repeat(4, "world", 100, 100));
        incidents.addAll(repeat(1, "world", 800, 800));

        assertTrue(RepeatOffender.summarise(incidents, 7, 10).get(0).isChronic());
    }

    @Test
    void theLimitIsRespected() {
        List<StoredIncident> incidents = new ArrayList<>();
        for (int chunk = 0; chunk < 6; chunk++) {
            incidents.addAll(repeat(2, "world", chunk * 16, 0));
        }

        assertEquals(3, RepeatOffender.summarise(incidents, 7, 3).size());
        assertTrue(RepeatOffender.summarise(incidents, 7, 0).isEmpty());
    }

    @Test
    void anEmptyHistoryProducesAnEmptyRanking() {
        assertTrue(RepeatOffender.summarise(List.of(), 7, 10).isEmpty());
    }

    @Test
    void theIndexAnswersByLocationAndSaysNothingAboutUnknownChunks() {
        OffenderIndex index = OffenderIndex.of(RepeatOffender.summarise(repeat(3, "world", 100, 100), 7, 10));

        assertFalse(index.isEmpty());
        assertTrue(index.describe("world", 100, 100).contains("3 of the last 3"));
        assertNull(index.describe("world", 200, 200));
        assertNull(index.describe("world_nether", 100, 100));
        assertNull(index.describe(null, 100, 100));
    }

    @Test
    void anEmptyIndexIsSafeToQuery() {
        assertTrue(OffenderIndex.empty().isEmpty());
        assertNull(OffenderIndex.empty().describe("world", 0, 0));
        assertTrue(OffenderIndex.of(List.of()).isEmpty());
        assertTrue(OffenderIndex.of(null).isEmpty());
    }
}
