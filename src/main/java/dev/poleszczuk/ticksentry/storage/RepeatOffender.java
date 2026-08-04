package dev.poleszczuk.ticksentry.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A chunk that has been behind more than one incident.
 *
 * <p>A single alert cannot tell a one-off from a standing problem: a chunk full of cows might be
 * a farm somebody built five minutes ago, or the same farm that has been dragging the server
 * down every evening for a fortnight. The incidents are already in the database, so the answer
 * costs one fold over the history.</p>
 *
 * <p>Only automatic incidents count. A {@code /lagwatch report} run five times in a row while
 * testing something would otherwise turn whatever chunk happened to be busiest into a
 * "chronic problem".</p>
 */
public final class RepeatOffender {

    /** Below two appearances there is no pattern to speak of, only a single incident. */
    public static final int MIN_HITS = 2;

    /** Appearances from which a chunk is called out as a standing problem. */
    private static final int CHRONIC_HITS = 3;

    /** Share of all incidents from which a chunk is called out as a standing problem. */
    private static final double CHRONIC_SHARE = 0.34D;

    private final String world;
    private final int blockX;
    private final int blockZ;
    private final int hits;
    private final int outOf;
    private final int days;
    private final Instant lastSeen;
    private final double worstMspt;

    /**
     * @param world     world name
     * @param blockX    block X coordinate of the chunk centre
     * @param blockZ    block Z coordinate of the chunk centre
     * @param hits      how many incidents named this chunk
     * @param outOf     how many incidents there were in total in the window
     * @param days      length of the window in days
     * @param lastSeen  when this chunk was last named
     * @param worstMspt worst tick time recorded while this chunk was the suspect
     */
    public RepeatOffender(String world, int blockX, int blockZ, int hits, int outOf, int days,
                          Instant lastSeen, double worstMspt) {
        this.world = world;
        this.blockX = blockX;
        this.blockZ = blockZ;
        this.hits = hits;
        this.outOf = outOf;
        this.days = days;
        this.lastSeen = lastSeen;
        this.worstMspt = worstMspt;
    }

    /**
     * Folds a history into a ranking of chunks that keep coming back.
     *
     * <p>Kept pure and shared by both stores, so the in-memory fallback and the SQLite history
     * can never disagree about what counts as a repeat offender.</p>
     *
     * @param incidents incidents inside the window, in any order
     * @param days      length of the window in days, carried into the result
     * @param limit     maximum number of entries to return
     * @return chunks named at least {@value #MIN_HITS} times, most frequent first
     */
    public static List<RepeatOffender> summarise(List<StoredIncident> incidents, int days, int limit) {
        if (limit <= 0 || incidents.isEmpty()) {
            return List.of();
        }

        Map<String, Accumulator> byChunk = new HashMap<>();
        int automatic = 0;
        for (StoredIncident incident : incidents) {
            if (incident.manual()) {
                continue;
            }
            automatic++;
            if (incident.world() == null) {
                continue;
            }
            byChunk.computeIfAbsent(key(incident.world(), incident.blockX(), incident.blockZ()),
                    key -> new Accumulator(incident.world(), incident.blockX(), incident.blockZ()))
                    .add(incident);
        }

        List<RepeatOffender> result = new ArrayList<>();
        for (Accumulator accumulator : byChunk.values()) {
            if (accumulator.hits >= MIN_HITS) {
                result.add(accumulator.build(automatic, days));
            }
        }
        // Ties break on severity and then on location, so the ranking never shuffles by itself.
        result.sort(Comparator.comparingInt(RepeatOffender::hits).reversed()
                .thenComparing(Comparator.comparingDouble(RepeatOffender::worstMspt).reversed())
                .thenComparing(RepeatOffender::prettyLocation));
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    /** @return key identifying one chunk, matching the one {@link OffenderIndex} looks up by */
    static String key(String world, int blockX, int blockZ) {
        return world + ':' + blockX + ':' + blockZ;
    }

    /** @return world name */
    public String world() {
        return world;
    }

    /** @return block X coordinate of the chunk centre */
    public int blockX() {
        return blockX;
    }

    /** @return block Z coordinate of the chunk centre */
    public int blockZ() {
        return blockZ;
    }

    /** @return how many incidents named this chunk */
    public int hits() {
        return hits;
    }

    /** @return how many incidents there were in total in the window */
    public int outOf() {
        return outOf;
    }

    /** @return length of the analysed window in days */
    public int days() {
        return days;
    }

    /** @return when this chunk was last named */
    public Instant lastSeen() {
        return lastSeen;
    }

    /** @return worst tick time recorded while this chunk was the suspect */
    public double worstMspt() {
        return worstMspt;
    }

    /** @return share of all incidents this chunk was behind, between 0 and 1 */
    public double share() {
        return outOf <= 0 ? 0.0D : (double) hits / (double) outOf;
    }

    /** @return whether this is a standing problem rather than a coincidence */
    public boolean isChronic() {
        return hits >= CHRONIC_HITS && share() >= CHRONIC_SHARE;
    }

    /** @return readable location, for example {@code world @ 120, 344} */
    public String prettyLocation() {
        return world + " @ " + blockX + ", " + blockZ;
    }

    /**
     * @return one line for an alert, for example
     *         {@code "behind 7 of the last 12 incidents (worst 180 ms)"}
     */
    public String describe() {
        return String.format(Locale.ROOT, "behind %d of the last %d incidents (worst %.0f ms)",
                hits, outOf, worstMspt);
    }

    @Override
    public String toString() {
        return "RepeatOffender[" + prettyLocation() + ", " + hits + "/" + outOf + "]";
    }

    /** Running totals for one chunk while folding the history. */
    private static final class Accumulator {

        private final String world;
        private final int blockX;
        private final int blockZ;
        private int hits;
        private Instant lastSeen;
        private double worstMspt;

        private Accumulator(String world, int blockX, int blockZ) {
            this.world = world;
            this.blockX = blockX;
            this.blockZ = blockZ;
        }

        private void add(StoredIncident incident) {
            hits++;
            if (lastSeen == null || incident.timestamp().isAfter(lastSeen)) {
                lastSeen = incident.timestamp();
            }
            worstMspt = Math.max(worstMspt, incident.mspt());
        }

        private RepeatOffender build(int outOf, int days) {
            return new RepeatOffender(world, blockX, blockZ, hits, outOf, days, lastSeen, worstMspt);
        }
    }
}
