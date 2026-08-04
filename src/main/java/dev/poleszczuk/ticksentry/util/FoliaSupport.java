package dev.poleszczuk.ticksentry.util;

/**
 * Detects Folia, so the plugin can decline to run there instead of breaking.
 *
 * <p>Folia is not "Paper with more threads". It splits the world into regions that tick
 * independently, each on its own thread. That takes away the two things every part of TickSentry
 * is built on: a single main thread that all Bukkit calls can be made from, and a single tick
 * time that describes the whole server. A chunk scan walking every loaded chunk would be reading
 * regions owned by other threads, and "average MSPT" would not mean anything even if it could be
 * measured.</p>
 *
 * <p>Half-working is the worst outcome here - a monitoring plugin that silently reports numbers
 * it cannot actually measure. So Folia gets an explanation and a clean shutdown, and proper
 * support waits until the scan and the monitor are rewritten around regions.</p>
 */
public final class FoliaSupport {

    /** Present only on Folia; Paper and Spigot do not ship it. */
    private static final String MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    private static Boolean folia;

    private FoliaSupport() {
    }

    /** @return whether the server is running Folia */
    public static boolean isFolia() {
        if (folia == null) {
            try {
                Class.forName(MARKER);
                folia = true;
            } catch (ClassNotFoundException ex) {
                folia = false;
            }
        }
        return folia;
    }

    /** @return why the plugin cannot run on Folia, written for whoever reads the console */
    public static String explanation() {
        return "TickSentry does not support Folia yet, so it is shutting down rather than "
                + "reporting numbers it cannot measure. Folia ticks each region on its own thread, "
                + "which means there is no single tick time to watch and no single thread the "
                + "chunk scan may read from. On Paper and Spigot everything works as documented. "
                + "If you want this on Folia, say so on the issue tracker - it needs the scanner "
                + "and the monitor rebuilt around regions, not a flag.";
    }
}
