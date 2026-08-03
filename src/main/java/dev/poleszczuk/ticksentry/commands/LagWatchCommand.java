package dev.poleszczuk.ticksentry.commands;

import dev.poleszczuk.ticksentry.TickSentryPlugin;
import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.monitor.TickMonitor;
import dev.poleszczuk.ticksentry.storage.StoredIncident;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Handles {@code /lagwatch} - server health at a glance, without leaving the game.
 */
public final class LagWatchCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "ticksentry.admin";
    private static final List<String> SUBCOMMANDS = List.of("status", "report", "history", "stats", "reload");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    /** How many incidents {@code /lagwatch history} prints. */
    private static final int HISTORY_SHOWN = 8;

    /** Default period analysed by {@code /lagwatch stats}. */
    private static final int DEFAULT_STATS_DAYS = 7;

    private final TickSentryPlugin plugin;

    /**
     * @param plugin plugin instance the command reads its state from
     */
    public LagWatchCommand(TickSentryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status":
                showStatus(sender);
                break;
            case "report":
                showReport(sender, args.length > 1 && "discord".equalsIgnoreCase(args[1]));
                break;
            case "history":
                showHistory(sender);
                break;
            case "stats":
                showStats(sender, parseDays(args));
                break;
            case "reload":
                reload(sender);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Usage: /" + label
                        + " <status|report|history|stats|reload>");
                break;
        }
        return true;
    }

    /** Prints the current monitor readings. */
    private void showStatus(CommandSender sender) {
        TickMonitor monitor = plugin.tickMonitor();
        double mspt = monitor.averageMspt();
        double threshold = plugin.configManager().msptThresholdMs();

        header(sender, "Status");
        sender.sendMessage(ChatColor.GRAY + "Monitoring: " + (monitor.isRunning()
                ? ChatColor.GREEN + "running" : ChatColor.RED + "stopped"));
        sender.sendMessage(ChatColor.GRAY + "TPS: " + healthColor(monitor.tps() >= 19.0D, monitor.tps() >= 17.0D)
                + String.format(Locale.ROOT, "%.2f", monitor.tps()) + ChatColor.DARK_GRAY + " / 20");
        sender.sendMessage(ChatColor.GRAY + "Tick time: " + healthColor(mspt <= threshold * 0.6D, mspt <= threshold)
                + String.format(Locale.ROOT, "%.1f ms", mspt)
                + ChatColor.DARK_GRAY + " (threshold: " + String.format(Locale.ROOT, "%.0f ms", threshold) + ")");
        sender.sendMessage(ChatColor.GRAY + "Longest freeze in window: " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%.0f ms", monitor.peakIntervalMs()));

        long breach = monitor.currentBreachSeconds();
        if (breach > 0L) {
            sender.sendMessage(ChatColor.RED + "Threshold exceeded for " + breach + " s (alert after "
                    + plugin.configManager().sustainedSeconds() + " s).");
        }
        long cooldown = monitor.alertCooldownRemainingSeconds();
        if (cooldown > 0L) {
            sender.sendMessage(ChatColor.GRAY + "Next alert possible in " + ChatColor.WHITE + cooldown + " s"
                    + ChatColor.GRAY + ".");
        }
        sender.sendMessage(ChatColor.GRAY + "Discord: " + (plugin.configManager().discordEnabled()
                ? ChatColor.GREEN + "configured" : ChatColor.YELLOW + "disabled or webhook missing"));

        String spark = plugin.sparkBridge().summary();
        sender.sendMessage(ChatColor.GRAY + "Spark: " + (spark == null
                ? ChatColor.DARK_GRAY + "unavailable (using built-in measurements)"
                : ChatColor.WHITE + spark));
        String memory = plugin.memoryWatcher().describe();
        if (memory != null) {
            sender.sendMessage(ChatColor.GRAY + "Memory: " + ChatColor.WHITE + memory);
        }
        sender.sendMessage(ChatColor.GRAY + "History: " + ChatColor.WHITE + plugin.alertStore().describe()
                + ChatColor.DARK_GRAY + " (" + Plural.incidents(plugin.incidentsLast24h()) + " in the last 24 h)");
    }

    /** Forces a scan and prints the result in chat; optionally sends it to Discord too. */
    private void showReport(CommandSender sender, boolean alsoDiscord) {
        boolean started = plugin.runScan(true, event -> printReport(sender, event, alsoDiscord));
        if (!started) {
            sender.sendMessage(ChatColor.YELLOW + "A scan is already running - the result will show up shortly.");
        }
    }

    /** Prints a finished report; called once the tick-spread scan completes. */
    private void printReport(CommandSender sender, LagEvent event, boolean alsoDiscord) {
        plugin.recordManual(event);

        header(sender, "Report");
        sender.sendMessage(ChatColor.GRAY + "TPS " + ChatColor.WHITE + String.format(Locale.ROOT, "%.2f", event.tps())
                + ChatColor.GRAY + ", tick time " + ChatColor.WHITE + String.format(Locale.ROOT, "%.1f ms", event.averageMspt())
                + ChatColor.GRAY + ", scanned " + ChatColor.WHITE + event.loadedChunks() + ChatColor.GRAY
                + " chunks (" + event.totalEntities() + " entities).");

        if (event.topChunks().isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + "No chunk stands out - the game world looks calm.");
        } else {
            sender.sendMessage(ChatColor.GRAY + "Likely cause: " + ChatColor.AQUA + event.category().title());
            int index = 1;
            for (ChunkStat stat : event.topChunks()) {
                sender.sendMessage(ChatColor.DARK_GRAY + " " + index++ + ". " + ChatColor.WHITE + stat.prettyLocation()
                        + ChatColor.GRAY + " - " + stat.entityCount() + " entities, "
                        + stat.tileEntityCount() + " block entities" + describeDominant(stat));
            }
            sender.sendMessage(ChatColor.YELLOW + event.suggestedAction());
        }
        if (event.memoryNote() != null) {
            sender.sendMessage(ChatColor.YELLOW + event.memoryNote());
        }
        if (event.sparkSummary() != null) {
            sender.sendMessage(ChatColor.DARK_GRAY + event.sparkSummary());
        }

        if (alsoDiscord) {
            if (plugin.configManager().discordEnabled()) {
                plugin.webhook().sendLagAlert(event);
                sender.sendMessage(ChatColor.GRAY + "Report also sent to Discord.");
            } else {
                sender.sendMessage(ChatColor.RED + "Discord is disabled or the webhook URL is empty.");
            }
        }
    }

    /** Prints recent incidents; with SQLite storage this includes ones from before a restart. */
    private void showHistory(CommandSender sender) {
        plugin.alertStore().recent(HISTORY_SHOWN, incidents -> {
            header(sender, "History");
            if (incidents.isEmpty()) {
                sender.sendMessage(ChatColor.GREEN + "No incidents recorded yet.");
                return;
            }
            for (StoredIncident incident : incidents) {
                boolean today = Duration.between(incident.timestamp(), Instant.now()).toHours() < 24L;
                DateTimeFormatter format = today ? TIME : DATE_TIME;
                sender.sendMessage(ChatColor.DARK_GRAY + format.format(incident.timestamp()) + ChatColor.GRAY
                        + " (" + ago(incident.timestamp()) + " ago) " + ChatColor.WHITE
                        + String.format(Locale.ROOT, "%.0f ms", incident.mspt())
                        + ChatColor.GRAY + " - " + incident.category().title()
                        + (incident.world() == null ? "" : ChatColor.DARK_GRAY + " @ " + incident.prettyLocation())
                        + (incident.manual() ? ChatColor.DARK_GRAY + " [manual]" : ""));
            }
        });
    }

    /** Prints an incident summary: how many, caused by what, and at which hour most often. */
    private void showStats(CommandSender sender, int days) {
        plugin.alertStore().stats(days, stats -> {
            header(sender, "Stats (" + days + " days)");
            if (stats.total() == 0) {
                sender.sendMessage(ChatColor.GREEN + "No incidents in this period - the server held up well.");
                return;
            }

            sender.sendMessage(ChatColor.GRAY + "Recorded: " + ChatColor.WHITE + Plural.incidents(stats.total()));

            LagCategory dominant = stats.dominantCategory();
            if (dominant != null) {
                sender.sendMessage(ChatColor.GRAY + "Most common cause: " + ChatColor.AQUA + dominant.title()
                        + ChatColor.DARK_GRAY + " (" + stats.byCategory().getOrDefault(dominant, 0) + "x)");
            }

            int worstHour = stats.worstHour();
            if (worstHour >= 0) {
                sender.sendMessage(ChatColor.GRAY + "Worst time of day: " + ChatColor.WHITE
                        + String.format("%02d:00-%02d:59", worstHour, worstHour)
                        + ChatColor.DARK_GRAY + " (" + Plural.incidents(stats.byHour()[worstHour]) + ")");
            }

            StoredIncident worst = stats.worst();
            if (worst != null) {
                sender.sendMessage(ChatColor.GRAY + "Worst moment: " + ChatColor.WHITE
                        + String.format(Locale.ROOT, "%.0f ms", worst.mspt()) + ChatColor.DARK_GRAY
                        + " " + DATE_TIME.format(worst.timestamp()) + " - " + worst.prettyLocation());
            }

            List<String> histogram = stats.hourHistogram();
            if (!histogram.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Spread across the day:");
                histogram.forEach(line -> sender.sendMessage(ChatColor.DARK_GRAY + " " + line));
            }
        });
    }

    /** Reads the optional day count from the command arguments. */
    private static int parseDays(String[] args) {
        if (args.length < 2) {
            return DEFAULT_STATS_DAYS;
        }
        try {
            return Math.min(365, Math.max(1, Integer.parseInt(args[1])));
        } catch (NumberFormatException ex) {
            return DEFAULT_STATS_DAYS;
        }
    }

    /** Reloads the config and resets the monitor's sample window. */
    private void reload(CommandSender sender) {
        plugin.configManager().reload();
        plugin.tickMonitor().reset();
        header(sender, "Reload");
        sender.sendMessage(ChatColor.GREEN + "Configuration reloaded. Threshold: "
                + String.format(Locale.ROOT, "%.0f ms", plugin.configManager().msptThresholdMs())
                + " for " + plugin.configManager().sustainedSeconds() + " s.");
    }

    private static String describeDominant(ChunkStat stat) {
        var dominant = stat.dominantEntityType();
        if (dominant == null || dominant.getValue() < 10) {
            return "";
        }
        return ChatColor.DARK_GRAY + " (mostly " + dominant.getValue() + "x "
                + dominant.getKey().toLowerCase(Locale.ROOT).replace('_', ' ') + ")";
    }

    private static void header(CommandSender sender, String section) {
        sender.sendMessage(ChatColor.DARK_GRAY + "--- " + ChatColor.AQUA + "TickSentry " + ChatColor.WHITE + section
                + ChatColor.DARK_GRAY + " ---");
    }

    private static ChatColor healthColor(boolean good, boolean acceptable) {
        if (good) {
            return ChatColor.GREEN;
        }
        return acceptable ? ChatColor.YELLOW : ChatColor.RED;
    }

    private static String ago(Instant instant) {
        Duration duration = Duration.between(instant, Instant.now());
        long minutes = duration.toMinutes();
        return minutes < 1L ? duration.toSeconds() + " s" : minutes + " min";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 2 && "report".equalsIgnoreCase(args[0])) {
            return List.of("discord");
        }
        if (args.length == 2 && "stats".equalsIgnoreCase(args[0])) {
            return List.of("1", "7", "30");
        }
        return List.of();
    }
}
