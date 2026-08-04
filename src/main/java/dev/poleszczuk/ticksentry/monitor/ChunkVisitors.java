package dev.poleszczuk.ticksentry.monitor;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers which player was last seen in each chunk.
 *
 * <p>Coordinates alone only tell an admin where to go. On a server with players, the far more
 * useful question is <em>whose</em> build it is, and the cheapest honest answer is who has been
 * standing there. It is a hint, not proof of ownership - see {@link RegionLookup} for the
 * claim plugins that can actually answer that.</p>
 *
 * <p>{@link PlayerMoveEvent} fires several times per player per second, so the handler does the
 * least possible work: two shifts and a comparison, and it only records anything when a player
 * actually crosses a chunk border. Entries are held in an access-ordered map capped at
 * {@value #MAX_TRACKED_CHUNKS}, so a player walking across the world evicts the chunks nobody
 * has been near in a while instead of growing the map forever.</p>
 *
 * <p>Everything here runs on the main thread - player events and the chunk scan both do - so
 * the map needs no synchronisation.</p>
 */
public final class ChunkVisitors implements Listener {

    /** Upper bound on remembered chunks; roughly a few hundred kilobytes at worst. */
    private static final int MAX_TRACKED_CHUNKS = 4096;

    private final Map<String, Visit> visits =
            new LinkedHashMap<String, Visit>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Visit> eldest) {
                    return size() > MAX_TRACKED_CHUNKS;
                }
            };

    /**
     * Records a player crossing into a new chunk.
     *
     * <p>Runs at {@code MONITOR} priority so a cancelled move is never counted, and returns
     * immediately for the overwhelmingly common case of moving inside the same chunk.</p>
     *
     * @param event move event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Location from = event.getFrom();
        if (from.getBlockX() >> 4 == to.getBlockX() >> 4
                && from.getBlockZ() >> 4 == to.getBlockZ() >> 4
                && from.getWorld() == to.getWorld()) {
            return;
        }
        record(event.getPlayer(), to);
    }

    /**
     * @param event teleport event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            record(event.getPlayer(), event.getTo());
        }
    }

    /**
     * @param event join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        record(event.getPlayer(), event.getPlayer().getLocation());
    }

    /**
     * Records where a player was standing when they left - the chunk they logged out in is
     * exactly the one an unattended farm tends to sit in.
     *
     * @param event quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        record(event.getPlayer(), event.getPlayer().getLocation());
    }

    private void record(Player player, Location where) {
        if (where.getWorld() == null) {
            return;
        }
        String key = key(where.getWorld().getName(), where.getBlockX() >> 4, where.getBlockZ() >> 4);
        Visit visit = visits.get(key);
        if (visit == null) {
            visits.put(key, new Visit(player.getName()));
        } else {
            visit.touch(player.getName());
        }
    }

    /**
     * Looks up who was last seen in a chunk.
     *
     * @param worldName world name
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @return the visit, or {@code null} when nobody has been there since the server started
     */
    public Visit lastVisitor(String worldName, int chunkX, int chunkZ) {
        return visits.get(key(worldName, chunkX, chunkZ));
    }

    /** @return how many chunks are currently remembered */
    public int trackedChunks() {
        return visits.size();
    }

    /** Forgets everything. Used on reload so stale names cannot outlive a config change. */
    public void clear() {
        visits.clear();
    }

    private static String key(String worldName, int chunkX, int chunkZ) {
        return worldName + ':' + chunkX + ':' + chunkZ;
    }

    /** The last player seen in one chunk, and how often anyone has been there. */
    public static final class Visit {

        private String playerName;
        private long lastSeenMillis;
        private int entries;

        private Visit(String playerName) {
            this.playerName = playerName;
            this.lastSeenMillis = System.currentTimeMillis();
            this.entries = 1;
        }

        private void touch(String name) {
            this.playerName = name;
            this.lastSeenMillis = System.currentTimeMillis();
            this.entries++;
        }

        /** @return name of the player last seen there */
        public String playerName() {
            return playerName;
        }

        /** @return wall clock time of that visit */
        public long lastSeenMillis() {
            return lastSeenMillis;
        }

        /** @return how many times a player has entered this chunk since the server started */
        public int entries() {
            return entries;
        }

        /** @return seconds since the last visit */
        public long secondsAgo() {
            return Math.max(0L, (System.currentTimeMillis() - lastSeenMillis) / 1000L);
        }
    }
}
