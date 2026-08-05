package dev.poleszczuk.ticksentry.alert;

import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exact body a generic webhook receives.
 *
 * <p>Worth pinning down because two different kinds of consumer read it: a chat service renders
 * {@code text} and ignores everything else, and a script reads the fields and ignores the text. Break
 * either and the failure is silent - somebody's automation just stops matching.</p>
 */
class AlertPayloadTest {

    private static LagEvent incident(boolean withChunk) {
        List<ChunkStat> chunks = withChunk
                ? List.of(new ChunkStat("world", 100, 100, 0,
                        Map.of("COW", 400), Map.of("HOPPER", 30), "somebody's claim", null))
                : List.of();
        return new LagEvent(Instant.parse("2026-08-05T12:00:00Z"), 18.3D, 80.1D, 320.0D, 1200, 4200,
                chunks, LagCategory.MOB_FARM, "Go and look at it.", 12L, false, null,
                null, null, null, 35.0D, 210.0D);
    }

    @Test
    void theBodyIsValidJsonWithBothAudiencesServed() {
        String json = AlertPayload.incident(incident(true), 50.0D);

        assertTrue(json.startsWith("{") && json.endsWith("}"));
        // The sentence a chat webhook will render...
        assertTrue(json.contains("\"text\":\"Server is lagging: Mob farm - TPS 18.3, "
                + "tick time 80 ms at world @ 1608, 1608.\""));
        // ...and the numbers a script wants instead.
        assertTrue(json.contains("\"cause\":\"MOB_FARM\""));
        assertTrue(json.contains("\"mspt\":80.1"));
        assertTrue(json.contains("\"tps\":18.3"));
        assertTrue(json.contains("\"thresholdMs\":50.0"));
        assertTrue(json.contains("\"world\":\"world\""));
        assertTrue(json.contains("\"x\":1608"));
        assertTrue(json.contains("\"z\":1608"));
        assertTrue(json.contains("\"event\":\"incident\""));
    }

    @Test
    void percentilesAppearOnlyWhenMeasured() {
        assertTrue(AlertPayload.incident(incident(true), 50.0D).contains("\"p99Ms\":210.0"));

        LagEvent withoutPercentiles = new LagEvent(Instant.now(), 18.0D, 80.0D, 100.0D, 10, 20,
                List.of(), LagCategory.UNKNOWN, "advice", 1L, false, null, null, null, null);

        assertFalse(AlertPayload.incident(withoutPercentiles, 50.0D).contains("p99Ms"));
    }

    @Test
    void aLocationlessIncidentOmitsTheLocationRatherThanSendingNull() {
        // A receiver asking "is there a place to go" should not have to tell a missing field from a
        // null one.
        String json = AlertPayload.incident(incident(false), 50.0D);

        assertFalse(json.contains("\"world\""));
        assertFalse(json.contains("\"x\""));
        assertTrue(json.contains("\"cause\":\"MOB_FARM\""));
        assertTrue(json.contains("\"text\":\"Server is lagging: Mob farm - TPS 18.3, "
                + "tick time 80 ms.\""), "and the sentence just stops early");
    }

    @Test
    void theOwnerIsIncludedWhenKnown() {
        assertTrue(AlertPayload.incident(incident(true), 50.0D)
                .contains("\"owner\":\"somebody's claim\""));
    }

    @Test
    void aManualReportSaysSo() {
        LagEvent manual = new LagEvent(Instant.now(), 19.0D, 20.0D, 60.0D, 10, 20,
                List.of(), LagCategory.UNKNOWN, "advice", 1L, true, null, null, null, null);

        String json = AlertPayload.incident(manual, 50.0D);

        assertTrue(json.contains("\"manual\":true"));
        assertTrue(json.contains("\"text\":\"Requested report:"));
    }

    @Test
    void aWorldNameWithAQuoteCannotBreakTheBody() {
        LagEvent odd = new LagEvent(Instant.now(), 18.0D, 80.0D, 100.0D, 10, 20,
                List.of(ChunkStat.ofEntities("odd\"world", 0, 0, Map.of("COW", 10))),
                LagCategory.MOB_FARM, "advice", 1L, false, null, null, null, null);

        assertTrue(AlertPayload.incident(odd, 50.0D).contains("\"world\":\"odd\\\"world\""));
    }

    @Test
    void recoveryCarriesTheDurationAsANumberAndAsProse() {
        String json = AlertPayload.recovery(272L, 19.9D, 12.0D);

        assertTrue(json.contains("\"event\":\"recovery\""));
        assertTrue(json.contains("\"durationSeconds\":272"));
        assertTrue(json.contains("back to normal after 272 s"));
    }

    @Test
    void aMultiLineCleanUpSummaryStaysOneJsonString() {
        String json = AlertPayload.remediation("Removed 400 items:\n - at world @ 1, 2");

        assertTrue(json.contains("\\n"), "the newline is escaped, not literal");
        assertFalse(json.contains("\n"));
    }
}
