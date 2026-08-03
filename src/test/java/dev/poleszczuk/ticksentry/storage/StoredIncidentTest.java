package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StoredIncidentTest {

    @Test
    @DisplayName("Flattening keeps the main chunk and the dominant type")
    void flattensPrimaryChunk() {
        ChunkStat farm = ChunkStat.ofEntities("world", 7, -3, Map.of("COW", 200, "PIG", 10));
        LagEvent event = LagEvent.of(12.4D, 88.0D, 210.0D, 400, 5000, List.of(farm), 12L, false, null, null);

        StoredIncident incident = StoredIncident.from(event);

        assertEquals(LagCategory.MOB_FARM, incident.category());
        assertEquals("world", incident.world());
        assertEquals(120, incident.blockX());
        assertEquals(-40, incident.blockZ());
        assertEquals(210, incident.entities());
        assertEquals("COW", incident.dominantType());
        assertEquals(200, incident.dominantCount());
        assertEquals(88.0D, incident.mspt(), 1e-9);
        assertEquals("world @ 120, -40", incident.prettyLocation());
    }

    @Test
    @DisplayName("An incident without a suspect chunk stores no location")
    void handlesEventWithoutChunks() {
        LagEvent event = LagEvent.of(15.0D, 70.0D, 120.0D, 300, 900, List.of(), 5L, true, null, null);

        StoredIncident incident = StoredIncident.from(event);

        assertEquals(LagCategory.UNKNOWN, incident.category());
        assertNull(incident.world());
        assertNull(incident.dominantType());
        assertEquals("no specific place", incident.prettyLocation());
        assertEquals(true, incident.manual());
    }
}
