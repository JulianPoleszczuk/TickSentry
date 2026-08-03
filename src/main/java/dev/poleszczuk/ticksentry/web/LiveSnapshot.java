package dev.poleszczuk.ticksentry.web;

import dev.poleszczuk.ticksentry.util.Json;

/**
 * Migawka stanu serwera przygotowana dla dashboardu.
 *
 * <p>Kluczowy powod istnienia tej klasy: watek HTTP <b>nie moze</b> siegac po Bukkit API.
 * Migawke sklada glowny watek co kilka sekund, a handler HTTP tylko ja odczytuje.</p>
 *
 * @param tps            aktualny TPS
 * @param mspt           sredni czas ticku
 * @param peakMs         najdluzsza zwiecha w oknie pomiarowym
 * @param threshold      prog alarmowy z konfiguracji
 * @param players        liczba graczy online
 * @param monitoring     czy monitor tickow dziala
 * @param inIncident     czy trwa niezakonczony incydent
 * @param incidents24h   liczba incydentow z ostatniej doby
 * @param lastCategory   przyczyna ostatniego incydentu lub {@code null}
 * @param sparkSummary   statystyki ze sparka lub {@code null}
 * @param generatedAt    moment zebrania migawki
 */
public record LiveSnapshot(
        double tps,
        double mspt,
        double peakMs,
        double threshold,
        int players,
        boolean monitoring,
        boolean inIncident,
        int incidents24h,
        String lastCategory,
        String sparkSummary,
        long generatedAt
) {

    /** @return migawka zastepcza, uzywana zanim powstanie pierwszy prawdziwy pomiar */
    public static LiveSnapshot empty() {
        return new LiveSnapshot(20.0D, 0.0D, 0.0D, 50.0D, 0, false, false, 0, null, null,
                System.currentTimeMillis());
    }

    /**
     * Serializuje migawke do JSON-a.
     *
     * @return obiekt JSON dla endpointu {@code /api/live}
     */
    public String toJson() {
        return "{"
                + Json.field("tps", tps) + ","
                + Json.field("mspt", mspt) + ","
                + Json.field("peakMs", peakMs) + ","
                + Json.field("threshold", threshold) + ","
                + Json.field("players", players) + ","
                + Json.field("monitoring", monitoring) + ","
                + Json.field("inIncident", inIncident) + ","
                + Json.field("incidents24h", incidents24h) + ","
                + Json.field("lastCategory", lastCategory) + ","
                + Json.field("spark", sparkSummary) + ","
                + Json.field("generatedAt", generatedAt)
                + "}";
    }
}
