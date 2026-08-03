package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * Sklad incydentow. Implementacja moze trzymac je w pamieci albo w bazie na dysku.
 *
 * <p>Odczyty sa asynchroniczne z zalozenia - implementacja dyskowa nie moze blokowac
 * glownego watku na I/O. Wyniki wracaja callbackiem <b>zawsze na glownym watku</b>,
 * dzieki czemu wolno z nich pisac do gracza i siegac po Bukkit API.</p>
 */
public interface AlertStore {

    /**
     * Zapisuje incydent.
     *
     * @param event incydent do zapamietania
     */
    void record(LagEvent event);

    /**
     * Pobiera najnowsze incydenty.
     *
     * @param limit    maksymalna liczba wynikow
     * @param callback odbiorca listy (glowny watek), od najnowszego
     */
    void recent(int limit, Consumer<List<StoredIncident>> callback);

    /**
     * Liczy podsumowanie z ostatnich dni.
     *
     * @param days     ile dni wstecz analizowac
     * @param callback odbiorca wyniku (glowny watek)
     */
    void stats(int days, Consumer<IncidentStats> callback);

    /** @return krotki opis rodzaju skladu, pokazywany w {@code /lagwatch status} */
    String describe();

    /** Zamyka sklad, konczac zalegle zapisy. */
    void close();
}
