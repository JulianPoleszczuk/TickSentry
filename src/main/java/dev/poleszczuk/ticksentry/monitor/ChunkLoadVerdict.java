package dev.poleszczuk.ticksentry.monitor;

import java.util.Locale;

/**
 * What the rate of chunks coming into memory says about a slowdown.
 *
 * <p>This covers a cause the chunk scan is blind to by construction. Counting what sits in a
 * chunk cannot see the work of <em>making</em> chunks: a player crossing unexplored terrain in an
 * elytra, a nether portal dropping someone into fresh ground, a pregenerator left running. The
 * server is busy, no single chunk stands out, and the old answer was "no obvious source".</p>
 *
 * <p>Pure logic with no Bukkit, so the thresholds are unit tested. The measuring happens in
 * {@link ChunkLoadRate}.</p>
 */
public final class ChunkLoadVerdict {

    /** New chunks generated per second from which terrain generation is the story. */
    public static final double GENERATION_HEAVY = 3.0D;

    /** New chunks per second worth mentioning next to another cause. */
    private static final double GENERATION_NOTABLE = 1.0D;

    /** Chunks read from disk per second from which streaming is the story. */
    public static final double LOADING_HEAVY = 40.0D;

    /** Chunks read from disk per second worth mentioning next to another cause. */
    private static final double LOADING_NOTABLE = 15.0D;

    private static final ChunkLoadVerdict QUIET = new ChunkLoadVerdict(0.0D, 0.0D);

    private final double loadedPerSecond;
    private final double generatedPerSecond;

    private ChunkLoadVerdict(double loadedPerSecond, double generatedPerSecond) {
        this.loadedPerSecond = loadedPerSecond;
        this.generatedPerSecond = generatedPerSecond;
    }

    /** @return a verdict for a server that is loading nothing */
    public static ChunkLoadVerdict quiet() {
        return QUIET;
    }

    /**
     * @param loadedPerSecond    chunks read from disk per second, generated ones included
     * @param generatedPerSecond chunks generated from scratch per second
     * @return the verdict on those rates
     */
    public static ChunkLoadVerdict of(double loadedPerSecond, double generatedPerSecond) {
        if (loadedPerSecond <= 0.0D && generatedPerSecond <= 0.0D) {
            return QUIET;
        }
        return new ChunkLoadVerdict(Math.max(0.0D, loadedPerSecond), Math.max(0.0D, generatedPerSecond));
    }

    /** @return chunks read from disk per second */
    public double loadedPerSecond() {
        return loadedPerSecond;
    }

    /** @return chunks generated from scratch per second */
    public double generatedPerSecond() {
        return generatedPerSecond;
    }

    /** @return whether chunk loading alone accounts for the slowdown */
    public boolean explainsLag() {
        return generatedPerSecond >= GENERATION_HEAVY || loadedPerSecond >= LOADING_HEAVY;
    }

    /** @return whether there is anything worth showing next to another cause */
    public boolean hasMessage() {
        return generatedPerSecond >= GENERATION_NOTABLE || loadedPerSecond >= LOADING_NOTABLE;
    }

    /**
     * @return one line for the admin, or {@code null} when the numbers are unremarkable
     */
    public String message() {
        if (!hasMessage()) {
            return null;
        }
        return String.format(Locale.ROOT,
                "The server is loading %.0f chunks a second, %.1f of them generated from scratch.",
                loadedPerSecond, generatedPerSecond);
    }

    /**
     * @return what to do about it, or {@code null} when the numbers are unremarkable
     */
    public String suggestion() {
        if (!hasMessage()) {
            return null;
        }
        if (generatedPerSecond >= GENERATION_NOTABLE) {
            return "New land is being generated - somebody is exploring, riding an elytra, or a "
                    + "pregenerator is running. This kind of lag follows the player around and "
                    + "stops when they do. Pregenerating the world in advance, or lowering the "
                    + "view distance, is what actually fixes it.";
        }
        return "Chunks are being read from disk fast enough to hurt. That is usually someone "
                + "moving quickly across explored ground; a lower view distance, or a faster disk, "
                + "is what helps.";
    }
}
