package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.util.Json;

import java.time.Instant;
import java.util.Map;

/**
 * A flattened incident, in the shape that goes into the database and comes back out.
 *
 * <p>The full list of suspicious chunks is not stored - for history and statistics the main one
 * is enough. That way one incident is one row.</p>
 *
 * @param timestamp     when it was detected
 * @param tps           TPS at detection time
 * @param mspt          average tick time at detection time
 * @param category      guessed cause
 * @param world         world of the main suspect chunk, or {@code null}
 * @param blockX        block X coordinate of that chunk's centre
 * @param blockZ        block Z coordinate of that chunk's centre
 * @param entities      entity count in that chunk
 * @param dominantType  most common entity type, or {@code null}
 * @param dominantCount number of entities of the dominant type
 * @param manual        whether the incident came from a manual scan
 */
public record StoredIncident(
        Instant timestamp,
        double tps,
        double mspt,
        LagCategory category,
        String world,
        int blockX,
        int blockZ,
        int entities,
        String dominantType,
        int dominantCount,
        boolean manual
) {

    /**
     * Flattens a full incident into a storable row.
     *
     * @param event source incident
     * @return record ready to be inserted
     */
    public static StoredIncident from(LagEvent event) {
        ChunkStat primary = event.primaryChunk();
        Map.Entry<String, Integer> dominant = primary == null ? null : primary.dominantEntityType();
        return new StoredIncident(
                event.timestamp(),
                event.tps(),
                event.averageMspt(),
                event.category(),
                primary == null ? null : primary.worldName(),
                primary == null ? 0 : primary.blockX(),
                primary == null ? 0 : primary.blockZ(),
                primary == null ? 0 : primary.entityCount(),
                dominant == null ? null : dominant.getKey(),
                dominant == null ? 0 : dominant.getValue(),
                event.manual());
    }

    /** @return readable location, or a note that there is none */
    public String prettyLocation() {
        return world == null ? "no specific place" : world + " @ " + blockX + ", " + blockZ;
    }

    /**
     * Serialises the incident for the web dashboard.
     *
     * @return JSON object with the fields read by dashboard.html
     */
    public String toJson() {
        return "{"
                + Json.field("at", timestamp.toEpochMilli()) + ","
                + Json.field("mspt", mspt) + ","
                + Json.field("tps", tps) + ","
                + Json.field("category", category.title()) + ","
                + Json.field("world", world) + ","
                + Json.field("x", blockX) + ","
                + Json.field("z", blockZ) + ","
                + Json.field("entities", entities) + ","
                + Json.field("manual", manual)
                + "}";
    }

    /**
     * Builds a JSON array from a list of incidents.
     *
     * @param incidents list to serialise
     * @return JSON array ready for the dashboard
     */
    public static String toJsonArray(java.util.List<StoredIncident> incidents) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < incidents.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(incidents.get(i).toJson());
        }
        return json.append(']').toString();
    }
}
