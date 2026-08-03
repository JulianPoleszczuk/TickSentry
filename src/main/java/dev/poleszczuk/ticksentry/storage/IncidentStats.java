package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Summary of incidents over a period - the answer to "when and why does this server lag".
 */
public final class IncidentStats {

    private final int days;
    private final int total;
    private final Map<LagCategory, Integer> byCategory;
    private final int[] byHour;
    private final StoredIncident worst;

    /**
     * @param days       length of the analysed period, in days
     * @param total      number of incidents in that period
     * @param byCategory how many times each cause appeared
     * @param byHour     incident counts per hour of the day (index 0-23)
     * @param worst      incident with the worst tick time, or {@code null} when there is no data
     */
    public IncidentStats(int days, int total, Map<LagCategory, Integer> byCategory, int[] byHour,
                         StoredIncident worst) {
        this.days = days;
        this.total = total;
        // Copied key by key: the EnumMap(Map) constructor rejects an empty non-enum map,
        // because it cannot work out which enum type it should hold.
        Map<LagCategory, Integer> copy = new EnumMap<>(LagCategory.class);
        copy.putAll(byCategory);
        this.byCategory = Collections.unmodifiableMap(copy);
        this.byHour = byHour.clone();
        this.worst = worst;
    }

    /**
     * @param days length of the period
     * @return empty result for a period without a single incident
     */
    public static IncidentStats empty(int days) {
        return new IncidentStats(days, 0, new EnumMap<>(LagCategory.class), new int[24], null);
    }

    /** @return length of the analysed period, in days */
    public int days() {
        return days;
    }

    /** @return number of incidents in the period */
    public int total() {
        return total;
    }

    /** @return how many times each cause appeared */
    public Map<LagCategory, Integer> byCategory() {
        return byCategory;
    }

    /** @return incident counts per hour of the day */
    public int[] byHour() {
        return byHour.clone();
    }

    /** @return incident with the worst tick time, or {@code null} */
    public StoredIncident worst() {
        return worst;
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
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>();
        for (int hour = 0; hour < byHour.length; hour++) {
            if (byHour[hour] == 0) {
                continue;
            }
            int bars = Math.max(1, Math.round(byHour[hour] * 20.0F / max));
            StringBuilder bar = new StringBuilder(bars);
            for (int i = 0; i < bars; i++) {
                bar.append('|');
            }
            lines.add(String.format("%02d:00 %s %d", hour, bar, byHour[hour]));
        }
        return lines;
    }
}
