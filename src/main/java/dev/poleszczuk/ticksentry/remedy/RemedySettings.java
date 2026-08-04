package dev.poleszczuk.ticksentry.remedy;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything the automatic clean-up is allowed to do, read from {@code config.yml}.
 *
 * <p>Kept as its own immutable object rather than another dozen fields on the config manager,
 * and passed to the pure decision logic so that {@link RemedyPlan} can be tested without a
 * server or a configuration file.</p>
 *
 * <p>The defaults are deliberately timid: disabled, and in dry-run when enabled. Removing things
 * players built or dropped is not a decision a monitoring plugin gets to make on its own.</p>
 */
public final class RemedySettings {

    private final boolean enabled;
    private final boolean dryRun;
    private final int warningSeconds;
    private final int cooldownSeconds;
    private final boolean clearItems;
    private final int itemThreshold;
    private final boolean capMobs;
    private final int mobThreshold;
    private final int mobKeep;
    private final Set<String> protectedTypes;

    /**
     * @param enabled         whether the plugin may change anything at all
     * @param dryRun          report what would happen instead of doing it
     * @param warningSeconds  how long players are warned before anything is removed
     * @param cooldownSeconds minimum gap between two clean-ups
     * @param clearItems      whether dropped items may be swept
     * @param itemThreshold   items in one chunk from which sweeping is allowed
     * @param capMobs         whether a mob pile-up may be thinned out
     * @param mobThreshold    mobs of one type in one chunk from which thinning is allowed
     * @param mobKeep         how many of that type to leave behind
     * @param protectedTypes  entity types never to remove, whatever the count
     */
    public RemedySettings(boolean enabled, boolean dryRun, int warningSeconds, int cooldownSeconds,
                          boolean clearItems, int itemThreshold, boolean capMobs, int mobThreshold,
                          int mobKeep, List<String> protectedTypes) {
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.warningSeconds = Math.max(0, warningSeconds);
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.clearItems = clearItems;
        this.itemThreshold = Math.max(1, itemThreshold);
        this.capMobs = capMobs;
        this.mobThreshold = Math.max(1, mobThreshold);
        this.mobKeep = Math.max(0, mobKeep);

        Set<String> upper = new HashSet<>();
        if (protectedTypes != null) {
            for (String type : protectedTypes) {
                upper.add(type.toUpperCase(Locale.ROOT).trim());
            }
        }
        this.protectedTypes = Collections.unmodifiableSet(upper);
    }

    /** @return settings that permit nothing - what the plugin uses until an admin says otherwise */
    public static RemedySettings disabled() {
        return new RemedySettings(false, true, 30, 600, false, 300, false, 300, 50, List.of());
    }

    /** @return whether the plugin may change anything at all */
    public boolean enabled() {
        return enabled;
    }

    /** @return whether to report what would happen instead of doing it */
    public boolean dryRun() {
        return dryRun;
    }

    /** @return how long players are warned before anything is removed */
    public int warningSeconds() {
        return warningSeconds;
    }

    /** @return minimum gap between two clean-ups */
    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    /** @return whether dropped items may be swept */
    public boolean clearItems() {
        return clearItems;
    }

    /** @return items in one chunk from which sweeping is allowed */
    public int itemThreshold() {
        return itemThreshold;
    }

    /** @return whether a mob pile-up may be thinned out */
    public boolean capMobs() {
        return capMobs;
    }

    /** @return mobs of one type in one chunk from which thinning is allowed */
    public int mobThreshold() {
        return mobThreshold;
    }

    /** @return how many mobs of the offending type to leave behind */
    public int mobKeep() {
        return mobKeep;
    }

    /**
     * @param entityType entity type name
     * @return whether that type is on the never-touch list
     */
    public boolean isProtected(String entityType) {
        return protectedTypes.contains(entityType.toUpperCase(Locale.ROOT));
    }

    /** @return the never-touch list, as an unmodifiable set of upper-case type names */
    public Set<String> protectedTypes() {
        return protectedTypes;
    }
}
