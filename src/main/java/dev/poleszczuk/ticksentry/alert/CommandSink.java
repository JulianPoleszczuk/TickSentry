package dev.poleszczuk.ticksentry.alert;

import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs console commands when something happens.
 *
 * <p>The escape hatch. However many destinations get built in, somebody will want the one that was
 * not - a Telegram bridge, a bossbar plugin, a paging service with its own command. All of those can
 * be reached with a command, so this turns "please add support for X" into a config line.</p>
 *
 * <p>Commands run <b>as the console</b>, unrestricted, which is why the whole section is off by
 * default and says so in {@code config.yml}. An admin who fills it in has decided to run those
 * commands; the plugin's job is to substitute the numbers and not to smuggle anything else in.</p>
 *
 * <p>Every substituted value is stripped of newlines and control characters. Not because a chunk
 * category can contain one, but because a world name is chosen by whoever set the server up and a
 * command assembled from outside text should not be able to grow a second line.</p>
 */
public final class CommandSink implements AlertSink {

    private final Plugin plugin;
    private final BooleanSupplier enabled;
    private final Supplier<List<String>> onIncident;
    private final Supplier<List<String>> onRecovery;

    /**
     * @param plugin     plugin instance (server and logging)
     * @param enabled    whether this is switched on, read fresh so a reload takes effect
     * @param onIncident commands to run when an incident is reported
     * @param onRecovery commands to run when the server recovers
     */
    public CommandSink(Plugin plugin, BooleanSupplier enabled,
                       Supplier<List<String>> onIncident, Supplier<List<String>> onRecovery) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.onIncident = onIncident;
        this.onRecovery = onRecovery;
    }

    @Override
    public void incident(LagEvent event) {
        Map<String, String> values = new HashMap<>();
        values.put("cause", event.category().title());
        values.put("tps", String.format(Locale.ROOT, "%.1f", event.tps()));
        values.put("mspt", String.format(Locale.ROOT, "%.0f", event.averageMspt()));
        ChunkStat primary = event.primaryChunk();
        // Empty rather than absent when no chunk was to blame, so a command with {world} in it runs
        // with a gap instead of printing the placeholder back at the admin.
        values.put("world", primary == null ? "" : primary.worldName());
        values.put("x", primary == null ? "" : String.valueOf(primary.blockX()));
        values.put("z", primary == null ? "" : String.valueOf(primary.blockZ()));
        values.put("duration", "");
        run(onIncident.get(), values);
    }

    @Override
    public void recovery(long durationSeconds, double tps, double mspt) {
        Map<String, String> values = new HashMap<>();
        values.put("duration", String.valueOf(durationSeconds));
        values.put("tps", String.format(Locale.ROOT, "%.1f", tps));
        values.put("mspt", String.format(Locale.ROOT, "%.0f", mspt));
        values.put("cause", "");
        values.put("world", "");
        values.put("x", "");
        values.put("z", "");
        run(onRecovery.get(), values);
    }

    @Override
    public void remediation(String summary) {
        // Nothing here on purpose. The clean-up already announces itself to the console, to admins in
        // game and to every other sink; letting it also trigger commands would mean one config
        // mistake could have the plugin react to its own removals in a loop.
    }

    @Override
    public boolean isConfigured() {
        return enabled.getAsBoolean()
                && (!onIncident.get().isEmpty() || !onRecovery.get().isEmpty());
    }

    @Override
    public String name() {
        return "commands";
    }

    /**
     * Fills in the placeholders and dispatches each command.
     *
     * @param commands raw command lines from the configuration
     * @param values   placeholder name to value
     */
    private void run(List<String> commands, Map<String, String> values) {
        for (String template : commands) {
            String command = substitute(template, values);
            if (command.isEmpty()) {
                continue;
            }
            try {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
            } catch (RuntimeException ex) {
                // A command that fails is the admin's to fix, and must not stop the ones after it or
                // interfere with the alert that triggered them.
                plugin.getLogger().warning("The configured command '" + command + "' failed: " + ex);
            }
        }
    }

    /**
     * Replaces {@code {name}} placeholders, sanitising what goes in.
     *
     * @param template raw line from the configuration
     * @param values   placeholder name to value
     * @return the finished command, with any leading slash removed
     */
    static String substitute(String template, Map<String, String> values) {
        String command = template.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        for (Map.Entry<String, String> value : values.entrySet()) {
            command = command.replace("{" + value.getKey() + "}", clean(value.getValue()));
        }
        // Runs of spaces are collapsed, because a placeholder with no value would otherwise leave one
        // behind and Bukkit splits arguments on spaces - "say lasted  s" hands a command an empty
        // argument it never asked for. The cost is that a deliberate double space in a message is
        // lost, which is a cosmetic price for not producing a subtly broken command.
        return command.replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * @param value text about to be pasted into a command
     * @return the same text with newlines and control characters removed
     */
    private static String clean(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character >= 0x20 && character != 0x7F) {
                cleaned.append(character);
            }
        }
        return cleaned.toString();
    }
}
