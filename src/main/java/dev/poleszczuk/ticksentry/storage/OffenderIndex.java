package dev.poleszczuk.ticksentry.storage;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of the repeat offenders, laid out for instant lookup by location.
 *
 * <p>An alert is assembled on the main thread and cannot wait for a database read, so the plugin
 * keeps one of these refreshed in the background and hands it out as it is. It is immutable, so
 * whoever is reading it never sees a half-updated ranking.</p>
 */
public final class OffenderIndex {

    private static final OffenderIndex EMPTY = new OffenderIndex(Collections.emptyMap(), List.of());

    private final Map<String, RepeatOffender> byLocation;
    private final List<RepeatOffender> ranked;

    private OffenderIndex(Map<String, RepeatOffender> byLocation, List<RepeatOffender> ranked) {
        this.byLocation = byLocation;
        this.ranked = ranked;
    }

    /** @return an index that knows about no offenders at all */
    public static OffenderIndex empty() {
        return EMPTY;
    }

    /**
     * @param offenders ranked offenders, most frequent first
     * @return index over them
     */
    public static OffenderIndex of(Collection<RepeatOffender> offenders) {
        if (offenders == null || offenders.isEmpty()) {
            return EMPTY;
        }
        Map<String, RepeatOffender> byLocation = new HashMap<>();
        for (RepeatOffender offender : offenders) {
            byLocation.put(RepeatOffender.key(offender.world(), offender.blockX(), offender.blockZ()), offender);
        }
        return new OffenderIndex(byLocation, List.copyOf(offenders));
    }

    /** @return {@code true} when no chunk has offended more than once */
    public boolean isEmpty() {
        return ranked.isEmpty();
    }

    /** @return the ranking, most frequent first */
    public List<RepeatOffender> ranked() {
        return ranked;
    }

    /**
     * @param world  world name
     * @param blockX block X coordinate of the chunk centre
     * @param blockZ block Z coordinate of the chunk centre
     * @return what is known about that chunk, or {@code null} when it has offended at most once
     */
    public RepeatOffender find(String world, int blockX, int blockZ) {
        return world == null ? null : byLocation.get(RepeatOffender.key(world, blockX, blockZ));
    }

    /**
     * @param world  world name
     * @param blockX block X coordinate of the chunk centre
     * @param blockZ block Z coordinate of the chunk centre
     * @return one line describing the chunk's record, or {@code null} when it has none
     */
    public String describe(String world, int blockX, int blockZ) {
        RepeatOffender offender = find(world, blockX, blockZ);
        return offender == null ? null : offender.describe();
    }
}
