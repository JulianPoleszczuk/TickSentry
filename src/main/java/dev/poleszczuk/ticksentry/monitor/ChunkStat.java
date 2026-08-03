package dev.poleszczuk.ticksentry.monitor;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

/**
 * Migawka zawartosci jednego zaladowanego chunka.
 *
 * <p>Klasa celowo nie zna typow z Bukkita - typy encji i block-entity trzymane sa jako nazwy
 * (np. {@code "COW"}, {@code "HOPPER"}), dzieki czemu cala logika oceny i kategoryzacji
 * jest testowalna bez uruchamiania serwera.</p>
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

    /**
     * @param worldName        nazwa swiata, w ktorym lezy chunk
     * @param chunkX           wspolrzedna X chunka
     * @param chunkZ           wspolrzedna Z chunka
     * @param playerCount      liczba graczy w chunku
     * @param entityTypeCounts liczba encji w rozbiciu na typy
     * @param tileTypeCounts   liczba block-entity w rozbiciu na typy
     */
    public ChunkStat(String worldName, int chunkX, int chunkZ, int playerCount,
                     Map<String, Integer> entityTypeCounts, Map<String, Integer> tileTypeCounts) {
        this.worldName = worldName;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.playerCount = playerCount;
        this.entityTypeCounts = Map.copyOf(entityTypeCounts);
        this.tileTypeCounts = Map.copyOf(tileTypeCounts);
        this.entityCount = sum(this.entityTypeCounts);
        this.tileEntityCount = sum(this.tileTypeCounts);
    }

    private static int sum(Map<String, Integer> counts) {
        int total = 0;
        for (int value : counts.values()) {
            total += value;
        }
        return total;
    }

    /** @return nazwa swiata */
    public String worldName() {
        return worldName;
    }

    /** @return wspolrzedna X chunka */
    public int chunkX() {
        return chunkX;
    }

    /** @return wspolrzedna Z chunka */
    public int chunkZ() {
        return chunkZ;
    }

    /** @return wspolrzedna X srodka chunka w blokach (gotowa do wklejenia w /tp) */
    public int blockX() {
        return chunkX * 16 + 8;
    }

    /** @return wspolrzedna Z srodka chunka w blokach (gotowa do wklejenia w /tp) */
    public int blockZ() {
        return chunkZ * 16 + 8;
    }

    /** @return laczna liczba encji w chunku */
    public int entityCount() {
        return entityCount;
    }

    /** @return laczna liczba block-entity (skrzynie, hoppery, piece...) w chunku */
    public int tileEntityCount() {
        return tileEntityCount;
    }

    /** @return liczba graczy przebywajacych w chunku */
    public int playerCount() {
        return playerCount;
    }

    /** @return liczba encji wg typu, mapa niemodyfikowalna */
    public Map<String, Integer> entityTypeCounts() {
        return entityTypeCounts;
    }

    /** @return liczba block-entity wg typu, mapa niemodyfikowalna */
    public Map<String, Integer> tileTypeCounts() {
        return tileTypeCounts;
    }

    /** @return najliczniejszy typ encji wraz z liczba wystapien lub {@code null}, gdy chunk nie ma encji */
    public Map.Entry<String, Integer> dominantEntityType() {
        return dominant(entityTypeCounts);
    }

    /** @return najliczniejszy typ block-entity wraz z liczba wystapien lub {@code null}, gdy chunk nie ma block-entity */
    public Map.Entry<String, Integer> dominantTileType() {
        return dominant(tileTypeCounts);
    }

    private static Map.Entry<String, Integer> dominant(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                // Przy remisie decyduje nazwa - dzieki temu wynik jest deterministyczny.
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .orElse(null);
    }

    /** @return czytelny opis lokalizacji, np. {@code world @ 120, 344} */
    public String prettyLocation() {
        return worldName + " @ " + blockX() + ", " + blockZ();
    }

    @Override
    public String toString() {
        return "ChunkStat[" + prettyLocation() + ", encje=" + entityCount + ", block-entity=" + tileEntityCount + "]";
    }

    /**
     * Wygodny konstruktor dla przypadku bez rozbicia na typy block-entity.
     *
     * @param worldName        nazwa swiata
     * @param chunkX           wspolrzedna X chunka
     * @param chunkZ           wspolrzedna Z chunka
     * @param entityTypeCounts liczba encji wg typu
     * @return nowa migawka bez block-entity i bez graczy
     */
    public static ChunkStat ofEntities(String worldName, int chunkX, int chunkZ, Map<String, Integer> entityTypeCounts) {
        return new ChunkStat(worldName, chunkX, chunkZ, 0, entityTypeCounts, Collections.emptyMap());
    }
}
