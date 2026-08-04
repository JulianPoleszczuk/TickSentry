package dev.poleszczuk.ticksentry.web;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsSnapshotTest {

    private static MetricsSnapshot sample(long heapMax, Map<String, Double> plugins) {
        return new MetricsSnapshot(19.87D, 12.5D, 48.0D, 50.0D, 12, true, false, 3,
                1_073_741_824L, heapMax, 4L, 120L, 1200, 2, plugins, 1_754_308_800_000L);
    }

    @Test
    void everyMetricCarriesHelpAndTypeLines() {
        String text = sample(4_294_967_296L, Map.of()).render();

        for (String metric : new String[] {"ticksentry_up", "ticksentry_tps",
                "ticksentry_mspt_milliseconds", "ticksentry_players", "ticksentry_heap_used_bytes"}) {
            assertTrue(text.contains("# HELP " + metric + " "), "missing HELP for " + metric);
            assertTrue(text.contains("# TYPE " + metric + " gauge"), "missing TYPE for " + metric);
        }
    }

    @Test
    void valuesAreRenderedWithoutLocaleOrExponent() {
        String text = sample(4_294_967_296L, Map.of()).render();

        assertTrue(text.contains("\nticksentry_tps 19.8700\n"));
        assertTrue(text.contains("\nticksentry_mspt_milliseconds 12.5000\n"));
        // Whole numbers stay whole, and a big byte count must not turn into 1.0737E9.
        assertTrue(text.contains("\nticksentry_players 12\n"));
        assertTrue(text.contains("\nticksentry_heap_used_bytes 1073741824\n"));
        assertTrue(text.contains("\nticksentry_loaded_chunks 1200\n"));
    }

    @Test
    void booleansBecomeOneAndZero() {
        String text = sample(4_294_967_296L, Map.of()).render();

        assertTrue(text.contains("\nticksentry_monitoring 1\n"));
        assertTrue(text.contains("\nticksentry_incident_active 0\n"));
        assertTrue(text.contains("\nticksentry_up 1\n"));
    }

    @Test
    void aJvmWithoutAHeapLimitExportsNoLimit() {
        // -1 as a limit would break every "percent of heap used" query built on top of it.
        String text = sample(-1L, Map.of()).render();

        assertFalse(text.contains("ticksentry_heap_max_bytes"));
        assertTrue(text.contains("ticksentry_heap_used_bytes"));
    }

    @Test
    void pluginTimingsBecomeOneLabelledSeriesEach() {
        Map<String, Double> plugins = new LinkedHashMap<>();
        plugins.put("Essentials", 1.25D);
        plugins.put("WorldGuard", 0.5D);

        String text = sample(4_294_967_296L, plugins).render();

        assertTrue(text.contains("ticksentry_plugin_handler_seconds{plugin=\"Essentials\"} 1.2500"));
        assertTrue(text.contains("ticksentry_plugin_handler_seconds{plugin=\"WorldGuard\"} 0.5000"));
        assertEquals(1, countOccurrences(text, "# TYPE ticksentry_plugin_handler_seconds gauge"));
    }

    @Test
    void withoutMeasurementsThePluginMetricIsOmittedEntirely() {
        assertFalse(sample(4_294_967_296L, Map.of()).render().contains("ticksentry_plugin_handler_seconds"));
    }

    @Test
    void labelValuesAreEscaped() {
        assertEquals("a\\\\b", MetricsSnapshot.escapeLabel("a\\b"));
        assertEquals("say \\\"hi\\\"", MetricsSnapshot.escapeLabel("say \"hi\""));
        assertEquals("one\\ntwo", MetricsSnapshot.escapeLabel("one\ntwo"));
        assertEquals("Plain", MetricsSnapshot.escapeLabel("Plain"));
    }

    @Test
    void aPluginNamedWithAQuoteCannotBreakTheOutput() {
        String text = sample(4_294_967_296L, Map.of("Odd\"Name", 0.25D)).render();

        assertTrue(text.contains("{plugin=\"Odd\\\"Name\"} 0.2500"));
    }

    @Test
    void theBodyEndsWithANewlineAsTheFormatRequires() {
        assertTrue(sample(4_294_967_296L, Map.of()).render().endsWith("\n"));
    }

    @Test
    void theEmptySnapshotStillRendersSomethingValid() {
        String text = MetricsSnapshot.empty().render();

        assertTrue(text.contains("ticksentry_up 1"));
        assertTrue(text.endsWith("\n"));
    }

    @Test
    void aNullPluginMapIsTreatedAsNoMeasurements() {
        assertFalse(sample(4_294_967_296L, null).render().contains("ticksentry_plugin_handler_seconds"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
