package dev.poleszczuk.ticksentry.monitor;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scores and ranks chunk snapshots, then guesses what is causing the lag.
 *
 * <p>The whole class is pure - no Bukkit, no state - so it can be covered by unit tests.
 * This is where the only "intelligence" of the plugin lives: the cost weights of game
 * objects and the thresholds that decide the category.</p>
 *
 * <p>Weights approximate the real tick cost relative to an average mob (= 1.0). They are
 * not the result of profiling - they exist so that 40 hoppers outweigh 40 chests, and
 * 200 dropped items do not outrank 200 villagers.</p>
 */
public final class HotspotAnalyzer {

    /**
     * Chunks scoring below this are not considered suspicious.
     *
     * <p>Calibrated on a live server: at 25, an ordinary chunk holding a few dozen falling
     * blocks during terrain generation was being labelled a "mob farm". A chunk has to stand
     * out clearly before it makes it into a report.</p>
     */
    public static final double MIN_INTERESTING_SCORE = 80.0D;

    /** Share of a single entity type that counts as "dominant" (a farm, an item pile). */
    private static final double DOMINANCE_SHARE = 0.5D;

    /** Minimum entity count before a chunk can get an entity-related category at all. */
    private static final int ENTITY_HEAVY_COUNT = 60;

    /** Number of players that makes a chunk a crowd. */
    private static final int PLAYER_CLUSTER_COUNT = 5;

    /** Share of the score coming from block entities that points at redstone. */
    private static final double TILE_DOMINANCE_SHARE = 0.6D;

    /** Weights used when a caller does not supply its own. */
    private static final CostWeights DEFAULT_WEIGHTS = CostWeights.defaults();

    private HotspotAnalyzer() {
    }

    /**
     * Computes the weighted cost of entities in a chunk.
     *
     * @param stat chunk snapshot
     * @return sum of the weights of all entities
     */
    public static double entityScore(ChunkStat stat) {
        return entityScore(stat, DEFAULT_WEIGHTS);
    }

    /**
     * Computes the weighted cost of entities in a chunk.
     *
     * @param stat    chunk snapshot
     * @param weights cost weights to apply
     * @return sum of the weights of all entities
     */
    public static double entityScore(ChunkStat stat, CostWeights weights) {
        double score = 0.0D;
        for (Map.Entry<String, Integer> entry : stat.entityTypeCounts().entrySet()) {
            score += weights.entityWeight(entry.getKey()) * entry.getValue();
        }
        return score;
    }

    /**
     * Computes the weighted cost of block entities in a chunk.
     *
     * @param stat chunk snapshot
     * @return sum of the weights of all block entities
     */
    public static double tileScore(ChunkStat stat) {
        return tileScore(stat, DEFAULT_WEIGHTS);
    }

    /**
     * Computes the weighted cost of block entities in a chunk.
     *
     * @param stat    chunk snapshot
     * @param weights cost weights to apply
     * @return sum of the weights of all block entities
     */
    public static double tileScore(ChunkStat stat, CostWeights weights) {
        double score = 0.0D;
        for (Map.Entry<String, Integer> entry : stat.tileTypeCounts().entrySet()) {
            score += weights.tileWeight(entry.getKey()) * entry.getValue();
        }
        return score;
    }

    /**
     * Total score of a chunk - the higher, the more likely the culprit.
     *
     * @param stat chunk snapshot
     * @return combined entity and block entity score
     */
    public static double score(ChunkStat stat) {
        return score(stat, DEFAULT_WEIGHTS);
    }

    /**
     * Total score of a chunk under the given weights.
     *
     * @param stat    chunk snapshot
     * @param weights cost weights to apply
     * @return combined entity and block entity score
     */
    public static double score(ChunkStat stat, CostWeights weights) {
        return entityScore(stat, weights) + tileScore(stat, weights);
    }

    /**
     * Picks the most suspicious chunks.
     *
     * @param stats every scanned chunk
     * @param limit maximum number of results
     * @return list sorted by score descending, without chunks below the relevance threshold
     */
    public static List<ChunkStat> topChunks(List<ChunkStat> stats, int limit) {
        return topChunks(stats, limit, DEFAULT_WEIGHTS);
    }

