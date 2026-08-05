package dev.poleszczuk.ticksentry.commands;

import dev.poleszczuk.ticksentry.TickSentryPlugin;
import dev.poleszczuk.ticksentry.config.TriggerMetric;
import dev.poleszczuk.ticksentry.monitor.AdaptiveThreshold;
import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagCategory;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.monitor.PluginProfiler;
import dev.poleszczuk.ticksentry.monitor.PluginReport;
import dev.poleszczuk.ticksentry.monitor.PluginTiming;
import dev.poleszczuk.ticksentry.monitor.TickMonitor;
import dev.poleszczuk.ticksentry.remedy.RemedySettings;
import dev.poleszczuk.ticksentry.storage.RepeatOffender;
import dev.poleszczuk.ticksentry.storage.StoredIncident;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles {@code /lagwatch} - server health at a glance, without leaving the game.
 */
public final class LagWatchCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "ticksentry.admin";

    /**
     * Permission for the teleport button.
     *
     * <p>Separate from {@code ticksentry.admin} because it is a genuinely different power: it lets
     * whoever holds it teleport to any coordinates in any world, which is more than "may read the
     * reports".</p>
     */
    private static final String TELEPORT_PERMISSION = "ticksentry.teleport";

    private static final List<String> SUBCOMMANDS =
            List.of("status", "report", "plugins", "history", "offenders", "stats", "tp", "reload");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    /** How many incidents {@code /lagwatch history} prints. */
    private static final int HISTORY_SHOWN = 8;

    /** Default period analysed by {@code /lagwatch stats}. */
    private static final int DEFAULT_STATS_DAYS = 7;

    /** How many plugins {@code /lagwatch plugins} lists. */
    private static final int PLUGINS_SHOWN = 5;

    /** How many chunks {@code /lagwatch offenders} lists. */
    private static final int OFFENDERS_SHOWN = 8;

    private final TickSentryPlugin plugin;

    /**
     * @param plugin plugin instance the command reads its state from
     */
    public LagWatchCommand(TickSentryPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Shorthand for a line of translatable text.
     *
     * @param key          dotted key from {@code messages.yml}
     * @param replacements alternating placeholder names and values
     * @return the finished line
     */
    private String msg(String key, String... replacements) {
        return plugin.messages().get(key, replacements);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(msg("command.no-permission"));
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status":
                showStatus(sender);
                break;
            case "report":
                showReport(sender, label, args.length > 1 && "discord".equalsIgnoreCase(args[1]));
                break;
            case "plugins":
                showPlugins(sender);
                break;
            case "history":
                showHistory(sender, label);
                break;
            case "offenders":
                showOffenders(sender, label, args.length > 1
                        ? parseDays(args) : plugin.configManager().offenderDays());
                break;
            case "stats":
                showStats(sender, parseDays(args));
                break;
            case "tp":
                teleport(sender, label, args);
                break;
            case "reload":
                reload(sender);
                break;
            default:
                sender.sendMessage(msg("command.usage", "label", label));
                break;
        }
        return true;
    }

    /** Prints the current monitor readings. */
    private void showStatus(CommandSender sender) {
        TickMonitor monitor = plugin.tickMonitor();
        double mspt = monitor.averageMspt();
        double threshold = monitor.thresholdMs();

        header(sender, msg("command.section.status"));
        sender.sendMessage(msg("status.monitoring", "state",
                msg(monitor.isRunning() && monitor.hasReadings()
                        ? "status.monitoring-running" : "status.monitoring-stopped")));
        if (!plugin.worldScanAllowed()) {
            // Otherwise the first thing an admin does on Folia is wonder why every report says no
            // chunk stands out.
            sender.sendMessage(msg("status.limited-mode"));
        }
        sender.sendMessage(msg("status.tps",
                "colour", healthColor(monitor.tps() >= 19.0D, monitor.tps() >= 17.0D).toString(),
                "tps", String.format(Locale.ROOT, "%.2f", monitor.tps())));
        sender.sendMessage(msg("status.mspt",
                "colour", healthColor(mspt <= threshold * 0.6D, mspt <= threshold).toString(),
                "mspt", String.format(Locale.ROOT, "%.1f", mspt),
                "threshold", String.format(Locale.ROOT, "%.0f", threshold)));
        TriggerMetric trigger = plugin.configManager().triggerOn();
        if (trigger != TriggerMetric.AVERAGE) {
            // Otherwise the line above reads as the number being watched when it is not.
            sender.sendMessage(msg("status.trigger-on",
                    "metric", trigger.configName(),
                    "value", String.format(Locale.ROOT, "%.0f", monitor.triggerMspt())));
        }
        // The average says whether the server is generally behind; the percentiles say how bad its
        // bad ticks get. A server averaging 20 ms with a p99 of 400 ms stutters, and only one of
        // those two numbers shows it.
        sender.sendMessage(msg("status.percentiles",
                "colour", healthColor(monitor.p99Mspt() <= threshold,
                        monitor.p99Mspt() <= threshold * 2.0D).toString(),
                "p95", String.format(Locale.ROOT, "%.0f", monitor.p95Mspt()),
                "p99", String.format(Locale.ROOT, "%.0f", monitor.p99Mspt()),
                "worst", String.format(Locale.ROOT, "%.0f", monitor.worstTickMs())));
        sender.sendMessage(msg("status.peak",
                "peak", String.format(Locale.ROOT, "%.0f", monitor.peakIntervalMs())));
        if (!monitor.tickTimeSource().isRaw()) {
            // Otherwise the percentiles above look like per-tick measurements when they are not.
            sender.sendMessage(msg("status.tick-source", "source", monitor.tickTimeSource().describe()));
        }

        AdaptiveThreshold adaptive = plugin.adaptiveThreshold();
        if (plugin.configManager().adaptiveSettings().enabled()) {
            sender.sendMessage(adaptive.isReady()
                    ? msg("status.adaptive-ready", "baseline",
                            String.format(Locale.ROOT, "%.1f", adaptive.baseline()))
                    : msg("status.adaptive-learning",
                            "have", String.valueOf(adaptive.sampleCount()),
                            "need", String.valueOf(AdaptiveThreshold.MIN_SAMPLES)));
        }

        long breach = monitor.currentBreachSeconds();
        if (breach > 0L) {
            sender.sendMessage(msg("status.breach",
                    "seconds", String.valueOf(breach),
                    "after", String.valueOf(plugin.configManager().sustainedSeconds())));
        }
        long cooldown = monitor.alertCooldownRemainingSeconds();
        if (cooldown > 0L) {
            sender.sendMessage(msg("status.cooldown", "seconds", String.valueOf(cooldown)));
        }
        sender.sendMessage(msg("status.discord", "state",
                msg(plugin.configManager().discordEnabled()
                        ? "status.discord-configured" : "status.discord-missing")));

        String spark = plugin.sparkBridge().summary();
        sender.sendMessage(spark == null
                ? msg("status.spark-missing")
                : msg("status.spark", "spark", spark));

        RemedySettings remedy = plugin.configManager().remedySettings();
        if (!remedy.enabled()) {
            sender.sendMessage(msg("status.remedy-off"));
        } else {
            long remedyCooldown = plugin.remediation().cooldownRemainingSeconds();
            sender.sendMessage(msg(remedy.dryRun() ? "status.remedy-dry-run" : "status.remedy-active",
                    "next", remedyCooldown > 0L
                            ? msg("status.remedy-next", "seconds", String.valueOf(remedyCooldown)) : ""));
        }

        sender.sendMessage(msg(plugin.regionLookup().isAvailable()
                ? "status.protection-hooked" : "status.protection-missing"));

        String memory = plugin.memoryWatcher().describe();
        if (memory != null) {
            sender.sendMessage(msg("status.memory", "memory", memory));
        }
        sender.sendMessage(msg("status.history",
                "store", plugin.alertStore().describe(),
                "recent", Plural.incidents(plugin.incidentsLast24h())));
    }

    /** Forces a scan and prints the result in chat; optionally sends it to Discord too. */
    private void showReport(CommandSender sender, String label, boolean alsoDiscord) {
        boolean started = plugin.runScan(true, event -> printReport(sender, label, event, alsoDiscord));
        if (!started) {
            sender.sendMessage(msg("report.already-running"));
        }
    }

    /** Prints a finished report; called once the tick-spread scan completes. */
    private void printReport(CommandSender sender, String label, LagEvent event, boolean alsoDiscord) {
        plugin.recordManual(event);

        header(sender, msg("command.section.report"));
        sender.sendMessage(msg("report.summary",
                "tps", String.format(Locale.ROOT, "%.2f", event.tps()),
                "mspt", String.format(Locale.ROOT, "%.1f", event.averageMspt()),
                "chunks", String.valueOf(event.loadedChunks()),
                "entities", String.valueOf(event.totalEntities())));

        String cause = plugin.messages().categoryTitle(event.category());
        if (event.topChunks().isEmpty()) {
            sender.sendMessage(msg("report.calm"));
            // The world is fine but something else was not, so the advice still has to reach the admin.
            if (event.category() != LagCategory.UNKNOWN) {
                sender.sendMessage(msg("report.cause", "cause", cause));
                sender.sendMessage(msg("report.advice", "advice", event.suggestedAction()));
            }
        } else {
            sender.sendMessage(msg("report.cause", "cause", cause));
            int index = 1;
            for (ChunkStat stat : event.topChunks()) {
                sendLocated(sender, label, msg("report.chunk",
                        "index", String.valueOf(index++),
                        "location", stat.prettyLocation(),
                        "entities", String.valueOf(stat.entityCount()),
                        "tiles", String.valueOf(stat.tileEntityCount()),
                        "dominant", describeDominant(stat)),
                        stat.worldName(), stat.blockX(), stat.blockZ(), stat.prettyLocation());
                if (stat.attribution() != null) {
                    sender.sendMessage(msg("report.chunk-owner", "attribution", stat.attribution()));
                }
                if (stat.historyNote() != null) {
                    sender.sendMessage(msg("report.chunk-history", "history", stat.historyNote()));
                }
            }
            sender.sendMessage(msg("report.advice", "advice", event.suggestedAction()));
        }
        if (event.pluginNote() != null) {
            sender.sendMessage(msg("report.note", "note", event.pluginNote()));
        }
        if (event.chunkLoadNote() != null) {
            sender.sendMessage(msg("report.note", "note", event.chunkLoadNote()));
        }
        if (event.memoryNote() != null) {
            sender.sendMessage(msg("report.note", "note", event.memoryNote()));
        }
        if (event.sparkSummary() != null) {
            sender.sendMessage(msg("report.spark", "spark", event.sparkSummary()));
        }

        if (alsoDiscord) {
            if (plugin.configManager().discordEnabled()) {
                plugin.webhook().sendLagAlert(event);
                // The channel has just been told. Without this the automatic alert could fire
                // seconds later and post the same incident again, which is exactly what the
                // cooldown exists to prevent - it was simply never told about manual alerts.
                plugin.tickMonitor().markAlertSent();
                sender.sendMessage(msg("report.sent-to-discord"));
            } else {
                sender.sendMessage(msg("report.discord-unconfigured"));
            }
        }
    }

    /** Prints which plugins spent the most time in their event handlers recently. */
    private void showPlugins(CommandSender sender) {
        header(sender, msg("command.section.plugins"));
        PluginProfiler profiler = plugin.pluginProfiler();
        if (!profiler.isRunning()) {
            sender.sendMessage(msg("plugins.disabled"));
            return;
        }

        PluginReport report = profiler.report(plugin.configManager().profilerWindowSeconds());
        if (report.isEmpty()) {
            sender.sendMessage(msg("plugins.nothing-measured"));
        } else {
            sender.sendMessage(msg("plugins.window",
                    "seconds", String.format(Locale.ROOT, "%.0f", report.windowSeconds()),
                    "handlers", String.valueOf(profiler.wrappedListeners())));
            int index = 1;
            for (PluginTiming timing : report.top(PLUGINS_SHOWN)) {
                double share = timing.share(report.windowNanos());
                sender.sendMessage(msg("plugins.entry",
                        "index", String.valueOf(index++),
                        "colour", healthColor(share < 0.10D, share < PluginReport.SIGNIFICANT_SHARE).toString(),
                        "plugin", timing.pluginName(),
                        "ms", String.format(Locale.ROOT, "%.0f", timing.totalMs()),
                        "share", String.format(Locale.ROOT, "%.0f", share * 100.0D),
                        "event", timing.worstEvent() == null ? "" : timing.worstEvent()));
            }
            sender.sendMessage(report.explainsLag()
                    ? msg("report.advice", "advice", report.suggestion(plugin.messages()))
                    : msg("plugins.all-clear"));
        }

        // Bukkit gives no way to time scheduled tasks from the outside, so this is a count only.
        List<Map.Entry<String, Integer>> tasks = profiler.pendingTasks().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(PLUGINS_SHOWN)
                .collect(Collectors.toList());
        if (!tasks.isEmpty()) {
            sender.sendMessage(msg("plugins.pending-tasks", "tasks",
                    tasks.stream().map(entry -> entry.getKey() + " " + entry.getValue())
                            .collect(Collectors.joining(", "))));
        }
    }

    /** Prints recent incidents; with SQLite storage this includes ones from before a restart. */
    private void showHistory(CommandSender sender, String label) {
        plugin.alertStore().recent(HISTORY_SHOWN, incidents -> {
            header(sender, msg("command.section.history"));
            if (incidents.isEmpty()) {
                sender.sendMessage(msg("history.empty"));
                return;
            }
            for (StoredIncident incident : incidents) {
                boolean today = Duration.between(incident.timestamp(), Instant.now()).toHours() < 24L;
                DateTimeFormatter format = today ? TIME : DATE_TIME;
                sendLocated(sender, label, msg("history.entry",
                        "time", format.format(incident.timestamp()),
                        "ago", ago(incident.timestamp()),
                        "mspt", String.format(Locale.ROOT, "%.0f", incident.mspt()),
                        "cause", plugin.messages().categoryTitle(incident.category()),
                        "location", incident.world() == null
                                ? "" : msg("history.at", "location", incident.prettyLocation()),
                        "manual", incident.manual() ? msg("history.manual") : ""),
                        incident.world(), incident.blockX(), incident.blockZ(),
                        incident.prettyLocation());
            }
        });
    }

    /**
     * Prints the chunks that keep coming back.
     *
     * <p>This is the difference between a farm somebody built ten minutes ago and one that has
     * been dragging the server down every evening for a week.</p>
     */
    private void showOffenders(CommandSender sender, String label, int days) {
        plugin.alertStore().offenders(days, OFFENDERS_SHOWN, offenders -> {
            header(sender, msg("command.section.offenders", "days", String.valueOf(days)));
            if (offenders.isEmpty()) {
                sender.sendMessage(msg("offenders.empty"));
                return;
            }
            int index = 1;
            for (RepeatOffender offender : offenders) {
                sendLocated(sender, label, msg("offenders.entry",
                        "index", String.valueOf(index++),
                        "colour", (offender.isChronic() ? ChatColor.RED : ChatColor.YELLOW).toString(),
                        "location", offender.prettyLocation(),
                        "hits", String.valueOf(offender.hits()),
                        "total", String.valueOf(offender.outOf()),
                        "worst", String.format(Locale.ROOT, "%.0f", offender.worstMspt()),
                        "ago", ago(offender.lastSeen())),
                        offender.world(), offender.blockX(), offender.blockZ(),
                        offender.prettyLocation());
            }
            RepeatOffender worst = offenders.get(0);
            if (worst.isChronic()) {
                sender.sendMessage(msg("offenders.start-here",
                        "location", worst.prettyLocation(),
                        "x", String.valueOf(worst.blockX()),
                        "z", String.valueOf(worst.blockZ())));
            }
        });
    }

    /** Prints an incident summary: how many, caused by what, and at which hour most often. */
    private void showStats(CommandSender sender, int days) {
        plugin.alertStore().stats(days, stats -> {
            header(sender, msg("command.section.stats", "days", String.valueOf(days)));
            if (stats.total() == 0) {
                sender.sendMessage(msg("stats.empty"));
                return;
            }

            sender.sendMessage(msg("stats.recorded", "count", Plural.incidents(stats.total())));

            LagCategory dominant = stats.dominantCategory();
            if (dominant != null) {
                sender.sendMessage(msg("stats.dominant-cause",
                        "cause", plugin.messages().categoryTitle(dominant),
                        "count", String.valueOf(stats.byCategory().getOrDefault(dominant, 0))));
            }

            int worstHour = stats.worstHour();
            if (worstHour >= 0) {
                sender.sendMessage(msg("stats.worst-hour",
                        "hour", String.format(Locale.ROOT, "%02d", worstHour),
                        "count", Plural.incidents(stats.byHour()[worstHour])));
            }

            StoredIncident worst = stats.worst();
            if (worst != null) {
                sender.sendMessage(msg("stats.worst-moment",
                        "mspt", String.format(Locale.ROOT, "%.0f", worst.mspt()),
                        "when", DATE_TIME.format(worst.timestamp()),
                        "location", worst.prettyLocation()));
            }

            List<String> histogram = stats.hourHistogram();
            if (!histogram.isEmpty()) {
                sender.sendMessage(msg("stats.histogram-header"));
                histogram.forEach(line -> sender.sendMessage(msg("stats.histogram-row", "row", line)));
            }
        });
    }

    /**
     * Takes the sender to a reported location.
     *
     * <p>What the buttons in every listing run. Also typeable, which is the point: the coordinates
     * are in the command, so there is no per-player listing state to keep in step and nothing goes
     * stale when a chunk stops being a problem.</p>
     */
    private void teleport(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(TELEPORT_PERMISSION)) {
            sender.sendMessage(msg("teleport.no-permission"));
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(msg("teleport.players-only"));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(msg("teleport.usage", "label", label));
            return;
        }

        // Parsed from the end, so a world name containing a space still works. Bukkit splits a
        // command on spaces and offers no quoting, and the coordinates are always the last two.
        int x;
        int z;
        try {
            x = Integer.parseInt(args[args.length - 2]);
            z = Integer.parseInt(args[args.length - 1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(msg("teleport.usage", "label", label));
            return;
        }

        String worldName = String.join(" ",
                Arrays.copyOfRange(args, 1, args.length - 2));
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            sender.sendMessage(msg("teleport.unknown-world", "world", worldName));
            return;
        }

        Player player = (Player) sender;
        // This loads the target chunk on the main thread, which is the very thing the plugin warns
        // about elsewhere. It is a deliberate one-off: an admin asked to go and look, and the
        // alternative is making them type coordinates by hand.
        player.teleport(new Location(world, x + 0.5D, safeY(world, player, x, z), z + 0.5D));
        sender.sendMessage(msg("teleport.done", "location", world.getName() + " @ " + x + ", " + z));
    }

    /**
     * Picks a Y to arrive at.
     *
     * <p>The highest block is right almost everywhere and wrong in the Nether, where it is the roof:
     * an admin sent to investigate a farm would land on top of the world, a hundred blocks above
     * whatever they came to see. There, their current height is the better guess.</p>
     */
    private static int safeY(World world, Player player, int x, int z) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            return Math.max(1, Math.min(125, player.getLocation().getBlockY()));
        }
        return world.getHighestBlockYAt(x, z) + 1;
    }

    /**
     * Sends a listing line, with a teleport button when the recipient can use one.
     *
     * <p>Console and anyone without the permission get exactly the line they got before.</p>
     *
     * @param sender   who is being told
     * @param label    the alias the sender typed, so the button works under {@code /ts} too
     * @param line     the finished line
     * @param world    world name, or {@code null} when the incident had no location
     * @param x        block X
     * @param z        block Z
     * @param location readable location for the hover text
     */
    private void sendLocated(CommandSender sender, String label, String line,
                             String world, int x, int z, String location) {
        if (world == null || !(sender instanceof Player) || !sender.hasPermission(TELEPORT_PERMISSION)) {
            sender.sendMessage(line);
            return;
        }
        sender.spigot().sendMessage(TeleportLink.append(line,
                msg("command.teleport-button"),
                msg("command.teleport-hover", "location", location),
                TeleportLink.command(label, world, x, z)));
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
        plugin.configManager().reportOutdatedConfig();
        plugin.messages().reload();
        plugin.tickMonitor().reset();
        // The baseline window length is a setting, so a reload starts it over rather than
        // mixing samples taken under two different window sizes.
        plugin.adaptiveThreshold().reconfigure(plugin.configManager().adaptiveSettings());
        header(sender, msg("command.section.reload"));
        sender.sendMessage(msg("reload.done",
                "threshold", String.format(Locale.ROOT, "%.0f", plugin.configManager().msptThresholdMs()),
                "seconds", String.valueOf(plugin.configManager().sustainedSeconds()),
                "adaptive", plugin.configManager().adaptiveSettings().enabled()
                        ? msg("reload.adaptive-relearning") : ""));
    }

    private String describeDominant(ChunkStat stat) {
        var dominant = stat.dominantEntityType();
        if (dominant == null || dominant.getValue() < 10) {
            return "";
        }
        return msg("report.chunk-dominant",
                "count", String.valueOf(dominant.getValue()),
                "type", dominant.getKey().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private void header(CommandSender sender, String section) {
        sender.sendMessage(msg("command.header", "section", section));
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
        if (minutes < 1L) {
            return duration.getSeconds() + " s";
        }
        if (minutes < 60L) {
            return minutes + " min";
        }
        // Repeat offenders reach back days, where "4310 min" tells nobody anything.
        long hours = duration.toHours();
        return hours < 48L ? hours + " h" : duration.toDays() + " d";
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
        if (args.length == 2 && ("stats".equalsIgnoreCase(args[0]) || "offenders".equalsIgnoreCase(args[0]))) {
            return List.of("1", "7", "30");
        }
        if (args.length == 2 && "tp".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getWorlds().stream()
                    .map(World::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
