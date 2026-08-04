package dev.poleszczuk.ticksentry.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server state rendered in the Prometheus text exposition format.
 *
 * <p>The web panel already answers "how is the server right now". What it cannot do is keep a
 * month of history, alert at three in the morning, or sit next to the metrics from the machine
 * the server runs on. Prometheus does all three, and the HTTP server needed to feed it was
 * already here - this is the endpoint, nothing more.</p>
 *
 * <p>Like {@link LiveSnapshot}, the object is assembled on the main thread and only read by HTTP
 * threads, which must never touch the Bukkit API. Rendering is pure, so the output format is
 * covered by unit tests.</p>
 */
public final class MetricsSnapshot {

    private static final MetricsSnapshot EMPTY = new MetricsSnapshot(
            20.0D, 0.0D, 0.0D, 50.0D, 0, false, false, 0, 0L, -1L, 0L, 0L, 0, 0,
            Collections.emptyMap(), 0L);

    private final double tps;
    private final double mspt;
    private final double peakMs;
    private final double threshold;
    private final int players;
    private final boolean monitoring;
    private final boolean inIncident;
    private final int incidents24h;
    private final long heapUsedBytes;
    private final long heapMaxBytes;
    private final long gcCollections;
    private final long gcTimeMs;
    private final int loadedChunks;
    private final int repeatOffenders;
    private final Map<String, Double> pluginSeconds;
    private final long generatedAt;

    /**
     * @param tps             current TPS
     * @param mspt            average tick time
     * @param peakMs          longest freeze in the sample window
     * @param threshold       alert threshold from the configuration
     * @param players         number of players online
     * @param monitoring      whether the tick monitor is running
     * @param inIncident      whether an unfinished incident is in progress
     * @param incidents24h    number of incidents in the last 24 hours
     * @param heapUsedBytes   heap currently in use
     * @param heapMaxBytes    heap limit, or -1 when the JVM does not report one
     * @param gcCollections   collections since the previous reading
     * @param gcTimeMs        milliseconds spent collecting since the previous reading
     * @param loadedChunks    chunks loaded across all worlds
     * @param repeatOffenders how many chunks have been behind more than one incident
     * @param pluginSeconds   seconds each plugin spent in its event handlers in the window
     * @param generatedAt     when the snapshot was taken
     */
    public MetricsSnapshot(double tps, double mspt, double peakMs, double threshold, int players,
                           boolean monitoring, boolean inIncident, int incidents24h,
                           long heapUsedBytes, long heapMaxBytes, long gcCollections, long gcTimeMs,
                           int loadedChunks, int repeatOffenders, Map<String, Double> pluginSeconds,
                           long generatedAt) {
        this.tps = tps;
        this.mspt = mspt;
        this.peakMs = peakMs;
        this.threshold = threshold;
        this.players = players;
        this.monitoring = monitoring;
        this.inIncident = inIncident;
        this.incidents24h = incidents24h;
        this.heapUsedBytes = heapUsedBytes;
        this.heapMaxBytes = heapMaxBytes;
        this.gcCollections = gcCollections;
        this.gcTimeMs = gcTimeMs;
        this.loadedChunks = loadedChunks;
        this.repeatOffenders = repeatOffenders;
        this.pluginSeconds = pluginSeconds == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(pluginSeconds);
        this.generatedAt = generatedAt;
    }

    /** @return placeholder snapshot, served before the first real measurement exists */
    public static MetricsSnapshot empty() {
        return EMPTY;
    }

    /**
     * Renders every metric in the Prometheus text exposition format.
     *
     * <p>Everything is a gauge. Prometheus counters must never decrease, and none of these
     * numbers can promise that across a server restart - a gauge that says what it means beats
     * a counter that lies.</p>
     *
     * @return response body for {@code /metrics}, ending in a newline as the format requires
     */
    public String render() {
        StringBuilder text = new StringBuilder(1024);

        gauge(text, "ticksentry_up", "1 while the plugin is running and serving metrics", 1.0D);
        gauge(text, "ticksentry_tps", "Ticks per second, out of 20", tps);
        gauge(text, "ticksentry_mspt_milliseconds", "Average time one tick takes", mspt);
        gauge(text, "ticksentry_mspt_peak_milliseconds", "Longest gap between ticks in the window", peakMs);
        gauge(text, "ticksentry_mspt_threshold_milliseconds", "Tick time above which lag is reported", threshold);
        gauge(text, "ticksentry_players", "Players online", players);
        gauge(text, "ticksentry_monitoring", "1 when the tick monitor is running", monitoring ? 1.0D : 0.0D);
        gauge(text, "ticksentry_incident_active", "1 while an incident is in progress", inIncident ? 1.0D : 0.0D);
        gauge(text, "ticksentry_incidents_24h", "Incidents recorded in the last 24 hours", incidents24h);
        gauge(text, "ticksentry_repeat_offender_chunks",
                "Chunks that have been behind more than one incident", repeatOffenders);
        gauge(text, "ticksentry_loaded_chunks", "Chunks loaded across all worlds", loadedChunks);
        gauge(text, "ticksentry_heap_used_bytes", "Heap currently in use", heapUsedBytes);
        if (heapMaxBytes > 0L) {
            // A JVM without -Xmx reports -1, and exporting that as a limit would break any
            // "percent of heap used" query built on top of it.
            gauge(text, "ticksentry_heap_max_bytes", "Heap limit", heapMaxBytes);
        }
        gauge(text, "ticksentry_gc_collections", "Garbage collections since the previous reading", gcCollections);
        gauge(text, "ticksentry_gc_milliseconds",
                "Milliseconds spent collecting garbage since the previous reading", gcTimeMs);

        if (!pluginSeconds.isEmpty()) {
            text.append("# HELP ticksentry_plugin_handler_seconds ")
                    .append("Seconds a plugin spent in its synchronous event handlers in the profiler window\n")
                    .append("# TYPE ticksentry_plugin_handler_seconds gauge\n");
            for (Map.Entry<String, Double> entry : pluginSeconds.entrySet()) {
                text.append("ticksentry_plugin_handler_seconds{plugin=\"")
                        .append(escapeLabel(entry.getKey())).append("\"} ")
                        .append(number(entry.getValue())).append('\n');
            }
        }

        gauge(text, "ticksentry_snapshot_timestamp_seconds",
                "When these numbers were taken", generatedAt / 1000.0D);
        return text.toString();
    }

    private static void gauge(StringBuilder text, String name, String help, double value) {
        text.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" gauge\n")
                .append(name).append(' ').append(number(value)).append('\n');
    }

    /** Formats without an exponent or a thousands separator - Prometheus accepts neither locale. */
    private static String number(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15D) {
            return String.format(Locale.ROOT, "%d", (long) value);
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** Escapes a label value: backslash, double quote and newline, as the format requires. */
    static String escapeLabel(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                default:
                    escaped.append(character);
                    break;
            }
        }
        return escaped.toString();
    }
}
