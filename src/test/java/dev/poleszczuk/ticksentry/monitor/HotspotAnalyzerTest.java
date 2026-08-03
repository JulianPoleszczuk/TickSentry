package dev.poleszczuk.ticksentry.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotspotAnalyzerTest {

    private static ChunkStat entities(int x, int z, Map<String, Integer> counts) {
        return ChunkStat.ofEntities("world", x, z, counts);
    }

    private static ChunkStat tiles(int x, int z, Map<String, Integer> counts) {
        return new ChunkStat("world", x, z, 0, Map.of(), counts);
    }

    @Test
    @DisplayName("A hopper weighs many times more than a chest")
    void hopperOutweighsChest() {
        double hoppers = HotspotAnalyzer.score(tiles(0, 0, Map.of("HOPPER", 40)));
        double chests = HotspotAnalyzer.score(tiles(0, 0, Map.of("CHEST", 40)));
        assertTrue(hoppers > chests * 5, "40 hoppers should weigh far more than 40 chests");
    }

    @Test
    @DisplayName("Unknown types fall back to the default weights")
    void unknownTypesUseDefaultWeights() {
        assertEquals(1.0D, HotspotAnalyzer.entityWeight("SOME_MODDED_MOB"), 1e-9);
        assertEquals(0.3D, HotspotAnalyzer.tileWeight("SOME_MODDED_BLOCK"), 1e-9);
        assertEquals(0.05D, HotspotAnalyzer.tileWeight("BIRCH_WALL_SIGN"), 1e-9);
        assertEquals(0.15D, HotspotAnalyzer.tileWeight("RED_SHULKER_BOX"), 1e-9);
    }

    @Test
    @DisplayName("Top chunks come back sorted descending and capped at the limit")
    void topChunksAreSortedAndLimited() {
        ChunkStat small = entities(1, 1, Map.of("COW", 40));
        ChunkStat big = entities(2, 2, Map.of("VILLAGER", 60));
        ChunkStat medium = entities(3, 3, Map.of("ZOMBIE", 100));

        List<ChunkStat> top = HotspotAnalyzer.topChunks(List.of(small, big, medium), 2);

        assertEquals(2, top.size());
        assertEquals(big, top.get(0));
        assertEquals(medium, top.get(1));
    }

    @Test
    @DisplayName("Chunks below the relevance threshold are filtered out")
    void quietChunksAreFilteredOut() {
        ChunkStat quiet = entities(0, 0, Map.of("COW", 10));
        assertTrue(HotspotAnalyzer.topChunks(List.of(quiet), 5).isEmpty());
    }

    @Test
    @DisplayName("Categorisation recognises a mob farm")
    void detectsMobFarm() {
        assertEquals(LagCategory.MOB_FARM, HotspotAnalyzer.categorize(entities(0, 0, Map.of("COW", 200))));
    }

    @Test
    @DisplayName("Categorisation recognises an item pile")
    void detectsItemClutter() {
        assertEquals(LagCategory.ITEM_CLUTTER, HotspotAnalyzer.categorize(entities(0, 0, Map.of("ITEM", 300))));
        assertEquals(LagCategory.ITEM_CLUTTER, HotspotAnalyzer.categorize(entities(0, 0, Map.of("DROPPED_ITEM", 300))));
    }

    @Test
    @DisplayName("Categorisation recognises a redstone build")
    void detectsRedstone() {
        ChunkStat sorter = new ChunkStat("world", 0, 0, 0, Map.of("COW", 20), Map.of("HOPPER", 60));
        assertEquals(LagCategory.REDSTONE, HotspotAnalyzer.categorize(sorter));
    }

    @Test
    @DisplayName("Categorisation recognises a crowd of players")
    void detectsPlayerCluster() {
        ChunkStat spawn = new ChunkStat("world", 0, 0, 30, Map.of("PLAYER", 30), Map.of());
        assertEquals(LagCategory.PLAYER_CLUSTER, HotspotAnalyzer.categorize(spawn));

        ChunkStat crowdedArena = new ChunkStat("world", 0, 0, 60, Map.of("PLAYER", 60), Map.of());
        assertEquals(LagCategory.PLAYER_CLUSTER, HotspotAnalyzer.categorize(crowdedArena));
    }

    @Test
    @DisplayName("A mix of different mobs is a general entity overload")
    void detectsGenericOverload() {
        ChunkStat mixed = entities(0, 0, Map.of("ZOMBIE", 30, "SKELETON", 30, "CREEPER", 30));
        assertEquals(LagCategory.ENTITY_OVERLOAD, HotspotAnalyzer.categorize(mixed));
    }

    @Test
    @DisplayName("A quiet chunk gets no category at all")
    void quietChunkIsUnknown() {
        assertEquals(LagCategory.UNKNOWN, HotspotAnalyzer.categorize(entities(0, 0, Map.of("COW", 10))));
    }

    @Test
    @DisplayName("Short-lived entities from terrain generation do not fake a farm")
    void fallingBlocksAreNotAFarm() {
        // Regression from a live server test: 43 falling blocks were classified as a mob farm.
        ChunkStat generating = entities(0, 0, Map.of("FALLING_BLOCK", 43));
        assertEquals(LagCategory.UNKNOWN, HotspotAnalyzer.categorize(generating));
        assertTrue(HotspotAnalyzer.topChunks(List.of(generating), 5).isEmpty());
    }

    @Test
    @DisplayName("The /kill command targets the chunk bounds and uses the vanilla id")
    void killCommandTargetsChunkBounds() {
        ChunkStat stat = entities(7, -3, Map.of("DROPPED_ITEM", 100));
        String command = HotspotAnalyzer.killCommand(stat, "DROPPED_ITEM");

        assertEquals("/kill @e[type=item,x=112,y=-64,z=-48,dx=16,dy=384,dz=16]", command);
    }

    @Test
    @DisplayName("Chunk centre converts to world coordinates")
    void chunkCoordinatesConvertToBlocks() {
        ChunkStat stat = entities(7, -3, Map.of("COW", 1));
        assertEquals(120, stat.blockX());
        assertEquals(-40, stat.blockZ());
        assertEquals("world @ 120, -40", stat.prettyLocation());
    }

    @Test
    @DisplayName("The dominant type is resolved deterministically")
    void dominantTypeIsDeterministic() {
        ChunkStat stat = entities(0, 0, Map.of("COW", 50, "SHEEP", 50, "PIG", 10));
        Map.Entry<String, Integer> dominant = stat.dominantEntityType();

        assertNotNull(dominant);
        assertEquals("COW", dominant.getKey());
    }

    @Test
    @DisplayName("The mob farm suggestion contains a ready command")
    void suggestionContainsCommand() {
        ChunkStat farm = entities(4, 4, Map.of("COW", 200));
        String suggestion = HotspotAnalyzer.suggestedAction(farm, LagCategory.MOB_FARM);

        assertTrue(suggestion.contains("/tp 72 ~ 72"), suggestion);
        assertTrue(suggestion.contains("type=cow"), suggestion);
    }
}
