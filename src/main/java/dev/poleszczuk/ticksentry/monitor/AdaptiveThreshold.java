package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.AdaptiveSettings;

import java.util.Arrays;

/**
 * A lag threshold derived from what this particular server normally does.
 *
 * <p>A fixed 50 ms suits the average server and nobody else. A box that habitually runs at 45 ms
 * gets an alert for every hiccup until the admin gives up and turns alerts off; a box that runs
 * at 8 ms can quintuple its tick time - a real, worth-investigating regression - and never say a
 * word.</p>
 *
 * <p>So the baseline is the <b>median</b> tick time over the last hour, and the threshold is a
 * multiple of it. The median rather than the mean, because a handful of bad minutes should not
 * teach the server that bad is normal. Samples taken during an incident are not fed in at all,
 * for the same reason.</p>
 *
 * <p>Both ends are clamped. Without a floor, a very fast server would alert on ordinary jitter;
 * without a ceiling, a permanently broken server would quietly learn that 300 ms is fine.</p>
 *
 * <p>Recomputed once per sample rather than per tick - sorting an hour of samples sixty thousand
 * times a minute would be its own performance problem.</p>
 */
public final class AdaptiveThreshold {

    /** Below this many samples the median says nothing, and the fixed threshold is used. */
    public static final int MIN_SAMPLES = 24;

    private final int sampleIntervalSeconds;

    private AdaptiveSettings settings;
    private double[] samples;
    private int cursor;
    private int filled;

    private volatile double baseline;
    private volatile double effective;

    /**
     * @param settings              how the threshold should adapt
     * @param sampleIntervalSeconds how often {@link #record(double, double)} will be called
     */
    public AdaptiveThreshold(AdaptiveSettings settings, int sampleIntervalSeconds) {
        this.sampleIntervalSeconds = Math.max(1, sampleIntervalSeconds);
        reconfigure(settings);
    }

    /**
     * Applies new settings and clears the history.
     *
     * <p>The window length is part of the settings, so a reload starts the baseline again rather
     * than mixing two window sizes.</p>
     *
     * @param settings new settings
     */
    public void reconfigure(AdaptiveSettings settings) {
        this.settings = settings;
        int capacity = Math.max(MIN_SAMPLES,
                settings.baselineMinutes() * 60 / sampleIntervalSeconds);
        this.samples = new double[capacity];
        this.cursor = 0;
        this.filled = 0;
        this.baseline = 0.0D;
        this.effective = 0.0D;
    }

    /**
     * Feeds in one measurement and recomputes the threshold.
     *
     * <p>Do not call this while an incident is in progress - the point of the baseline is what
     * the server looks like when it is behaving.</p>
     *
     * @param mspt           current average tick time
     * @param fixedThreshold the configured threshold, used until the baseline is usable
     */
    public void record(double mspt, double fixedThreshold) {
        if (mspt > 0.0D) {
            samples[cursor] = mspt;
            cursor = (cursor + 1) % samples.length;
            if (filled < samples.length) {
                filled++;
            }
        }

        if (!settings.enabled() || filled < MIN_SAMPLES) {
            this.effective = fixedThreshold;
            return;
        }
        double median = median(samples, filled);
        this.baseline = median;
        this.effective = clamp(median * settings.multiplier(), settings.minimumMs(), settings.maximumMs());
    }

    /**
     * @param fixedThreshold the configured threshold, returned when adapting is off or not ready
     * @return the tick time above which the server counts as overloaded
     */
    public double threshold(double fixedThreshold) {
        double current = effective;
        return current > 0.0D ? current : fixedThreshold;
    }

    /** @return the server's typical tick time, or 0 before the baseline is usable */
    public double baseline() {
        return baseline;
    }

    /** @return whether enough samples have been collected to adapt */
    public boolean isReady() {
        return settings.enabled() && filled >= MIN_SAMPLES;
    }

    /** @return how many samples the baseline currently rests on */
    public int sampleCount() {
        return filled;
    }

    /**
     * Middle value of the first {@code count} entries.
     *
     * @param values sample buffer, only partly filled early on
     * @param count  how many entries are valid
     * @return the median, or 0 when there is nothing to measure
     */
    static double median(double[] values, int count) {
        if (count <= 0) {
            return 0.0D;
        }
        double[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        int middle = count / 2;
        return count % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0D;
    }

    /**
     * @param value value to constrain
     * @param min   lower bound
     * @param max   upper bound
     * @return the value, brought inside the bounds
     */
    static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
