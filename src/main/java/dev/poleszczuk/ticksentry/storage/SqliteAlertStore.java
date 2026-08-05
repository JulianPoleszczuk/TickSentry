package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.monitor.PluginBaseline;
import dev.poleszczuk.ticksentry.util.Scheduler;
import org.bukkit.plugin.Plugin;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Durable incident history in a SQLite file.
 *
 * <p>Every database operation goes through a single worker thread - writing to disk from the main
 * thread would be exactly the kind of stall this plugin is meant to detect. Read results return to
 * the main thread via the scheduler, so callbacks may safely message players.</p>
 *
 * <p>The {@code sqlite-jdbc} driver is not bundled into the jar - it is declared under
 * {@code libraries} in {@code plugin.yml}, so Paper downloads it on first startup.</p>
 */
public final class SqliteAlertStore implements AlertStore {

    private static final String SCHEMA =
            "CREATE TABLE IF NOT EXISTS incidents ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "ts INTEGER NOT NULL,"
            + "tps REAL NOT NULL,"
            + "mspt REAL NOT NULL,"
            + "category TEXT NOT NULL,"
            + "world TEXT,"
            + "block_x INTEGER NOT NULL,"
            + "block_z INTEGER NOT NULL,"
            + "entities INTEGER NOT NULL,"
            + "dominant_type TEXT,"
            + "dominant_count INTEGER NOT NULL,"
            + "manual INTEGER NOT NULL)";

    private static final String INDEX = "CREATE INDEX IF NOT EXISTS idx_incidents_ts ON incidents(ts)";

    /**
     * Per-plugin cost samples, so "expensive" can become "has become expensive".
     *
     * <p>The share is stored rather than the raw nanoseconds: it is already normalised against the
     * window length, so samples taken under different profiler settings stay comparable. The player
     * count goes with it because handler time scales with how busy the server was.</p>
     */
    private static final String PLUGIN_SCHEMA =
            "CREATE TABLE IF NOT EXISTS plugin_timings ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "ts INTEGER NOT NULL,"
            + "plugin TEXT NOT NULL,"
            + "share REAL NOT NULL,"
            + "players INTEGER NOT NULL)";

    private static final String PLUGIN_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_plugin_timings_ts ON plugin_timings(ts)";

    private static final String INSERT_PLUGIN =
            "INSERT INTO plugin_timings (ts, plugin, share, players) VALUES (?, ?, ?, ?)";

    private static final String SELECT_PLUGIN_SINCE =
            "SELECT plugin, share, players FROM plugin_timings WHERE ts >= ?";

    private static final String COLUMNS =
            "ts, tps, mspt, category, world, block_x, block_z, entities, dominant_type, dominant_count, manual";

    private static final String INSERT =
            "INSERT INTO incidents (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_RECENT =
            "SELECT " + COLUMNS + " FROM incidents ORDER BY ts DESC LIMIT ?";

    private static final String SELECT_SINCE =
            "SELECT " + COLUMNS + " FROM incidents WHERE ts >= ? ORDER BY ts DESC";

    private final Plugin plugin;
    private final Scheduler scheduler;
    private final Connection connection;
    private final ExecutorService executor;
    private final File file;

