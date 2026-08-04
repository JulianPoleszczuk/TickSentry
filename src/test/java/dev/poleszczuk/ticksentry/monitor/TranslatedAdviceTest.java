package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.config.Messages;
import dev.poleszczuk.ticksentry.config.Placeholders;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the seam between the pure analysis code and {@code messages.yml}: a configured key wins,
 * an unconfigured one leaves the built-in English exactly as it was.
 */
class TranslatedAdviceTest {

    /** Stands in for a messages.yml holding only the keys it was given. */
    private static Messages bundle(Map<String, String> entries) {
        return (key, replacements) -> {
            String template = entries.get(key);
            return template == null ? null : Placeholders.fill(template, replacements);
        };
    }

    private static ChunkStat farm() {
        return ChunkStat.ofEntities("world", 100, 200, Map.of("COW", 400));
    }

    @Test
    void anUntranslatedKeyKeepsTheBuiltInEnglish() {
        String english = HotspotAnalyzer.suggestedAction(farm(), LagCategory.MOB_FARM);
        String throughEmptyBundle = HotspotAnalyzer.suggestedAction(
                farm(), LagCategory.MOB_FARM, bundle(Map.of()));

        assertEquals(english, throughEmptyBundle);
        assertTrue(english.contains("Suspected farm"));
    }

    @Test
    void aTranslatedKeyReplacesTheSentenceAndFillsItsPlaceholders() {
        Messages polish = bundle(Map.of("advice.mob-farm",
                "Idz tam ({tp}). Podejrzana farma: {count}x {type}. Napraw: {kill}"));

        String advice = HotspotAnalyzer.suggestedAction(farm(), LagCategory.MOB_FARM, polish);

        assertTrue(advice.startsWith("Idz tam (/tp 1608 ~ 3208)."));
        assertTrue(advice.contains("400x cow"));
        assertTrue(advice.contains("/kill @e[type=cow"));
    }

    @Test
    void everyCategoryHasItsOwnKey() {
        // A shared key would mean translating one cause silently retranslates another.
        // The chunk needs both entities and block entities, or the block-entity categories
        // would fall into their "nothing dominant" variant and read a different key.
        ChunkStat mixed = new ChunkStat("world", 100, 200, 3,
                Map.of("COW", 400), Map.of("HOPPER", 60));

        Map<String, String> all = new HashMap<>();
        for (LagCategory category : LagCategory.values()) {
            all.put(keyFor(category), "TRANSLATED-" + category.name());
        }
        Messages translated = bundle(all);

        for (LagCategory category : LagCategory.values()) {
            assertEquals("TRANSLATED-" + category.name(),
                    HotspotAnalyzer.suggestedAction(mixed, category, translated),
                    "category " + category + " did not use its own key");
        }
    }

    @Test
    void theEmptyChunkVariantsHaveTheirOwnKeysToo() {
        ChunkStat empty = ChunkStat.ofEntities("world", 0, 0, Map.of());
        Messages translated = bundle(Map.of(
                "advice.mob-farm-unknown", "PUSTA-FARMA",
                "advice.redstone-unknown", "PUSTY-REDSTONE",
                "advice.spawners-unknown", "PUSTE-SPAWNERY",
                "advice.minecarts-unknown", "PUSTE-WAGONIKI"));

        assertEquals("PUSTA-FARMA", HotspotAnalyzer.suggestedAction(empty, LagCategory.MOB_FARM, translated));
        assertEquals("PUSTY-REDSTONE", HotspotAnalyzer.suggestedAction(empty, LagCategory.REDSTONE, translated));
        assertEquals("PUSTE-SPAWNERY", HotspotAnalyzer.suggestedAction(empty, LagCategory.SPAWNERS, translated));
        assertEquals("PUSTE-WAGONIKI", HotspotAnalyzer.suggestedAction(empty, LagCategory.MINECARTS, translated));
    }

