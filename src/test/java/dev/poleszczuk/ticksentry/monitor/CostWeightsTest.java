package dev.poleszczuk.ticksentry.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostWeightsTest {

    private static Map<String, Double> map(String key, double value) {
        Map<String, Double> weights = new HashMap<>();
        weights.put(key, value);
        return weights;
    }

    @Test
    @DisplayName("An override replaces the built-in value and leaves the rest alone")
    void overrideReplacesOneValue() {
        CostWeights weights = CostWeights.withOverrides(map("VILLAGER", 9.0D), new HashMap<>());

        assertEquals(9.0D, weights.entityWeight("VILLAGER"), 1e-9);
        assertEquals(0.5D, weights.entityWeight("ITEM"), 1e-9, "untouched types keep their default");
        assertEquals(3.0D, weights.tileWeight("HOPPER"), 1e-9, "block entities are untouched too");
    }

    @Test
    @DisplayName("Type names are matched whatever the case")
    void typeNamesAreCaseInsensitive() {
        CostWeights weights = CostWeights.withOverrides(map("villager", 7.0D), new HashMap<>());

        assertEquals(7.0D, weights.entityWeight("VILLAGER"), 1e-9);
        assertEquals(7.0D, weights.entityWeight("Villager"), 1e-9);
    }

    @Test
    @DisplayName("Unknown types fall back to the defaults, decoration stays cheap")
    void unknownTypesUseFallbacks() {
        CostWeights weights = CostWeights.defaults();

        assertEquals(1.0D, weights.entityWeight("SOME_MODDED_MOB"), 1e-9);
        assertEquals(0.3D, weights.tileWeight("SOME_MODDED_BLOCK"), 1e-9);
        assertEquals(0.05D, weights.tileWeight("BIRCH_WALL_SIGN"), 1e-9);
        assertEquals(0.15D, weights.tileWeight("RED_SHULKER_BOX"), 1e-9);
    }

    @Test
    @DisplayName("Changing a weight changes which chunk is reported as worst")
    void weightsActuallyChangeTheVerdict() {
        ChunkStat villagers = ChunkStat.ofEntities("world", 1, 1, map("VILLAGER", 30).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().intValue())));
        ChunkStat cows = ChunkStat.ofEntities("world", 2, 2, map("COW", 120).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().intValue())));

        // Default weights: 30 villagers score 90, 120 cows score 120 - the cows win.
        List<ChunkStat> byDefault = HotspotAnalyzer.topChunks(List.of(villagers, cows), 1);
        assertEquals(cows, byDefault.get(0));

        // Tell the plugin villagers are very expensive and the ranking flips.
        CostWeights heavyVillagers = CostWeights.withOverrides(map("VILLAGER", 10.0D), new HashMap<>());
        List<ChunkStat> reweighted = HotspotAnalyzer.topChunks(List.of(villagers, cows), 1, heavyVillagers);
        assertEquals(villagers, reweighted.get(0));
    }

    @Test
    @DisplayName("A cheap enough override drops a chunk out of the report entirely")
    void loweringAWeightCanSilenceAChunk() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("ITEM", 200);
        ChunkStat itemPile = ChunkStat.ofEntities("world", 0, 0, counts);

        // 200 items at the default 0.5 score 100, comfortably over the threshold of 80.
        assertFalse(HotspotAnalyzer.topChunks(List.of(itemPile), 5).isEmpty());

        CostWeights cheapItems = CostWeights.withOverrides(map("ITEM", 0.1D), new HashMap<>());
        assertTrue(HotspotAnalyzer.topChunks(List.of(itemPile), 5, cheapItems).isEmpty(),
                "at 0.1 each they score 20 and should no longer be reported");
    }
}
