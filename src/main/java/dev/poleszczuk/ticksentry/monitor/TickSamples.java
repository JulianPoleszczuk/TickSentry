package dev.poleszczuk.ticksentry.monitor;

import java.util.Arrays;

/**
 * A rolling window of tick times, with the readings that describe it.
 *
 * <p>The mean answers "is this server generally behind". It is also the reading that hides the
 * thing admins actually complain about: nineteen quick ticks and one 400 ms freeze average out to a
 * perfectly healthy 25 ms, and the average alone would call that fine. So the window also reports
 * percentiles - p95 is "how bad are the bad ticks", and it moves when a handful of ticks go wrong
 * while the mean barely twitches.</p>
 *
 * <p>Pure by design: no Bukkit, no clock, no configuration. Where the numbers come from is somebody
 * else's problem, and what to do about them is {@link TickMonitor}'s.</p>
 */
public final class TickSamples {

    private final double[] samples;
    private int cursor;
    private int filled;

    /**
     * @param capacity how many ticks the window covers; at least one
     */
    public TickSamples(int capacity) {
        this.samples = new double[Math.max(1, capacity)];
    }

    /**
     * Adds one measurement, overwriting the oldest once the window is full.
     *
     * @param milliseconds how long the tick took
     */
    public void add(double milliseconds) {
        samples[cursor] = milliseconds;
        cursor = (cursor + 1) % samples.length;
        if (filled < samples.length) {
            filled++;
        }
    }

    /** Forgets every measurement. */
    public void clear() {
        cursor = 0;
        filled = 0;
        Arrays.fill(samples, 0.0D);
    }

    /** @return how many measurements the window holds */
    public int size() {
        return filled;
    }

    /** @return how many measurements the window holds when full */
    public int capacity() {
        return samples.length;
    }

    /**
     * @return whether the window has been filled once
     *
     * <p>Worth asking before trusting any of the readings. A window holding three ticks of a
     * server that started two seconds ago describes the startup, not the server.</p>
     */
    public boolean isFull() {
        return filled >= samples.length;
    }

    /** @return mean tick time in the window, or 0 when nothing has been measured */
    public double mean() {
        if (filled == 0) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (int i = 0; i < filled; i++) {
            sum += samples[i];
        }
        return sum / filled;
    }

    /** @return the worst single tick in the window, or 0 when nothing has been measured */
    public double max() {
        double peak = 0.0D;
        for (int i = 0; i < filled; i++) {
            peak = Math.max(peak, samples[i]);
        }
        return peak;
    }

    /** @return 95th percentile tick time - what the bad ticks look like */
    public double p95() {
        return percentile(0.95D);
    }

    /** @return 99th percentile tick time - what the worst ticks look like */
    public double p99() {
        return percentile(0.99D);
    }

    /**
     * Nearest-rank percentile of the window.
     *
     * <p>No interpolation between neighbouring samples: every value returned is a tick that
     * actually happened, which is the right answer to give an admin who is about to go looking for
     * it. Sorting a copy each time is deliberate - the window is a hundred doubles, and keeping it
     * sorted would cost more than it saves.</p>
     *
     * @param fraction between 0 and 1, for example {@code 0.95}
     * @return the sample at that rank, or 0 when nothing has been measured
     */
    public double percentile(double fraction) {
        if (filled == 0) {
            return 0.0D;
        }
        double[] sorted = Arrays.copyOf(samples, filled);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(Math.max(0.0D, Math.min(1.0D, fraction)) * filled);
        return sorted[Math.min(filled - 1, Math.max(0, rank - 1))];
    }
}
