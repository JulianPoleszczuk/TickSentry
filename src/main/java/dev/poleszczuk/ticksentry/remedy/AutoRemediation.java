package dev.poleszczuk.ticksentry.remedy;

import dev.poleszczuk.ticksentry.monitor.LagEvent;
import org.bukkit.Chunk;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Removes what a scan found, once an admin has explicitly allowed it.
 *
 * <p>Everything else in TickSentry only looks. This is the one part that destroys things players
 * own, so it is off by default, starts in dry-run when switched on, warns before acting, and
 * refuses to touch anything that looks cared for: named mobs, tamed pets, leashed animals,
 * anything riding or being ridden, and whatever types the admin listed as protected.</p>
 *
 * <p>The plan is decided by {@link RemedyPlan} from the scan snapshot, but never trusted: the
 * world is re-read at the moment of removal, several seconds later, and only what is still there
 * gets removed. If the chunk unloaded or a player already cleaned up, nothing happens.</p>
 */
public final class AutoRemediation {

    private final Plugin plugin;
    private final Supplier<RemedySettings> settings;
    private final Consumer<String> reporter;

    private long lastRunMillis;

    /**
     * @param plugin   plugin instance (scheduler, worlds, logging)
     * @param settings current settings, re-read so {@code /lagwatch reload} takes effect
     * @param reporter receives a summary of what happened, for the log and Discord
     */
    public AutoRemediation(Plugin plugin, Supplier<RemedySettings> settings, Consumer<String> reporter) {
        this.plugin = plugin;
        this.settings = settings;
        this.reporter = reporter;
    }

    /**
     * Looks at an incident and cleans up after it, if the settings allow.
     *
     * @param event incident that has just been reported
     * @return the actions taken or, in dry-run, the ones that would have been
     */
    public List<RemedyAction> consider(LagEvent event) {
        RemedySettings current = settings.get();
        if (!current.enabled() || event.manual()) {
            return List.of();
        }
        if (onCooldown(current)) {
            return List.of();
        }

        List<RemedyAction> actions = RemedyPlan.decide(event.topChunks(), current);
        if (actions.isEmpty()) {
            return actions;
        }
        lastRunMillis = System.currentTimeMillis();

        if (current.dryRun()) {
            // Dry-run has to be loud, or nobody ever finds out what the settings would do.
            StringBuilder text = new StringBuilder("Automatic clean-up is in dry-run - it would have:");
            for (RemedyAction action : actions) {
                text.append("\n - ").append(action.describe());
            }
            text.append("\nSet remediation.dry-run to false in config.yml to let it act.");
            reporter.accept(text.toString());
            return actions;
        }

        for (RemedyAction action : actions) {
            warn(action, current.warningSeconds());
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> run(actions),
                Math.max(1L, current.warningSeconds() * 20L));
        return actions;
    }

    /** @return seconds until another clean-up is allowed, or 0 when one may run now */
    public long cooldownRemainingSeconds() {
        RemedySettings current = settings.get();
        long elapsed = System.currentTimeMillis() - lastRunMillis;
        long remaining = current.cooldownSeconds() * 1000L - elapsed;
        return remaining <= 0L || lastRunMillis == 0L ? 0L : remaining / 1000L;
    }

    private boolean onCooldown(RemedySettings current) {
        return lastRunMillis != 0L
                && System.currentTimeMillis() - lastRunMillis < current.cooldownSeconds() * 1000L;
    }

    /** Tells the players in the affected world what is about to happen, and when. */
    private void warn(RemedyAction action, int seconds) {
        World world = plugin.getServer().getWorld(action.worldName());
        if (world == null) {
            return;
        }
        String message = ChatColor.YELLOW + "[TickSentry] " + action.announcement()
                + (seconds > 0 ? " in " + seconds + " s." : ".");
        for (Player player : world.getPlayers()) {
            player.sendMessage(message);
        }
    }

    /** Carries out the plan against the world as it is now, not as it was when the plan was made. */
    private void run(List<RemedyAction> actions) {
        RemedySettings current = settings.get();
        if (!current.enabled() || current.dryRun()) {
            // An admin changed their mind during the warning window - that decision wins.
            return;
        }

        List<String> done = new ArrayList<>();
        for (RemedyAction action : actions) {
            int removed = execute(action, current);
            if (removed > 0) {
                done.add(removed + " removed - " + action.describe());
            }
        }
        if (done.isEmpty()) {
            return;
        }

        StringBuilder text = new StringBuilder("Automatic clean-up ran:");
        for (String line : done) {
            text.append("\n - ").append(line);
        }
        reporter.accept(text.toString());
    }

    /**
     * Removes up to the planned number of entities from one chunk.
     *
     * @return how many were actually removed
     */
    private int execute(RemedyAction action, RemedySettings current) {
        try {
            World world = plugin.getServer().getWorld(action.worldName());
            if (world == null || !world.isChunkLoaded(action.chunkX(), action.chunkZ())) {
                // Reading an unloaded chunk would pull it back in from disk to delete things in
                // it - which is both pointless and the opposite of reducing load.
                return 0;
            }
            Chunk chunk = world.getChunkAt(action.chunkX(), action.chunkZ());

            int removed = 0;
            for (Entity entity : chunk.getEntities()) {
                if (removed >= action.toRemove()) {
                    break;
                }
                if (!matches(entity, action) || isCaredFor(entity, current)) {
                    continue;
                }
                entity.remove();
                removed++;
            }
            return removed;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Automatic clean-up could not finish at "
                    + action.prettyLocation() + ": " + ex);
            return 0;
        }
    }

    /** @return whether this entity is one the action is aimed at */
    private static boolean matches(Entity entity, RemedyAction action) {
        if (action.kind() == RemedyAction.Kind.CLEAR_ITEMS) {
            return entity instanceof Item || entity instanceof ExperienceOrb;
        }
        return entity.getType().name().equals(action.entityType());
    }

    /**
     * Decides whether something looks like it belongs to somebody.
     *
     * <p>Erring towards leaving things alone is the whole point. A missed mob costs a few
     * milliseconds a tick; a deleted pet costs a player something they cannot get back.</p>
     */
    private static boolean isCaredFor(Entity entity, RemedySettings settings) {
        if (entity instanceof Player) {
            return true;
        }
        if (entity.getCustomName() != null) {
            return true;
        }
        if (settings.isProtected(entity.getType().name())) {
            return true;
        }
        if (entity instanceof Tameable && ((Tameable) entity).isTamed()) {
            return true;
        }
        if (entity instanceof LivingEntity && ((LivingEntity) entity).isLeashed()) {
            return true;
        }
        if (entity.getVehicle() != null || !entity.getPassengers().isEmpty()) {
            return true;
        }
        return entity instanceof Item && hasCustomName(((Item) entity).getItemStack());
    }

    /** A renamed item was somebody's doing, so it is not litter. */
    private static boolean hasCustomName(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.hasDisplayName();
    }
}
