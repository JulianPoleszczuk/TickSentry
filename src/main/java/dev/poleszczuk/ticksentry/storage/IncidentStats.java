package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagCategory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Summary of incidents over a period - the answer to "when and why does this server lag".
 *
 * @param days       length of the analysed period, in days
 * @param total      number of incidents in that period
 * @param byCategory how many times each cause appeared
 * @param byHour     incident counts per hour of the day (index 0-23)
 * @param worst      incident with the worst tick time, or {@code null} when there is no data
 */
public record IncidentStats(
        int days,
        int total,
        Map<LagCategory, Integer> byCategory,
        int[] byHour,
        StoredIncident worst
) {

    /**
     * @param days length of the period
     * @return empty result for a period without a single incident
     */
    public static IncidentStats empty(int days) {
        return new IncidentStats(days, 0, Map.of(), new int[24], null);
    }

    /** @return most common cause, or {@code null} when there is no data */
    public LagCategory dominantCategory() {
        return byCategory.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Points at the time of day the server lags most often.
     *
     * @return hour 0-23, or -1 when there is no data
     */
    public int worstHour() {
        int best = -1;
        int bestCount = 0;
        for (int hour = 0; hour < byHour.length; hour++) {
            if (byHour[hour] > bestCount) {
                bestCount = byHour[hour];
                best = hour;
            }
        }
        return best;
    }

    /**
     * Builds a simple bar chart of the daily spread, ready to print in chat.
     *
     * @return one line per non-empty hour
     */
    public List<String> hourHistogram() {
        int max = 0;
        for (int count : byHour) {
            max = Math.max(max, count);
        }
        if (max == 0) {
            return List.of();
        }
        List<String> lines = new java.util.ArrayList<>();
        for (int hour = 0; hour < byHour.length; hour++) {
            if (byHour[hour] == 0) {
                continue;
            }
            int bars = Math.max(1, Math.round(byHour[hour] * 20.0F / max));
            lines.add(String.format("%02d:00 %s %d", hour, "|".repeat(bars), byHour[hour]));
        }
        return lines;
    }
}
