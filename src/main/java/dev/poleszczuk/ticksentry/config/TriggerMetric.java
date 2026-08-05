package dev.poleszczuk.ticksentry.config;

import java.util.Locale;

/**
 * Which reading of the tick window the alert threshold is compared against.
 *
 * <p>{@link #AVERAGE} is the default and the historical behaviour: it answers "is this server
 * generally behind". It is also deaf to the complaint admins actually receive, because a server
 * freezing for 300 ms twice a second still averages out under the threshold.</p>
 *
 * <p>{@link #P95} answers "how bad are the bad ticks", and catches stutter the average hides. It is
 * opt-in for a plain reason: switching to it on a server that has been quiet for months will start
 * finding things, and that should be an admin's decision rather than a surprise from an update.</p>
 */
public enum TriggerMetric {

    /** Mean tick time over the window. */
    AVERAGE("average"),

    /** 95th percentile - one tick in twenty is at least this slow. */
    P95("p95"),

    /**
     * 99th percentile.
     *
     * <p>On a hundred-tick window this is very nearly the worst single tick of the last five
     * seconds, so it will alert on a server that stalls once every few seconds. That is a real
     * problem worth alerting on, but it is a much sharper instrument than {@link #P95}.</p>
     */
    P99("p99");

    private final String configName;

    TriggerMetric(String configName) {
        this.configName = configName;
    }

    /** @return the value as written in {@code config.yml} */
    public String configName() {
        return configName;
    }

    /**
     * Reads the setting, falling back rather than failing.
     *
     * <p>A typo here would otherwise decide whether the server is monitored at all, so an
     * unrecognised value keeps the default and the caller says something about it.</p>
     *
     * @param raw value from the configuration, may be {@code null}
     * @return the matching metric, or {@code null} when the value is not one of them
     */
    public static TriggerMetric parse(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        for (TriggerMetric metric : values()) {
            if (metric.configName.equals(cleaned)) {
                return metric;
            }
        }
        return null;
    }
}
