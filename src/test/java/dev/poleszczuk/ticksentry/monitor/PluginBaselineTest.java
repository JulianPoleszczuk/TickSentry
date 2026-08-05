package dev.poleszczuk.ticksentry.monitor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deciding whether a plugin has got worse, rather than merely being expensive.
 *
 * <p>Most of these tests are about <em>not</em> reporting. A regression detector that fires on a busy
 * evening, on two quiet samples, or on a plugin that went from 0.01% to 0.05% of a tick gets switched
 * off within a week, and then it detects nothing at all.</p>
 */
class PluginBaselineTest {

    @Test
    void aPluginThatDoubledIsReported() {
        List<PluginBaseline.Sample> history = samples(10, 0.01D, 12);

        PluginBaseline.Regression regression =
                PluginBaseline.compare("Essentials", 0.03D, 12, history);

        assertNotNull(regression);
        assertEquals(3.0D, regression.ratio(), 0.001D);
        assertEquals(0.01D, regression.baselineShare(), 0.0001D);
        assertEquals(10, regression.samples());
    }

    @Test
    void aPluginHoldingSteadyIsNotReported() {
        List<PluginBaseline.Sample> history = samples(10, 0.02D, 12);

        assertNull(PluginBaseline.compare("Essentials", 0.021D, 12, history));
    }

    @Test
    void anExpensivePluginThatWasAlwaysExpensiveIsNotARegression() {
        // The whole distinction this class exists for. Forty percent of every tick is worth knowing
        // about - and /lagwatch plugins already says so - but nothing changed, so nothing here fires.
        List<PluginBaseline.Sample> history = samples(20, 0.40D, 12);

        assertNull(PluginBaseline.compare("HeavyPlugin", 0.41D, 12, history));
    }

    @Test
    void aBusyEveningIsNotARegression() {
        // Handler time scales with events, and events scale with players. Five players' worth of
        // history says nothing about forty players' worth of load, so there is no comparison to make.
        List<PluginBaseline.Sample> history = samples(20, 0.01D, 5);

        assertNull(PluginBaseline.compare("Essentials", 0.05D, 40, history));
    }

    @Test
    void historyFromASimilarPlayerCountIsUsed() {
        // Twelve against fourteen is the same evening, not a different server.
        List<PluginBaseline.Sample> history = samples(10, 0.01D, 14);

        assertNotNull(PluginBaseline.compare("Essentials", 0.03D, 12, history));
    }

    @Test
    void onlyTheComparableSamplesCountTowardsTheBaseline() {
        // A history of mostly-busy readings with a handful of quiet ones must be judged on the quiet
        // ones when the server is quiet now - and there must be enough of them.
        List<PluginBaseline.Sample> history = new ArrayList<>(samples(30, 0.20D, 40));
        history.addAll(samples(3, 0.01D, 4));

        assertNull(PluginBaseline.compare("Essentials", 0.05D, 4, history),
                "three comparable samples is not a baseline");

        history.addAll(samples(6, 0.01D, 4));
        PluginBaseline.Regression regression = PluginBaseline.compare("Essentials", 0.05D, 4, history);
        assertNotNull(regression);
        assertEquals(9, regression.samples(), "only the quiet readings were compared");
    }

    @Test
    void tooLittleHistorySaysNothing() {
        assertNull(PluginBaseline.compare("Essentials", 0.05D,
                12, samples(PluginBaseline.MIN_SAMPLES - 1, 0.01D, 12)));
        assertNull(PluginBaseline.compare("Essentials", 0.05D, 12, List.of()));
        assertNull(PluginBaseline.compare("Essentials", 0.05D, 12, null));
    }

    @Test
    void aMultipleOfNearlyNothingIsStillNothing() {
        // Ten times 0.01% of a tick is 0.1% of a tick. Reporting it would bury the case that matters.
        List<PluginBaseline.Sample> history = samples(20, 0.0001D, 12);

        assertNull(PluginBaseline.compare("TinyPlugin", 0.001D, 12, history));
    }

    @Test
    void aPluginThatUsedToCostNothingAndNowCostsSomethingIsNotDividedByZero() {
        List<PluginBaseline.Sample> history = samples(20, 0.0D, 12);

        // No baseline to be a multiple of, so there is no honest ratio to report.
        assertNull(PluginBaseline.compare("NewPlugin", 0.05D, 12, history));
    }

    @Test
    void theBaselineIsAMedianSoOneBadAfternoonDoesNotBecomeNormal() {
        List<PluginBaseline.Sample> history = new ArrayList<>(samples(9, 0.01D, 12));
        history.addAll(samples(2, 0.90D, 12));

        PluginBaseline.Regression regression =
                PluginBaseline.compare("Essentials", 0.03D, 12, history);

        assertNotNull(regression, "a mean would have been dragged up past the current reading");
        assertEquals(0.01D, regression.baselineShare(), 0.0001D);
    }

    @Test
    void comparabilityIsSymmetricAndForgivingAtLowCounts() {
        assertTrue(PluginBaseline.isComparable(0, 2));
        assertTrue(PluginBaseline.isComparable(2, 0));
        assertTrue(PluginBaseline.isComparable(12, 14));
        assertTrue(PluginBaseline.isComparable(14, 12));
        assertTrue(PluginBaseline.isComparable(20, 40), "40 -> 20 is exactly the tolerance");
        assertFalse(PluginBaseline.isComparable(5, 40));
        assertFalse(PluginBaseline.isComparable(40, 5));
    }

    @Test
    void theSentenceSaysWhatChangedWithoutGuessingWhy() {
        PluginBaseline.Regression regression =
                PluginBaseline.compare("Essentials", 0.03D, 12, samples(10, 0.01D, 12));

        String text = regression.describe();
        assertTrue(text.contains("Essentials"));
        assertTrue(text.contains("3.0x"));
        assertTrue(text.contains("3.0%"), "the current share");
        assertTrue(text.contains("1.0%"), "and what it used to be");
        // It must not claim to know the cause - it cannot see inside another plugin.
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("updat"));
    }

    @Test
    void medianHandlesBothParities() {
        assertEquals(2.0D, PluginBaseline.median(List.of(1.0D, 2.0D, 3.0D)));
        assertEquals(2.5D, PluginBaseline.median(List.of(1.0D, 2.0D, 3.0D, 4.0D)));
        assertEquals(0.0D, PluginBaseline.median(List.of()));
    }

    private static List<PluginBaseline.Sample> samples(int count, double share, int players) {
        List<PluginBaseline.Sample> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            samples.add(new PluginBaseline.Sample(share, players));
        }
        return samples;
    }
}
