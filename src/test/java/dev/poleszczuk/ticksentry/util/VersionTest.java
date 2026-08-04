package dev.poleszczuk.ticksentry.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    @Test
    void aHigherNumberIsNewer() {
        assertTrue(Version.isNewer("1.1.0", "1.0.0"));
        assertTrue(Version.isNewer("2.0.0", "1.9.9"));
        assertTrue(Version.isNewer("1.0.1", "1.0.0"));
    }

    @Test
    void theSameVersionIsNotAnUpdate() {
        assertFalse(Version.isNewer("1.0.0", "1.0.0"));
        assertFalse(Version.isNewer("v1.0.0", "1.0.0"));
    }

    @Test
    void anOlderReleaseIsNeverOffered() {
        // Telling an admin to "update" to something older costs the message all its credibility.
        assertFalse(Version.isNewer("1.0.0", "1.1.0"));
        assertFalse(Version.isNewer("0.9.0", "1.0.0"));
    }

    @Test
    void aTagPrefixIsIgnored() {
        assertTrue(Version.isNewer("v1.2.0", "1.1.0"));
        assertFalse(Version.isNewer("v1.1.0", "1.2.0"));
    }

    @Test
    void missingPartsCountAsZero() {
        assertTrue(Version.isNewer("1.1", "1.0.9"));
        assertFalse(Version.isNewer("1.0", "1.0.0"));
        assertTrue(Version.isNewer("2", "1.9.9"));
    }

    @Test
    void tenBeatsNineRatherThanLosingToIt() {
        // A string comparison would put "1.9.0" above "1.10.0" - hence comparing numbers.
        assertTrue(Version.isNewer("1.10.0", "1.9.0"));
        assertFalse(Version.isNewer("1.9.0", "1.10.0"));
    }

    @Test
    void suffixesAreIgnoredRatherThanGuessedAt() {
        assertFalse(Version.isNewer("1.0.0-SNAPSHOT", "1.0.0"));
        assertTrue(Version.isNewer("1.1.0-rc1", "1.0.0"));
    }

    @Test
    void nonsenseNeverProducesAnUpdatePrompt() {
        assertFalse(Version.isNewer(null, "1.0.0"));
        assertFalse(Version.isNewer("1.0.0", null));
        assertFalse(Version.isNewer("latest", "1.0.0"));
        assertFalse(Version.isNewer("", "1.0.0"));
    }

    @Test
    void parsingPullsOutTheNumbers() {
        assertArrayEquals(new int[] {1, 2, 3}, Version.parse("v1.2.3"));
        assertArrayEquals(new int[] {1, 0}, Version.parse("1.0-SNAPSHOT"));
        assertArrayEquals(new int[0], Version.parse("nightly"));
        assertArrayEquals(new int[0], Version.parse(null));
    }

    @Test
    void anAbsurdlyLongNumberStopsTheParseRatherThanThrowing() {
        assertEquals(1, Version.parse("1.99999999999999999999.3")[0]);
    }

    @Test
    void theTagIsReadOutOfAGithubResponse() {
        String json = "{\"url\":\"https://api.github.com/x\",\"tag_name\":\"v1.4.2\",\"name\":\"1.4.2\"}";

        assertEquals("v1.4.2", Json.readString(json, "tag_name"));
        assertEquals("1.4.2", Json.readString(json, "name"));
    }

    @Test
    void aResponseWithoutATagIsNotAnUpdate() {
        assertNull(Json.readString("{\"message\":\"Not Found\"}", "tag_name"));
        assertNull(Json.readString("", "tag_name"));
        assertNull(Json.readString(null, "tag_name"));
        assertNull(Json.readString("{\"tag_name\":\"v1\"}", null));
    }

    @Test
    void aFieldNameIsMatchedWholeNotAsAPattern() {
        // "tag.name" must not match "tag_name" - a regex metacharacter in the key is literal.
        assertNull(Json.readString("{\"tag_name\":\"v1.4.2\"}", "tag.name"));
    }
}
