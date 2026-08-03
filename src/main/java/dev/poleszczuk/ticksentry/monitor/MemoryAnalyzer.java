package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.monitor.MemoryProbe.MemorySample;

/**
 * Decides whether memory or the garbage collector explains a slowdown.
 *
 * <p>Pure logic with no Bukkit and no state, so unit tests cover it.</p>
 *
 * <p>The reasoning is simple on purpose. If the garbage collector ate a noticeable share of the
 * last few seconds, it stopped the server - that is what a GC pause does, and no amount of
 * counting cows would ever have shown it. If the heap is nearly full, collections get more
 * frequent and longer, so the server is heading for trouble even when it is coping right now.</p>
 */
public final class MemoryAnalyzer {

    /** Above this share of wall clock spent collecting, the GC is the story. */
    private static final double GC_SHARE_SERIOUS = 0.20D;

    /** A smaller share still worth mentioning next to another cause. */
    private static final double GC_SHARE_NOTABLE = 0.08D;

    /** Heap fullness at which collections start hurting, in percent. */
    private static final int HEAP_HIGH_PERCENT = 90;

    private MemoryAnalyzer() {
    }

    /**
     * Looks at a reading and works out what, if anything, to tell the admin.
     *
     * @param sample   memory reading covering the window
     * @param windowMs length of the window the reading covers, in milliseconds
     * @return a verdict; never {@code null}
     */
    public static Verdict diagnose(MemorySample sample, long windowMs) {
        double gcShare = windowMs <= 0L ? 0.0D : (double) sample.collectionMs() / windowMs;
        int heapPercent = sample.usedPercent();
        int gcPercent = (int) Math.round(gcShare * 100.0D);

        boolean heapHigh = heapPercent >= HEAP_HIGH_PERCENT;

        if (gcShare >= GC_SHARE_SERIOUS) {
            String message = "The garbage collector used " + gcPercent + "% of the last "
                    + Math.round(windowMs / 1000.0D) + " s (" + sample.collections()
                    + " collections). The server was frozen for that time. Memory: " + sample.describe() + ".";
            String advice = heapHigh
                    ? "The heap is nearly full, so give the server more RAM (-Xmx) or find what is filling it."
                    : "Check the startup flags - a badly tuned heap makes the collector run far too often.";
            return new Verdict(true, message + " " + advice);
        }

        if (heapHigh) {
            String message = "Memory is nearly full: " + sample.describe()
                    + ". The garbage collector will run more and more often, which shows up as freezes."
                    + " Consider giving the server more RAM (-Xmx).";
            // A full heap explains a slowdown on its own only once the collector is actually busy.
            return new Verdict(gcShare >= GC_SHARE_NOTABLE, message);
        }

        if (gcShare >= GC_SHARE_NOTABLE) {
            return new Verdict(false, "The garbage collector took " + gcPercent + "% of the last "
                    + Math.round(windowMs / 1000.0D) + " s, which adds to the delay. Memory: "
                    + sample.describe() + ".");
        }

        return new Verdict(false, null);
    }

    /** What the memory figures say about a slowdown. */
    public static final class Verdict {

        private final boolean explainsLag;
        private final String message;

        /**
         * @param explainsLag whether memory alone accounts for the slowdown
         * @param message     text for the admin, or {@code null} when there is nothing to say
         */
        public Verdict(boolean explainsLag, String message) {
            this.explainsLag = explainsLag;
            this.message = message;
        }

        /** @return {@code true} when memory or the collector is the main cause */
        public boolean explainsLag() {
            return explainsLag;
        }

        /** @return text for the admin, or {@code null} when memory looks fine */
        public String message() {
            return message;
        }

        /** @return {@code true} when there is anything worth showing */
        public boolean hasMessage() {
            return message != null;
        }
    }
}
