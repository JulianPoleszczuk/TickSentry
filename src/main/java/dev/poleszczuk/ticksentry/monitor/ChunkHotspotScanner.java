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
 * Walks loaded chunks and points at the ones most likely responsible for lag.
 *
 * <p>Reading entities and block entities has to happen on the main server thread - the Bukkit
 * API is not safe to touch asynchronously. To avoid stalling the server anyway, the scan is
 * <b>spread across multiple ticks</b> with a hard time budget per tick. Measured on a live
 * server: scanning 625 chunks in one go took 181 ms, meaning a plugin fighting lag was itself
 * causing a freeze. Now every tick hands control back after {@value #BUDGET_MILLIS} ms and the
 * result arrives through a callback once finished.</p>
 *
 * <p>Deliberate MVP simplification: no call-stack sampling like spark. Instead, high MSPT is
 * correlated with an anomalous chunk in the same time window.</p>
 */
public final class ChunkHotspotScanner {

    /** Chunks holding fewer objects cannot reach the relevance threshold - skipped without allocating maps. */
    private static final int MIN_OBJECTS_PER_CHUNK = 8;

    /** How many milliseconds of a single tick may be spent scanning. */
    private static final long BUDGET_MILLIS = 3L;

    private static final long BUDGET_NANOS = BUDGET_MILLIS * 1_000_000L;

    private final Plugin plugin;
    private final ConfigManager config;
    private final SparkBridge spark;
    private final MemoryWatcher memory;
    private boolean scanning;

    /**
     * @param plugin plugin instance (server and scheduler access)
     * @param config source of ignored worlds and the result limit
     * @param spark  optional source of extra statistics
     * @param memory memory and garbage collector readings
     */
    public ChunkHotspotScanner(Plugin plugin, ConfigManager config, SparkBridge spark, MemoryWatcher memory) {
        this.plugin = plugin;
        this.config = config;
        this.spark = spark;
        this.memory = memory;
    }

    /** @return {@code true} while a scan is in progress */
    public boolean isScanning() {
        return scanning;
    }

    /**
     * Starts a scan spread across upcoming ticks.
     *
     * <p>Returns immediately; the finished report reaches {@code callback} on the main thread,
     * usually within the same or the next few ticks.</p>
     *
     * @param tps      current TPS
     * @param mspt     current average MSPT
     * @param peakMs   longest gap between ticks in the window
     * @param manual   whether the scan was forced by a command
     * @param callback receiver of the finished incident
     * @return {@code false} if another scan is already running and this request was skipped
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

    /** Turns raw Bukkit arrays into an API-independent chunk snapshot. */
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

    /** Processes the chunk queue in slices, yielding once the per-tick budget runs out. */
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
                // The chunk may have been unloaded between ticks - reading it would force a reload.
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
            plugin.getLogger().fine("Scanned " + scannedChunks + " chunks across " + ticksUsed
                    + " ticks (" + durationMs + " ms wall clock).");
            callback.accept(LagEvent.of(tps, mspt, peakMs, scannedChunks, totalEntities, top,
                    durationMs, manual, spark.summary(), memory.verdict()));
        }
    }
}
