package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.ConfigManager;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Przeglada zaladowane chunki i wskazuje te, ktore najprawdopodobniej odpowiadaja za lag.
 *
 * <p><b>Musi byc wywolywany z glownego watku serwera</b> - odczyt encji i block-entity przez
 * Bukkit API nie jest bezpieczny asynchronicznie. Sam skan jest tani (odczyt juz zaladowanych
 * struktur), a odpala sie rzadko, bo chroni go cooldown alertow.</p>
 *
 * <p>Swiadome uproszczenie MVP: nie probujemy samplowac stosu wywolan jak spark. Zamiast tego
 * korelujemy wysoki MSPT z anomalna zawartoscia konkretnego chunka w tym samym oknie czasowym.</p>
 */
public final class ChunkHotspotScanner {

    /** Chunki z mniejsza liczba obiektow nie maja szans przekroczyc progu istotnosci - pomijamy je bez alokacji map. */
    private static final int MIN_OBJECTS_PER_CHUNK = 8;

    private final Plugin plugin;
    private final ConfigManager config;

    /**
     * @param plugin instancja pluginu (dostep do serwera i logu)
     * @param config zrodlo listy pomijanych swiatow i limitu wynikow
     */
    public ChunkHotspotScanner(Plugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Skanuje wszystkie nieignorowane swiaty i sklada raport o incydencie.
     *
     * @param tps       aktualny TPS
     * @param mspt      aktualna srednia MSPT
     * @param peakMs    najdluzszy odstep miedzy tickami w oknie
     * @param manual    czy skan zostal wymuszony komenda
     * @return incydent z lista najbardziej podejrzanych chunkow
     */
    public LagEvent scan(double tps, double mspt, double peakMs, boolean manual) {
        long start = System.nanoTime();
        List<ChunkStat> stats = new ArrayList<>();
        int loadedChunks = 0;
        int totalEntities = 0;

        for (World world : plugin.getServer().getWorlds()) {
            if (config.isWorldIgnored(world.getName())) {
                continue;
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                loadedChunks++;
                Entity[] entities = chunk.getEntities();
                totalEntities += entities.length;

                BlockState[] tiles = chunk.getTileEntities(false);
                if (entities.length + tiles.length < MIN_OBJECTS_PER_CHUNK) {
                    continue;
                }
                stats.add(toStat(world, chunk, entities, tiles));
            }
        }

        List<ChunkStat> top = HotspotAnalyzer.topChunks(stats, config.topChunksCount());
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        if (durationMs > 50L) {
            plugin.getLogger().info("Skan " + loadedChunks + " chunkow zajal " + durationMs + " ms.");
        }
        return LagEvent.of(tps, mspt, peakMs, loadedChunks, totalEntities, top, durationMs, manual);
    }

    /** Zamienia surowe tablice z Bukkita na niezalezna od API migawke chunka. */
    private ChunkStat toStat(World world, Chunk chunk, Entity[] entities, BlockState[] tiles) {
        Map<String, Integer> entityCounts = new HashMap<>();
        int players = 0;
        for (Entity entity : entities) {
            EntityType type = entity.getType();
            if (type == EntityType.PLAYER) {
                players++;
            }
            entityCounts.merge(type.name(), 1, Integer::sum);
        }

        Map<String, Integer> tileCounts = new HashMap<>();
        for (BlockState tile : tiles) {
            tileCounts.merge(tile.getType().name(), 1, Integer::sum);
        }

        return new ChunkStat(world.getName(), chunk.getX(), chunk.getZ(), players, entityCounts, tileCounts);
    }
}
