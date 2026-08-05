package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.Messages;

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
    private final String memoryNote;
    private final String pluginNote;
    private final String chunkLoadNote;
    private final double p95Ms;
    private final double p99Ms;

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
     * @param memoryNote      what memory and the garbage collector were doing, or {@code null}
     * @param pluginNote      which plugin was eating the tick, or {@code null} when none stood out
     * @param chunkLoadNote   how fast chunks were coming into memory, or {@code null}
     */
    public LagEvent(Instant timestamp, double tps, double averageMspt, double peakMs, int loadedChunks,
                    int totalEntities, List<ChunkStat> topChunks, LagCategory category, String suggestedAction,
                    long scanDurationMs, boolean manual, String sparkSummary, String memoryNote,
                    String pluginNote, String chunkLoadNote) {
        this(timestamp, tps, averageMspt, peakMs, loadedChunks, totalEntities, topChunks, category,
                suggestedAction, scanDurationMs, manual, sparkSummary, memoryNote, pluginNote,
                chunkLoadNote, 0.0D, 0.0D);
    }

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
     * @param memoryNote      what memory and the garbage collector were doing, or {@code null}
     * @param pluginNote      which plugin was eating the tick, or {@code null} when none stood out
     * @param chunkLoadNote   how fast chunks were coming into memory, or {@code null}
     * @param p95Ms           95th percentile tick time, or 0 when unknown
     * @param p99Ms           99th percentile tick time, or 0 when unknown
     */
    public LagEvent(Instant timestamp, double tps, double averageMspt, double peakMs, int loadedChunks,
                    int totalEntities, List<ChunkStat> topChunks, LagCategory category, String suggestedAction,
                    long scanDurationMs, boolean manual, String sparkSummary, String memoryNote,
                    String pluginNote, String chunkLoadNote, double p95Ms, double p99Ms) {
        this.p95Ms = p95Ms;
        this.p99Ms = p99Ms;
        this.pluginNote = pluginNote;
        this.chunkLoadNote = chunkLoadNote;
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
        this.memoryNote = memoryNote;
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
     * @param memory         what memory looked like, or {@code null} when unknown
     * @return incident ready to be reported
     */
    public static LagEvent of(double tps, double averageMspt, double peakMs, int loadedChunks,
                              int totalEntities, List<ChunkStat> topChunks, long scanDurationMs,
                              boolean manual, String sparkSummary, MemoryAnalyzer.Verdict memory) {
        return of(tps, averageMspt, peakMs, loadedChunks, totalEntities, topChunks, scanDurationMs,
                manual, sparkSummary, memory, CostWeights.defaults());
    }

    /**
     * Assembles an incident using the given cost weights.
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
     * @param memory         what memory looked like, or {@code null} when unknown
     * @param weights        cost weights used to categorise the chunk
     * @return incident ready to be reported
     */
    public static LagEvent of(double tps, double averageMspt, double peakMs, int loadedChunks,
                              int totalEntities, List<ChunkStat> topChunks, long scanDurationMs,
                              boolean manual, String sparkSummary, MemoryAnalyzer.Verdict memory,
                              CostWeights weights) {
        return of(tps, averageMspt, peakMs, loadedChunks, totalEntities, topChunks, scanDurationMs,
                manual, sparkSummary, memory, weights, PluginReport.empty(), ChunkLoadVerdict.quiet());
    }

    /**
     * Assembles an incident without a chunk load reading.
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
     * @param memory         what memory looked like, or {@code null} when unknown
     * @param weights        cost weights used to categorise the chunk
     * @param plugins        per-plugin event handler timings, never {@code null}
     * @return incident ready to be reported
     */
    public static LagEvent of(double tps, double averageMspt, double peakMs, int loadedChunks,
                              int totalEntities, List<ChunkStat> topChunks, long scanDurationMs,
                              boolean manual, String sparkSummary, MemoryAnalyzer.Verdict memory,
                              CostWeights weights, PluginReport plugins) {
        return of(tps, averageMspt, peakMs, loadedChunks, totalEntities, topChunks, scanDurationMs,
                manual, sparkSummary, memory, weights, plugins, ChunkLoadVerdict.quiet());
    }

    /**
     * Assembles an incident, leaving every sentence in English.
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
     * @param memory         what memory looked like, or {@code null} when unknown
     * @param weights        cost weights used to categorise the chunk
     * @param plugins        per-plugin event handler timings, never {@code null}
     * @param chunkLoad      how fast chunks were coming into memory, never {@code null}
     * @return incident ready to be reported
     */
    public static LagEvent of(double tps, double averageMspt, double peakMs, int loadedChunks,
                              int totalEntities, List<ChunkStat> topChunks, long scanDurationMs,
                              boolean manual, String sparkSummary, MemoryAnalyzer.Verdict memory,
                              CostWeights weights, PluginReport plugins, ChunkLoadVerdict chunkLoad) {
        return of(tps, averageMspt, peakMs, loadedChunks, totalEntities, topChunks, scanDurationMs,
                manual, sparkSummary, memory, weights, plugins, chunkLoad, Messages.none());
    }

    /**
     * Assembles an incident from everything the plugin measured.
     *
     * <p>Deciding the cause runs in a fixed order. A plugin that took at least half of the
     * window outranks anything a chunk scan found, because no number of cows explains the
     * server thread sitting inside one plugin's handler. Otherwise the chunk verdict wins, and
     * only when no chunk stands out do a merely expensive plugin and then memory get their
     * turn.</p>
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
     * @param memory         what memory looked like, or {@code null} when unknown
     * @param weights        cost weights used to categorise the chunk
     * @param plugins        per-plugin event handler timings, never {@code null}
     * @param chunkLoad      how fast chunks were coming into memory, never {@code null}
     * @param messages       translation lookup; {@link Messages#none()} keeps everything English
     * @return incident ready to be reported
     */
    public static LagEvent of(double tps, double averageMspt, double peakMs, int loadedChunks,
                              int totalEntities, List<ChunkStat> topChunks, long scanDurationMs,
                              boolean manual, String sparkSummary, MemoryAnalyzer.Verdict memory,
                              CostWeights weights, PluginReport plugins, ChunkLoadVerdict chunkLoad,
                              Messages messages) {
        Messages text = messages == null ? Messages.none() : messages;
        PluginReport pluginReport = plugins == null ? PluginReport.empty() : plugins;
        ChunkLoadVerdict loadRate = chunkLoad == null ? ChunkLoadVerdict.quiet() : chunkLoad;
        ChunkStat primary = topChunks.isEmpty() ? null : topChunks.get(0);
        LagCategory category;

        if (pluginReport.dominatesLag()) {
            category = LagCategory.PLUGIN;
        } else {
            category = primary == null ? LagCategory.UNKNOWN : HotspotAnalyzer.categorize(primary, weights);
            if (category == LagCategory.UNKNOWN && pluginReport.explainsLag()) {
                category = LagCategory.PLUGIN;
            } else if (category == LagCategory.UNKNOWN && loadRate.explainsLag()) {
                // Nothing is sitting still causing this - the server is busy making the world.
                category = LagCategory.CHUNK_LOADING;
            } else if (category == LagCategory.UNKNOWN && memory != null && memory.explainsLag()) {
                // Nothing in the world stood out, but memory did - then memory is the answer.
                category = LagCategory.MEMORY;
            }
        }

        String action;
        if (category == LagCategory.PLUGIN) {
            action = pluginReport.suggestion(text);
        } else if (category == LagCategory.CHUNK_LOADING) {
            action = loadRate.suggestion(text);
        } else if (primary == null || category == LagCategory.MEMORY) {
            action = HotspotAnalyzer.suggestedAction(
                    ChunkStat.ofEntities("-", 0, 0, new HashMap<>()), category, text);
        } else {
            action = HotspotAnalyzer.suggestedAction(primary, category, text);
        }

        return new LagEvent(Instant.now(), tps, averageMspt, peakMs, loadedChunks, totalEntities,
                topChunks, category, action, scanDurationMs, manual, sparkSummary,
                memory == null ? null : memory.message(), pluginReport.message(text),
                loadRate.message(text));
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

    /** @return what memory and the garbage collector were doing, or {@code null} */
    public String memoryNote() {
        return memoryNote;
    }

    /** @return which plugin was eating the tick, or {@code null} when none stood out */
    public String pluginNote() {
        return pluginNote;
    }

    /** @return how fast chunks were coming into memory, or {@code null} when unremarkable */
    public String chunkLoadNote() {
        return chunkLoadNote;
    }

    /** @return most suspicious chunk, or {@code null} when none stood out */
    public ChunkStat primaryChunk() {
        return topChunks.isEmpty() ? null : topChunks.get(0);
    }

    /** @return 95th percentile tick time in the window, or 0 when it was not measured */
    public double p95Ms() {
        return p95Ms;
    }

    /** @return 99th percentile tick time in the window, or 0 when it was not measured */
    public double p99Ms() {
        return p99Ms;
    }

    /** @return whether percentiles are available - false on a server that cannot measure them */
    public boolean hasPercentiles() {
        return p95Ms > 0.0D;
    }

    /**
     * Copies this incident with the tick time percentiles attached.
     *
     * <p>A wither rather than two more parameters on each of the {@code of} factories: the scan
     * takes several ticks, so the percentiles are read where the finished incident is handed on,
     * and the analysis code that builds one has no business knowing about them.</p>
     *
     * @param p95 95th percentile tick time
     * @param p99 99th percentile tick time
     * @return a new incident; this one is left untouched
     */
    public LagEvent withPercentiles(double p95, double p99) {
        return new LagEvent(timestamp, tps, averageMspt, peakMs, loadedChunks, totalEntities,
                topChunks, category, suggestedAction, scanDurationMs, manual, sparkSummary,
                memoryNote, pluginNote, chunkLoadNote, p95, p99);
    }
}
