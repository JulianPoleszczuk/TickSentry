package dev.poleszczuk.ticksentry.monitor;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Counts how fast chunks are coming into memory.
 *
 * <p>The handler does nothing but increment a counter, which matters: on a server generating
 * terrain this event fires hundreds of times a second, and the last thing a lag detector should
 * do is add to the problem.</p>
 *
 * <p>Counts are held in a ring of buckets rotated on the same timer as the memory readings, so a
 * report can ask about the last minute rather than about all time.</p>
 */
public final class ChunkLoadRate implements Listener {

    /** Buckets kept; at one rotation every five seconds this covers a minute. */
    private static final int BUCKETS = 12;

    private final int bucketSeconds;
    private final long[] loaded = new long[BUCKETS];
    private final long[] generated = new long[BUCKETS];

    private int cursor;
    private int filled;
    private long currentLoaded;
    private long currentGenerated;

    /**
     * @param bucketSeconds how often {@link #rotate()} will be called, in seconds
     */
    public ChunkLoadRate(int bucketSeconds) {
        this.bucketSeconds = Math.max(1, bucketSeconds);
    }

    /**
     * @param event chunk load event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        currentLoaded++;
        if (event.isNewChunk()) {
            currentGenerated++;
        }
    }

    /** Closes the current bucket and starts a new one. Call this on a steady interval. */
    public void rotate() {
        loaded[cursor] = currentLoaded;
        generated[cursor] = currentGenerated;
        currentLoaded = 0L;
        currentGenerated = 0L;
        cursor = (cursor + 1) % BUCKETS;
        if (filled < BUCKETS) {
            filled++;
        }
    }

    /**
     * @return what the recent chunk load rate says about a slowdown, never {@code null}
     */
    public ChunkLoadVerdict verdict() {
        if (filled == 0) {
            return ChunkLoadVerdict.quiet();
        }
        long totalLoaded = 0L;
        long totalGenerated = 0L;
        for (int i = 0; i < filled; i++) {
            totalLoaded += loaded[i];
            totalGenerated += generated[i];
        }
        double seconds = (double) filled * bucketSeconds;
        return ChunkLoadVerdict.of(totalLoaded / seconds, totalGenerated / seconds);
    }

    /** Forgets every count. Used on reload, so stale rates cannot outlive a config change. */
    public void reset() {
        java.util.Arrays.fill(loaded, 0L);
        java.util.Arrays.fill(generated, 0L);
        cursor = 0;
        filled = 0;
        currentLoaded = 0L;
        currentGenerated = 0L;
    }
}
