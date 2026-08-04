package dev.poleszczuk.ticksentry.remedy;

import java.util.Locale;

/**
 * One thing the automatic clean-up intends to do to one chunk.
 *
 * <p>Deciding and doing are kept apart on purpose. An action is a plain description with no
 * Bukkit in it, which makes the decision testable, lets dry-run print exactly what would have
 * happened, and gives the executor something to re-check against the world before it removes
 * anything.</p>
 */
public final class RemedyAction {

    /** What kind of clean-up this is. */
    public enum Kind {

        /** Sweep dropped items and experience orbs off the ground. */
        CLEAR_ITEMS,

        /** Thin out a pile-up of one mob type, leaving some behind. */
        CAP_MOBS
    }

    private final Kind kind;
    private final String worldName;
    private final int chunkX;
    private final int chunkZ;
    private final String entityType;
    private final int present;
    private final int toRemove;

    /**
     * @param kind       what kind of clean-up
     * @param worldName  world holding the chunk
     * @param chunkX     chunk X coordinate
     * @param chunkZ     chunk Z coordinate
     * @param entityType entity type to remove, or {@code null} for every kind of litter
     * @param present    how many were counted when the plan was made
     * @param toRemove   how many to remove at most
     */
    public RemedyAction(Kind kind, String worldName, int chunkX, int chunkZ, String entityType,
                        int present, int toRemove) {
        this.kind = kind;
        this.worldName = worldName;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.entityType = entityType;
        this.present = present;
        this.toRemove = toRemove;
    }

    /** @return what kind of clean-up */
    public Kind kind() {
        return kind;
    }

    /** @return world holding the chunk */
    public String worldName() {
        return worldName;
    }

    /** @return chunk X coordinate */
    public int chunkX() {
        return chunkX;
    }

    /** @return chunk Z coordinate */
    public int chunkZ() {
        return chunkZ;
    }

    /** @return entity type to remove, or {@code null} when every kind of litter counts */
    public String entityType() {
        return entityType;
    }

    /** @return how many were counted when the plan was made */
    public int present() {
        return present;
    }

    /** @return how many to remove at most */
    public int toRemove() {
        return toRemove;
    }

    /** @return block X coordinate of the chunk centre */
    public int blockX() {
        return chunkX * 16 + 8;
    }

    /** @return block Z coordinate of the chunk centre */
    public int blockZ() {
        return chunkZ * 16 + 8;
    }

    /** @return readable location, for example {@code world @ 120, 344} */
    public String prettyLocation() {
        return worldName + " @ " + blockX() + ", " + blockZ();
    }

    /**
     * @return a sentence an admin can read in the log or in chat, for example
     *         {@code "clear 812 dropped items at world @ 1608, 1608"}
     */
    public String describe() {
        if (kind == Kind.CLEAR_ITEMS) {
            return "clear " + toRemove + " dropped items at " + prettyLocation();
        }
        return "remove " + toRemove + " of " + present + " "
                + friendly(entityType) + " at " + prettyLocation();
    }

    /** @return what players are told before it happens, phrased for people, not admins */
    public String announcement() {
        if (kind == Kind.CLEAR_ITEMS) {
            return "Dropped items at " + prettyLocation() + " are about to be cleared";
        }
        return "Some of the " + friendly(entityType) + " at " + prettyLocation()
                + " are about to be removed";
    }

    private static String friendly(String type) {
        return type == null ? "entities" : type.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    @Override
    public String toString() {
        return "RemedyAction[" + kind + ", " + describe() + "]";
    }
}
