package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.monitor.PluginBaseline;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Incident history kept in memory - the fallback store, used when writing to disk is disabled
 * or the database cannot be opened. It disappears when the server restarts.
 */
public final class MemoryAlertStore implements AlertStore {

    /**
     * How many samples per plugin to keep.
     *
     * <p>Enough to clear {@link PluginBaseline#MIN_SAMPLES} several times over at one player count,
     * because samples get filtered by how busy the server was and a flat cap on the total would
     * leave nothing comparable.</p>
     */
    private static final int PLUGIN_SAMPLE_CAPACITY = 64;

    private final Deque<StoredIncident> incidents = new ArrayDeque<>();
    private final Map<String, Deque<PluginBaseline.Sample>> pluginSamples = new HashMap<>();
    private final int capacity;

    /**
     * @param capacity how many recent incidents to keep
     */
    public MemoryAlertStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public synchronized void record(LagEvent event) {
        incidents.addFirst(StoredIncident.from(event));
        while (incidents.size() > capacity) {
            incidents.removeLast();
        }
    }

    @Override
    public void recent(int limit, Consumer<List<StoredIncident>> callback) {
        callback.accept(snapshot(limit));
    }

    @Override
    public void stats(int days, Consumer<IncidentStats> callback) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<StoredIncident> all = snapshot(Integer.MAX_VALUE);

        Map<LagCategory, Integer> byCategory = new EnumMap<>(LagCategory.class);
        int[] byHour = new int[24];
        StoredIncident worst = null;
        int total = 0;

        for (StoredIncident incident : all) {
            if (incident.timestamp().isBefore(since)) {
                continue;
            }
            total++;
            byCategory.merge(incident.category(), 1, Integer::sum);
            byHour[incident.timestamp().atZone(ZoneId.systemDefault()).getHour()]++;
            if (worst == null || incident.mspt() > worst.mspt()) {
                worst = incident;
            }
        }
        callback.accept(new IncidentStats(days, total, byCategory, byHour, worst));
    }

    @Override
    public void offenders(int days, int limit, Consumer<List<RepeatOffender>> callback) {
        callback.accept(RepeatOffender.summarise(since(days), days, limit));
    }

    @Override
    public synchronized void recordPluginTimings(Map<String, Double> samples, int players) {
        for (Map.Entry<String, Double> entry : samples.entrySet()) {
            Deque<PluginBaseline.Sample> history =
                    pluginSamples.computeIfAbsent(entry.getKey(), key -> new ArrayDeque<>());
            history.addLast(new PluginBaseline.Sample(entry.getValue(), players));
            while (history.size() > PLUGIN_SAMPLE_CAPACITY) {
                history.removeFirst();
            }
        }
    }

    @Override
    public synchronized void pluginHistory(int days,
                                           Consumer<Map<String, List<PluginBaseline.Sample>>> callback) {
        // The day count is ignored: this store has no timestamps and is bounded by capacity, so
        // "everything it has" is the only window it can honestly offer.
        Map<String, List<PluginBaseline.Sample>> result = new HashMap<>();
        pluginSamples.forEach((plugin, history) -> result.put(plugin, new ArrayList<>(history)));
        callback.accept(result);
    }

    @Override
    public void prune(int keepDays) {
        // Nothing to do - this store is bounded by capacity and dies with the server anyway.
    }

    @Override
    public String describe() {
        return "memory (lost on restart)";
    }

    /** @return every remembered incident no older than the given number of days */
    private List<StoredIncident> since(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<StoredIncident> result = new ArrayList<>();
        for (StoredIncident incident : snapshot(Integer.MAX_VALUE)) {
            if (!incident.timestamp().isBefore(since)) {
                result.add(incident);
            }
        }
        return result;
    }

    @Override
    public void close() {
        // Nothing to close.
    }

    private synchronized List<StoredIncident> snapshot(int limit) {
        List<StoredIncident> result = new ArrayList<>(Math.min(limit, incidents.size()));
        for (StoredIncident incident : incidents) {
            if (result.size() >= limit) {
                break;
            }
            result.add(incident);
        }
        return result;
    }
}
