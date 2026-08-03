package dev.poleszczuk.ticksentry.monitor;

import java.time.Instant;
import java.util.List;

/**
 * Pojedynczy wykryty incydent lagu wraz z kontekstem potrzebnym do zaraportowania go adminowi.
 *
 * @param timestamp      moment wykrycia
 * @param tps            TPS z ostatniej minuty
 * @param averageMspt    srednia krocząca MSPT
 * @param peakMs         najdluzszy odstep miedzy tickami w oknie
 * @param loadedChunks   liczba przeskanowanych zaladowanych chunkow
 * @param totalEntities  laczna liczba encji we wszystkich skanowanych swiatach
 * @param topChunks      najbardziej podejrzane chunki, posortowane malejaco
 * @param category       zgadnieta przyczyna (dla chunka nr 1)
 * @param suggestedAction podpowiedz dla admina
 * @param scanDurationMs ile trwalo samo skanowanie chunkow
 * @param manual         czy incydent zostal wywolany recznie komenda {@code /lagwatch report}
 * @param sparkSummary   dodatkowe statystyki ze sparka albo {@code null}, gdy sparka nie ma
 */
public record LagEvent(
        Instant timestamp,
        double tps,
        double averageMspt,
        double peakMs,
        int loadedChunks,
        int totalEntities,
        List<ChunkStat> topChunks,
        LagCategory category,
        String suggestedAction,
        long scanDurationMs,
        boolean manual,
        String sparkSummary
) {

    /**
     * Sklada incydent na podstawie wyniku skanowania, samodzielnie ustalajac kategorie i sugestie
     * na bazie najbardziej podejrzanego chunka.
     *
     * @param tps            TPS z ostatniej minuty
     * @param averageMspt    srednia krocząca MSPT
     * @param peakMs         najdluzszy odstep miedzy tickami
     * @param loadedChunks   liczba przeskanowanych chunkow
     * @param totalEntities  laczna liczba encji
     * @param topChunks      posortowana lista podejrzanych chunkow
     * @param scanDurationMs czas trwania skanowania
     * @param manual         czy skan byl reczny
     * @param sparkSummary   statystyki ze sparka albo {@code null}
     * @return gotowy do wyslania incydent
     */
    public static LagEvent of(double tps, double averageMspt, double peakMs, int loadedChunks,
                              int totalEntities, List<ChunkStat> topChunks, long scanDurationMs,
                              boolean manual, String sparkSummary) {
        ChunkStat primary = topChunks.isEmpty() ? null : topChunks.get(0);
        LagCategory category = primary == null ? LagCategory.UNKNOWN : HotspotAnalyzer.categorize(primary);
        String action = primary == null
                ? HotspotAnalyzer.suggestedAction(
                        ChunkStat.ofEntities("-", 0, 0, java.util.Map.of()), LagCategory.UNKNOWN)
                : HotspotAnalyzer.suggestedAction(primary, category);
        return new LagEvent(Instant.now(), tps, averageMspt, peakMs, loadedChunks, totalEntities,
                List.copyOf(topChunks), category, action, scanDurationMs, manual, sparkSummary);
    }

    /** @return najbardziej podejrzany chunk albo {@code null}, gdy zaden sie nie wyroznil */
    public ChunkStat primaryChunk() {
        return topChunks.isEmpty() ? null : topChunks.get(0);
    }
}
