package dev.poleszczuk.ticksentry.util;

/**
 * Detects Folia, so the plugin can run the part of itself that still means something there.
 *
 * <p>Folia is not "Paper with more threads". It splits the world into regions that tick
 * independently, each on its own thread. That takes away the two things the chunk scan is built on:
 * a single main thread every Bukkit call can be made from, and a single tick time that describes the
 * whole server. Walking every loaded chunk would mean reading regions owned by other threads, which
 * is not a slow way of doing it - it is the wrong way.</p>
 *
 * <p>So the scan stays off. But the scan is not the whole plugin, and the plugin used to shut down
 * over it, which meant a Folia server got nothing at all: no memory watching, no per-plugin handler
 * timings, no history, no panel, no alerts. None of those needed a single main thread. They run now,
 * and the console says plainly which half is missing.</p>
 *
 * <p>Half-working is still the worst outcome, so nothing here is assumed from the fact that this is
 * Folia. Whether the server will give a tick time is discovered by asking it: a fork that answers
 * gets monitored, and one that refuses is told so rather than handed a comforting number nobody
 * measured.</p>
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

    /**
     * @return what an admin on Folia needs to know, written for whoever reads the console
     *
     * <p>Deliberately specific about what is gone. "Limited support" tells nobody anything; someone
     * who reads this should be able to predict what their alerts will and will not say.</p>
     */
    public static String limitedModeExplanation() {
        return "Folia detected - running in limited mode. Working: memory and garbage collector "
                + "watching, per-plugin event handler timings, incident history, the web panel, "
                + "Discord and in-game alerts. Not working: the chunk scan, so an alert can name a "
                + "plugin or memory but never a chunk, a farm or an owner. Automatic clean-up is off "
                + "too - it would have to remove entities from regions owned by other threads. Both "
                + "need one thread that may read the whole world, and Folia has none. On Paper and "
                + "Spigot everything works as documented.";
    }

    /**
     * @return what to say when the server will not report a tick time at all
     *
     * <p>A separate failure from {@link #limitedModeExplanation()}: a Folia server may still give
     * usable readings, and some other fork may not.</p>
     */
    public static String noReadingsExplanation() {
        return "This server does not report a tick time, so lag detection is off - there is nothing "
                + "to compare a threshold against. Memory watching, plugin timings and the history "
                + "still work, and /lagwatch report will still say what it can.";
    }
}
