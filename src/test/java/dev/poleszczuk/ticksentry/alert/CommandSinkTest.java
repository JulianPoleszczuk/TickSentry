package dev.poleszczuk.ticksentry.alert;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Turning a configured line into a command.
 *
 * <p>These commands run as the console, unrestricted, so what gets substituted into them matters more
 * than usual. A world name is chosen by whoever set the server up, and text from outside should not be
 * able to grow the command a second line.</p>
 */
class CommandSinkTest {

    private static final Map<String, String> VALUES = Map.of(
            "cause", "Mob farm",
            "world", "world_nether",
            "x", "1608",
            "z", "-32",
            "tps", "18.3",
            "mspt", "80",
            "duration", "");

    @Test
    void placeholdersAreFilledIn() {
        assertEquals("say Lag at world_nether 1608 -32 (Mob farm)",
                CommandSink.substitute("say Lag at {world} {x} {z} ({cause})", VALUES));
    }

    @Test
    void aLeadingSlashIsAcceptedAndRemoved() {
        // dispatchCommand wants the command without one, but everybody types it with one.
        assertEquals("say hello", CommandSink.substitute("/say hello", VALUES));
        assertEquals("say hello", CommandSink.substitute("  /say hello  ", VALUES));
    }

    @Test
    void aNewlineInASubstitutedValueCannotGrowASecondCommand() {
        Map<String, String> hostile = Map.of("world", "world\nop somebody");

        String command = CommandSink.substitute("say in {world}", hostile);

        assertFalse(command.contains("\n"));
        assertEquals("say in worldop somebody", command);
    }

    @Test
    void controlCharactersAreStripped() {
        Map<String, String> odd = Map.of("cause", "Mobfarm");

        assertEquals("say Mobfarm", CommandSink.substitute("say {cause}", odd));
    }

    @Test
    void anUnknownPlaceholderIsLeftAloneRatherThanBlanked() {
        // Leaving it visible is how an admin finds out they mistyped it. Silently blanking would
        // produce a command that looks like it worked.
        assertEquals("say {nonsense}", CommandSink.substitute("say {nonsense}", VALUES));
    }

    @Test
    void aPlaceholderWithNoValueLeavesAGap() {
        // {duration} is empty on an incident, and the command still has to be runnable.
        assertEquals("say lasted s", CommandSink.substitute("say lasted {duration} s", VALUES));
    }

    @Test
    void aBlankLineProducesNothingToRun() {
        assertEquals("", CommandSink.substitute("   ", VALUES));
        assertEquals("", CommandSink.substitute("/", VALUES));
    }
}
