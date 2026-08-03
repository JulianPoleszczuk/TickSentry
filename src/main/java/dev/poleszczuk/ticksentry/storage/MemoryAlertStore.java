package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Incident history kept in memory - the fallback store, used when writing to disk is disabled
 * or the database cannot be opened. It disappears when the server restarts.
 */
public final class MemoryAlertStore implements AlertStore {

    private final Deque<StoredIncident> incidents = new ArrayDeque<>();
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
    public String describe() {
        return "memory (lost on restart)";
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
