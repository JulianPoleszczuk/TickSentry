package dev.poleszczuk.ticksentry.web;

import dev.poleszczuk.ticksentry.util.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Bufor kolowy z probkami wydajnosci, zasilajacy wykres na dashboardzie.
 *
 * <p>Probki dopisuje glowny watek serwera, a czyta je watek HTTP, dlatego obie operacje
 * sa zsynchronizowane. Bufor ma staly rozmiar - najstarsze probki wypadaja same,
 * wiec pamiec nie rosnie niezaleznie od tego, jak dlugo serwer chodzi.</p>
 */
public final class MsptHistory {

    private final long[] timestamps;
    private final double[] mspt;
    private final double[] tps;
    private final int capacity;

    private int cursor;
    private int size;

    /**
     * @param capacity ile probek przechowywac
     */
    public MsptHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.timestamps = new long[this.capacity];
        this.mspt = new double[this.capacity];
        this.tps = new double[this.capacity];
    }

    /**
     * Dopisuje probke, nadpisujac najstarsza po zapelnieniu bufora.
     *
     * @param timestampMillis moment pomiaru
     * @param msptValue       sredni czas ticku
     * @param tpsValue        TPS
     */
    public synchronized void add(long timestampMillis, double msptValue, double tpsValue) {
        timestamps[cursor] = timestampMillis;
        mspt[cursor] = msptValue;
        tps[cursor] = tpsValue;
        cursor = (cursor + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }

    /** @return liczba przechowywanych probek */
    public synchronized int size() {
        return size;
    }

    /**
     * Zwraca probki w kolejnosci chronologicznej.
     *
     * @return lista probek od najstarszej do najnowszej
     */
    public synchronized List<Sample> samples() {
        List<Sample> result = new ArrayList<>(size);
        // Przy pelnym buforze najstarsza probka lezy tam, gdzie wskazuje kursor.
        int start = size < capacity ? 0 : cursor;
        for (int i = 0; i < size; i++) {
            int index = (start + i) % capacity;
            result.add(new Sample(timestamps[index], mspt[index], tps[index]));
        }
        return result;
    }

    /**
     * Serializuje probki do tablicy JSON gotowej dla wykresu.
     *
     * @return fragment JSON-a, np. {@code [{"t":123,"mspt":8.1,"tps":20.0}]}
     */
    public String toJsonArray() {
        List<Sample> samples = samples();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Sample sample = samples.get(i);
            json.append('{')
                    .append(Json.field("t", sample.timestampMillis())).append(',')
                    .append(Json.field("mspt", sample.mspt())).append(',')
                    .append(Json.field("tps", sample.tps()))
                    .append('}');
        }
        return json.append(']').toString();
    }

    /**
     * Pojedynczy pomiar wydajnosci.
     *
     * @param timestampMillis moment pomiaru
     * @param mspt            sredni czas ticku
     * @param tps             TPS
     */
    public record Sample(long timestampMillis, double mspt, double tps) {
    }
}
