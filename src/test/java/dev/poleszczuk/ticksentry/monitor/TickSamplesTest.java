package dev.poleszczuk.ticksentry.monitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window of tick times and the readings taken from it.
 *
 * <p>The test worth reading here is {@link #theMeanHidesAFreezeThatThePercentilesShow()} - it is the
 * whole reason percentiles were added.</p>
 */
class TickSamplesTest {

    @Test
    void anEmptyWindowReportsZeroRatherThanFailing() {
        TickSamples samples = new TickSamples(10);

        assertEquals(0.0D, samples.mean());
        assertEquals(0.0D, samples.max());
        assertEquals(0.0D, samples.p95());
        assertEquals(0.0D, samples.p99());
        assertEquals(0, samples.size());
        assertFalse(samples.isFull());
    }

    @Test
    void readingsUseOnlyTheSamplesTakenSoFar() {
        // The buffer is ten wide but holds two measurements, and eight zeroes must not drag the
        // mean down to a fifth of the truth.
        TickSamples samples = new TickSamples(10);
        samples.add(40.0D);
        samples.add(60.0D);

        assertEquals(50.0D, samples.mean());
        assertEquals(60.0D, samples.max());
        assertEquals(2, samples.size());
        assertFalse(samples.isFull());
    }

    @Test
    void theWindowOverwritesItsOldestSample() {
        TickSamples samples = new TickSamples(3);
        samples.add(100.0D);
        samples.add(10.0D);
        samples.add(10.0D);
        assertTrue(samples.isFull());
        assertEquals(40.0D, samples.mean());

        samples.add(10.0D);

        assertEquals(10.0D, samples.mean(), "the 100 ms tick has aged out");
        assertEquals(10.0D, samples.max());
    }

    @Test
    void theMeanHidesAFreezeThatThePercentilesShow() {
        // Why this class exists. Nineteen healthy ticks and one 400 ms stall average out to a
        // number no threshold would ever fire on, while p99 names the stall outright.
        TickSamples samples = new TickSamples(20);
        for (int i = 0; i < 19; i++) {
            samples.add(5.0D);
        }
        samples.add(400.0D);

        assertEquals(24.75D, samples.mean(), 0.001D);
        assertTrue(samples.mean() < 50.0D, "the default threshold would not fire on this");
        assertEquals(400.0D, samples.p99());
        assertEquals(400.0D, samples.max());
    }

    @Test
    void percentilesPickARealSampleAndDoNotInterpolate() {
        // An admin told "p95 was 80 ms" should be able to find a tick that took 80 ms.
        TickSamples samples = new TickSamples(100);
        for (int i = 1; i <= 100; i++) {
            samples.add(i);
        }

        assertEquals(95.0D, samples.p95());
        assertEquals(99.0D, samples.p99());
        assertEquals(50.0D, samples.percentile(0.5D));
        assertEquals(100.0D, samples.percentile(1.0D));
    }

    @Test
    void percentilesHoldUpOnAWindowTooSmallToHaveA95th() {
        TickSamples samples = new TickSamples(3);
        samples.add(10.0D);
        samples.add(20.0D);
        samples.add(30.0D);

        // Nearest rank on three samples: there is no 95th, so the worst one is the honest answer.
        assertEquals(30.0D, samples.p95());
        assertEquals(30.0D, samples.p99());
    }

    @Test
    void aFractionOutsideZeroToOneIsBroughtBackInside() {
        TickSamples samples = new TickSamples(4);
        samples.add(1.0D);
        samples.add(2.0D);
        samples.add(3.0D);
        samples.add(4.0D);

        assertEquals(1.0D, samples.percentile(-1.0D));
        assertEquals(4.0D, samples.percentile(7.0D));
    }

    @Test
    void clearingForgetsEverything() {
        TickSamples samples = new TickSamples(4);
        samples.add(100.0D);
        samples.add(100.0D);

        samples.clear();

        assertEquals(0, samples.size());
        assertEquals(0.0D, samples.mean());
        assertEquals(0.0D, samples.max());
    }

    @Test
    void aWindowOfOneStillWorks() {
        // config.yml clamps rolling-average-ticks to at least 20, but the class should not depend
        // on somebody else's validation to avoid dividing by zero.
        TickSamples samples = new TickSamples(0);
        samples.add(42.0D);

        assertEquals(1, samples.capacity());
        assertEquals(42.0D, samples.mean());
        assertEquals(42.0D, samples.p99());
        assertTrue(samples.isFull());
    }
}
