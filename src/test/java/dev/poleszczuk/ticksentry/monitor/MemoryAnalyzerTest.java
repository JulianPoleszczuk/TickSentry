package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.monitor.MemoryProbe.MemorySample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAnalyzerTest {

    private static final long MB = 1024L * 1024L;

    private static MemorySample sample(long usedMb, long maxMb, long collections, long collectionMs) {
        return new MemorySample(usedMb * MB, maxMb * MB, collections, collectionMs);
    }

    @Test
    @DisplayName("A calm server produces no message at all")
    void quietServerSaysNothing() {
        MemoryAnalyzer.Verdict verdict = MemoryAnalyzer.diagnose(sample(1000, 4096, 2, 30), 5000L);

        assertFalse(verdict.explainsLag());
        assertFalse(verdict.hasMessage());
        assertNull(verdict.message());
    }

    @Test
    @DisplayName("A collector eating a fifth of the window explains the lag on its own")
    void heavyCollectingExplainsLag() {
        // 1200 ms of collecting inside a 5 s window - the server was frozen for a quarter of it.
        MemoryAnalyzer.Verdict verdict = MemoryAnalyzer.diagnose(sample(2000, 4096, 8, 1200), 5000L);

        assertTrue(verdict.explainsLag());
        assertTrue(verdict.message().contains("24%"), verdict.message());
        assertTrue(verdict.message().contains("8 collections"), verdict.message());
    }

    @Test
    @DisplayName("A nearly full heap is reported, and blamed once the collector is busy too")
    void fullHeapIsReported() {
        MemoryAnalyzer.Verdict calm = MemoryAnalyzer.diagnose(sample(3900, 4096, 1, 100), 5000L);
        assertTrue(calm.hasMessage(), "a 95% heap is always worth mentioning");
        assertFalse(calm.explainsLag(), "but on its own it does not prove the lag came from memory");

        MemoryAnalyzer.Verdict busy = MemoryAnalyzer.diagnose(sample(3900, 4096, 5, 600), 5000L);
        assertTrue(busy.explainsLag(), "full heap plus busy collector is the classic case");
    }

    @Test
    @DisplayName("Mild collecting is mentioned but not blamed")
    void mildCollectingIsOnlyMentioned() {
        MemoryAnalyzer.Verdict verdict = MemoryAnalyzer.diagnose(sample(2000, 4096, 3, 500), 5000L);

        assertTrue(verdict.hasMessage());
        assertFalse(verdict.explainsLag());
        assertTrue(verdict.message().contains("10%"), verdict.message());
    }

    @Test
    @DisplayName("An unknown heap limit does not break the description")
    void handlesUnknownHeapLimit() {
        MemorySample unknown = new MemorySample(500 * MB, -1L, 0, 0);

        assertEquals(-1, unknown.usedPercent());
        assertEquals("500 MB in use", unknown.describe());
        assertFalse(MemoryAnalyzer.diagnose(unknown, 5000L).hasMessage());
    }

    @Test
    @DisplayName("A zero-length window never divides by zero")
    void zeroWindowIsSafe() {
        MemoryAnalyzer.Verdict verdict = MemoryAnalyzer.diagnose(sample(2000, 4096, 5, 900), 0L);

        assertFalse(verdict.explainsLag());
    }

    @Test
    @DisplayName("Memory is described in plain megabytes")
    void describesMemory() {
        assertEquals("2048 MB of 4096 MB (50%)", sample(2048, 4096, 0, 0).describe());
    }
}
