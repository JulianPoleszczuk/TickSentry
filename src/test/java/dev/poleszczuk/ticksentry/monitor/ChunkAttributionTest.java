package dev.poleszczuk.ticksentry.monitor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkAttributionTest {

    @Test
    void bothSourcesAreCombinedIntoOneLine() {
        String text = ChunkAttribution.format("region \"ironfarm\" (Steve)", "Notch", 240L);

        assertEquals("region \"ironfarm\" (Steve); last player there: Notch, 4 min ago", text);
    }

    @Test
    void aRegionOnItsOwnIsTheWholeAnswer() {
        assertEquals("claim of Steve", ChunkAttribution.format("claim of Steve", null, 0L));
    }

    @Test
    void withoutAProtectionPluginTheLastVisitorHasToDo() {
        assertEquals("last player there: Notch, 30 s ago", ChunkAttribution.format(null, "Notch", 30L));
    }

    @Test
    void knowingNothingProducesNothingRatherThanAnEmptyLine() {
        assertNull(ChunkAttribution.format(null, null, 0L));
        assertNull(ChunkAttribution.format("", "", 0L));
    }

    @Test
    void ageIsRoundedToTheLargestUsefulUnit() {
        assertTrue(ChunkAttribution.format(null, "A", 45L).endsWith("45 s ago"));
        assertTrue(ChunkAttribution.format(null, "A", 60L).endsWith("1 min ago"));
        assertTrue(ChunkAttribution.format(null, "A", 3599L).endsWith("59 min ago"));
        assertTrue(ChunkAttribution.format(null, "A", 3600L).endsWith("1 h ago"));
        assertTrue(ChunkAttribution.format(null, "A", 7200L).endsWith("2 h ago"));
    }

    @Test
    void attachingAnOwnerLeavesTheOriginalSnapshotAlone() {
        ChunkStat original = ChunkStat.ofEntities("world", 100, 100, Map.of("COW", 400));
        ChunkStat annotated = original.withAttribution("claim of Steve");

        assertNull(original.attribution());
        assertEquals("claim of Steve", annotated.attribution());
        assertNotSame(original, annotated);

        // Everything else has to survive the copy - the report is built from these numbers.
        assertEquals(original.entityCount(), annotated.entityCount());
        assertEquals(original.prettyLocation(), annotated.prettyLocation());
        assertEquals(original.entityTypeCounts(), annotated.entityTypeCounts());
    }
}
