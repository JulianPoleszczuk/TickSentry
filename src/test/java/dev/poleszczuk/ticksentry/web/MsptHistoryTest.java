package dev.poleszczuk.ticksentry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MsptHistoryTest {

    @Test
    @DisplayName("Samples come back in chronological order")
    void keepsChronologicalOrder() {
        MsptHistory history = new MsptHistory(5);
        history.add(100L, 8.0D, 20.0D);
        history.add(200L, 9.0D, 19.9D);
        history.add(300L, 10.0D, 19.8D);

        List<MsptHistory.Sample> samples = history.samples();

        assertEquals(3, samples.size());
        assertEquals(100L, samples.get(0).timestampMillis());
        assertEquals(300L, samples.get(2).timestampMillis());
    }

    @Test
    @DisplayName("Once the buffer is full the oldest samples fall out")
    void oldSamplesFallOut() {
        MsptHistory history = new MsptHistory(3);
        for (long i = 1; i <= 5; i++) {
            history.add(i * 100L, i, 20.0D);
        }

        List<MsptHistory.Sample> samples = history.samples();

        assertEquals(3, samples.size());
        assertEquals(300L, samples.get(0).timestampMillis(), "oldest retained sample");
        assertEquals(500L, samples.get(2).timestampMillis(), "newest sample");
    }

    @Test
    @DisplayName("An empty buffer serialises to an empty JSON array")
    void emptyHistorySerializesToEmptyArray() {
        assertEquals("[]", new MsptHistory(10).toJsonArray());
    }

    @Test
    @DisplayName("Serialisation carries time, mspt and tps of every sample")
    void serializesSamples() {
        MsptHistory history = new MsptHistory(4);
        history.add(1700000000000L, 12.34D, 19.87D);

        String json = history.toJsonArray();

        assertEquals("[{\"t\":1700000000000,\"mspt\":12.3,\"tps\":19.9}]", json);
        assertTrue(json.startsWith("[{") && json.endsWith("}]"));
    }

    @Test
    @DisplayName("A single-slot buffer keeps only the latest sample")
    void singleSlotKeepsLatest() {
        MsptHistory history = new MsptHistory(1);
        history.add(1L, 5.0D, 20.0D);
        history.add(2L, 6.0D, 19.0D);

        List<MsptHistory.Sample> samples = history.samples();

        assertEquals(1, samples.size());
        assertEquals(2L, samples.get(0).timestampMillis());
    }
}
