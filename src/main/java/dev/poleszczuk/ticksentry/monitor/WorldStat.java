package dev.poleszczuk.ticksentry.monitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * What one world is carrying right now.
 *
 * <p>Every other number this plugin reports is server-wide, which is fine until an admin with five
 * worlds asks the obvious next question: <em>which one</em>. A total of 4,000 entities says nothing
 * about whether that is spread evenly or all sitting in a nether hub.</p>
 *
 * <p>Entities per chunk is the reading that actually ranks them. A world with 3,000 entities across
 * 2,000 loaded chunks is an ordinary busy overworld; the same 3,000 in 200 chunks is somebody's
 * farm, and the totals alone cannot tell those apart.</p>
 *
 * <p>Pure - no Bukkit - so the ranking and the arithmetic are testable.</p>
 */
public final class WorldStat {

    private final String name;
    private final int loadedChunks;
    private final int entities;
    private final int players;

    /**
     * @param name         world name
     * @param loadedChunks how many chunks are loaded in it
     * @param entities     how many entities it holds
     * @param players      how many players are in it
     */
    public WorldStat(String name, int loadedChunks, int entities, int players) {
        this.name = name;
        this.loadedChunks = Math.max(0, loadedChunks);
        this.entities = Math.max(0, entities);
        this.players = Math.max(0, players);
    }

    /** @return world name */
    public String name() {
        return name;
    }

    /** @return how many chunks are loaded */
    public int loadedChunks() {
        return loadedChunks;
    }

    /** @return how many entities the world holds */
    public int entities() {
        return entities;
    }

    /** @return how many players are in the world */
    public int players() {
        return players;
    }

    /**
     * @return entities per loaded chunk, or 0 when nothing is loaded
     *
     * <p>The density, which is what separates a big world from a crowded one.</p>
     */
    public double entitiesPerChunk() {
        return loadedChunks == 0 ? 0.0D : (double) entities / loadedChunks;
    }

    /**
     * @param totalEntities entity count across every world
     * @return this world's share of them, between 0 and 1; 0 when there are none anywhere
     */
    public double share(int totalEntities) {
        return totalEntities <= 0 ? 0.0D : (double) entities / totalEntities;
    }

    /**
     * Orders worlds by the one that most needs looking at.
     *
     * <p>By density rather than by raw count: a small world packed with entities is the interesting
     * one, and sorting by totals would always put the overworld first simply because it is where
     * everybody spends their time. Ties break on the name, so the listing does not shuffle between
     * two runs a second apart.</p>
     *
     * @param worlds worlds in any order
     * @return a new list, densest first
     */
    public static List<WorldStat> ranked(Collection<WorldStat> worlds) {
        List<WorldStat> sorted = new ArrayList<>(worlds);
        sorted.sort(Comparator.comparingDouble(WorldStat::entitiesPerChunk).reversed()
                .thenComparing(Comparator.comparingInt(WorldStat::entities).reversed())
                .thenComparing(WorldStat::name));
        return sorted;
    }

    /**
     * @param worlds worlds to add up
     * @return total entities across all of them
     */
    public static int totalEntities(Collection<WorldStat> worlds) {
        int total = 0;
        for (WorldStat world : worlds) {
            total += world.entities();
        }
        return total;
    }

    @Override
    public String toString() {
        return "WorldStat[" + name + ", chunks=" + loadedChunks + ", entities=" + entities + "]";
    }
}
