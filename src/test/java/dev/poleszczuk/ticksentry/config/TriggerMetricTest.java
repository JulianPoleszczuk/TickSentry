package dev.poleszczuk.ticksentry.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reading {@code monitor.trigger-on}.
 *
 * <p>This one setting decides which number the alert threshold is compared against, so an
 * unrecognised value must be reported rather than guessed at - {@link TriggerMetric#parse} answers
 * {@code null} and the caller logs it and keeps the default.</p>
 */
class TriggerMetricTest {

    @Test
    void everyDocumentedValueIsAccepted() {
        assertEquals(TriggerMetric.AVERAGE, TriggerMetric.parse("average"));
        assertEquals(TriggerMetric.P95, TriggerMetric.parse("p95"));
        assertEquals(TriggerMetric.P99, TriggerMetric.parse("p99"));
    }

    @Test
    void caseAndSurroundingSpaceDoNotMatter() {
        // YAML makes both easy to write by accident.
        assertEquals(TriggerMetric.P95, TriggerMetric.parse("P95"));
        assertEquals(TriggerMetric.AVERAGE, TriggerMetric.parse("  Average  "));
    }

    @Test
    void anythingElseIsRefusedRatherThanGuessedAt() {
        assertNull(TriggerMetric.parse("p90"));
        assertNull(TriggerMetric.parse("mean"));
        assertNull(TriggerMetric.parse(""));
        assertNull(TriggerMetric.parse(null));
    }

    @Test
    void theConfigNameIsWhatConfigYmlUses() {
        // The enum constant is P95 but config.yml says p95, and status prints this string back.
        assertEquals("p95", TriggerMetric.P95.configName());
        assertEquals("average", TriggerMetric.AVERAGE.configName());
    }
}
