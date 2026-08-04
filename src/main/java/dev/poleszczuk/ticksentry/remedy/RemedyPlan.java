package dev.poleszczuk.ticksentry.remedy;

import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.HotspotAnalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Decides what, if anything, the automatic clean-up should do about a set of suspicious chunks.
 *
 * <p>Pure - no Bukkit, no state - so every rule about what may and may not be removed is covered
 * by unit tests. That matters more here than anywhere else in the plugin: this is the one part
 * that destroys things players own, and a mistake in it is not something a restart undoes.</p>
 *
 * <p>The rules are conservative by design. Litter on the ground is swept whole; a mob pile-up is
 * only thinned down to a floor, never emptied; and a type on the protected list is left alone no
 * matter how many of it there are.</p>
 */
public final class RemedyPlan {

    private RemedyPlan() {
    }

    /**
     * Works out what to do about the chunks a scan flagged.
     *
     * @param chunks   suspicious chunks, in any order
     * @param settings what the admin has allowed
     * @return actions to carry out, empty when nothing qualifies
     */
    public static List<RemedyAction> decide(List<ChunkStat> chunks, RemedySettings settings) {
        if (chunks == null || chunks.isEmpty() || settings == null || !settings.enabled()) {
            return Collections.emptyList();
        }
        List<RemedyAction> actions = new ArrayList<>();
        for (ChunkStat chunk : chunks) {
            actions.addAll(decide(chunk, settings));
        }
        return actions;
    }

    /**
     * Works out what to do about a single chunk.
     *
     * @param chunk    chunk snapshot
     * @param settings what the admin has allowed
     * @return actions for this chunk, empty when nothing qualifies
     */
    public static List<RemedyAction> decide(ChunkStat chunk, RemedySettings settings) {
        if (chunk == null || settings == null || !settings.enabled()) {
            return Collections.emptyList();
        }
        List<RemedyAction> actions = new ArrayList<>(2);

        if (settings.clearItems()) {
            int litter = countLitter(chunk);
            if (litter >= settings.itemThreshold()) {
                actions.add(new RemedyAction(RemedyAction.Kind.CLEAR_ITEMS, chunk.worldName(),
                        chunk.chunkX(), chunk.chunkZ(), null, litter, litter));
            }
        }

        if (settings.capMobs()) {
            Map.Entry<String, Integer> worst = worstMob(chunk, settings);
            if (worst != null) {
                int excess = worst.getValue() - settings.mobKeep();
                if (excess > 0) {
                    actions.add(new RemedyAction(RemedyAction.Kind.CAP_MOBS, chunk.worldName(),
                            chunk.chunkX(), chunk.chunkZ(), worst.getKey(), worst.getValue(), excess));
                }
            }
        }

        return actions;
    }

    /** @return dropped items and experience orbs in the chunk */
    private static int countLitter(ChunkStat chunk) {
        int litter = 0;
        for (Map.Entry<String, Integer> entry : chunk.entityTypeCounts().entrySet()) {
            if (HotspotAnalyzer.isItemLike(entry.getKey())) {
                litter += entry.getValue();
            }
        }
        return litter;
    }

    /**
     * Finds the mob type worth thinning out.
     *
     * <p>Players, litter and anything on the protected list are skipped before the comparison,
     * so a chunk whose most common entity is a villager still gets its zombies capped rather
     * than being left alone entirely.</p>
     */
    private static Map.Entry<String, Integer> worstMob(ChunkStat chunk, RemedySettings settings) {
        Map.Entry<String, Integer> worst = null;
        for (Map.Entry<String, Integer> entry : chunk.entityTypeCounts().entrySet()) {
            String type = entry.getKey();
            if (HotspotAnalyzer.isItemLike(type) || "PLAYER".equals(type) || settings.isProtected(type)) {
                continue;
            }
            if (entry.getValue() < settings.mobThreshold()) {
                continue;
            }
            // Ties break on the name so the same chunk always produces the same plan.
            if (worst == null || entry.getValue() > worst.getValue()
                    || (entry.getValue().equals(worst.getValue()) && type.compareTo(worst.getKey()) < 0)) {
                worst = entry;
            }
        }
        return worst;
    }
}
