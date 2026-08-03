package dev.poleszczuk.ticksentry.monitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How expensive each kind of entity and block entity is treated as being.
 *
 * <p>The numbers are relative to an average mob (= 1.0). They are sensible guesses, not
 * measurements - they exist so that 40 hoppers outweigh 40 chests, and 200 dropped items weigh
 * less than 200 villagers. Servers differ, so admins can override any of them in
 * {@code config.yml} without touching the code.</p>
 *
 * <p>Pure data with no Bukkit, so tests can build one directly.</p>
 */
public final class CostWeights {

    private static final double DEFAULT_ENTITY_WEIGHT = 1.0D;
    private static final double DEFAULT_TILE_WEIGHT = 0.3D;

    private final Map<String, Double> entityWeights;
    private final Map<String, Double> tileWeights;
    private final double defaultEntityWeight;
    private final double defaultTileWeight;

    /**
     * @param entityWeights       per-type entity weights, keys are matched case-insensitively
     * @param tileWeights         per-type block entity weights
     * @param defaultEntityWeight weight for entity types not listed
     * @param defaultTileWeight   weight for block entity types not listed
     */
    public CostWeights(Map<String, Double> entityWeights, Map<String, Double> tileWeights,
                       double defaultEntityWeight, double defaultTileWeight) {
        this.entityWeights = upperCaseKeys(entityWeights);
        this.tileWeights = upperCaseKeys(tileWeights);
        this.defaultEntityWeight = defaultEntityWeight;
        this.defaultTileWeight = defaultTileWeight;
    }

    private static Map<String, Double> upperCaseKeys(Map<String, Double> source) {
        Map<String, Double> copy = new HashMap<>();
        source.forEach((key, value) -> copy.put(key.toUpperCase(Locale.ROOT), value));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * The built-in weights, used when the config says nothing.
     *
     * @return default set of weights
     */
    public static CostWeights defaults() {
        Map<String, Double> entities = new HashMap<>();
        // A player costs far more than a mob: they keep chunks loaded and generate network traffic.
        entities.put("PLAYER", 5.0D);
        entities.put("ITEM", 0.5D);
        entities.put("DROPPED_ITEM", 0.5D);
        entities.put("EXPERIENCE_ORB", 0.6D);
        // Short-lived entities - they appear in bulk during terrain generation and combat, then vanish.
        entities.put("FALLING_BLOCK", 0.3D);
        entities.put("ARROW", 0.3D);
        entities.put("SNOWBALL", 0.2D);
        entities.put("ARMOR_STAND", 0.4D);
        entities.put("ITEM_FRAME", 0.2D);
        entities.put("GLOW_ITEM_FRAME", 0.2D);
        entities.put("PAINTING", 0.1D);
        entities.put("VILLAGER", 3.0D);
        entities.put("WANDERING_TRADER", 2.0D);
        entities.put("IRON_GOLEM", 1.5D);
        entities.put("ALLAY", 1.5D);
        entities.put("PIGLIN", 1.5D);
        entities.put("PIGLIN_BRUTE", 1.5D);
        entities.put("HOGLIN", 1.5D);
        entities.put("ZOMBIFIED_PIGLIN", 1.5D);
        entities.put("HOPPER_MINECART", 2.5D);
        entities.put("MINECART_HOPPER", 2.5D);
        entities.put("CHEST_MINECART", 1.5D);
        entities.put("MINECART_CHEST", 1.5D);
        entities.put("ZOMBIE", 1.2D);
        entities.put("SKELETON", 1.2D);
        entities.put("CREEPER", 1.2D);
        entities.put("SPIDER", 1.2D);
        entities.put("ENDERMAN", 1.2D);

        Map<String, Double> tiles = new HashMap<>();
        tiles.put("HOPPER", 3.0D);
        tiles.put("DROPPER", 1.0D);
        tiles.put("DISPENSER", 1.0D);
        tiles.put("SPAWNER", 2.5D);
        tiles.put("TRIAL_SPAWNER", 2.5D);
        tiles.put("BEACON", 1.5D);
        tiles.put("CONDUIT", 1.5D);
        tiles.put("BREWING_STAND", 0.5D);
        tiles.put("FURNACE", 0.8D);
        tiles.put("BLAST_FURNACE", 0.8D);
        tiles.put("SMOKER", 0.8D);
        tiles.put("CAMPFIRE", 0.3D);
        tiles.put("SOUL_CAMPFIRE", 0.3D);
        tiles.put("CHEST", 0.15D);
        tiles.put("TRAPPED_CHEST", 0.15D);
        tiles.put("BARREL", 0.15D);
        tiles.put("ENDER_CHEST", 0.15D);

        return new CostWeights(entities, tiles, DEFAULT_ENTITY_WEIGHT, DEFAULT_TILE_WEIGHT);
    }

    /**
     * Builds a set of weights from config overrides layered on top of the defaults.
     *
     * @param entityOverrides entity weights from the config, may be empty
     * @param tileOverrides   block entity weights from the config, may be empty
     * @return defaults with the overrides applied
     */
    public static CostWeights withOverrides(Map<String, Double> entityOverrides,
                                            Map<String, Double> tileOverrides) {
        CostWeights base = defaults();
        Map<String, Double> entities = new HashMap<>(base.entityWeights);
        Map<String, Double> tiles = new HashMap<>(base.tileWeights);
        entityOverrides.forEach((key, value) -> entities.put(key.toUpperCase(Locale.ROOT), value));
        tileOverrides.forEach((key, value) -> tiles.put(key.toUpperCase(Locale.ROOT), value));
        return new CostWeights(entities, tiles, base.defaultEntityWeight, base.defaultTileWeight);
    }

    /**
     * @param entityType entity type name
     * @return cost weight of that entity type
     */
    public double entityWeight(String entityType) {
        return entityWeights.getOrDefault(entityType.toUpperCase(Locale.ROOT), defaultEntityWeight);
    }

    /**
     * @param tileType block entity type name
     * @return cost weight of that block entity type
     */
    public double tileWeight(String tileType) {
        String type = tileType.toUpperCase(Locale.ROOT);
        Double exact = tileWeights.get(type);
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
        return defaultTileWeight;
    }
}
