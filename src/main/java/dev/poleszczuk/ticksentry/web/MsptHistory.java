package dev.poleszczuk.ticksentry.web;

import dev.poleszczuk.ticksentry.util.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Ring buffer of performance samples feeding the dashboard chart.
 *
 * <p>Samples are appended by the main server thread and read by an HTTP thread, so both
 * operations are synchronised. The buffer has a fixed size - the oldest samples fall out on
 * their own, so memory does not grow no matter how long the server runs.</p>
 */
public final class MsptHistory {

    private final long[] timestamps;
    private final double[] mspt;
    private final double[] tps;
    private final int capacity;

    private int cursor;
    private int size;

    /**
     * @param capacity how many samples to keep
     */
    public MsptHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.timestamps = new long[this.capacity];
        this.mspt = new double[this.capacity];
        this.tps = new double[this.capacity];
    }

    /**
     * Appends a sample, overwriting the oldest one once the buffer is full.
     *
     * @param timestampMillis moment of measurement
     * @param msptValue       average tick time
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

    /** @return number of stored samples */
    public synchronized int size() {
        return size;
    }

    /**
     * Returns the samples in chronological order.
     *
     * @return list of samples from oldest to newest
     */
    public synchronized List<Sample> samples() {
        List<Sample> result = new ArrayList<>(size);
        // Once the buffer is full, the oldest sample sits where the cursor points.
        int start = size < capacity ? 0 : cursor;
        for (int i = 0; i < size; i++) {
            int index = (start + i) % capacity;
            result.add(new Sample(timestamps[index], mspt[index], tps[index]));
        }
        return result;
    }

    /**
     * Serialises the samples into a JSON array ready for the chart.
     *
     * @return JSON fragment, for example {@code [{"t":123,"mspt":8.1,"tps":20.0}]}
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
     * A single performance measurement.
     *
     * @param timestampMillis moment of measurement
     * @param mspt            average tick time
     * @param tps             TPS
     */
    public record Sample(long timestampMillis, double mspt, double tps) {
    }
}
