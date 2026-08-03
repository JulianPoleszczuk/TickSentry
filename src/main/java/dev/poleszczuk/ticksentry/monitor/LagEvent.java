package dev.poleszczuk.ticksentry.monitor;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * A single detected lag incident together with the context needed to report it to an admin.
 *
 * <p>Written as a plain immutable class rather than a record, because the plugin targets
 * Java 11 so that it also runs on Minecraft 1.16 servers.</p>
 */
public final class LagEvent {

    private final Instant timestamp;
    private final double tps;
    private final double averageMspt;
    private final double peakMs;
    private final int loadedChunks;
    private final int totalEntities;
    private final List<ChunkStat> topChunks;
    private final LagCategory category;
    private final String suggestedAction;
    private final long scanDurationMs;
    private final boolean manual;
    private final String sparkSummary;

    /**
     * @param timestamp       when it was detected
     * @param tps             one-minute TPS
     * @param averageMspt     rolling average MSPT
     * @param peakMs          longest gap between ticks in the window
     * @param loadedChunks    number of loaded chunks that were scanned
     * @param totalEntities   total entity count across the scanned worlds
     * @param topChunks       most suspicious chunks, sorted descending
     * @param category        guessed cause (for chunk number one)
     * @param suggestedAction hint for the admin
     * @param scanDurationMs  how long the chunk scan itself took
     * @param manual          whether the incident came from {@code /lagwatch report}
     * @param sparkSummary    extra statistics from spark, or {@code null} when spark is absent
     */
    public LagEvent(Instant timestamp, double tps, double averageMspt, double peakMs, int loadedChunks,
                    int totalEntities, List<ChunkStat> topChunks, LagCategory category, String suggestedAction,
                    long scanDurationMs, boolean manual, String sparkSummary) {
        this.timestamp = timestamp;
        this.tps = tps;
        this.averageMspt = averageMspt;
        this.peakMs = peakMs;
        this.loadedChunks = loadedChunks;
        this.totalEntities = totalEntities;
        this.topChunks = Collections.unmodifiableList(new java.util.ArrayList<>(topChunks));
        this.category = category;
        this.suggestedAction = suggestedAction;
        this.scanDurationMs = scanDurationMs;
        this.manual = manual;
        this.sparkSummary = sparkSummary;
    }

    /**
     * Assembles an incident from a scan result, working out the category and the suggestion
     * from the most suspicious chunk.
     *
     * @param tps            one-minute TPS
     * @param averageMspt    rolling average MSPT
     * @param peakMs         longest gap between ticks
     * @param loadedChunks   number of scanned chunks
     * @param totalEntities  total entity count
     * @param topChunks      sorted list of suspicious chunks
     * @param scanDurationMs scan duration
     * @param manual         whether the scan was manual
     * @param sparkSummary   spark statistics, or {@code null}
     * @return incident ready to be reported
     */
    public static LagEvent of(double tps, double averageMspt, double peakMs, int loadedChunks,
                              int totalEntities, List<ChunkStat> topChunks, long scanDurationMs,
                              boolean manual, String sparkSummary) {
        ChunkStat primary = topChunks.isEmpty() ? null : topChunks.get(0);
        LagCategory category = primary == null ? LagCategory.UNKNOWN : HotspotAnalyzer.categorize(primary);
        String action = primary == null
                ? HotspotAnalyzer.suggestedAction(
                        ChunkStat.ofEntities("-", 0, 0, new HashMap<>()), LagCategory.UNKNOWN)
                : HotspotAnalyzer.suggestedAction(primary, category);
        return new LagEvent(Instant.now(), tps, averageMspt, peakMs, loadedChunks, totalEntities,
                topChunks, category, action, scanDurationMs, manual, sparkSummary);
    }

    /** @return when the incident was detected */
    public Instant timestamp() {
        return timestamp;
    }

    /** @return one-minute TPS */
    public double tps() {
        return tps;
    }

    /** @return rolling average MSPT */
    public double averageMspt() {
        return averageMspt;
    }

    /** @return longest gap between ticks in the window */
    public double peakMs() {
        return peakMs;
    }

    /** @return number of scanned chunks */
    public int loadedChunks() {
        return loadedChunks;
    }

    /** @return total entity count across the scanned worlds */
    public int totalEntities() {
        return totalEntities;
    }

    /** @return most suspicious chunks, sorted descending */
    public List<ChunkStat> topChunks() {
        return topChunks;
    }

    /** @return guessed cause */
    public LagCategory category() {
        return category;
    }

    /** @return hint for the admin */
    public String suggestedAction() {
        return suggestedAction;
    }

    /** @return how long the chunk scan took */
    public long scanDurationMs() {
        return scanDurationMs;
    }

    /** @return whether the incident came from a manual report */
    public boolean manual() {
        return manual;
    }

    /** @return extra statistics from spark, or {@code null} */
    public String sparkSummary() {
        return sparkSummary;
    }

    /** @return most suspicious chunk, or {@code null} when none stood out */
    public ChunkStat primaryChunk() {
        return topChunks.isEmpty() ? null : topChunks.get(0);
    }
}