    private SqliteAlertStore(Plugin plugin, Scheduler scheduler, File file, Connection connection) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.file = file;
        this.connection = connection;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "TickSentry-Storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens (and creates if needed) the incident database.
     *
     * @param plugin    plugin instance
     * @param scheduler where read results are handed back to the server thread
     * @param file      database file
     * @param keepDays  after how many days old rows are deleted (0 = never)
     * @return ready store, or {@code null} when the database cannot be opened
     */
    public static SqliteAlertStore open(Plugin plugin, Scheduler scheduler, File file, int keepDays) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Could not create the directory for the incident database.");
                return null;
            }

            SQLiteDataSource source = new SQLiteDataSource();
            source.setUrl("jdbc:sqlite:" + file.getAbsolutePath());
            Connection connection = source.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(SCHEMA);
                statement.executeUpdate(INDEX);
                statement.executeUpdate(PLUGIN_SCHEMA);
                statement.executeUpdate(PLUGIN_INDEX);
            }

            SqliteAlertStore store = new SqliteAlertStore(plugin, scheduler, file, connection);
            store.prune(keepDays);
            return store;
        } catch (SQLException | RuntimeException | NoClassDefFoundError ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not open the incident database - history will be kept in memory only", ex);
            return null;
        }
    }

    @Override
    public void record(LagEvent event) {
        StoredIncident incident = StoredIncident.from(event);
        executor.execute(() -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setLong(1, incident.timestamp().toEpochMilli());
                statement.setDouble(2, incident.tps());
                statement.setDouble(3, incident.mspt());
                statement.setString(4, incident.category().name());
                statement.setString(5, incident.world());
                statement.setInt(6, incident.blockX());
                statement.setInt(7, incident.blockZ());
                statement.setInt(8, incident.entities());
                statement.setString(9, incident.dominantType());
                statement.setInt(10, incident.dominantCount());
                statement.setInt(11, incident.manual() ? 1 : 0);
                statement.executeUpdate();
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not save the incident", ex);
            }
        });
    }

    @Override
    public void recent(int limit, Consumer<List<StoredIncident>> callback) {
        executor.execute(() -> {
            List<StoredIncident> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_RECENT)) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(read(rows));
                    }
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not read the history", ex);
            }
            backToMainThread(callback, result);
        });
    }

    @Override
    public void stats(int days, Consumer<IncidentStats> callback) {
        executor.execute(() -> {
            IncidentStats stats = IncidentStats.empty(days);
            try (PreparedStatement statement = connection.prepareStatement(SELECT_SINCE)) {
                statement.setLong(1, Instant.now().minus(days, ChronoUnit.DAYS).toEpochMilli());
                try (ResultSet rows = statement.executeQuery()) {
                    Map<LagCategory, Integer> byCategory = new EnumMap<>(LagCategory.class);
                    int[] byHour = new int[24];
                    StoredIncident worst = null;
                    int total = 0;
                    while (rows.next()) {
                        StoredIncident incident = read(rows);
                        total++;
                        byCategory.merge(incident.category(), 1, Integer::sum);
                        byHour[incident.timestamp().atZone(ZoneId.systemDefault()).getHour()]++;
                        if (worst == null || incident.mspt() > worst.mspt()) {
                            worst = incident;
                        }
                    }
                    stats = new IncidentStats(days, total, byCategory, byHour, worst);
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not compute the statistics", ex);
            }
            backToMainThread(callback, stats);
        });
    }

    @Override
    public void offenders(int days, int limit, Consumer<List<RepeatOffender>> callback) {
        executor.execute(() -> {
            List<RepeatOffender> result = List.of();
            try {
                // Folded in Java rather than with GROUP BY on purpose: the same code then decides
                // what counts as a repeat offender here and in the in-memory fallback, and the
                // window holds hundreds of rows at most.
                result = RepeatOffender.summarise(readSince(days), days, limit);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not look for repeat offenders", ex);
            }
            backToMainThread(callback, result);
        });
    }

    @Override
    public void recordPluginTimings(Map<String, Double> samples, int players) {
        if (samples.isEmpty()) {
            return;
        }
        // Copied before leaving the calling thread: the caller built this from live counters and is
        // free to reuse the map.
        Map<String, Double> snapshot = new LinkedHashMap<>(samples);
        long now = System.currentTimeMillis();
        executor.execute(() -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_PLUGIN)) {
                for (Map.Entry<String, Double> entry : snapshot.entrySet()) {
                    statement.setLong(1, now);
                    statement.setString(2, entry.getKey());
                    statement.setDouble(3, entry.getValue());
                    statement.setInt(4, players);
                    statement.addBatch();
                }
                statement.executeBatch();
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not save the plugin timings", ex);
            }
        });
    }

    @Override
    public void pluginHistory(int days, Consumer<Map<String, List<PluginBaseline.Sample>>> callback) {
        executor.execute(() -> {
            Map<String, List<PluginBaseline.Sample>> result = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_PLUGIN_SINCE)) {
                statement.setLong(1, Instant.now().minus(days, ChronoUnit.DAYS).toEpochMilli());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.computeIfAbsent(rows.getString("plugin"), key -> new ArrayList<>())
                                .add(new PluginBaseline.Sample(
                                        rows.getDouble("share"), rows.getInt("players")));
                    }
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not read the plugin timing history", ex);
            }
            backToMainThread(callback, result);
        });
    }

    @Override
    public String describe() {
        return "SQLite (" + file.getName() + ")";
    }

    /** Reads every incident inside the window. Runs on the storage thread. */
    private List<StoredIncident> readSince(int days) throws SQLException {
        List<StoredIncident> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_SINCE)) {
            statement.setLong(1, Instant.now().minus(days, ChronoUnit.DAYS).toEpochMilli());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
        }
        return result;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Error while closing the incident database", ex);
        }
    }

    @Override
    public void prune(int keepDays) {
        if (keepDays <= 0) {
            return;
        }
        executor.execute(() -> pruneNow(keepDays));
    }

    /** Deletes rows older than the given number of days. Runs on the storage thread. */
    private void pruneNow(int keepDays) {
        long cutoff = Instant.now().minus(keepDays, ChronoUnit.DAYS).toEpochMilli();
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM incidents WHERE ts < ?")) {
            statement.setLong(1, cutoff);
            int removed = statement.executeUpdate();
            if (removed > 0) {
                plugin.getLogger().info("Removed " + removed + " incidents older than " + keepDays + " days.");
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not prune old incidents", ex);
        }
        // Plugin samples are taken far more often than incidents happen, so leaving them out of the
        // retention sweep would make them the thing that grows the database.
        try (PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM plugin_timings WHERE ts < ?")) {
            statement.setLong(1, cutoff);
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not prune old plugin timings", ex);
        }
    }

    private static StoredIncident read(ResultSet rows) throws SQLException {
        return new StoredIncident(
                Instant.ofEpochMilli(rows.getLong("ts")),
                rows.getDouble("tps"),
                rows.getDouble("mspt"),
                parseCategory(rows.getString("category")),
                rows.getString("world"),
                rows.getInt("block_x"),
                rows.getInt("block_z"),
                rows.getInt("entities"),
                rows.getString("dominant_type"),
                rows.getInt("dominant_count"),
                rows.getInt("manual") == 1);
    }

    /** An unknown category (say after a plugin downgrade) must not break reading the history. */
    private static LagCategory parseCategory(String name) {
        try {
            return LagCategory.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return LagCategory.UNKNOWN;
        }
    }

    /** Hands the result back to the main thread so the callback may safely use the Bukkit API. */
    private <T> void backToMainThread(Consumer<T> callback, T value) {
        if (!plugin.isEnabled()) {
            return;
        }
        scheduler.run(() -> callback.accept(value));
    }
}
