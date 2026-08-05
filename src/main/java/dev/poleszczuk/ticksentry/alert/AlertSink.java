package dev.poleszczuk.ticksentry.alert;

import dev.poleszczuk.ticksentry.monitor.LagEvent;

/**
 * Somewhere an alert can be sent.
 *
 * <p>Discord was the only destination, and it was wired straight into the plugin, so "I want this in
 * Slack" or "I want this to run a command" meant editing the plugin. There is nothing
 * Discord-specific about the decision to alert - only about the formatting - so the decision now
 * happens once and every destination is one of these.</p>
 *
 * <p><b>Threading:</b> called on the main server thread with a finished, immutable {@link LagEvent}.
 * Anything slow - a network request, a disk write - must be handed to another thread by the
 * implementation, because this is called from the same tick that noticed the lag.</p>
 */
public interface AlertSink {

    /**
     * Reports a detected incident.
     *
     * @param event what was detected
     */
    void incident(LagEvent event);

    /**
     * Reports that the server is healthy again.
     *
     * @param durationSeconds how long the incident lasted
     * @param tps             TPS after recovery
     * @param mspt            tick time after recovery
     */
    void recovery(long durationSeconds, double tps, double mspt);

    /**
     * Reports what the automatic clean-up did, or would have done in dry-run.
     *
     * @param summary multi-line description of the actions
     */
    void remediation(String summary);

    /**
     * @return whether this destination is switched on and has everything it needs.
     *         A sink that is not configured is skipped rather than failing once per incident.
     */
    boolean isConfigured();

    /** @return short name for {@code /lagwatch status}, for example {@code "Discord"} */
    String name();

    /** Releases whatever the sink holds - threads, connections. Called on plugin disable. */
    default void shutdown() {
    }
}
