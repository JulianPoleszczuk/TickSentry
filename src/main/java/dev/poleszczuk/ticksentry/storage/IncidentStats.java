package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagCategory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Podsumowanie incydentow z zadanego okresu - odpowiedz na pytanie "kiedy i przez co nam laguje".
 *
 * @param days       dlugosc analizowanego okresu w dniach
 * @param total      liczba incydentow w tym okresie
 * @param byCategory ile razy wystapila kazda przyczyna
 * @param byHour     liczba incydentow w rozbiciu na godziny doby (indeks 0-23)
 * @param worst      incydent z najgorszym czasem ticku lub {@code null}, gdy brak danych
 */
public record IncidentStats(
        int days,
        int total,
        Map<LagCategory, Integer> byCategory,
        int[] byHour,
        StoredIncident worst
) {

    /** @return pusty wynik dla okresu bez zadnego incydentu */
    public static IncidentStats empty(int days) {
        return new IncidentStats(days, 0, Map.of(), new int[24], null);
    }

    /** @return najczestsza przyczyna albo {@code null}, gdy brak danych */
    public LagCategory dominantCategory() {
        return byCategory.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Wskazuje pore doby, w ktorej serwer laguje najczesciej.
     *
     * @return godzina 0-23 albo -1, gdy brak danych
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
     * Buduje prosty wykres slupkowy rozkladu dobowego, gotowy do wyslania w czacie.
     *
     * @return lista linii, po jednej na kazda niepusta godzine
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