    @Test
    void thePluginReportTranslatesBothItsSentences() {
        long nanos = 6_000_000_000L;
        PluginReport report = PluginReport.of(10L * 1_000_000_000L,
                List.of(new PluginTiming("HeavyPlugin", nanos, 500L, "PlayerMoveEvent", nanos)));
        Messages translated = bundle(Map.of(
                "plugin-report.message", "{plugin} zzarl {share}% w {event}",
                "plugin-report.suggestion", "Zacznij od {plugin}"));

        assertEquals("HeavyPlugin zzarl 60% w PlayerMoveEvent", report.message(translated));
        assertEquals("Zacznij od HeavyPlugin", report.suggestion(translated));
        // Untranslated still reads as before.
        assertTrue(report.message().contains("used 60% of the last"));
    }

    @Test
    void aQuietPluginReportStaysSilentInEveryLanguage() {
        PluginReport quiet = PluginReport.of(10L * 1_000_000_000L,
                List.of(new PluginTiming("Quiet", 100_000_000L, 5L, "X", 100_000_000L)));
        Messages translated = bundle(Map.of("plugin-report.message", "NIGDY"));

        assertNull(quiet.message(translated));
        assertNull(quiet.suggestion(translated));
    }

    @Test
    void theChunkLoadVerdictTranslatesItsTwoDifferentPiecesOfAdvice() {
        Messages translated = bundle(Map.of(
                "chunk-load.message", "{loaded} chunkow/s, {generated} nowych",
                "chunk-load.generating", "GENERUJE",
                "chunk-load.streaming", "CZYTA-Z-DYSKU"));

        ChunkLoadVerdict generating = ChunkLoadVerdict.of(20.0D, 5.0D);
        ChunkLoadVerdict streaming = ChunkLoadVerdict.of(60.0D, 0.0D);

        assertEquals("20 chunkow/s, 5.0 nowych", generating.message(translated));
        assertEquals("GENERUJE", generating.suggestion(translated));
        assertEquals("CZYTA-Z-DYSKU", streaming.suggestion(translated));
    }

    @Test
    void theMemoryVerdictPicksTheKeyThatMatchesTheSituation() {
        Messages translated = bundle(Map.of(
                "memory.gc-serious", "GC-POWAZNIE {percent}%",
                "memory.gc-serious-heap-full", "GC-I-PELNA-STERTA {memory}",
                "memory.gc-notable", "GC-ZAUWAZALNE {percent}%"));

        // A quarter of the window collecting, with room left on the heap.
        MemoryProbe.MemorySample roomy = new MemoryProbe.MemorySample(
                1_000_000_000L, 4_000_000_000L, 8L, 1250L);
        assertEquals("GC-POWAZNIE 25%", MemoryAnalyzer.diagnose(roomy, 5000L, translated).message());

        // The same, but the heap is nearly gone - different advice, different key.
        MemoryProbe.MemorySample full = new MemoryProbe.MemorySample(
                3_900_000_000L, 4_000_000_000L, 8L, 1250L);
        assertTrue(MemoryAnalyzer.diagnose(full, 5000L, translated).message().startsWith("GC-I-PELNA-STERTA"));
    }

    @Test
    void anIncidentBuiltWithABundleCarriesTranslatedText() {
        Messages translated = bundle(Map.of("advice.mob-farm", "PO-POLSKU {count}x {type}"));

        LagEvent event = LagEvent.of(12.0D, 90.0D, 300.0D, 500, 4000, List.of(farm()), 20L, false,
                null, null, CostWeights.defaults(), PluginReport.empty(), ChunkLoadVerdict.quiet(),
                translated);

        assertEquals("PO-POLSKU 400x cow", event.suggestedAction());
    }

    @Test
    void anIncidentBuiltWithoutABundleIsUnchanged() {
        LagEvent withNone = LagEvent.of(12.0D, 90.0D, 300.0D, 500, 4000, List.of(farm()), 20L, false,
                null, null, CostWeights.defaults(), PluginReport.empty(), ChunkLoadVerdict.quiet(),
                Messages.none());
        LagEvent withNull = LagEvent.of(12.0D, 90.0D, 300.0D, 500, 4000, List.of(farm()), 20L, false,
                null, null, CostWeights.defaults(), PluginReport.empty(), ChunkLoadVerdict.quiet(), null);

        assertEquals(withNone.suggestedAction(), withNull.suggestedAction());
        assertTrue(withNone.suggestedAction().contains("Suspected farm"));
    }

    /** The advice key each category reads, mirroring the switch in the analyser. */
    private static String keyFor(LagCategory category) {
        return "advice." + category.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
