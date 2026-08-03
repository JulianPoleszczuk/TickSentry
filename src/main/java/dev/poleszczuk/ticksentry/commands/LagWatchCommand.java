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

/**
 * Obsluga komendy {@code /lagwatch} - podglad kondycji serwera bez wychodzenia z gry.
 */
public final class LagWatchCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "ticksentry.admin";
    private static final List<String> SUBCOMMANDS = List.of("status", "report", "history", "stats", "reload");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    /** Ile incydentow pokazuje {@code /lagwatch history}. */
    private static final int HISTORY_SHOWN = 8;

    /** Domyslny okres analizowany przez {@code /lagwatch stats}. */
    private static final int DEFAULT_STATS_DAYS = 7;

    private final TickSentryPlugin plugin;

    /**
     * @param plugin instancja pluginu, z ktorej komenda czerpie stan
     */
    public LagWatchCommand(TickSentryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "Brak uprawnien.");
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> showStatus(sender);
            case "report" -> showReport(sender, args.length > 1 && "discord".equalsIgnoreCase(args[1]));
            case "history" -> showHistory(sender);
            case "stats" -> showStats(sender, parseDays(args));
            case "reload" -> reload(sender);
            default -> sender.sendMessage(ChatColor.RED + "Uzycie: /" + label + " <status|report|history|stats|reload>");
        }
        return true;
    }

    /** Wypisuje biezace odczyty monitora. */
    private void showStatus(CommandSender sender) {
        TickMonitor monitor = plugin.tickMonitor();
        double mspt = monitor.averageMspt();
        double threshold = plugin.configManager().msptThresholdMs();

        header(sender, "Status");
        sender.sendMessage(ChatColor.GRAY + "Monitoring: " + (monitor.isRunning()
                ? ChatColor.GREEN + "aktywny" : ChatColor.RED + "zatrzymany"));
        sender.sendMessage(ChatColor.GRAY + "TPS: " + healthColor(monitor.tps() >= 19.0D, monitor.tps() >= 17.0D)
                + String.format(Locale.ROOT, "%.2f", monitor.tps()) + ChatColor.DARK_GRAY + " / 20");
        sender.sendMessage(ChatColor.GRAY + "Czas ticku: " + healthColor(mspt <= threshold * 0.6D, mspt <= threshold)
                + String.format(Locale.ROOT, "%.1f ms", mspt)
                + ChatColor.DARK_GRAY + " (prog: " + String.format(Locale.ROOT, "%.0f ms", threshold) + ")");
        sender.sendMessage(ChatColor.GRAY + "Najdluzsza zwiecha w oknie: " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%.0f ms", monitor.peakIntervalMs()));

        long breach = monitor.currentBreachSeconds();
        if (breach > 0L) {
            sender.sendMessage(ChatColor.RED + "Prog przekroczony od " + breach + " s (alert po "
                    + plugin.configManager().sustainedSeconds() + " s).");
        }
        long cooldown = monitor.alertCooldownRemainingSeconds();
        if (cooldown > 0L) {
            sender.sendMessage(ChatColor.GRAY + "Kolejny alert mozliwy za " + ChatColor.WHITE + cooldown + " s" + ChatColor.GRAY + ".");
        }
        sender.sendMessage(ChatColor.GRAY + "Discord: " + (plugin.configManager().discordEnabled()
                ? ChatColor.GREEN + "skonfigurowany" : ChatColor.YELLOW + "wylaczony lub brak webhooka"));

        String spark = plugin.sparkBridge().summary();
        sender.sendMessage(ChatColor.GRAY + "Spark: " + (spark == null
                ? ChatColor.DARK_GRAY + "niedostepny (uzywam wlasnych pomiarow)"
                : ChatColor.WHITE + spark));
        sender.sendMessage(ChatColor.GRAY + "Historia: " + ChatColor.WHITE + plugin.alertStore().describe()
                + ChatColor.DARK_GRAY + " (" + Plural.incidents(plugin.incidentsLast24h()) + " w ostatniej dobie)");
    }

    /** Wymusza skan i wypisuje wynik w czacie; opcjonalnie wysyla go tez na Discorda. */
    private void showReport(CommandSender sender, boolean alsoDiscord) {
        boolean started = plugin.runScan(true, event -> printReport(sender, event, alsoDiscord));
        if (!started) {
            sender.sendMessage(ChatColor.YELLOW + "Skan juz trwa - wynik pojawi sie za chwile.");
        }
    }

    /** Wypisuje gotowy raport; wywolywane po zakonczeniu skanu rozlozonego na ticki. */
    private void printReport(CommandSender sender, LagEvent event, boolean alsoDiscord) {
        plugin.recordManual(event);

        header(sender, "Raport");
        sender.sendMessage(ChatColor.GRAY + "TPS " + ChatColor.WHITE + String.format(Locale.ROOT, "%.2f", event.tps())
                + ChatColor.GRAY + ", czas ticku " + ChatColor.WHITE + String.format(Locale.ROOT, "%.1f ms", event.averageMspt())
                + ChatColor.GRAY + ", przeskanowano " + ChatColor.WHITE + event.loadedChunks() + ChatColor.GRAY
                + " chunkow (" + event.totalEntities() + " encji).");

        if (event.topChunks().isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + "Zaden chunk sie nie wyroznia - swiat gry wyglada spokojnie.");
        } else {
            sender.sendMessage(ChatColor.GRAY + "Prawdopodobna przyczyna: " + ChatColor.AQUA + event.category().title());
            int index = 1;
            for (ChunkStat stat : event.topChunks()) {
                sender.sendMessage(ChatColor.DARK_GRAY + " " + index++ + ". " + ChatColor.WHITE + stat.prettyLocation()
                        + ChatColor.GRAY + " - " + stat.entityCount() + " encji, "
                        + stat.tileEntityCount() + " block-entity" + describeDominant(stat));
            }
            sender.sendMessage(ChatColor.YELLOW + event.suggestedAction());
        }
        if (event.sparkSummary() != null) {
            sender.sendMessage(ChatColor.DARK_GRAY + event.sparkSummary());
        }

        if (alsoDiscord) {
            if (plugin.configManager().discordEnabled()) {
                plugin.webhook().sendLagAlert(event);
                sender.sendMessage(ChatColor.GRAY + "Raport wyslany takze na Discorda.");
            } else {
                sender.sendMessage(ChatColor.RED + "Discord jest wylaczony albo webhook-url jest pusty.");
            }
        }
    }

    /** Wypisuje ostatnie incydenty; przy skladzie SQLite obejmuje takze te sprzed restartu. */
    private void showHistory(CommandSender sender) {
        plugin.alertStore().recent(HISTORY_SHOWN, incidents -> {
            header(sender, "Historia");
            if (incidents.isEmpty()) {
                sender.sendMessage(ChatColor.GREEN + "Nie zapisano jeszcze zadnego incydentu.");
                return;
            }
            for (StoredIncident incident : incidents) {
                boolean today = Duration.between(incident.timestamp(), Instant.now()).toHours() < 24L;
                DateTimeFormatter format = today ? TIME : DATE_TIME;
                sender.sendMessage(ChatColor.DARK_GRAY + format.format(incident.timestamp()) + ChatColor.GRAY
                        + " (" + ago(incident.timestamp()) + " temu) " + ChatColor.WHITE
                        + String.format(Locale.ROOT, "%.0f ms", incident.mspt())
                        + ChatColor.GRAY + " - " + incident.category().title()
                        + (incident.world() == null ? "" : ChatColor.DARK_GRAY + " @ " + incident.prettyLocation())
                        + (incident.manual() ? ChatColor.DARK_GRAY + " [recznie]" : ""));
            }
        });
    }

    /** Wypisuje podsumowanie incydentow: ile, przez co i o ktorej godzinie najczesciej. */
    private void showStats(CommandSender sender, int days) {
        plugin.alertStore().stats(days, stats -> {
            header(sender, "Statystyki (" + days + " dni)");
            if (stats.total() == 0) {
                sender.sendMessage(ChatColor.GREEN + "Brak incydentow w tym okresie - serwer trzymal sie dobrze.");
                return;
            }

            sender.sendMessage(ChatColor.GRAY + "Zapisano: " + ChatColor.WHITE + Plural.incidents(stats.total()));

            LagCategory dominant = stats.dominantCategory();
            if (dominant != null) {
                sender.sendMessage(ChatColor.GRAY + "Najczestsza przyczyna: " + ChatColor.AQUA + dominant.title()
                        + ChatColor.DARK_GRAY + " (" + stats.byCategory().getOrDefault(dominant, 0) + "x)");
            }

            int worstHour = stats.worstHour();
            if (worstHour >= 0) {
                sender.sendMessage(ChatColor.GRAY + "Najgorsza pora: " + ChatColor.WHITE
                        + String.format("%02d:00-%02d:59", worstHour, worstHour)
                        + ChatColor.DARK_GRAY + " (" + Plural.incidents(stats.byHour()[worstHour]) + ")");
            }

            StoredIncident worst = stats.worst();
            if (worst != null) {
                sender.sendMessage(ChatColor.GRAY + "Najciezszy moment: " + ChatColor.WHITE
                        + String.format(Locale.ROOT, "%.0f ms", worst.mspt()) + ChatColor.DARK_GRAY
                        + " " + DATE_TIME.format(worst.timestamp()) + " - " + worst.prettyLocation());
            }

            List<String> histogram = stats.hourHistogram();
            if (!histogram.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Rozklad w ciagu doby:");
                histogram.forEach(line -> sender.sendMessage(ChatColor.DARK_GRAY + " " + line));
            }
        });
    }

    /** Czyta opcjonalna liczbe dni z argumentow komendy. */
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

    /** Przeladowuje config i resetuje okno pomiarowe monitora. */
    private void reload(CommandSender sender) {
        plugin.configManager().reload();
        plugin.tickMonitor().reset();
        header(sender, "Reload");
        sender.sendMessage(ChatColor.GREEN + "Konfiguracja przeladowana. Prog: "
                + String.format(Locale.ROOT, "%.0f ms", plugin.configManager().msptThresholdMs())
                + " przez " + plugin.configManager().sustainedSeconds() + " s.");
    }

    private static String describeDominant(ChunkStat stat) {
        var dominant = stat.dominantEntityType();
        if (dominant == null || dominant.getValue() < 10) {
            return "";
        }
        return ChatColor.DARK_GRAY + " (glownie " + dominant.getValue() + "x "
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
            return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(prefix)).toList();
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
