package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentStatsTest {

    private static int[] hours(Map<Integer, Integer> counts) {
        int[] byHour = new int[24];
        counts.forEach((hour, count) -> byHour[hour] = count);
        return byHour;
    }

    @Test
    @DisplayName("An empty result names neither a cause nor a time of day")
    void emptyStatsHaveNoAnswers() {
        IncidentStats stats = IncidentStats.empty(7);

        assertEquals(0, stats.total());
        assertNull(stats.dominantCategory());
        assertEquals(-1, stats.worstHour());
        assertTrue(stats.hourHistogram().isEmpty());
    }

    @Test
    @DisplayName("The dominant cause is the one with the highest count")
    void findsDominantCategory() {
        IncidentStats stats = new IncidentStats(7, 9,
                Map.of(LagCategory.MOB_FARM, 5, LagCategory.REDSTONE, 3, LagCategory.ITEM_CLUTTER, 1),
                new int[24], null);

        assertEquals(LagCategory.MOB_FARM, stats.dominantCategory());
    }

    @Test
    @DisplayName("The worst time of day is the hour with the most incidents")
    void findsWorstHour() {
        IncidentStats stats = new IncidentStats(7, 12, Map.of(),
                hours(Map.of(3, 1, 20, 8, 21, 3)), null);

        assertEquals(20, stats.worstHour());
    }

    @Test
    @DisplayName("The histogram skips empty hours and scales bars to the maximum")
    void histogramSkipsEmptyHoursAndScales() {
        IncidentStats stats = new IncidentStats(7, 12, Map.of(),
                hours(Map.of(9, 1, 20, 10)), null);

        List<String> lines = stats.hourHistogram();

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("09:00 "), lines.get(0));
        assertTrue(lines.get(1).startsWith("20:00 "), lines.get(1));
        // The peak hour gets a full-length bar, a quiet one the minimum.
        assertTrue(lines.get(1).chars().filter(c -> c == '|').count()
                > lines.get(0).chars().filter(c -> c == '|').count());
    }
}
