package dev.poleszczuk.ticksentry.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaceholdersTest {

    @Test
    void aPlaceholderIsReplacedWithItsValue() {
        assertEquals("Cause: Mob farm",
                Placeholders.fill("Cause: {cause}", "cause", "Mob farm"));
    }

    @Test
    void severalPlaceholdersAreFilledInOnePass() {
        assertEquals("world @ 100, 200 in 30 s",
                Placeholders.fill("{location} in {seconds} s",
                        "location", "world @ 100, 200", "seconds", "30"));
    }

    @Test
    void theSamePlaceholderCanAppearTwice() {
        assertEquals("Steve, really Steve",
                Placeholders.fill("{who}, really {who}", "who", "Steve"));
    }

    @Test
    void anUnknownPlaceholderIsLeftAloneRatherThanBlanked() {
        // A typo in a translation should look like a typo, not like a message with a hole in it.
        assertEquals("Hello {name}", Placeholders.fill("Hello {name}", "other", "x"));
    }

    @Test
    void aNullValueBecomesNothing() {
        assertEquals("Cause: ", Placeholders.fill("Cause: {cause}", "cause", null));
    }

    @Test
    void aValueThatLooksLikeAPlaceholderIsNotSubstitutedAgain() {
        // A player called {seconds} must not swallow the next replacement.
        assertEquals("{seconds} in 30 s",
                Placeholders.fill("{who} in {seconds} s", "who", "{seconds}", "seconds", "30"));
    }

    @Test
    void oddArgumentsAreIgnoredRatherThanThrowing() {
        assertEquals("a X {b}", Placeholders.fill("a {a} {b}", "a", "X", "b"));
    }

    @Test
    void nothingToDoIsSafe() {
        assertEquals("plain", Placeholders.fill("plain"));
        assertEquals("plain", Placeholders.fill("plain", "a", "b"));
        assertNull(Placeholders.fill(null, "a", "b"));
        assertEquals("x", Placeholders.fill("x", (String[]) null));
    }
}
