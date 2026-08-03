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

    private static final double DEFAULT_ENTITY_WEIGHT = 1.0D;
    private static final double DEFAULT_TILE_WEIGHT = 0.3D;

    private static final Map<String, Double> ENTITY_WEIGHTS = Map.ofEntries(
            // A player costs far more than a mob: they keep chunks loaded and generate network traffic.
            Map.entry("PLAYER", 5.0D),
            Map.entry("ITEM", 0.5D),
            Map.entry("DROPPED_ITEM", 0.5D),
            Map.entry("EXPERIENCE_ORB", 0.6D),
            // Short-lived entities - they appear in bulk during terrain generation and combat, then vanish.
            Map.entry("FALLING_BLOCK", 0.3D),
            Map.entry("ARROW", 0.3D),
            Map.entry("SNOWBALL", 0.2D),
            Map.entry("ARMOR_STAND", 0.4D),
            Map.entry("ITEM_FRAME", 0.2D),
            Map.entry("GLOW_ITEM_FRAME", 0.2D),
            Map.entry("PAINTING", 0.1D),
            Map.entry("VILLAGER", 3.0D),
            Map.entry("WANDERING_TRADER", 2.0D),
            Map.entry("IRON_GOLEM", 1.5D),
            Map.entry("ALLAY", 1.5D),
            Map.entry("PIGLIN", 1.5D),
            Map.entry("PIGLIN_BRUTE", 1.5D),
            Map.entry("HOGLIN", 1.5D),
            Map.entry("ZOMBIFIED_PIGLIN", 1.5D),
            Map.entry("HOPPER_MINECART", 2.5D),
            Map.entry("MINECART_HOPPER", 2.5D),
            Map.entry("CHEST_MINECART", 1.5D),
            Map.entry("MINECART_CHEST", 1.5D),
            Map.entry("ZOMBIE", 1.2D),
            Map.entry("SKELETON", 1.2D),
            Map.entry("CREEPER", 1.2D),
            Map.entry("SPIDER", 1.2D),
            Map.entry("ENDERMAN", 1.2D)
    );

    private static final Map<String, Double> TILE_WEIGHTS = Map.ofEntries(
            Map.entry("HOPPER", 3.0D),
            Map.entry("DROPPER", 1.0D),
            Map.entry("DISPENSER", 1.0D),
            Map.entry("SPAWNER", 2.5D),
            Map.entry("TRIAL_SPAWNER", 2.5D),
            Map.entry("BEACON", 1.5D),
            Map.entry("CONDUIT", 1.5D),
            Map.entry("BREWING_STAND", 0.5D),
            Map.entry("FURNACE", 0.8D),
            Map.entry("BLAST_FURNACE", 0.8D),
            Map.entry("SMOKER", 0.8D),
            Map.entry("CAMPFIRE", 0.3D),
            Map.entry("SOUL_CAMPFIRE", 0.3D),
            Map.entry("CHEST", 0.15D),
            Map.entry("TRAPPED_CHEST", 0.15D),
            Map.entry("BARREL", 0.15D),
            Map.entry("ENDER_CHEST", 0.15D)
    );

    private HotspotAnalyzer() {
    }

    /**
     * Computes the weighted cost of entities in a chunk.
     *
     * @param stat chunk snapshot
     * @return sum of the weights of all entities
     */
    public static double entityScore(ChunkStat stat) {
        double score = 0.0D;
        for (Map.Entry<String, Integer> entry : stat.entityTypeCounts().entrySet()) {
            score += entityWeight(entry.getKey()) * entry.getValue();
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
        double score = 0.0D;
        for (Map.Entry<String, Integer> entry : stat.tileTypeCounts().entrySet()) {
            score += tileWeight(entry.getKey()) * entry.getValue();
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
        return entityScore(stat) + tileScore(stat);
    }

    /**
     * Picks the most suspicious chunks.
     *
     * @param stats every scanned chunk
     * @param limit maximum number of results
     * @return list sorted by score descending, without chunks below the relevance threshold
     */
    public static List<ChunkStat> topChunks(List<ChunkStat> stats, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return stats.stream()
                .filter(stat -> score(stat) >= MIN_INTERESTING_SCORE)
                // Ties break on entity count, then location - the result has to be reproducible.
                .sorted(Comparator.comparingDouble(HotspotAnalyzer::score).reversed()
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
        double entityScore = entityScore(stat);
        double tileScore = tileScore(stat);
        double total = entityScore + tileScore;
        if (total < MIN_INTERESTING_SCORE) {
            return LagCategory.UNKNOWN;
        }

        if (tileScore >= total * TILE_DOMINANCE_SHARE) {
            return LagCategory.REDSTONE;
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
            case PLAYER_CLUSTER:
                return "There are " + stat.playerCount()
                        + " players in this chunk - if that is spawn or an event, the lag is expected. Check: "
                        + tp + ".";
            case ENTITY_OVERLOAD:
                return "A lot of mixed entities (" + stat.entityCount()
                        + ") in one chunk. Take a look: " + tp + ".";
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
     * @return cost weight of that entity type
     */
    public static double entityWeight(String entityType) {
        return ENTITY_WEIGHTS.getOrDefault(entityType.toUpperCase(Locale.ROOT), DEFAULT_ENTITY_WEIGHT);
    }

    /**
     * @param tileType block entity type name
     * @return cost weight of that block entity type
     */
    public static double tileWeight(String tileType) {
        String type = tileType.toUpperCase(Locale.ROOT);
        Double exact = TILE_WEIGHTS.get(type);
        if (exact != null) {
            return exact;
        }
        // Decoration comes in hundreds of variants (signs, banners, heads) and is practically free.
        if (type.endsWith("SIGN") || type.endsWith("BANNER") || type.endsWith("HEAD")
                || type.endsWith("SKULL") || type.endsWith("BED") || type.endsWith("POT")) {
            return 0.05D;
        }
        if (type.endsWith("SHULKER_BOX")) {
            return 0.15D;
        }
        return DEFAULT_TILE_WEIGHT;
    }

    private static boolean isItemLike(String type) {
        return "ITEM".equals(type) || "DROPPED_ITEM".equals(type) || "EXPERIENCE_ORB".equals(type);
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
