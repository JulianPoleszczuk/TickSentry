package dev.poleszczuk.ticksentry.web;

import dev.poleszczuk.ticksentry.util.Json;

/**
 * Snapshot of server state prepared for the dashboard.
 *
 * <p>The reason this class exists: an HTTP thread <b>must not</b> reach into the Bukkit API.
 * The snapshot is assembled by the main thread every few seconds, and the HTTP handler only
 * reads it.</p>
 *
 * @param tps          current TPS
 * @param mspt         average tick time
 * @param peakMs       longest freeze in the sample window
 * @param threshold    alert threshold from the configuration
 * @param players      number of players online
 * @param monitoring   whether the tick monitor is running
 * @param inIncident   whether an unfinished incident is in progress
 * @param incidents24h number of incidents in the last 24 hours
 * @param lastCategory cause of the last incident, or {@code null}
 * @param sparkSummary statistics from spark, or {@code null}
 * @param generatedAt  when the snapshot was taken
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

    /** @return placeholder snapshot, used before the first real measurement exists */
    public static LiveSnapshot empty() {
        return new LiveSnapshot(20.0D, 0.0D, 0.0D, 50.0D, 0, false, false, 0, null, null,
                System.currentTimeMillis());
    }

    /**
     * Serialises the snapshot to JSON.
     *
     * @return JSON object for the {@code /api/live} endpoint
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
