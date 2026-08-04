package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.Messages;
import dev.poleszczuk.ticksentry.monitor.MemoryProbe.MemorySample;

/**
 * Keeps an eye on memory between alerts.
 *
 * <p>The probe reports what happened since the previous reading, so something has to take those
 * readings on a steady beat. This class does that and remembers the latest verdict, ready for
 * whenever an alert needs it.</p>
 */
public final class MemoryWatcher {

    private final MemoryProbe probe = new MemoryProbe();
    private final long intervalMs;
    private final Messages messages;

    private volatile MemorySample lastSample;
    private volatile MemoryAnalyzer.Verdict lastVerdict = new MemoryAnalyzer.Verdict(false, null);

    /**
     * @param intervalMs how often {@link #poll()} will be called, in milliseconds
     */
    public MemoryWatcher(long intervalMs) {
        this(intervalMs, Messages.none());
    }

    /**
     * @param intervalMs how often {@link #poll()} will be called, in milliseconds
     * @param messages   translation lookup for the verdict text
     */
    public MemoryWatcher(long intervalMs, Messages messages) {
        this.intervalMs = Math.max(1L, intervalMs);
        this.messages = messages == null ? Messages.none() : messages;
    }

    /** Takes a fresh reading. Call this on a steady interval. */
    public void poll() {
        MemorySample sample = probe.sample();
        this.lastSample = sample;
        this.lastVerdict = MemoryAnalyzer.diagnose(sample, intervalMs, messages);
    }

    /** @return what the last reading said about memory */
    public MemoryAnalyzer.Verdict verdict() {
        return lastVerdict;
    }

    /** @return the last reading, or {@code null} before the first poll */
    public MemorySample sample() {
        return lastSample;
    }

    /** @return short memory description for status output, or {@code null} before the first poll */
    public String describe() {
        MemorySample sample = lastSample;
        return sample == null ? null : sample.describe();
    }
}
