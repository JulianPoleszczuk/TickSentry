package dev.poleszczuk.ticksentry.monitor;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

/**
 * Snapshot of the contents of a single loaded chunk.
 *
 * <p>The class deliberately knows nothing about Bukkit types - entity and block entity types
 * are kept as plain names (for example {@code "COW"}, {@code "HOPPER"}), which makes the whole
 * scoring and categorisation logic testable without starting a server.</p>
 */
public final class ChunkStat {

    private final String worldName;
    private final int chunkX;
    private final int chunkZ;
    private final int entityCount;
    private final int tileEntityCount;
    private final int playerCount;
    private final Map<String, Integer> entityTypeCounts;
    private final Map<String, Integer> tileTypeCounts;
    private final String attribution;

    /**
     * @param worldName        name of the world holding the chunk
     * @param chunkX           chunk X coordinate
     * @param chunkZ           chunk Z coordinate
     * @param playerCount      number of players inside the chunk
     * @param entityTypeCounts entity counts broken down by type
     * @param tileTypeCounts   block entity counts broken down by type
     */
    public ChunkStat(String worldName, int chunkX, int chunkZ, int playerCount,
                     Map<String, Integer> entityTypeCounts, Map<String, Integer> tileTypeCounts) {
        this(worldName, chunkX, chunkZ, playerCount, entityTypeCounts, tileTypeCounts, null);
    }

    /**
     * @param worldName        name of the world holding the chunk
     * @param chunkX           chunk X coordinate
     * @param chunkZ           chunk Z coordinate
     * @param playerCount      number of players inside the chunk
     * @param entityTypeCounts entity counts broken down by type
     * @param tileTypeCounts   block entity counts broken down by type
     * @param attribution      who the place belongs to, or {@code null} when unknown
     */
    public ChunkStat(String worldName, int chunkX, int chunkZ, int playerCount,
                     Map<String, Integer> entityTypeCounts, Map<String, Integer> tileTypeCounts,
                     String attribution) {
        this.worldName = worldName;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.playerCount = playerCount;
        this.entityTypeCounts = Map.copyOf(entityTypeCounts);
        this.tileTypeCounts = Map.copyOf(tileTypeCounts);
        this.entityCount = sum(this.entityTypeCounts);
        this.tileEntityCount = sum(this.tileTypeCounts);
        this.attribution = attribution;
    }

    private static int sum(Map<String, Integer> counts) {
        int total = 0;
        for (int value : counts.values()) {
            total += value;
        }
        return total;
    }

    /** @return world name */
    public String worldName() {
        return worldName;
    }

    /** @return chunk X coordinate */
    public int chunkX() {
        return chunkX;
    }

    /** @return chunk Z coordinate */
    public int chunkZ() {
        return chunkZ;
    }

    /** @return block X coordinate of the chunk centre, ready to paste into /tp */
    public int blockX() {
        return chunkX * 16 + 8;
    }

    /** @return block Z coordinate of the chunk centre, ready to paste into /tp */
    public int blockZ() {
        return chunkZ * 16 + 8;
    }

    /** @return total number of entities in the chunk */
    public int entityCount() {
        return entityCount;
    }

    /** @return total number of block entities (chests, hoppers, furnaces...) in the chunk */
    public int tileEntityCount() {
        return tileEntityCount;
    }

    /** @return number of players inside the chunk */
    public int playerCount() {
        return playerCount;
    }

    /** @return entity counts by type, as an unmodifiable map */
    public Map<String, Integer> entityTypeCounts() {
        return entityTypeCounts;
    }

    /** @return block entity counts by type, as an unmodifiable map */
    public Map<String, Integer> tileTypeCounts() {
        return tileTypeCounts;
    }

    /** @return most common entity type with its count, or {@code null} when the chunk has no entities */
    public Map.Entry<String, Integer> dominantEntityType() {
        return dominant(entityTypeCounts);
    }

    /** @return most common block entity type with its count, or {@code null} when there are none */
    public Map.Entry<String, Integer> dominantTileType() {
        return dominant(tileTypeCounts);
    }

    private static Map.Entry<String, Integer> dominant(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                // Ties break on the name, which keeps the result deterministic.
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .orElse(null);
    }

    /**
     * @return who the place belongs to (a claim, a region, or the last player seen there),
     *         or {@code null} when nothing is known
     */
    public String attribution() {
        return attribution;
    }

    /**
     * Copies this snapshot with an attribution line attached.
     *
     * @param attribution description of who the place belongs to
     * @return a new snapshot; this one is left untouched
     */
    public ChunkStat withAttribution(String attribution) {
        return new ChunkStat(worldName, chunkX, chunkZ, playerCount,
                entityTypeCounts, tileTypeCounts, attribution);
    }

    /** @return readable location, for example {@code world @ 120, 344} */
    public String prettyLocation() {
        return worldName + " @ " + blockX() + ", " + blockZ();
    }

    @Override
    public String toString() {
        return "ChunkStat[" + prettyLocation() + ", entities=" + entityCount
                + ", block entities=" + tileEntityCount + "]";
    }

    /**
     * Convenience factory for the case without any block entity breakdown.
     *
     * @param worldName        world name
     * @param chunkX           chunk X coordinate
     * @param chunkZ           chunk Z coordinate
     * @param entityTypeCounts entity counts by type
     * @return snapshot with no block entities and no players
     */
    public static ChunkStat ofEntities(String worldName, int chunkX, int chunkZ, Map<String, Integer> entityTypeCounts) {
        return new ChunkStat(worldName, chunkX, chunkZ, 0, entityTypeCounts, Collections.emptyMap());
    }
}
