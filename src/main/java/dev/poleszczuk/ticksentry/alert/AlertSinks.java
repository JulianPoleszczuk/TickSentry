package dev.poleszczuk.ticksentry.alert;

import dev.poleszczuk.ticksentry.monitor.LagEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Every destination an alert goes to, behind one call.
 *
 * <p>The reason this exists rather than a loop at the call site: one broken destination must not stop
 * the others. A webhook pointed at a host that no longer resolves would otherwise take the in-game
 * warning down with it, and the whole point of having several destinations is that one of them
 * survives whatever went wrong.</p>
 */
public final class AlertSinks {

    private final Plugin plugin;
    private final List<AlertSink> sinks = new ArrayList<>();

    /**
     * @param plugin plugin instance, for logging a sink that throws
     */
    public AlertSinks(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Adds a destination.
     *
     * @param sink where alerts should also go
     */
    public void add(AlertSink sink) {
        sinks.add(sink);
    }

    /**
     * @return the names of every configured destination, for {@code /lagwatch status};
     *         empty when nothing is set up
     */
    public List<String> configuredNames() {
        return sinks.stream()
                .filter(AlertSink::isConfigured)
                .map(AlertSink::name)
                .collect(Collectors.toList());
    }

    /**
     * Sends an incident everywhere.
     *
     * @param event what was detected
     */
    public void incident(LagEvent event) {
        each("incident", sink -> sink.incident(event));
    }

    /**
     * Sends a recovery notice everywhere.
     *
     * @param durationSeconds how long the incident lasted
     * @param tps             TPS after recovery
     * @param mspt            tick time after recovery
     */
    public void recovery(long durationSeconds, double tps, double mspt) {
        each("recovery", sink -> sink.recovery(durationSeconds, tps, mspt));
    }

    /**
     * Sends a clean-up summary everywhere.
     *
     * @param summary what happened, or would have happened
     */
    public void remediation(String summary) {
        each("clean-up", sink -> sink.remediation(summary));
    }

    /** Shuts every destination down, in the order they were added. */
    public void shutdown() {
        each("shutdown", AlertSink::shutdown);
    }

    /**
     * Runs an action against every configured sink, surviving any one of them failing.
     *
     * @param what   what was being sent, for the log line
     * @param action what to do with each sink
     */
    private void each(String what, java.util.function.Consumer<AlertSink> action) {
        for (AlertSink sink : sinks) {
            if (!sink.isConfigured()) {
                continue;
            }
            try {
                action.accept(sink);
            } catch (RuntimeException | LinkageError ex) {
                // One bad destination must not take the others with it - that is the entire reason
                // for having more than one.
                plugin.getLogger().log(Level.WARNING,
                        "Could not send the " + what + " to " + sink.name(), ex);
            }
        }
    }
}
