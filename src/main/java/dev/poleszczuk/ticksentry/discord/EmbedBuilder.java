package dev.poleszczuk.ticksentry.discord;

import dev.poleszczuk.ticksentry.util.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a Discord embed as JSON without any serialisation library.
 *
 * <p>The set of supported fields is deliberately minimal - just what a TickSentry alert needs.
 * Every value goes through {@link #escape(String)}, so content coming from the game (world names,
 * mob types) cannot break the payload.</p>
 */
public final class EmbedBuilder {

    /** Discord rejects an embed whose description exceeds 4096 characters. */
    private static final int MAX_DESCRIPTION = 4000;

    /** Length limit for the value of a single embed field. */
    private static final int MAX_FIELD_VALUE = 1000;

    private String title;
    private String description;
    private Integer color;
    private String footer;
    private Instant timestamp;
    private final List<String> fields = new ArrayList<>();

    /**
     * @param title embed heading
     * @return this builder
     */
    public EmbedBuilder title(String title) {
        this.title = title;
        return this;
    }

    /**
     * @param description text below the heading
     * @return this builder
     */
    public EmbedBuilder description(String description) {
        this.description = truncate(description, MAX_DESCRIPTION);
        return this;
    }

    /**
     * @param color embed side bar colour as an RGB number (e.g. 0xE74C3C)
     * @return this builder
     */
    public EmbedBuilder color(int color) {
        this.color = color;
        return this;
    }

    /**
     * Adds an embed field.
     *
     * @param name   field name
     * @param value  field content
     * @param inline whether the field sits next to the previous one
     * @return this builder
     */
    public EmbedBuilder field(String name, String value, boolean inline) {
        fields.add("{\"name\":\"" + escape(name) + "\",\"value\":\""
                + escape(truncate(value, MAX_FIELD_VALUE)) + "\",\"inline\":" + inline + "}");
        return this;
    }

    /**
     * @param footer embed footer
     * @return this builder
     */
    public EmbedBuilder footer(String footer) {
        this.footer = footer;
        return this;
    }

    /**
     * @param timestamp timestamp Discord shows next to the footer
     * @return this builder
     */
    public EmbedBuilder timestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    /**
     * Serialises the embed to JSON.
     *
     * @return JSON object ready to place inside the {@code embeds} array
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
     * Escapes text according to JSON rules.
     *
     * @param text input text, may be {@code null}
     * @return text safe to place between quotes
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
