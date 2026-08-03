package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.util.Json;

import java.time.Instant;
import java.util.Map;

/**
 * Splaszczony incydent w postaci, w jakiej trafia do bazy i wraca z niej.
 *
 * <p>Nie przechowujemy calej listy podejrzanych chunkow - do historii i statystyk wystarcza
 * ten glowny. Dzieki temu jeden incydent to jeden wiersz.</p>
 *
 * @param timestamp      moment wykrycia
 * @param tps            TPS w chwili wykrycia
 * @param mspt           sredni czas ticku w chwili wykrycia
 * @param category       zgadnieta przyczyna
 * @param world          swiat glownego podejrzanego chunka lub {@code null}
 * @param blockX         wspolrzedna X srodka tego chunka
 * @param blockZ         wspolrzedna Z srodka tego chunka
 * @param entities       liczba encji w tym chunku
 * @param dominantType   najliczniejszy typ encji lub {@code null}
 * @param dominantCount  liczba wystapien dominujacego typu
 * @param manual         czy incydent pochodzil z recznego skanu
 */
public record StoredIncident(
        Instant timestamp,
        double tps,
        double mspt,
        LagCategory category,
        String world,
        int blockX,
        int blockZ,
        int entities,
        String dominantType,
        int dominantCount,
        boolean manual
) {

    /**
     * Splaszcza pelny incydent do postaci nadajacej sie do zapisu.
     *
     * @param event zrodlowy incydent
     * @return rekord gotowy do wstawienia do bazy
     */
    public static StoredIncident from(LagEvent event) {
        ChunkStat primary = event.primaryChunk();
        Map.Entry<String, Integer> dominant = primary == null ? null : primary.dominantEntityType();
        return new StoredIncident(
                event.timestamp(),
                event.tps(),
                event.averageMspt(),
                event.category(),
                primary == null ? null : primary.worldName(),
                primary == null ? 0 : primary.blockX(),
                primary == null ? 0 : primary.blockZ(),
                primary == null ? 0 : primary.entityCount(),
                dominant == null ? null : dominant.getKey(),
                dominant == null ? 0 : dominant.getValue(),
                event.manual());
    }

    /** @return czytelny opis lokalizacji albo informacja o jej braku */
    public String prettyLocation() {
        return world == null ? "brak konkretnego miejsca" : world + " @ " + blockX + ", " + blockZ;
    }

    /**
     * Serializuje incydent na potrzeby panelu webowego.
     *
     * @return obiekt JSON z polami czytanymi przez dashboard.html
     */
    public String toJson() {
        return "{"
                + Json.field("at", timestamp.toEpochMilli()) + ","
                + Json.field("mspt", mspt) + ","
                + Json.field("tps", tps) + ","
                + Json.field("category", category.title()) + ","
                + Json.field("world", world) + ","
                + Json.field("x", blockX) + ","
                + Json.field("z", blockZ) + ","
                + Json.field("entities", entities) + ","
                + Json.field("manual", manual)
                + "}";
    }

    /**
     * Sklada tablice JSON z listy incydentow.
     *
     * @param incidents lista do zserializowania
     * @return tablica JSON gotowa dla dashboardu
     */
    public static String toJsonArray(java.util.List<StoredIncident> incidents) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < incidents.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(incidents.get(i).toJson());
        }
        return json.append(']').toString();
    }
}
