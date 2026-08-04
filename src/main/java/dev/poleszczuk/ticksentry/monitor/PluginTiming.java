package dev.poleszczuk.ticksentry.monitor;

import java.util.Locale;

/**
 * How much server time one plugin spent inside its event handlers during a measured window.
 *
 * <p>Only synchronous events are counted, so the numbers can be compared against wall clock
 * time: an asynchronous handler runs on its own thread and never delays a tick.</p>
 */
public final class PluginTiming {

    private final String pluginName;
    private final long totalNanos;
    private final long calls;
    private final String worstEvent;
    private final long worstEventNanos;

    /**
     * @param pluginName      name of the plugin owning the handlers
     * @param totalNanos      total time spent in all of its handlers
     * @param calls           how many times its handlers were invoked
     * @param worstEvent      name of the event that cost the most, or {@code null}
     * @param worstEventNanos time spent in that single event
     */
    public PluginTiming(String pluginName, long totalNanos, long calls,
                        String worstEvent, long worstEventNanos) {
        this.pluginName = pluginName;
        this.totalNanos = totalNanos;
        this.calls = calls;
        this.worstEvent = worstEvent;
        this.worstEventNanos = worstEventNanos;
    }

    /** @return name of the plugin */
    public String pluginName() {
        return pluginName;
    }

    /** @return total nanoseconds spent in this plugin's synchronous handlers */
    public long totalNanos() {
        return totalNanos;
    }

    /** @return number of handler invocations in the window */
    public long calls() {
        return calls;
    }

    /** @return the event that cost the most, or {@code null} when nothing was recorded */
    public String worstEvent() {
        return worstEvent;
    }

    /** @return nanoseconds spent handling {@link #worstEvent()} */
    public long worstEventNanos() {
        return worstEventNanos;
    }

    /** @return total time in milliseconds */
    public double totalMs() {
        return totalNanos / 1_000_000.0D;
    }

    /**
     * Share of the window this plugin was responsible for.
     *
     * @param windowNanos length of the measured window in nanoseconds
     * @return fraction between 0 and 1, or 0 when the window is empty
     */
    public double share(long windowNanos) {
        return windowNanos <= 0L ? 0.0D : (double) totalNanos / (double) windowNanos;
    }

    /**
     * One-line description for a report.
     *
     * @param windowNanos length of the measured window in nanoseconds
     * @return for example {@code "Essentials: 431 ms (43%), worst PlayerMoveEvent, 12403 calls"}
     */
    public String describe(long windowNanos) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%s: %.0f ms (%.0f%%)",
                pluginName, totalMs(), share(windowNanos) * 100.0D));
        if (worstEvent != null) {
            text.append(", worst ").append(worstEvent);
        }
        return text.append(", ").append(calls).append(" calls").toString();
    }

    @Override
    public String toString() {
        return "PluginTiming[" + pluginName + ", " + totalMs() + " ms, " + calls + " calls]";
    }
}
