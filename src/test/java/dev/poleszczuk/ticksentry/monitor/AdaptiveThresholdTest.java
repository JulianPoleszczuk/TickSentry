package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.AdaptiveSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveThresholdTest {

    private static final double FIXED = 50.0D;

    private static AdaptiveSettings on(double multiplier, double min, double max) {
        return new AdaptiveSettings(true, multiplier, min, max, 60);
    }

    private static AdaptiveThreshold fed(AdaptiveSettings settings, double mspt, int times) {
        AdaptiveThreshold threshold = new AdaptiveThreshold(settings, 5);
        for (int i = 0; i < times; i++) {
            threshold.record(mspt, FIXED);
        }
        return threshold;
    }

    @Test
    void withoutEnoughSamplesTheFixedThresholdStands() {
        AdaptiveThreshold threshold = fed(on(2.0D, 25.0D, 100.0D), 10.0D, AdaptiveThreshold.MIN_SAMPLES - 1);

        assertFalse(threshold.isReady());
        assertEquals(FIXED, threshold.threshold(FIXED));
    }

    @Test
    void aQuickServerGetsAThresholdNearItsOwnSpeed() {
        // 8 ms typical, doubled, is 16 - below the floor, so the floor wins.
        AdaptiveThreshold threshold = fed(on(2.0D, 25.0D, 100.0D), 8.0D, 100);

        assertTrue(threshold.isReady());
        assertEquals(8.0D, threshold.baseline(), 0.0001D);
        assertEquals(25.0D, threshold.threshold(FIXED), 0.0001D);
    }

    @Test
    void aBusyServerGetsRoomToBreathe() {
        // 45 ms is normal here; a fixed 50 would alert constantly.
        AdaptiveThreshold threshold = fed(on(2.0D, 25.0D, 100.0D), 45.0D, 100);

        assertEquals(90.0D, threshold.threshold(FIXED), 0.0001D);
    }

    @Test
    void aPermanentlyBrokenServerStillAlerts() {
        // Without a ceiling this would settle at 600 ms and never say a word again.
        AdaptiveThreshold threshold = fed(on(2.0D, 25.0D, 100.0D), 300.0D, 100);

        assertEquals(100.0D, threshold.threshold(FIXED), 0.0001D);
    }

    @Test
    void aFewBadMinutesDoNotTeachTheServerThatBadIsNormal() {
        AdaptiveThreshold threshold = new AdaptiveThreshold(on(2.0D, 25.0D, 200.0D), 5);
        for (int i = 0; i < 90; i++) {
            threshold.record(20.0D, FIXED);
        }
        for (int i = 0; i < 20; i++) {
            threshold.record(400.0D, FIXED);
        }

        // A mean would be dragged to ~90 ms here; the median stays where the server lives.
        assertEquals(20.0D, threshold.baseline(), 0.0001D);
        assertEquals(40.0D, threshold.threshold(FIXED), 0.0001D);
    }

    @Test
    void turningItOffLeavesTheFixedThresholdAlone() {
        AdaptiveThreshold threshold = fed(AdaptiveSettings.disabled(), 8.0D, 100);

        assertFalse(threshold.isReady());
        assertEquals(FIXED, threshold.threshold(FIXED));
    }

    @Test
    void reconfiguringStartsTheBaselineOver() {
        AdaptiveThreshold threshold = fed(on(2.0D, 25.0D, 100.0D), 45.0D, 100);
        assertTrue(threshold.isReady());

        threshold.reconfigure(on(3.0D, 25.0D, 100.0D));

        assertFalse(threshold.isReady());
        assertEquals(0, threshold.sampleCount());
        assertEquals(FIXED, threshold.threshold(FIXED));
    }

    @Test
    void theWindowForgetsOldSamples() {
        // One sample every 5 s for one minute is twelve slots, so a minute of new readings
        // replaces the lot.
        AdaptiveSettings shortWindow = new AdaptiveSettings(true, 2.0D, 1.0D, 500.0D, 1);
        AdaptiveThreshold threshold = new AdaptiveThreshold(shortWindow, 5);

        for (int i = 0; i < 200; i++) {
            threshold.record(10.0D, FIXED);
        }
        assertEquals(10.0D, threshold.baseline(), 0.0001D);

        for (int i = 0; i < 200; i++) {
            threshold.record(60.0D, FIXED);
        }
        assertEquals(60.0D, threshold.baseline(), 0.0001D);
    }

    @Test
    void zeroReadingsAreNotSamples() {
        // getAverageTickTime() returns 0 before the server has ticked; feeding that in would
        // drag the baseline to nothing.
        AdaptiveThreshold threshold = new AdaptiveThreshold(on(2.0D, 1.0D, 500.0D), 5);
        for (int i = 0; i < 50; i++) {
            threshold.record(0.0D, FIXED);
        }

        assertEquals(0, threshold.sampleCount());
        assertEquals(FIXED, threshold.threshold(FIXED));
    }

    @Test
    void absurdSettingsAreBroughtBackIntoRange() {
        // Below 1.0 the threshold would sit under the server's own normal speed and alert forever.
        assertEquals(1.0D, new AdaptiveSettings(true, 0.1D, 25.0D, 100.0D, 60).multiplier());
        assertEquals(10.0D, new AdaptiveSettings(true, 99.0D, 25.0D, 100.0D, 60).multiplier());
        // A maximum below the minimum is nonsense; the floor wins.
        assertEquals(80.0D, new AdaptiveSettings(true, 2.0D, 80.0D, 10.0D, 60).maximumMs());
        assertEquals(1, new AdaptiveSettings(true, 2.0D, 25.0D, 100.0D, 0).baselineMinutes());
    }

    @Test
    void theMedianHandlesBothOddAndEvenCounts() {
        assertEquals(3.0D, AdaptiveThreshold.median(new double[] {5.0D, 1.0D, 3.0D}, 3), 0.0001D);
        assertEquals(3.0D, AdaptiveThreshold.median(new double[] {4.0D, 2.0D, 1.0D, 5.0D}, 4), 0.0001D);
        assertEquals(0.0D, AdaptiveThreshold.median(new double[] {1.0D}, 0), 0.0001D);
        // Only the filled part of the buffer counts - the empty tail must not drag it to zero.
        assertEquals(10.0D, AdaptiveThreshold.median(new double[] {10.0D, 0.0D, 0.0D}, 1), 0.0001D);
    }

    @Test
    void clampKeepsValuesInsideTheBounds() {
        assertEquals(25.0D, AdaptiveThreshold.clamp(10.0D, 25.0D, 100.0D));
        assertEquals(100.0D, AdaptiveThreshold.clamp(500.0D, 25.0D, 100.0D));
        assertEquals(60.0D, AdaptiveThreshold.clamp(60.0D, 25.0D, 100.0D));
    }
}
