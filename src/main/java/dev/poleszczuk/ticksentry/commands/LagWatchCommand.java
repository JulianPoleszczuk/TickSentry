package dev.poleszczuk.ticksentry.commands;

import dev.poleszczuk.ticksentry.TickSentryPlugin;
import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import dev.poleszczuk.ticksentry.monitor.TickMonitor;
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
    private static final List<String> SUBCOMMANDS = List.of("status", "report", "history", "reload");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Ile incydentow pokazuje {@code /lagwatch history}. */
    private static final int HISTORY_SHOWN = 8;

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
            case "reload" -> reload(sender);
            default -> sender.sendMessage(ChatColor.RED + "Uzycie: /" + label + " <status|report|history|reload>");
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
        if (spark != null) {
            sender.sendMessage(ChatColor.DARK_GRAY + spark);
        }
    }

    /** Wymusza skan i wypisuje wynik w czacie; opcjonalnie wysyla go tez na Discorda. */
    private void showReport(CommandSender sender, boolean alsoDiscord) {
        LagEvent event = plugin.runScan(true);
        plugin.alertHistory().record(event);

        header(sender, "Raport");
        sender.sendMessage(ChatColor.GRAY + "TPS " + ChatColor.WHITE + String.format(Locale.ROOT, "%.2f", event.tps())
                + ChatColor.GRAY + ", czas ticku " + ChatColor.WHITE + String.format(Locale.ROOT, "%.1f ms", event.averageMspt())
                + ChatColor.GRAY + ", przeskanowano " + ChatColor.WHITE + event.loadedChunks() + ChatColor.GRAY
                + " chunkow (" + event.totalEntities() + " encji) w " + event.scanDurationMs() + " ms.");

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

    /** Wypisuje ostatnie incydenty z tej sesji serwera. */
    private void showHistory(CommandSender sender) {
        List<LagEvent> history = plugin.alertHistory().recent(HISTORY_SHOWN);
        header(sender, "Historia");
        if (history.isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + "Od startu serwera nie bylo zadnego incydentu.");
            return;
        }
        for (LagEvent event : history) {
            ChunkStat primary = event.primaryChunk();
            sender.sendMessage(ChatColor.DARK_GRAY + TIME.format(event.timestamp()) + ChatColor.GRAY
                    + " (" + ago(event.timestamp()) + " temu) " + ChatColor.WHITE
                    + String.format(Locale.ROOT, "%.0f ms", event.averageMspt())
                    + ChatColor.GRAY + " - " + event.category().title()
                    + (primary == null ? "" : ChatColor.DARK_GRAY + " @ " + primary.prettyLocation())
                    + (event.manual() ? ChatColor.DARK_GRAY + " [recznie]" : ""));
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
        return List.of();
    }
}
