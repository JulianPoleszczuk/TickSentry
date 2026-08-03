package dev.poleszczuk.ticksentry.monitor;

import java.time.Instant;
import java.util.List;

/**
 * A single detected lag incident together with the context needed to report it to an admin.
 *
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
public record LagEvent(
        Instant timestamp,
        double tps,
        double averageMspt,
        double peakMs,
        int loadedChunks,
        int totalEntities,
        List<ChunkStat> topChunks,
        LagCategory category,
        String suggestedAction,
        long scanDurationMs,
        boolean manual,
        String sparkSummary
) {

    /**
     * Assembles an incident from a scan result, working out the category and the suggestion
     * from the most suspicious chunk.
     *
     * @param tps             one-minute TPS
     * @param averageMspt     rolling average MSPT
     * @param peakMs          longest gap between ticks
     * @param loadedChunks    number of scanned chunks
     * @param totalEntities   total entity count
     * @param topChunks       sorted list of suspicious chunks
     * @param scanDurationMs  scan duration
     * @param manual          whether the scan was manual
     * @param sparkSummary    spark statistics, or {@code null}
     * @return incident ready to be reported
     */
    public static LagEvent of(double tps, double averageMspt, double peakMs, int loadedChunks,
                              int totalEntities, List<ChunkStat> topChunks, long scanDurationMs,
                              boolean manual, String sparkSummary) {
        ChunkStat primary = topChunks.isEmpty() ? null : topChunks.get(0);
        LagCategory category = primary == null ? LagCategory.UNKNOWN : HotspotAnalyzer.categorize(primary);
        String action = primary == null
                ? HotspotAnalyzer.suggestedAction(
                        ChunkStat.ofEntities("-", 0, 0, java.util.Map.of()), LagCategory.UNKNOWN)
                : HotspotAnalyzer.suggestedAction(primary, category);
        return new LagEvent(Instant.now(), tps, averageMspt, peakMs, loadedChunks, totalEntities,
                List.copyOf(topChunks), category, action, scanDurationMs, manual, sparkSummary);
    }

    /** @return most suspicious chunk, or {@code null} when none stood out */
    public ChunkStat primaryChunk() {
        return topChunks.isEmpty() ? null : topChunks.get(0);
    }
}
