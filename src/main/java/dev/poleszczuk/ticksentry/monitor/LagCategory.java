package dev.poleszczuk.ticksentry.monitor;

/**
 * Likely cause of lag attributed to a specific chunk.
 * Wording is deliberately aimed at server admins, not at profiler users.
 */
public enum LagCategory {

    /** Many entities of a single type - a mob farm or a spawner nobody cleaned up. */
    MOB_FARM("Mob farm", "Lots of entities of the same type in one place"),

    /** Dropped items and experience orbs piling up. */
    ITEM_CLUTTER("Dropped items", "Hundreds of items or XP orbs lying on the ground"),

    /** Many block entities, usually hoppers, droppers and sorting systems. */
    REDSTONE("Redstone / hoppers", "Lots of hoppers, droppers or furnaces"),

    /** A crowd of players, for example spawn or an arena fight. */
    PLAYER_CLUSTER("Player crowd", "Many players inside a single chunk"),

    /** Many entities, but without one clearly dominant type. */
    ENTITY_OVERLOAD("Entity overload", "A large mix of different entities at once"),

    /** No obvious culprit could be identified. */
    UNKNOWN("No obvious source", "No chunk stands out clearly");

    private final String title;
    private final String description;

    LagCategory(String title, String description) {
        this.title = title;
        this.description = description;
    }

    /** @return short display name of the category */
    public String title() {
        return title;
    }

    /** @return one-sentence explanation for the admin */
    public String description() {
        return description;
    }
}