    /**
     * Picks the most suspicious chunks using the given weights.
     *
     * @param stats   every scanned chunk
     * @param limit   maximum number of results
     * @param weights cost weights to apply
     * @return list sorted by score descending, without chunks below the relevance threshold
     */
    public static List<ChunkStat> topChunks(List<ChunkStat> stats, int limit, CostWeights weights) {
        if (limit <= 0) {
            return List.of();
        }
        return stats.stream()
                .filter(stat -> score(stat, weights) >= MIN_INTERESTING_SCORE)
                // Ties break on entity count, then location - the result has to be reproducible.
                .sorted(Comparator.comparingDouble((ChunkStat stat) -> score(stat, weights)).reversed()
                        .thenComparing(Comparator.comparingInt(ChunkStat::entityCount).reversed())
                        .thenComparing(ChunkStat::prettyLocation))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Guesses the cause of lag for a single chunk.
     *
     * @param stat chunk snapshot
     * @return matching category, never {@code null}
     */
    public static LagCategory categorize(ChunkStat stat) {
        return categorize(stat, DEFAULT_WEIGHTS);
    }

    /**
     * Guesses the cause of lag for a single chunk using the given weights.
     *
     * @param stat    chunk snapshot
     * @param weights cost weights to apply
     * @return matching category, never {@code null}
     */
    public static LagCategory categorize(ChunkStat stat, CostWeights weights) {
        double entityScore = entityScore(stat, weights);
        double tileScore = tileScore(stat, weights);
        double total = entityScore + tileScore;
        if (total < MIN_INTERESTING_SCORE) {
            return LagCategory.UNKNOWN;
        }

        if (tileScore >= total * TILE_DOMINANCE_SHARE) {
            Map.Entry<String, Integer> dominantTile = stat.dominantTileType();
            // A wall of spawners and a wall of hoppers both score as block entities, but they
            // are completely different problems with completely different fixes.
            return dominantTile != null && isSpawner(dominantTile.getKey())
                    ? LagCategory.SPAWNERS
                    : LagCategory.REDSTONE;
        }

        Map.Entry<String, Integer> dominant = stat.dominantEntityType();
        double dominantShare = dominant == null || stat.entityCount() == 0
                ? 0.0D
                : (double) dominant.getValue() / stat.entityCount();

        if (dominant != null && dominantShare >= DOMINANCE_SHARE && stat.entityCount() >= ENTITY_HEAVY_COUNT) {
            String type = dominant.getKey().toUpperCase(Locale.ROOT);
            if (isItemLike(type)) {
                return LagCategory.ITEM_CLUTTER;
            }
            if ("PLAYER".equals(type)) {
                return LagCategory.PLAYER_CLUSTER;
            }
            if (isMinecart(type)) {
                return LagCategory.MINECARTS;
            }
            return LagCategory.MOB_FARM;
        }

        if (stat.playerCount() >= PLAYER_CLUSTER_COUNT) {
            return LagCategory.PLAYER_CLUSTER;
        }

        if (stat.entityCount() >= ENTITY_HEAVY_COUNT) {
            return LagCategory.ENTITY_OVERLOAD;
        }

        return LagCategory.UNKNOWN;
    }

    /**
     * Builds a hint for the admin - either a ready command or a short instruction.
     *
     * @param stat     chunk snapshot
     * @param category category produced by {@link #categorize(ChunkStat)}
     * @return one-sentence suggested action
     */
    public static String suggestedAction(ChunkStat stat, LagCategory category) {
        String tp = "/tp " + stat.blockX() + " ~ " + stat.blockZ();
        Map.Entry<String, Integer> dominantEntity = stat.dominantEntityType();
        Map.Entry<String, Integer> dominantTile = stat.dominantTileType();

        switch (category) {
            case MOB_FARM:
                return dominantEntity == null
                        ? "Go there (" + tp + ") and see what piled up."
                        : "Go there (" + tp + "). Suspected farm: " + dominantEntity.getValue() + "x "
                        + friendly(dominantEntity.getKey()) + ". Quick fix: "
                        + killCommand(stat, dominantEntity.getKey());
            case ITEM_CLUTTER:
                return "Clear the dropped items: " + killCommand(stat, "item")
                        + " (run " + tp + " first to see whose they are).";
            case REDSTONE:
                return dominantTile == null
                        ? "Check the redstone build at this spot (" + tp + ")."
                        : "Check the redstone build (" + tp + "): " + dominantTile.getValue() + "x "
                        + friendly(dominantTile.getKey())
                        + ". Hoppers are worth reducing or replacing with water streams.";
            case SPAWNERS:
                return dominantTile == null
                        ? "Check the spawners at this spot (" + tp + ")."
                        : dominantTile.getValue() + " spawners in one chunk (" + tp
                        + "). Each one keeps checking for room to spawn, whether or not anyone is "
                        + "using the grinder. Ask the owner to switch some off, or break a few.";
            case MINECARTS:
                return dominantEntity == null
                        ? "Check the minecarts at this spot (" + tp + ")."
                        : dominantEntity.getValue() + "x " + friendly(dominantEntity.getKey()) + " at " + tp
                        + ". Hopper carts are checked every tick even when empty - a water stream or "
                        + "fewer carts would do the same job. Quick fix: "
                        + killCommand(stat, dominantEntity.getKey());
            case CHUNK_LOADING:
                // The chunk load rate carries the advice, so nothing to add from the chunk itself.
                return "This one is not about a single place - see the chunk loading note below.";
            case PLAYER_CLUSTER:
                return "There are " + stat.playerCount()
                        + " players in this chunk - if that is spawn or an event, the lag is expected. Check: "
                        + tp + ".";
            case ENTITY_OVERLOAD:
                return "A lot of mixed entities (" + stat.entityCount()
                        + ") in one chunk. Take a look: " + tp + ".";
            case MEMORY:
                // The memory message itself carries the advice, so nothing to add here.
                return "This one is not about the world - see the memory note below.";
            case PLUGIN:
                // Only the plugin report knows which plugin it was, so it writes its own advice.
                return "This one is not about the world - see the plugin note below.";
            case UNKNOWN:
            default:
                return "No single chunk stands out - the cause may be outside the game world "
                        + "(a plugin, world saving, terrain generation). Running the spark profiler is worth a try.";
        }
    }

    /**
     * Builds a command that removes entities of a given type within the chunk bounds.
     *
     * @param stat chunk snapshot
     * @param type entity type (Bukkit name or vanilla id)
     * @return ready-to-paste {@code /kill} command
     */
    public static String killCommand(ChunkStat stat, String type) {
        int cornerX = stat.chunkX() * 16;
        int cornerZ = stat.chunkZ() * 16;
        return "/kill @e[type=" + vanillaId(type) + ",x=" + cornerX + ",y=-64,z=" + cornerZ
                + ",dx=16,dy=384,dz=16]";
    }

    /**
     * @param entityType entity type name
     * @return cost weight of that entity type under the built-in weights
     */
    public static double entityWeight(String entityType) {
        return DEFAULT_WEIGHTS.entityWeight(entityType);
    }

    /**
     * @param tileType block entity type name
     * @return cost weight of that block entity type under the built-in weights
     */
    public static double tileWeight(String tileType) {
        return DEFAULT_WEIGHTS.tileWeight(tileType);
    }

    /**
     * Tells dropped items and experience orbs apart from mobs.
     *
     * <p>Bukkit renamed the dropped item type between versions, so both spellings count.</p>
     *
     * @param type entity type name
     * @return whether the type is litter on the ground rather than a creature
     */
    /**
     * @param type block entity type name
     * @return whether it is a mob spawner, under any of the names Bukkit has used for one
     */
    public static boolean isSpawner(String type) {
        String upper = type.toUpperCase(Locale.ROOT);
        return "SPAWNER".equals(upper) || "MOB_SPAWNER".equals(upper)
                || "CREATURE_SPAWNER".equals(upper) || "TRIAL_SPAWNER".equals(upper);
    }

    /**
     * @param type entity type name
     * @return whether it is a minecart of any kind
     */
    public static boolean isMinecart(String type) {
        return type.toUpperCase(Locale.ROOT).contains("MINECART");
    }

    public static boolean isItemLike(String type) {
        String upper = type.toUpperCase(Locale.ROOT);
        return "ITEM".equals(upper) || "DROPPED_ITEM".equals(upper) || "EXPERIENCE_ORB".equals(upper);
    }

    private static String vanillaId(String type) {
        String lower = type.toLowerCase(Locale.ROOT);
        // Bukkit historically calls a dropped item DROPPED_ITEM; vanilla only knows "item".
        return "dropped_item".equals(lower) ? "item" : lower;
    }

    private static String friendly(String type) {
        return type.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
