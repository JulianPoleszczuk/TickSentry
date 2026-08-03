package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.ConfigManager;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Przeglada zaladowane chunki i wskazuje te, ktore najprawdopodobniej odpowiadaja za lag.
 *
 * <p>Odczyt encji i block-entity musi isc przez glowny watek - Bukkit API nie jest bezpieczne
 * asynchronicznie. Zeby mimo to nie zablokowac serwera, skan jest <b>rozlozony na wiele tickow</b>
 * z twardym budzetem czasu na tick. Pomiar na zywym serwerze: 625 chunkow za jednym zamachem
 * zajmowalo 181 ms, czyli plugin walczacy z lagiem sam robilby zwieche. Teraz kazdy tick oddaje
 * sterowanie po {@value #BUDGET_MILLIS} ms, a wynik przychodzi callbackiem po zakonczeniu.</p>
 *
 * <p>Swiadome uproszczenie MVP: nie probujemy samplowac stosu wywolan jak spark. Zamiast tego
 * korelujemy wysoki MSPT z anomalna zawartoscia konkretnego chunka w tym samym oknie czasowym.</p>
 */
public final class ChunkHotspotScanner {

    /** Chunki z mniejsza liczba obiektow nie maja szans przekroczyc progu istotnosci - pomijamy je bez alokacji map. */
    private static final int MIN_OBJECTS_PER_CHUNK = 8;

    /** Ile milisekund jednego ticku wolno zuzyc na skanowanie. */
    private static final long BUDGET_MILLIS = 3L;

    private static final long BUDGET_NANOS = BUDGET_MILLIS * 1_000_000L;

    private final Plugin plugin;
    private final ConfigManager config;
    private final SparkBridge spark;
    private boolean scanning;

    /**
     * @param plugin instancja pluginu (dostep do serwera i schedulera)
     * @param config zrodlo listy pomijanych swiatow i limitu wynikow
     * @param spark  opcjonalne zrodlo dodatkowych statystyk
     */
    public ChunkHotspotScanner(Plugin plugin, ConfigManager config, SparkBridge spark) {
        this.plugin = plugin;
        this.config = config;
        this.spark = spark;
    }

    /** @return {@code true}, jesli skan wlasnie trwa */
    public boolean isScanning() {
        return scanning;
    }

    /**
     * Rozpoczyna skan rozlozony na kolejne ticki.
     *
     * <p>Metoda wraca natychmiast; gotowy raport trafia do {@code callback} na glownym watku,
     * zwykle w tym samym lub w kilku nastepnych tickach.</p>
     *
     * @param tps      aktualny TPS
     * @param mspt     aktualna srednia MSPT
     * @param peakMs   najdluzszy odstep miedzy tickami w oknie
     * @param manual   czy skan zostal wymuszony komenda
     * @param callback odbiorca gotowego incydentu
     * @return {@code false}, jesli inny skan juz trwa i to zlecenie zostalo pominiete
     */
    public boolean startScan(double tps, double mspt, double peakMs, boolean manual, Consumer<LagEvent> callback) {
        if (scanning) {
            return false;
        }
        List<Chunk> queue = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (config.isWorldIgnored(world.getName())) {
                continue;
            }
            Collections.addAll(queue, world.getLoadedChunks());
        }
        scanning = true;
        new ScanTask(queue, tps, mspt, peakMs, manual, callback).runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    /** Zamienia surowe tablice z Bukkita na niezalezna od API migawke chunka. */
    private static ChunkStat toStat(Chunk chunk, Entity[] entities, BlockState[] tiles) {
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

        return new ChunkStat(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), players, entityCounts, tileCounts);
    }

    /** Przetwarza kolejke chunkow porcjami, oddajac sterowanie po wyczerpaniu budzetu na tick. */
    private final class ScanTask extends BukkitRunnable {

        private final List<Chunk> queue;
        private final List<ChunkStat> stats = new ArrayList<>();
        private final double tps;
        private final double mspt;
        private final double peakMs;
        private final boolean manual;
        private final Consumer<LagEvent> callback;
        private final long startNanos = System.nanoTime();

        private int index;
        private int scannedChunks;
        private int totalEntities;
        private int ticksUsed;

        private ScanTask(List<Chunk> queue, double tps, double mspt, double peakMs,
                         boolean manual, Consumer<LagEvent> callback) {
            this.queue = queue;
            this.tps = tps;
            this.mspt = mspt;
            this.peakMs = peakMs;
            this.manual = manual;
            this.callback = callback;
        }

        @Override
        public void run() {
            ticksUsed++;
            long deadline = System.nanoTime() + BUDGET_NANOS;

            while (index < queue.size()) {
                if (System.nanoTime() >= deadline) {
                    return;
                }
                Chunk chunk = queue.get(index++);
                // Chunk mogl zostac wyladowany miedzy tickami - odczyt wymusilby jego ponowne wczytanie.
                if (!chunk.isLoaded()) {
                    continue;
                }
                scannedChunks++;

                Entity[] entities = chunk.getEntities();
                totalEntities += entities.length;
                BlockState[] tiles = chunk.getTileEntities(false);
                if (entities.length + tiles.length >= MIN_OBJECTS_PER_CHUNK) {
                    stats.add(toStat(chunk, entities, tiles));
                }
            }

            cancel();
            scanning = false;
            finish();
        }

        private void finish() {
            List<ChunkStat> top = HotspotAnalyzer.topChunks(stats, config.topChunksCount());
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            plugin.getLogger().fine("Przeskanowano " + scannedChunks + " chunkow w " + ticksUsed
                    + " tickach (" + durationMs + " ms zegarowych).");
            callback.accept(LagEvent.of(tps, mspt, peakMs, scannedChunks, totalEntities, top,
                    durationMs, manual, spark.summary()));
        }
    }
}
