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
    @DisplayName("Hopper wazy wielokrotnie wiecej niz skrzynia")
    void hopperOutweighsChest() {
        double hoppers = HotspotAnalyzer.score(tiles(0, 0, Map.of("HOPPER", 40)));
        double chests = HotspotAnalyzer.score(tiles(0, 0, Map.of("CHEST", 40)));
        assertTrue(hoppers > chests * 5, "40 hopperow powinno wazyc duzo wiecej niz 40 skrzyn");
    }

    @Test
    @DisplayName("Nieznane typy dostaja wage domyslna")
    void unknownTypesUseDefaultWeights() {
        assertEquals(1.0D, HotspotAnalyzer.entityWeight("SOME_MODDED_MOB"), 1e-9);
        assertEquals(0.3D, HotspotAnalyzer.tileWeight("SOME_MODDED_BLOCK"), 1e-9);
        assertEquals(0.05D, HotspotAnalyzer.tileWeight("BIRCH_WALL_SIGN"), 1e-9);
        assertEquals(0.15D, HotspotAnalyzer.tileWeight("RED_SHULKER_BOX"), 1e-9);
    }

    @Test
    @DisplayName("Top chunki sa posortowane malejaco i przyciete do limitu")
    void topChunksAreSortedAndLimited() {
        ChunkStat small = entities(1, 1, Map.of("COW", 40));
        ChunkStat big = entities(2, 2, Map.of("VILLAGER", 60));
        ChunkStat medium = entities(3, 3, Map.of("ZOMBIE", 50));

        List<ChunkStat> top = HotspotAnalyzer.topChunks(List.of(small, big, medium), 2);

        assertEquals(2, top.size());
        assertEquals(big, top.get(0));
        assertEquals(medium, top.get(1));
    }

    @Test
    @DisplayName("Chunki ponizej progu istotnosci sa odfiltrowane")
    void quietChunksAreFilteredOut() {
        ChunkStat quiet = entities(0, 0, Map.of("COW", 10));
        assertTrue(HotspotAnalyzer.topChunks(List.of(quiet), 5).isEmpty());
    }

    @Test
    @DisplayName("Kategoryzacja rozpoznaje farme mobow")
    void detectsMobFarm() {
        assertEquals(LagCategory.MOB_FARM, HotspotAnalyzer.categorize(entities(0, 0, Map.of("COW", 200))));
    }

    @Test
    @DisplayName("Kategoryzacja rozpoznaje zwal itemow")
    void detectsItemClutter() {
        assertEquals(LagCategory.ITEM_CLUTTER, HotspotAnalyzer.categorize(entities(0, 0, Map.of("ITEM", 300))));
        assertEquals(LagCategory.ITEM_CLUTTER, HotspotAnalyzer.categorize(entities(0, 0, Map.of("DROPPED_ITEM", 300))));
    }

    @Test
    @DisplayName("Kategoryzacja rozpoznaje maszyne redstone")
    void detectsRedstone() {
        ChunkStat sorter = new ChunkStat("world", 0, 0, 0, Map.of("COW", 20), Map.of("HOPPER", 60));
        assertEquals(LagCategory.REDSTONE, HotspotAnalyzer.categorize(sorter));
    }

    @Test
    @DisplayName("Kategoryzacja rozpoznaje skupisko graczy")
    void detectsPlayerCluster() {
        ChunkStat spawn = new ChunkStat("world", 0, 0, 30, Map.of("PLAYER", 30), Map.of());
        assertEquals(LagCategory.PLAYER_CLUSTER, HotspotAnalyzer.categorize(spawn));

        ChunkStat crowdedArena = new ChunkStat("world", 0, 0, 60, Map.of("PLAYER", 60), Map.of());
        assertEquals(LagCategory.PLAYER_CLUSTER, HotspotAnalyzer.categorize(crowdedArena));
    }

    @Test
    @DisplayName("Mieszanka roznych mobow to ogolne przeciazenie encjami")
    void detectsGenericOverload() {
        ChunkStat mixed = entities(0, 0, Map.of("ZOMBIE", 20, "SKELETON", 20, "CREEPER", 20));
        assertEquals(LagCategory.ENTITY_OVERLOAD, HotspotAnalyzer.categorize(mixed));
    }

    @Test
    @DisplayName("Spokojny chunk nie dostaje zadnej kategorii")
    void quietChunkIsUnknown() {
        assertEquals(LagCategory.UNKNOWN, HotspotAnalyzer.categorize(entities(0, 0, Map.of("COW", 10))));
    }

    @Test
    @DisplayName("Komenda /kill celuje w rog chunka i uzywa wanilijnego id")
    void killCommandTargetsChunkBounds() {
        ChunkStat stat = entities(7, -3, Map.of("DROPPED_ITEM", 100));
        String command = HotspotAnalyzer.killCommand(stat, "DROPPED_ITEM");

        assertEquals("/kill @e[type=item,x=112,y=-64,z=-48,dx=16,dy=384,dz=16]", command);
    }

    @Test
    @DisplayName("Srodek chunka przeliczany jest na wspolrzedne swiata")
    void chunkCoordinatesConvertToBlocks() {
        ChunkStat stat = entities(7, -3, Map.of("COW", 1));
        assertEquals(120, stat.blockX());
        assertEquals(-40, stat.blockZ());
        assertEquals("world @ 120, -40", stat.prettyLocation());
    }

    @Test
    @DisplayName("Dominujacy typ jest wyliczany deterministycznie")
    void dominantTypeIsDeterministic() {
        ChunkStat stat = entities(0, 0, Map.of("COW", 50, "SHEEP", 50, "PIG", 10));
        Map.Entry<String, Integer> dominant = stat.dominantEntityType();

        assertNotNull(dominant);
        assertEquals("COW", dominant.getKey());
    }

    @Test
    @DisplayName("Sugestia dla farmy mobow zawiera gotowa komende")
    void suggestionContainsCommand() {
        ChunkStat farm = entities(4, 4, Map.of("COW", 200));
        String suggestion = HotspotAnalyzer.suggestedAction(farm, LagCategory.MOB_FARM);

        assertTrue(suggestion.contains("/tp 72 ~ 72"), suggestion);
        assertTrue(suggestion.contains("type=cow"), suggestion);
    }
}
