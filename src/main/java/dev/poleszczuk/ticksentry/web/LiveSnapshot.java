package dev.poleszczuk.ticksentry.web;

import dev.poleszczuk.ticksentry.util.Json;

/**
 * Snapshot of server state prepared for the dashboard.
 *
 * <p>The reason this class exists: an HTTP thread <b>must not</b> reach into the Bukkit API.
 * The snapshot is assembled by the main thread every few seconds, and the HTTP handler only
 * reads it.</p>
 */
public final class LiveSnapshot {

    private final double tps;
    private final double mspt;
    private final double p95Ms;
    private final double p99Ms;
    private final double peakMs;
    private final double threshold;
    private final int players;
    private final boolean monitoring;
    private final boolean inIncident;
    private final int incidents24h;
    private final String lastCategory;
    private final String sparkSummary;
    private final long generatedAt;

    /**
     * @param tps          current TPS
     * @param mspt         average tick time
     * @param p95Ms        95th percentile tick time
     * @param p99Ms        99th percentile tick time
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
    public LiveSnapshot(double tps, double mspt, double p95Ms, double p99Ms, double peakMs,
                        double threshold, int players,
                        boolean monitoring, boolean inIncident, int incidents24h, String lastCategory,
                        String sparkSummary, long generatedAt) {
        this.tps = tps;
        this.mspt = mspt;
        this.p95Ms = p95Ms;
        this.p99Ms = p99Ms;
        this.peakMs = peakMs;
        this.threshold = threshold;
        this.players = players;
        this.monitoring = monitoring;
        this.inIncident = inIncident;
        this.incidents24h = incidents24h;
        this.lastCategory = lastCategory;
        this.sparkSummary = sparkSummary;
        this.generatedAt = generatedAt;
    }

    /** @return placeholder snapshot, used before the first real measurement exists */
    public static LiveSnapshot empty() {
        return new LiveSnapshot(20.0D, 0.0D, 0.0D, 0.0D, 0.0D, 50.0D, 0, false, false, 0, null, null,
                System.currentTimeMillis());
    }

    /** @return average tick time */
    public double mspt() {
        return mspt;
    }

    /** @return current TPS */
    public double tps() {
        return tps;
    }

    /** @return when the snapshot was taken */
    public long generatedAt() {
        return generatedAt;
    }

    /** @return whether the tick monitor was running when this was taken */
    public boolean monitoring() {
        return monitoring;
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
                + Json.field("p95Ms", p95Ms) + ","
                + Json.field("p99Ms", p99Ms) + ","
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
