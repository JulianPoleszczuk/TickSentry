package dev.poleszczuk.ticksentry.storage;

import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Trwala historia incydentow w pliku SQLite.
 *
 * <p>Wszystkie operacje na bazie ida przez jeden watek roboczy - zapis na dysk z glownego watku
 * bylby dokladnie tym rodzajem zwiechy, ktory ten plugin ma wykrywac. Wyniki odczytow wracaja
 * na glowny watek przez scheduler, wiec callbacki moga bezpiecznie pisac do graczy.</p>
 *
 * <p>Sterownik {@code sqlite-jdbc} nie jest wbudowany w jar - deklaruje go {@code libraries}
 * w {@code plugin.yml}, dzieki czemu Paper pobiera go sam przy pierwszym starcie.</p>
 */
public final class SqliteAlertStore implements AlertStore {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS incidents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                tps REAL NOT NULL,
                mspt REAL NOT NULL,
                category TEXT NOT NULL,
                world TEXT,
                block_x INTEGER NOT NULL,
                block_z INTEGER NOT NULL,
                entities INTEGER NOT NULL,
                dominant_type TEXT,
                dominant_count INTEGER NOT NULL,
                manual INTEGER NOT NULL
            )
            """;

    private static final String INDEX = "CREATE INDEX IF NOT EXISTS idx_incidents_ts ON incidents(ts)";

    private static final String INSERT = """
            INSERT INTO incidents
                (ts, tps, mspt, category, world, block_x, block_z, entities, dominant_type, dominant_count, manual)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_RECENT = """
            SELECT ts, tps, mspt, category, world, block_x, block_z, entities, dominant_type, dominant_count, manual
            FROM incidents ORDER BY ts DESC LIMIT ?
            """;

    private static final String SELECT_SINCE = """
            SELECT ts, tps, mspt, category, world, block_x, block_z, entities, dominant_type, dominant_count, manual
            FROM incidents WHERE ts >= ? ORDER BY ts DESC
            """;

    private final Plugin plugin;
    private final Connection connection;
    private final ExecutorService executor;
    private final File file;

    private SqliteAlertStore(Plugin plugin, File file, Connection connection) {
        this.plugin = plugin;
        this.file = file;
        this.connection = connection;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "TickSentry-Storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Otwiera (i w razie potrzeby tworzy) baze incydentow.
     *
     * @param plugin   instancja pluginu
     * @param file     plik bazy
     * @param keepDays po ilu dniach kasowac stare wpisy (0 = nigdy)
     * @return gotowy sklad albo {@code null}, gdy bazy nie da sie otworzyc
     */
    public static SqliteAlertStore open(Plugin plugin, File file, int keepDays) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Nie udalo sie utworzyc katalogu na baze incydentow.");
                return null;
            }

            SQLiteDataSource source = new SQLiteDataSource();
            source.setUrl("jdbc:sqlite:" + file.getAbsolutePath());
            Connection connection = source.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(SCHEMA);
                statement.executeUpdate(INDEX);
            }

            SqliteAlertStore store = new SqliteAlertStore(plugin, file, connection);
            if (keepDays > 0) {
                store.executor.execute(() -> store.prune(keepDays));
            }
            return store;
        } catch (SQLException | RuntimeException | NoClassDefFoundError ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Nie udalo sie otworzyc bazy incydentow - historia bedzie tylko w pamieci", ex);
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
                plugin.getLogger().log(Level.WARNING, "Nie udalo sie zapisac incydentu", ex);
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
                plugin.getLogger().log(Level.WARNING, "Nie udalo sie odczytac historii", ex);
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
                plugin.getLogger().log(Level.WARNING, "Nie udalo sie policzyc statystyk", ex);
            }
            backToMainThread(callback, stats);
        });
    }

    @Override
    public String describe() {
        return "SQLite (" + file.getName() + ")";
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
            plugin.getLogger().log(Level.WARNING, "Blad przy zamykaniu bazy incydentow", ex);
        }
    }

    /** Kasuje wpisy starsze niz zadana liczba dni. */
    private void prune(int keepDays) {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM incidents WHERE ts < ?")) {
            statement.setLong(1, Instant.now().minus(keepDays, ChronoUnit.DAYS).toEpochMilli());
            int removed = statement.executeUpdate();
            if (removed > 0) {
                plugin.getLogger().info("Usunieto " + removed + " incydentow starszych niz " + keepDays + " dni.");
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Nie udalo sie wyczyscic starych incydentow", ex);
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

    /** Nieznana kategoria (np. po downgradzie pluginu) nie moze wywrocic odczytu historii. */
    private static LagCategory parseCategory(String name) {
        try {
            return LagCategory.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return LagCategory.UNKNOWN;
        }
    }

    /** Odsyla wynik na glowny watek, zeby callback mogl bezpiecznie uzywac Bukkit API. */
    private <T> void backToMainThread(Consumer<T> callback, T value) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(value));
    }
}
