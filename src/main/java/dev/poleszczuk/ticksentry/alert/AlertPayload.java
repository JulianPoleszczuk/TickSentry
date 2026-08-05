package dev.poleszczuk.ticksentry.alert;

import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.util.Json;

import java.util.Locale;

/**
 * What a generic webhook receives.
 *
 * <p>Two audiences, one body. {@code text} is a finished sentence, because Slack, Mattermost and most
 * chat webhooks render that field and nothing else - paste the URL in and it works. Everything else is
 * a flat field, because n8n, a script or a home automation box wants the numbers rather than a
 * sentence, and digging them back out of English prose would be absurd.</p>
 *
 * <p>Flat rather than nested on purpose: every tool that consumes webhooks can read
 * {@code $.mspt}, and not all of them can comfortably read {@code $.readings.tick.average}.</p>
 *
 * <p>Pure, so the exact bytes that go on the wire are testable.</p>
 */
public final class AlertPayload {

    private AlertPayload() {
    }

    /**
     * Builds the body for a detected incident.
     *
     * @param event     what was detected
     * @param threshold the tick time currently counted as overloaded
     * @return JSON ready to POST
     */
    public static String incident(LagEvent event, double threshold) {
        ChunkStat primary = event.primaryChunk();
        StringBuilder json = new StringBuilder(512).append('{')
                .append(Json.field("event", "incident")).append(',')
                .append(Json.field("text", incidentText(event))).append(',')
                .append(Json.field("cause", event.category().name())).append(',')
                .append(Json.field("causeTitle", event.category().title())).append(',')
                .append(Json.field("tps", event.tps())).append(',')
                .append(Json.field("mspt", event.averageMspt())).append(',')
                .append(Json.field("thresholdMs", threshold)).append(',')
                .append(Json.field("peakMs", event.peakMs())).append(',')
                .append(Json.field("manual", event.manual())).append(',')
                .append(Json.field("loadedChunks", event.loadedChunks())).append(',')
                .append(Json.field("totalEntities", event.totalEntities())).append(',')
                .append(Json.field("suggestion", event.suggestedAction())).append(',')
                .append(Json.field("timestamp", event.timestamp().toString()));

        if (event.hasPercentiles()) {
            json.append(',').append(Json.field("p95Ms", event.p95Ms()))
                    .append(',').append(Json.field("p99Ms", event.p99Ms()));
        }
        // Absent rather than null when no chunk was to blame: a receiver checking "is there a
        // location" should not have to tell a missing field from a null one.
        if (primary != null) {
            json.append(',').append(Json.field("world", primary.worldName()))
                    .append(',').append(Json.field("x", primary.blockX()))
                    .append(',').append(Json.field("z", primary.blockZ()))
                    .append(',').append(Json.field("entities", primary.entityCount()))
                    .append(',').append(Json.field("blockEntities", primary.tileEntityCount()));
            if (primary.attribution() != null) {
                json.append(',').append(Json.field("owner", primary.attribution()));
            }
        }
        if (event.pluginNote() != null) {
            json.append(',').append(Json.field("pluginNote", event.pluginNote()));
        }
        if (event.memoryNote() != null) {
            json.append(',').append(Json.field("memoryNote", event.memoryNote()));
        }
        return json.append('}').toString();
    }

    /**
     * Builds the body for a recovery.
     *
     * @param durationSeconds how long the incident lasted
     * @param tps             TPS after recovery
     * @param mspt            tick time after recovery
     * @return JSON ready to POST
     */
    public static String recovery(long durationSeconds, double tps, double mspt) {
        return "{"
                + Json.field("event", "recovery") + ","
                + Json.field("text", String.format(Locale.ROOT,
                        "Server is back to normal after %d s (TPS %.1f, tick time %.0f ms).",
                        durationSeconds, tps, mspt)) + ","
                + Json.field("durationSeconds", durationSeconds) + ","
                + Json.field("tps", tps) + ","
                + Json.field("mspt", mspt)
                + "}";
    }

    /**
     * Builds the body for a clean-up summary.
     *
     * @param summary what happened, or would have happened in dry-run
     * @return JSON ready to POST
     */
    public static String remediation(String summary) {
        return "{"
                + Json.field("event", "remediation") + ","
                + Json.field("text", summary) + ","
                + Json.field("summary", summary)
                + "}";
    }

    /** The one-line sentence a chat webhook will render. */
    private static String incidentText(LagEvent event) {
        ChunkStat primary = event.primaryChunk();
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "%s: %s - TPS %.1f, tick time %.0f ms",
                event.manual() ? "Requested report" : "Server is lagging",
                event.category().title(), event.tps(), event.averageMspt()));
        if (primary != null) {
            text.append(" at ").append(primary.prettyLocation());
        }
        return text.append('.').toString();
    }
}
