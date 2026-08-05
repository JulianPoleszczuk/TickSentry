package dev.poleszczuk.ticksentry.config;

/**
 * The handful of settings the tick monitor reads, without the server they normally come from.
 *
 * <p>Same reasoning as {@link Messages}: {@code TickMonitor} owns the detection logic - how long a
 * breach has to last, when the cooldown lets the next alert through, when an incident counts as
 * over - and that logic is worth testing. Taking a {@link ConfigManager} would mean needing a
 * running server to build one, so the monitor takes this instead and {@code ConfigManager}
 * implements it.</p>
 */
public interface MonitorSettings {

    /** @return tick time in milliseconds above which the server counts as overloaded */
    double msptThresholdMs();

    /** @return seconds of uninterrupted breach required before an alert fires */
    int sustainedSeconds();

    /** @return minimum gap between alerts, in seconds */
    int scanCooldownSeconds();

    /** @return how many seconds below the threshold end an incident */
    int recoverySeconds();

    /** @return size of the rolling MSPT window, in ticks */
    int rollingAverageTicks();

    /** @return which reading of the window the threshold is compared against */
    TriggerMetric triggerOn();
}
