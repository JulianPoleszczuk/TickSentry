package dev.poleszczuk.ticksentry.alert;

import dev.poleszczuk.ticksentry.monitor.LagEvent;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when TickSentry reports an incident, so other plugins can react.
 *
 * <p>The cheapest integration point there is. A server with its own staff-alert plugin, a queue
 * system that should stop admitting players while the server struggles, or a bossbar that warns
 * everybody - none of those need anything added here, they just listen:</p>
 *
 * <pre>
 * &#64;EventHandler
 * public void onLag(TickSentryIncidentEvent event) {
 *     LagEvent incident = event.getIncident();
 *     getLogger().info("Lag: " + incident.category().title());
 * }
 * </pre>
 *
 * <p>Not cancellable. It is a notification about something that has already been measured and
 * already been reported - there is nothing left to call off.</p>
 *
 * <p>Fired on the server thread, so a listener that does anything slow will itself become a cause of
 * lag. That is the listener's problem, but it is worth knowing before writing one.</p>
 */
public class TickSentryIncidentEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LagEvent incident;

    /**
     * @param incident the incident that was reported
     */
    public TickSentryIncidentEvent(LagEvent incident) {
        this.incident = incident;
    }

    /**
     * @return everything the plugin measured and concluded - the cause, the readings, the suspicious
     *         chunks, and the advice it gave the admin
     */
    public LagEvent getIncident() {
        return incident;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * @return the handler list, as Bukkit requires of every event class
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
