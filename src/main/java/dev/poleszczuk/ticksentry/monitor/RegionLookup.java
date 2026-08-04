package dev.poleszczuk.ticksentry.monitor;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Asks whichever land protection plugin is installed who owns a piece of ground.
 *
 * <p>"There are 1200 cows at 1608, 1608" only answers half the question an admin has. The other
 * half is whose farm it is, and the plugins that already know are WorldGuard, GriefPrevention
 * and Towny.</p>
 *
 * <p>Every hook is reflective and every call is wrapped, exactly like {@link SparkBridge}: none
 * of these plugins is a compile-time or runtime dependency, and any of them changing its API
 * costs TickSentry a line of extra detail rather than an exception. A plugin that is absent, or
 * whose API moved, simply yields {@code null}.</p>
 *
 * <p>Lookups only ever run for the handful of chunks that made it into a report, never for the
 * hundreds that were scanned.</p>
 */
public final class RegionLookup {

    private final Plugin plugin;

    private Boolean worldGuard;
    private Boolean griefPrevention;
    private Boolean towny;

    /**
     * @param plugin plugin instance, used to check which protection plugins are loaded
     */
    public RegionLookup(Plugin plugin) {
        this.plugin = plugin;
    }

    /** @return whether at least one supported protection plugin is present */
    public boolean isAvailable() {
        return hasWorldGuard() || hasGriefPrevention() || hasTowny();
    }

    /**
     * Names the region, claim or town covering a spot.
     *
     * @param world  world holding the spot
     * @param blockX block X coordinate
     * @param blockZ block Z coordinate
     * @return a short description such as {@code region "ironfarm" (Steve)}, or {@code null}
     */
    public String describe(World world, int blockX, int blockZ) {
        if (world == null || !isAvailable()) {
            return null;
        }
        Location location;
        try {
            // The chunk is loaded - the scan only ever looks at loaded ones - so reading the
            // surface height here costs nothing and cannot pull a chunk in from disk.
            location = new Location(world, blockX, world.getHighestBlockYAt(blockX, blockZ), blockZ);
        } catch (RuntimeException ex) {
            location = new Location(world, blockX, 64, blockZ);
        }

        String result = fromWorldGuard(world, location);
        if (result == null) {
            result = fromGriefPrevention(location);
        }
        if (result == null) {
            result = fromTowny(location);
        }
        return result;
    }

    private boolean hasWorldGuard() {
        if (worldGuard == null) {
            worldGuard = plugin.getServer().getPluginManager().getPlugin("WorldGuard") != null;
        }
        return worldGuard;
    }

    private boolean hasGriefPrevention() {
        if (griefPrevention == null) {
            griefPrevention = plugin.getServer().getPluginManager().getPlugin("GriefPrevention") != null;
        }
        return griefPrevention;
    }

    private boolean hasTowny() {
        if (towny == null) {
            towny = plugin.getServer().getPluginManager().getPlugin("Towny") != null;
        }
        return towny;
    }

    /**
     * WorldGuard 7: {@code WorldGuard -> platform -> region container -> manager -> regions here}.
     *
     * <p>Methods are looked up on the declared public types rather than on {@code getClass()},
     * so an implementation class that happens to be package-private cannot break the call.</p>
     */
    private String fromWorldGuard(World world, Location location) {
        if (!hasWorldGuard()) {
            return null;
        }
        try {
            Object worldGuardInstance = Class.forName("com.sk89q.worldguard.WorldGuard")
                    .getMethod("getInstance").invoke(null);
            Object platform = worldGuardInstance.getClass().getMethod("getPlatform").invoke(worldGuardInstance);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);

            Class<?> weWorldClass = Class.forName("com.sk89q.worldedit.world.World");
            Object weWorld = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
                    .getMethod("adapt", World.class).invoke(null, world);
            Object manager = container.getClass().getMethod("get", weWorldClass).invoke(container, weWorld);
            if (manager == null) {
                return null;
            }

            Class<?> vectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Object vector = vectorClass.getMethod("at", int.class, int.class, int.class)
                    .invoke(null, location.getBlockX(), location.getBlockY(), location.getBlockZ());
            Object applicable = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager")
                    .getMethod("getApplicableRegions", vectorClass).invoke(manager, vector);
            if (!(applicable instanceof Iterable)) {
                return null;
            }

            Class<?> regionClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion");
            Method getId = regionClass.getMethod("getId");
            Method getOwners = regionClass.getMethod("getOwners");
            for (Object region : (Iterable<?>) applicable) {
                String id = String.valueOf(getId.invoke(region));
                // WorldGuard gives every world a "__global__" region; naming it helps nobody.
                if ("__global__".equals(id)) {
                    continue;
                }
                Object owners = getOwners.invoke(region);
                String names = owners == null ? null
                        : String.valueOf(owners.getClass().getMethod("toPlayersString").invoke(owners));
                return names == null || names.isEmpty()
                        ? "region \"" + id + "\""
                        : "region \"" + id + "\" (" + names + ")";
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            worldGuard = false;
            return null;
        }
    }

    /** GriefPrevention: the data store can name the claim covering a location and its owner. */
    private String fromGriefPrevention(Location location) {
        if (!hasGriefPrevention()) {
            return null;
        }
        try {
            Class<?> mainClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object instance = mainClass.getField("instance").get(null);
            if (instance == null) {
                return null;
            }
            Object dataStore = mainClass.getField("dataStore").get(instance);
            if (dataStore == null) {
                return null;
            }
            Class<?> claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim");
            Object claim = Class.forName("me.ryanhamshire.GriefPrevention.DataStore")
                    .getMethod("getClaimAt", Location.class, boolean.class, claimClass)
                    .invoke(dataStore, location, true, null);
            if (claim == null) {
                return null;
            }
            Object owner = claimClass.getMethod("getOwnerName").invoke(claim);
            return owner == null ? "a claim" : "claim of " + owner;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            griefPrevention = false;
            return null;
        }
    }

    /** Towny: a town block knows which town it belongs to. */
    private String fromTowny(Location location) {
        if (!hasTowny()) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object townBlock = apiClass.getMethod("getTownBlock", Location.class).invoke(api, location);
            if (townBlock == null) {
                return null;
            }
            Object town = townBlock.getClass().getMethod("getTownOrNull").invoke(townBlock);
            if (town == null) {
                return null;
            }
            Object name = town.getClass().getMethod("getName").invoke(town);
            return name == null ? null : "town " + name;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            towny = false;
            return null;
        }
    }
}
