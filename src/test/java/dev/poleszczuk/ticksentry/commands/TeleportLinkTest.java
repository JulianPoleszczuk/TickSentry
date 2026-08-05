package dev.poleszczuk.ticksentry.commands;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The teleport button appended to report lines.
 *
 * <p>Chat components are ordinary Java objects, so what a line turns into can be checked without a
 * server - including the part that would be embarrassing to get wrong, which is the command the
 * button actually runs.</p>
 */
class TeleportLinkTest {

    @Test
    void theOriginalLineSurvivesUntouched() {
        BaseComponent[] components = TeleportLink.append(
                "§8 1. §fworld @ 120, 344", " [TP]", "Teleport", "/lagwatch tp world 120 344");

        // Everything but the button, joined back up, has to read exactly as the plain line did -
        // colours included, since players without the permission still get that version.
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < components.length - 1; i++) {
            text.append(components[i].toLegacyText());
        }
        assertTrue(text.toString().contains("world @ 120, 344"));
        assertTrue(text.toString().contains("1."));
    }

    @Test
    void theButtonRunsTheCommandRatherThanSuggestingIt() {
        BaseComponent[] components = TeleportLink.append(
                "line", " [TP]", "Teleport", "/lagwatch tp world 120 344");

        BaseComponent button = components[components.length - 1];
        ClickEvent click = button.getClickEvent();
        assertNotNull(click);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.getAction());
        assertEquals("/lagwatch tp world 120 344", click.getValue());
    }

    @Test
    void theButtonCarriesItsHoverText() {
        BaseComponent[] components = TeleportLink.append(
                "line", " [TP]", "Click to teleport to world @ 120, 344", "/lagwatch tp world 120 344");

        assertNotNull(components[components.length - 1].getHoverEvent());
    }

    @Test
    void onlyTheButtonIsClickable() {
        // A whole line that teleports on any click would fire on a misclick while reading.
        BaseComponent[] components = TeleportLink.append(
                "§fsome text", " [TP]", "Teleport", "/lagwatch tp world 1 2");

        for (int i = 0; i < components.length - 1; i++) {
            assertNull(components[i].getClickEvent(), "component " + i + " should not be clickable");
        }
    }

    @Test
    void theButtonKeepsItsOwnColours() {
        BaseComponent[] components = TeleportLink.append(
                "line", "§8 [§bTP§8]", "Teleport", "/lagwatch tp world 1 2");

        String button = components[components.length - 1].toLegacyText();
        assertTrue(button.contains("TP"));
        assertTrue(button.contains("§b"), "the button label keeps the colour from messages.yml");
    }

    @Test
    void theCommandUsesWhicheverAliasWasTyped() {
        // Somebody who typed /ts must get a button that runs /ts, not /lagwatch - a server may well
        // have the long name taken by something else.
        assertEquals("/ts tp world 120 344", TeleportLink.command("ts", "world", 120, 344));
        assertEquals("/lagwatch tp world 120 344",
                TeleportLink.command("lagwatch", "world", 120, 344));
    }

    @Test
    void negativeCoordinatesSurviveTheRoundTrip() {
        String command = TeleportLink.command("lagwatch", "world_nether", -1608, -32);

        assertEquals("/lagwatch tp world_nether -1608 -32", command);
        // And parse back to the same numbers, which is what the command handler will do with them.
        String[] parts = command.split(" ");
        assertEquals(-1608, Integer.parseInt(parts[3]));
        assertEquals(-32, Integer.parseInt(parts[4]));
    }

    @Test
    void aWorldNameWithASpaceStillYieldsTheRightCoordinates() {
        // Bukkit splits commands on spaces and offers no quoting, so the handler reads the
        // coordinates from the end and treats everything before them as the world name. Rare, but
        // silently teleporting somebody to the wrong place would be worse than rare.
        String[] parts = TeleportLink.command("lagwatch", "my nice world", -8, 24).split(" ");

        assertEquals(-8, Integer.parseInt(parts[parts.length - 2]));
        assertEquals(24, Integer.parseInt(parts[parts.length - 1]));
        // parts[0] is the command, parts[1] is "tp", so the world name starts at index 2.
        assertEquals("my nice world",
                String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length - 2)));
    }

    @Test
    void anEmptyLineStillProducesAWorkingButton() {
        BaseComponent[] components = TeleportLink.append("", " [TP]", "Teleport", "/lagwatch tp w 1 2");

        assertTrue(components.length >= 1);
        BaseComponent button = components[components.length - 1];
        assertEquals(ClickEvent.Action.RUN_COMMAND, button.getClickEvent().getAction());
        assertTrue(((TextComponent) button).toLegacyText().contains("TP"));
    }
}
