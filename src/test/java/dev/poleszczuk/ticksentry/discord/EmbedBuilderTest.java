package dev.poleszczuk.ticksentry.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedBuilderTest {

    @Test
    @DisplayName("Cudzyslowy i nowe linie sa escapowane")
    void escapesSpecialCharacters() {
        assertEquals("nazwa \\\"swiata\\\"", EmbedBuilder.escape("nazwa \"swiata\""));
        assertEquals("a\\nb", EmbedBuilder.escape("a\nb"));
        assertEquals("C:\\\\serwer", EmbedBuilder.escape("C:\\serwer"));
        assertEquals("", EmbedBuilder.escape(null));
    }

    @Test
    @DisplayName("Znaki sterujace ida jako sekwencje unicode")
    void escapesControlCharacters() {
        assertEquals("\\u0007", EmbedBuilder.escape("\u0007"));
    }

    @Test
    @DisplayName("Pusty embed to poprawny obiekt JSON")
    void emptyEmbedIsValidJson() {
        assertEquals("{}", new EmbedBuilder().toJson());
    }

    @Test
    @DisplayName("Pola sa skladane w tablice bez zbednych przecinkow")
    void buildsFullEmbed() {
        String json = new EmbedBuilder()
                .title("Uwaga")
                .description("Serwer laguje")
                .color(0xE74C3C)
                .field("TPS", "12.4", true)
                .field("Gdzie", "world @ 8, 8", true)
                .footer("TickSentry")
                .timestamp(Instant.parse("2026-08-03T10:15:30Z"))
                .toJson();

        assertEquals("{\"title\":\"Uwaga\",\"description\":\"Serwer laguje\",\"color\":15158332,"
                + "\"fields\":[{\"name\":\"TPS\",\"value\":\"12.4\",\"inline\":true},"
                + "{\"name\":\"Gdzie\",\"value\":\"world @ 8, 8\",\"inline\":true}],"
                + "\"footer\":{\"text\":\"TickSentry\"},"
                + "\"timestamp\":\"2026-08-03T10:15:30Z\"}", json);
    }

    @Test
    @DisplayName("Zbyt dlugi opis jest przycinany do limitu Discorda")
    void truncatesLongDescription() {
        String json = new EmbedBuilder().description("x".repeat(5000)).toJson();
        assertTrue(json.length() < 4100, "opis powinien zostac przyciety");
        assertTrue(json.contains("..."), "przyciety tekst powinien konczyc sie wielokropkiem");
    }
}
