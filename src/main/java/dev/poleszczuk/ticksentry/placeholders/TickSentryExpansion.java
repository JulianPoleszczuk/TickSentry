package dev.poleszczuk.ticksentry.placeholders;

import dev.poleszczuk.ticksentry.TickSentryPlugin;
import dev.poleszczuk.ticksentry.monitor.LagCategory;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Udostepnia odczyty TickSentry jako placeholdery PlaceholderAPI.
 *
 * <p>Wszystkie wartosci pochodza z pol trzymanych w pamieci - placeholder moze byc odpytywany
 * kilkadziesiat razy na sekunde (scoreboardy, tablisty), wiec nie wolno tu dotykac bazy ani
 * uruchamiac skanu.</p>
 *
 * <p>Dostepne placeholdery:</p>
 * <ul>
 *   <li>{@code %ticksentry_tps%} - TPS z jednym miejscem po przecinku</li>
 *   <li>{@code %ticksentry_mspt%} - sredni czas ticku w ms</li>
 *   <li>{@code %ticksentry_status%} - "OK" albo "LAG"</li>
 *   <li>{@code %ticksentry_monitoring%} - "aktywny" albo "zatrzymany"</li>
 *   <li>{@code %ticksentry_last_category%} - przyczyna ostatniego incydentu</li>
 *   <li>{@code %ticksentry_incidents_24h%} - liczba incydentow z ostatniej doby</li>
 *   <li>{@code %ticksentry_peak_ms%} - najdluzsza zwiecha w oknie pomiarowym</li>
 * </ul>
 */
public final class TickSentryExpansion extends PlaceholderExpansion {

    private final TickSentryPlugin plugin;

    /**
     * @param plugin instancja pluginu, z ktorej czerpane sa odczyty
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
        // Bez tego rozszerzenie znika po /papi reload, bo plugin nie jest ladowany ponownie.
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "tps" -> String.format(Locale.ROOT, "%.1f", plugin.tickMonitor().tps());
            case "mspt" -> String.format(Locale.ROOT, "%.1f", plugin.tickMonitor().averageMspt());
            case "peak_ms" -> String.format(Locale.ROOT, "%.0f", plugin.tickMonitor().peakIntervalMs());
            case "status" -> plugin.tickMonitor().isInIncident() ? "LAG" : "OK";
            case "monitoring" -> plugin.tickMonitor().isRunning() ? "aktywny" : "zatrzymany";
            case "incidents_24h" -> String.valueOf(plugin.incidentsLast24h());
            case "last_category" -> {
                LagCategory category = plugin.lastCategory();
                yield category == null ? "brak" : category.title();
            }
            default -> null;
        };
    }
}
