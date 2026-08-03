package dev.poleszczuk.ticksentry.discord;

import dev.poleszczuk.ticksentry.util.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Buduje JSON embeda Discorda bez zadnej biblioteki do serializacji.
 *
 * <p>Zakres pol jest celowo minimalny - tyle, ile potrzebuje alert TickSentry.
 * Wszystkie wartosci przechodza przez {@link #escape(String)}, wiec tresc z gry
 * (nazwy swiatow, typy mobow) nie zepsuje payloadu.</p>
 */
public final class EmbedBuilder {

    /** Discord odrzuca embed z opisem dluzszym niz 4096 znakow. */
    private static final int MAX_DESCRIPTION = 4000;

    /** Limit dlugosci wartosci pojedynczego pola embeda. */
    private static final int MAX_FIELD_VALUE = 1000;

    private String title;
    private String description;
    private Integer color;
    private String footer;
    private Instant timestamp;
    private final List<String> fields = new ArrayList<>();

    /**
     * @param title naglowek embeda
     * @return ten sam builder
     */
    public EmbedBuilder title(String title) {
        this.title = title;
        return this;
    }

    /**
     * @param description tekst pod naglowkiem
     * @return ten sam builder
     */
    public EmbedBuilder description(String description) {
        this.description = truncate(description, MAX_DESCRIPTION);
        return this;
    }

    /**
     * @param color kolor paska embeda jako liczba RGB (np. 0xE74C3C)
     * @return ten sam builder
     */
    public EmbedBuilder color(int color) {
        this.color = color;
        return this;
    }

    /**
     * Dodaje pole embeda.
     *
     * @param name   nazwa pola
     * @param value  tresc pola
     * @param inline czy pole ma stac obok poprzedniego
     * @return ten sam builder
     */
    public EmbedBuilder field(String name, String value, boolean inline) {
        fields.add("{\"name\":\"" + escape(name) + "\",\"value\":\""
                + escape(truncate(value, MAX_FIELD_VALUE)) + "\",\"inline\":" + inline + "}");
        return this;
    }

    /**
     * @param footer stopka embeda
     * @return ten sam builder
     */
    public EmbedBuilder footer(String footer) {
        this.footer = footer;
        return this;
    }

    /**
     * @param timestamp znacznik czasu pokazywany przez Discorda przy stopce
     * @return ten sam builder
     */
    public EmbedBuilder timestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    /**
     * Serializuje embed do JSON-a.
     *
     * @return obiekt JSON gotowy do wstawienia w tablice {@code embeds}
     */
    public String toJson() {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;

        if (title != null) {
            json.append("\"title\":\"").append(escape(title)).append('"');
            first = false;
        }
        if (description != null) {
            first = comma(json, first);
            json.append("\"description\":\"").append(escape(description)).append('"');
        }
        if (color != null) {
            first = comma(json, first);
            json.append("\"color\":").append(color);
        }
        if (!fields.isEmpty()) {
            first = comma(json, first);
            json.append("\"fields\":[").append(String.join(",", fields)).append(']');
        }
        if (footer != null) {
            first = comma(json, first);
            json.append("\"footer\":{\"text\":\"").append(escape(footer)).append("\"}");
        }
        if (timestamp != null) {
            comma(json, first);
            json.append("\"timestamp\":\"").append(timestamp.toString()).append('"');
        }
        return json.append('}').toString();
    }

    private static boolean comma(StringBuilder json, boolean first) {
        if (!first) {
            json.append(',');
        }
        return false;
    }

    /**
     * Escapuje tekst zgodnie z wymaganiami JSON-a.
     *
     * @param text tekst wejsciowy, moze byc {@code null}
     * @return tekst bezpieczny do wstawienia miedzy cudzyslowy
     */
    public static String escape(String text) {
        return Json.escape(text);
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 3) + "...";
    }
}
