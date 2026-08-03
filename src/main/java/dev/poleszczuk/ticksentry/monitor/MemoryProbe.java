package dev.poleszczuk.ticksentry.monitor;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;

/**
 * Reads memory and garbage collector figures from the Java runtime.
 *
 * <p>This closes the biggest gap in the plugin. When no chunk stands out, the freeze usually came
 * from the garbage collector or from the server running out of memory - and neither shows up when
 * you count entities. The numbers come from JMX, which is part of the JDK, so nothing new is
 * pulled in.</p>
 *
 * <p>Everything here is cheap: reading a counter, not walking the heap. Calling it once a second
 * costs nothing measurable.</p>
 */
public final class MemoryProbe {

    private final MemoryMXBean memory;
    private final List<GarbageCollectorMXBean> collectors;

    private long lastGcCount = -1L;
    private long lastGcTimeMs = -1L;

    /** Creates a probe bound to the running JVM. */
    public MemoryProbe() {
        this.memory = ManagementFactory.getMemoryMXBean();
        this.collectors = ManagementFactory.getGarbageCollectorMXBeans();
    }

    /**
     * Takes a reading and works out how much collecting happened since the previous call.
     *
     * @return current memory state, with GC figures counted since the last reading
     */
    public MemorySample sample() {
        long used = memory.getHeapMemoryUsage().getUsed();
        long max = memory.getHeapMemoryUsage().getMax();

        long totalCount = 0L;
        long totalTime = 0L;
        for (GarbageCollectorMXBean collector : collectors) {
            long count = collector.getCollectionCount();
            long time = collector.getCollectionTime();
            if (count > 0L) {
                totalCount += count;
            }
            if (time > 0L) {
                totalTime += time;
            }
        }

        // The first reading has nothing to compare against, so it reports no collections.
        long deltaCount = lastGcCount < 0L ? 0L : Math.max(0L, totalCount - lastGcCount);
        long deltaTime = lastGcTimeMs < 0L ? 0L : Math.max(0L, totalTime - lastGcTimeMs);
        lastGcCount = totalCount;
        lastGcTimeMs = totalTime;

        return new MemorySample(used, max, deltaCount, deltaTime);
    }

    /** One memory reading, plus how much garbage collecting happened since the previous one. */
    public static final class MemorySample {

        private final long usedBytes;
        private final long maxBytes;
        private final long collections;
        private final long collectionMs;

        /**
         * @param usedBytes    heap currently in use
         * @param maxBytes     heap limit, or -1 when the JVM does not report one
         * @param collections  garbage collections since the previous reading
         * @param collectionMs milliseconds spent collecting since the previous reading
         */
        public MemorySample(long usedBytes, long maxBytes, long collections, long collectionMs) {
            this.usedBytes = usedBytes;
            this.maxBytes = maxBytes;
            this.collections = collections;
            this.collectionMs = collectionMs;
        }

        /** @return heap currently in use, in bytes */
        public long usedBytes() {
            return usedBytes;
        }

        /** @return heap limit in bytes, or -1 when unknown */
        public long maxBytes() {
            return maxBytes;
        }

        /** @return garbage collections since the previous reading */
        public long collections() {
            return collections;
        }

        /** @return milliseconds spent collecting since the previous reading */
        public long collectionMs() {
            return collectionMs;
        }

        /** @return how full the heap is, from 0 to 100; -1 when the limit is unknown */
        public int usedPercent() {
            if (maxBytes <= 0L) {
                return -1;
            }
            return (int) Math.round(usedBytes * 100.0D / maxBytes);
        }

        /** @return heap in use, in megabytes */
        public long usedMb() {
            return usedBytes / (1024L * 1024L);
        }

        /** @return heap limit in megabytes, or -1 when unknown */
        public long maxMb() {
            return maxBytes <= 0L ? -1L : maxBytes / (1024L * 1024L);
        }

        /**
         * Describes memory for an admin, for example {@code "2048 MB of 4096 MB (50%)"}.
         *
         * @return readable one-liner
         */
        public String describe() {
            if (maxBytes <= 0L) {
                return usedMb() + " MB in use";
            }
            return usedMb() + " MB of " + maxMb() + " MB (" + usedPercent() + "%)";
        }
    }
}
