package dev.poleszczuk.ticksentry.monitor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ranking worlds by which one is worth looking at.
 *
 * <p>The decision worth pinning down is
 * {@link #densityDecidesTheOrderNotRawCount()}: sorting by entity count would always name the
 * overworld, because that is where the players are.</p>
 */
class WorldStatTest {

    @Test
    void densityDecidesTheOrderNotRawCount() {
        // The overworld holds more entities in total, but the nether has ten times as many per
        // chunk - and that is the one somebody built a farm in.
        WorldStat overworld = new WorldStat("world", 2000, 3000, 12);
        WorldStat nether = new WorldStat("world_nether", 150, 2250, 0);

        List<WorldStat> ranked = WorldStat.ranked(List.of(overworld, nether));

        assertEquals("world_nether", ranked.get(0).name());
        assertEquals("world", ranked.get(1).name());
    }

    @Test
    void aWorldWithNothingLoadedHasNoDensityRatherThanInfinity() {
        WorldStat empty = new WorldStat("world_the_end", 0, 0, 0);

        assertEquals(0.0D, empty.entitiesPerChunk());
    }

    @Test
    void tiesBreakOnTheNameSoTheListingDoesNotShuffle() {
        // Two runs a second apart must not reorder identical worlds, or an admin reading the list
        // cannot tell a real change from noise.
        WorldStat first = new WorldStat("beta", 100, 500, 0);
        WorldStat second = new WorldStat("alpha", 100, 500, 0);

        List<WorldStat> ranked = WorldStat.ranked(List.of(first, second));

        assertEquals("alpha", ranked.get(0).name());
        assertEquals("beta", ranked.get(1).name());
    }

    @Test
    void sharesAddUpToTheWhole() {
        List<WorldStat> worlds = List.of(
                new WorldStat("world", 100, 600, 4),
                new WorldStat("world_nether", 100, 400, 0));
        int total = WorldStat.totalEntities(worlds);

        assertEquals(1000, total);
        assertEquals(0.6D, worlds.get(0).share(total), 0.0001D);
        assertEquals(0.4D, worlds.get(1).share(total), 0.0001D);
    }

    @Test
    void anEmptyServerDividesByNothing() {
        WorldStat world = new WorldStat("world", 0, 0, 0);

        assertEquals(0.0D, world.share(0));
        assertEquals(0, WorldStat.totalEntities(List.of()));
        assertTrue(WorldStat.ranked(List.of()).isEmpty());
    }

    @Test
    void negativeCountsAreTreatedAsZero() {
        // Nothing should hand these in, but a reading that failed must not turn into a negative
        // density that sorts above every real world.
        WorldStat broken = new WorldStat("world", -5, -10, -1);

        assertEquals(0, broken.loadedChunks());
        assertEquals(0, broken.entities());
        assertEquals(0, broken.players());
        assertEquals(0.0D, broken.entitiesPerChunk());
    }

    @Test
    void rankingLeavesTheCallersListAlone() {
        List<WorldStat> original = List.of(
                new WorldStat("a", 10, 10, 0),
                new WorldStat("b", 10, 900, 0));

        WorldStat.ranked(original);

        assertEquals("a", original.get(0).name(), "an immutable list must not have been sorted");
    }
}
