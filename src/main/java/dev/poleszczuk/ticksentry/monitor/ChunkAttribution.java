package dev.poleszczuk.ticksentry.monitor;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns "there are 1200 cows at 1608, 1608" into "…and it is Steve's farm".
 *
 * <p>Two independent sources are combined. {@link RegionLookup} asks whichever land protection
 * plugin is installed who owns the ground, which is the authoritative answer when it exists.
 * {@link ChunkVisitors} knows who was last standing there, which is a guess but works on servers
 * with no claim plugin at all.</p>
 *
 * <p>Attribution runs only for the chunks that made it into a report, so the cost is a handful
 * of lookups per incident rather than one per scanned chunk.</p>
 */
public final class ChunkAttribution {

    /** A visit older than this says nothing useful about who built the place. */
    private static final long STALE_VISIT_SECONDS = 24L * 60L * 60L;

    private final Plugin plugin;
    private final ChunkVisitors visitors;
    private final RegionLookup regions;

    /**
     * @param plugin   plugin instance, used to resolve worlds
     * @param visitors tracker of who was last seen where
     * @param regions  soft hooks into land protection plugins
     */
    public ChunkAttribution(Plugin plugin, ChunkVisitors visitors, RegionLookup regions) {
        this.plugin = plugin;
        this.visitors = visitors;
        this.regions = regions;
    }

    /**
     * Copies the given chunks with an attribution line attached where one could be worked out.
     *
     * @param stats chunks that made it into a report
     * @return the same chunks in the same order, annotated
     */
    public List<ChunkStat> attach(List<ChunkStat> stats) {
        if (stats.isEmpty()) {
            return stats;
        }
        List<ChunkStat> annotated = new ArrayList<>(stats.size());
        for (ChunkStat stat : stats) {
            String note = describe(stat);
            annotated.add(note == null ? stat : stat.withAttribution(note));
        }
        return annotated;
    }

    /**
     * Works out who a chunk belongs to.
     *
     * @param stat chunk snapshot
     * @return attribution line, or {@code null} when nothing is known about it
     */
    public String describe(ChunkStat stat) {
        String region = null;
        try {
            World world = plugin.getServer().getWorld(stat.worldName());
            if (world != null) {
                region = regions.describe(world, stat.blockX(), stat.blockZ());
            }
        } catch (RuntimeException ex) {
            // A protection plugin misbehaving must not cost us the whole alert.
            plugin.getLogger().fine("Could not look up the region at " + stat.prettyLocation() + ": " + ex);
        }

        ChunkVisitors.Visit visit = visitors.lastVisitor(stat.worldName(), stat.chunkX(), stat.chunkZ());
        if (visit == null || visit.secondsAgo() > STALE_VISIT_SECONDS) {
            return format(region, null, 0L);
        }
        return format(region, visit.playerName(), visit.secondsAgo());
    }

    /**
     * Builds the attribution line. Pure, so the wording is covered by unit tests.
     *
     * @param region       what the protection plugin said, or {@code null}
     * @param visitorName  who was last seen there, or {@code null}
     * @param secondsAgo   how long ago that was
     * @return one line, or {@code null} when neither source knew anything
     */
    public static String format(String region, String visitorName, long secondsAgo) {
        boolean hasRegion = region != null && !region.isEmpty();
        boolean hasVisitor = visitorName != null && !visitorName.isEmpty();

        if (hasRegion && hasVisitor) {
            return region + "; last player there: " + visitorName + ", " + ago(secondsAgo) + " ago";
        }
        if (hasRegion) {
            return region;
        }
        if (hasVisitor) {
            return "last player there: " + visitorName + ", " + ago(secondsAgo) + " ago";
        }
        return null;
    }

    /** Rounds a duration to the largest unit that still says something useful. */
    private static String ago(long seconds) {
        if (seconds < 60L) {
            return seconds + " s";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + " min";
        }
        long hours = minutes / 60L;
        return hours + " h";
    }
}
