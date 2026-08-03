package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Historia wykrytych incydentow trzymana w pamieci.
 *
 * <p>MVP celowo nie zapisuje niczego na dysk - historia zyje do restartu serwera.
 * Trwale skladowanie (SQLite lub plik) to faza 2; ta klasa jest juz zamknietym
 * interfejsem, wiec podmiana implementacji nie ruszy reszty pluginu.</p>
 */
public final class AlertHistory {

    private final Deque<LagEvent> events = new ArrayDeque<>();
    private final int capacity;

    /**
     * @param capacity ile ostatnich incydentow przechowywac
     */
    public AlertHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /**
     * Zapisuje incydent, usuwajac najstarszy wpis po przekroczeniu pojemnosci.
     *
     * @param event incydent do zapamietania
     */
    public synchronized void record(LagEvent event) {
        events.addFirst(event);
        while (events.size() > capacity) {
            events.removeLast();
        }
    }

    /**
     * @param limit maksymalna liczba wynikow
     * @return incydenty od najnowszego
     */
    public synchronized List<LagEvent> recent(int limit) {
        List<LagEvent> result = new ArrayList<>(Math.min(limit, events.size()));
        for (LagEvent event : events) {
            if (result.size() >= limit) {
                break;
            }
            result.add(event);
        }
        return result;
    }

    /** @return liczba zapamietanych incydentow */
    public synchronized int size() {
        return events.size();
    }

    /** Czysci historie. */
    public synchronized void clear() {
        events.clear();
    }
}
