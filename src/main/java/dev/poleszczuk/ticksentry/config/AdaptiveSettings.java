package dev.poleszczuk.ticksentry.config;

/**
 * Settings for the threshold that learns what "normal" means on this particular server.
 *
 * <p>Immutable and free of Bukkit, so the maths in
 * {@link dev.poleszczuk.ticksentry.monitor.AdaptiveThreshold} can be tested against it
 * directly.</p>
 */
public final class AdaptiveSettings {

    private final boolean enabled;
    private final double multiplier;
    private final double minimumMs;
    private final double maximumMs;
    private final int baselineMinutes;

    /**
     * @param enabled         whether the threshold should adapt at all
     * @param multiplier      how many times its own typical tick time a server may reach
     * @param minimumMs       the threshold never drops below this
     * @param maximumMs       the threshold never rises above this
     * @param baselineMinutes how much history the baseline is built from
     */
    public AdaptiveSettings(boolean enabled, double multiplier, double minimumMs, double maximumMs,
                            int baselineMinutes) {
        this.enabled = enabled;
        // Below 1.0 the threshold would sit under the server's own normal tick time and alert
        // forever; above 10 it would never alert at all.
        this.multiplier = Math.min(10.0D, Math.max(1.0D, multiplier));
        this.minimumMs = Math.max(1.0D, minimumMs);
        this.maximumMs = Math.max(this.minimumMs, maximumMs);
        this.baselineMinutes = Math.min(24 * 60, Math.max(1, baselineMinutes));
    }

    /** @return settings that leave the fixed threshold alone */
    public static AdaptiveSettings disabled() {
        return new AdaptiveSettings(false, 2.0D, 25.0D, 100.0D, 60);
    }

    /** @return whether the threshold should adapt at all */
    public boolean enabled() {
        return enabled;
    }

    /** @return how many times its own typical tick time a server may reach before alerting */
    public double multiplier() {
        return multiplier;
    }

    /** @return the threshold never drops below this */
    public double minimumMs() {
        return minimumMs;
    }

    /** @return the threshold never rises above this */
    public double maximumMs() {
        return maximumMs;
    }

    /** @return how much history the baseline is built from */
    public int baselineMinutes() {
        return baselineMinutes;
    }
}
