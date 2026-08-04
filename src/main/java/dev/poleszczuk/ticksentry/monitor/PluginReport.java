package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.Messages;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Ranking of plugins by the time their synchronous event handlers took in a window.
 *
 * <p>The class is pure - no Bukkit, no state beyond the numbers it is handed - so the
 * thresholds that decide "this plugin is the problem" can be unit tested. Measuring itself
 * happens in {@link PluginProfiler}.</p>
 *
 * <p>Shares are relative to wall clock time, not to the total measured time. A plugin at 40%
 * really did hold the server thread for 40% of the window; the remaining 60% went to vanilla
 * ticking, other plugins and idle time between ticks.</p>
 */
public final class PluginReport {

    /** Share of the window from which a plugin is worth naming in an alert. */
    public static final double SIGNIFICANT_SHARE = 0.25D;

    /** Share from which a plugin outranks whatever the chunk scan found. */
    public static final double DOMINANT_SHARE = 0.50D;

    private static final PluginReport EMPTY = new PluginReport(0L, Collections.emptyList());

    private final long windowNanos;
    private final List<PluginTiming> timings;

    private PluginReport(long windowNanos, List<PluginTiming> timings) {
        this.windowNanos = windowNanos;
        this.timings = Collections.unmodifiableList(timings);
    }

    /** @return a report holding no measurements at all */
    public static PluginReport empty() {
        return EMPTY;
    }

    /**
     * Builds a report, sorting the entries from most to least expensive.
     *
     * @param windowNanos length of the measured window in nanoseconds
     * @param raw         per-plugin measurements in any order
     * @return sorted report, or {@link #empty()} when there is nothing to show
     */
    public static PluginReport of(long windowNanos, Collection<PluginTiming> raw) {
        if (windowNanos <= 0L || raw == null || raw.isEmpty()) {
            return EMPTY;
        }
        List<PluginTiming> sorted = new ArrayList<>(raw);
        // Ties break on the plugin name, which keeps reports stable between runs.
        sorted.sort(Comparator.comparingLong(PluginTiming::totalNanos).reversed()
                .thenComparing(PluginTiming::pluginName));
        return new PluginReport(windowNanos, sorted);
    }

    /** @return length of the measured window in nanoseconds */
    public long windowNanos() {
        return windowNanos;
    }

    /** @return length of the measured window in seconds */
    public double windowSeconds() {
        return windowNanos / 1_000_000_000.0D;
    }

    /** @return every measured plugin, most expensive first */
    public List<PluginTiming> timings() {
        return timings;
    }

    /** @return {@code true} when nothing was measured */
    public boolean isEmpty() {
        return timings.isEmpty();
    }

    /**
     * @param limit maximum number of entries
     * @return the most expensive plugins, at most {@code limit} of them
     */
    public List<PluginTiming> top(int limit) {
        if (limit <= 0 || timings.isEmpty()) {
            return Collections.emptyList();
        }
        return timings.subList(0, Math.min(limit, timings.size()));
    }

    /** @return the most expensive plugin, or {@code null} when nothing was measured */
    public PluginTiming worst() {
        return timings.isEmpty() ? null : timings.get(0);
    }

    /** @return share of the window taken by the most expensive plugin (0 when empty) */
    public double worstShare() {
        PluginTiming worst = worst();
        return worst == null ? 0.0D : worst.share(windowNanos);
    }

    /** @return whether one plugin took enough of the window to be worth mentioning */
    public boolean explainsLag() {
        return worstShare() >= SIGNIFICANT_SHARE;
    }

    /** @return whether one plugin took so much of the window that it outranks the chunk scan */
    public boolean dominatesLag() {
        return worstShare() >= DOMINANT_SHARE;
    }

    /**
     * Sentence describing the worst plugin, in the same style as the memory verdict.
     *
     * @return message for the admin, or {@code null} when no plugin stands out
     */
    public String message() {
        return message(Messages.none());
    }

    /**
     * Sentence describing the worst plugin, translated where one is configured.
     *
     * @param messages translation lookup; {@link Messages#none()} keeps it English
     * @return message for the admin, or {@code null} when no plugin stands out
     */
    public String message(Messages messages) {
        PluginTiming worst = worst();
        if (worst == null || !explainsLag()) {
            return null;
        }
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "%s used %.0f%% of the last %.0f s of server time (%.0f ms",
                worst.pluginName(), worst.share(windowNanos) * 100.0D, windowSeconds(), worst.totalMs()));
        if (worst.worstEvent() != null) {
            text.append(String.format(Locale.ROOT, ", mostly in %s", worst.worstEvent()));
        }
        text.append(", ").append(worst.calls()).append(" handler calls).");

        String translated = messages == null ? null : messages.find("plugin-report.message",
                "plugin", worst.pluginName(),
                "share", String.format(Locale.ROOT, "%.0f", worst.share(windowNanos) * 100.0D),
                "seconds", String.format(Locale.ROOT, "%.0f", windowSeconds()),
                "ms", String.format(Locale.ROOT, "%.0f", worst.totalMs()),
                "event", worst.worstEvent() == null ? "?" : worst.worstEvent(),
                "calls", String.valueOf(worst.calls()));
        return translated == null ? text.toString() : translated;
    }

    /**
     * What the admin should do about the worst plugin.
     *
     * @return suggested action, or {@code null} when no plugin stands out
     */
    public String suggestion() {
        return suggestion(Messages.none());
    }

    /**
     * What the admin should do about the worst plugin, translated where one is configured.
     *
     * @param messages translation lookup; {@link Messages#none()} keeps it English
     * @return suggested action, or {@code null} when no plugin stands out
     */
    public String suggestion(Messages messages) {
        PluginTiming worst = worst();
        if (worst == null || !explainsLag()) {
            return null;
        }
        String translated = messages == null ? null
                : messages.find("plugin-report.suggestion", "plugin", worst.pluginName());
        return translated != null ? translated
                : "Look at " + worst.pluginName() + " first: update it, check its settings, or disable it "
                  + "for a moment to confirm. Counting mobs will not help here.";
    }
}
