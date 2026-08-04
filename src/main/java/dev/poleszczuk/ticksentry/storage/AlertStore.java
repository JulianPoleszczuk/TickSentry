package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * Store of incidents. An implementation may keep them in memory or in a file-backed database.
 *
 * <p>Reads are asynchronous by design - a disk-backed implementation must not block the main
 * thread on I/O. Results come back through a callback <b>always on the main thread</b>, so they
 * are free to message players and touch the Bukkit API.</p>
 */
public interface AlertStore {

    /**
     * Records an incident.
     *
     * @param event incident to remember
     */
    void record(LagEvent event);

    /**
     * Fetches the most recent incidents.
     *
     * @param limit    maximum number of results
     * @param callback receiver of the list (main thread), newest first
     */
    void recent(int limit, Consumer<List<StoredIncident>> callback);

    /**
     * Computes a summary of the last few days.
     *
     * @param days     how many days back to analyse
     * @param callback receiver of the result (main thread)
     */
    void stats(int days, Consumer<IncidentStats> callback);

    /**
     * Finds the chunks that have been behind more than one incident.
     *
     * @param days     how many days back to analyse
     * @param limit    maximum number of results
     * @param callback receiver of the ranking (main thread), most frequent first
     */
    void offenders(int days, int limit, Consumer<List<RepeatOffender>> callback);

    /**
     * Deletes incidents older than the retention period.
     *
     * <p>Called on a timer as well as at startup: a server that stays up for months would
     * otherwise never apply its own {@code keep-days} setting.</p>
     *
     * @param keepDays how many days to keep; zero or less means keep everything
     */
    void prune(int keepDays);

    /** @return short description of the store type, shown in {@code /lagwatch status} */
    String describe();

    /** Closes the store, finishing pending writes. */
    void close();
}
