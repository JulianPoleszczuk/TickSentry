package dev.poleszczuk.ticksentry.commands;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.Arrays;

/**
 * Turns a line of a report into a line with a button that takes you there.
 *
 * <p>Every listing in this plugin ends in coordinates, and until now the only thing an admin could
 * do with them was read them out and type them again. The plugin already knows where the problem is;
 * making that a click is the difference between investigating a farm and deciding not to bother.</p>
 *
 * <p>Built on {@code net.md_5.bungee.api.chat} rather than Adventure, because the jar has to keep
 * working on Spigot, which has no Adventure. {@code CommandSender.spigot().sendMessage} has been on
 * both servers since long before the oldest version this plugin supports.</p>
 *
 * <p>Pure: it takes strings and hands back components, so what a report line turns into can be
 * checked without a server.</p>
 */
final class TeleportLink {

    private TeleportLink() {
    }

    /**
     * Appends a clickable button to a line of legacy-coloured text.
     *
     * @param line    the line as it would have been sent, colour codes already translated
     * @param button  label for the button, for example {@code " [TP]"}
     * @param hover   text shown when the button is hovered
     * @param command command the button runs, with its leading slash
     * @return components ready for {@code sender.spigot().sendMessage}
     */
    @SuppressWarnings("deprecation")
    // The BaseComponent[] hover constructor is deprecated in newer bungeecord-chat in favour of a
    // Content API that does not exist in the 1.16 one this jar is built against. Deprecated and
    // present everywhere beats current and absent on half the supported versions.
    static BaseComponent[] append(String line, String button, String hover, String command) {
        BaseComponent[] text = TextComponent.fromLegacyText(line);

        TextComponent link = new TextComponent(TextComponent.fromLegacyText(button));
        link.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText(hover)));

        BaseComponent[] combined = Arrays.copyOf(text, text.length + 1);
        combined[text.length] = link;
        return combined;
    }

    /**
     * Builds the command the button runs.
     *
     * <p>The coordinates travel in the command rather than being remembered per player. There is no
     * listing state to keep in step with what the player last ran, and nothing goes stale when a
     * chunk stops being a problem.</p>
     *
     * @param label the alias the player actually typed, so {@code /ts} keeps working
     * @param world world name
     * @param x     block X
     * @param z     block Z
     * @return the full command, with its leading slash
     */
    static String command(String label, String world, int x, int z) {
        return "/" + label + " tp " + world + " " + x + " " + z;
    }
}
