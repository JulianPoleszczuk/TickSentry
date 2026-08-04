package dev.poleszczuk.ticksentry.placeholders;

import dev.poleszczuk.ticksentry.TickSentryPlugin;
import dev.poleszczuk.ticksentry.monitor.LagCategory;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Exposes TickSentry readings as PlaceholderAPI placeholders.
 *
 * <p>Every value comes from fields held in memory - a placeholder can be queried dozens of times
 * per second (scoreboards, tab lists), so this code must never touch the database or trigger a
 * scan.</p>
 *
 * <p>Available placeholders:</p>
 * <ul>
 *   <li>{@code %ticksentry_tps%} - TPS with one decimal place</li>
 *   <li>{@code %ticksentry_mspt%} - average tick time in ms</li>
 *   <li>{@code %ticksentry_status%} - "OK" or "LAG"</li>
 *   <li>{@code %ticksentry_monitoring%} - "running" or "stopped"</li>
 *   <li>{@code %ticksentry_last_category%} - cause of the last incident</li>
 *   <li>{@code %ticksentry_incidents_24h%} - number of incidents in the last 24 hours</li>
 *   <li>{@code %ticksentry_peak_ms%} - longest freeze in the sample window</li>
 * </ul>
 */
public final class TickSentryExpansion extends PlaceholderExpansion {

    private final TickSentryPlugin plugin;

    /**
     * @param plugin plugin instance the readings come from
     */
    public TickSentryExpansion(TickSentryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ticksentry";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Julian Poleszczuk";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        // Without this the expansion disappears after /papi reload, since the plugin is not reloaded.
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        switch (params.toLowerCase(Locale.ROOT)) {
            case "tps":
                return String.format(Locale.ROOT, "%.1f", plugin.tickMonitor().tps());
            case "mspt":
                return String.format(Locale.ROOT, "%.1f", plugin.tickMonitor().averageMspt());
            case "peak_ms":
                return String.format(Locale.ROOT, "%.0f", plugin.tickMonitor().peakIntervalMs());
            case "status":
                // These end up on scoreboards and in chat formats, so they are translatable
                // like everything else a player can see.
                return plugin.messages().get(plugin.tickMonitor().isInIncident()
                        ? "placeholder.status-lag" : "placeholder.status-ok");
            case "monitoring":
                return plugin.messages().get(plugin.tickMonitor().isRunning()
                        ? "placeholder.monitoring-running" : "placeholder.monitoring-stopped");
            case "incidents_24h":
                return String.valueOf(plugin.incidentsLast24h());
            case "last_category":
                LagCategory category = plugin.lastCategory();
                return category == null
                        ? plugin.messages().get("placeholder.no-category")
                        : plugin.messages().categoryTitle(category);
            default:
                return null;
        }
    }
}
