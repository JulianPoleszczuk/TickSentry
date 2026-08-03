package dev.poleszczuk.ticksentry.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedBuilderTest {

    @Test
    @DisplayName("Quotes and newlines are escaped")
    void escapesSpecialCharacters() {
        assertEquals("the \\\"world\\\" name", EmbedBuilder.escape("the \"world\" name"));
        assertEquals("a\\nb", EmbedBuilder.escape("a\nb"));
        assertEquals("C:\\\\server", EmbedBuilder.escape("C:\\server"));
        assertEquals("", EmbedBuilder.escape(null));
    }

    @Test
    @DisplayName("Control characters become unicode escapes")
    void escapesControlCharacters() {
        assertEquals("\\u0007", EmbedBuilder.escape("\u0007"));
    }

    @Test
    @DisplayName("An empty embed is still valid JSON")
    void emptyEmbedIsValidJson() {
        assertEquals("{}", new EmbedBuilder().toJson());
    }

    @Test
    @DisplayName("Fields are joined into an array without stray commas")
    void buildsFullEmbed() {
        String json = new EmbedBuilder()
                .title("Heads up")
                .description("The server is lagging")
                .color(0xE74C3C)
                .field("TPS", "12.4", true)
                .field("Where", "world @ 8, 8", true)
                .footer("TickSentry")
                .timestamp(Instant.parse("2026-08-03T10:15:30Z"))
                .toJson();

        assertEquals("{\"title\":\"Heads up\",\"description\":\"The server is lagging\",\"color\":15158332,"
                + "\"fields\":[{\"name\":\"TPS\",\"value\":\"12.4\",\"inline\":true},"
                + "{\"name\":\"Where\",\"value\":\"world @ 8, 8\",\"inline\":true}],"
                + "\"footer\":{\"text\":\"TickSentry\"},"
                + "\"timestamp\":\"2026-08-03T10:15:30Z\"}", json);
    }

    @Test
    @DisplayName("Incident duration is described in plain words")
    void formatsDuration() {
        assertEquals("45 s", DiscordWebhookClient.humanDuration(45L));
        assertEquals("2 min", DiscordWebhookClient.humanDuration(120L));
        assertEquals("4 min 12 s", DiscordWebhookClient.humanDuration(252L));
        assertEquals("1 h 5 min", DiscordWebhookClient.humanDuration(3900L));
    }

    @Test
    @DisplayName("An over-long description is trimmed to the Discord limit")
    void truncatesLongDescription() {
        String json = new EmbedBuilder().description("x".repeat(5000)).toJson();
        assertTrue(json.length() < 4100, "the description should be trimmed");
        assertTrue(json.contains("..."), "trimmed text should end with an ellipsis");
    }
}
