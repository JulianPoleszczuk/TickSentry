package dev.poleszczuk.ticksentry.util;

/**
 * Bare-minimum helpers for assembling JSON by hand.
 *
 * <p>The plugin does not pull in a serialisation library - it only ever sends a handful of simple
 * structures (a Discord embed, dashboard responses), so correct escaping is all that is needed.</p>
 */
public final class Json {

    private Json() {
    }

    /**
     * Escapes text according to JSON rules.
     *
     * @param text input text, may be {@code null}
     * @return text safe to place between quotes
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Builds a {@code "key":"value"} pair with the value escaped.
     *
     * @param key   field name
     * @param value value, may be {@code null} (written as {@code null})
     * @return JSON fragment
     */
    public static String field(String key, String value) {
        return value == null
                ? "\"" + escape(key) + "\":null"
                : "\"" + escape(key) + "\":\"" + escape(value) + "\"";
    }

    /**
     * Builds a {@code "key":number} pair rounded to one decimal place.
     *
     * @param key   field name
     * @param value numeric value
     * @return JSON fragment
     */
    public static String field(String key, double value) {
        return "\"" + escape(key) + "\":" + String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /**
     * Builds a {@code "key":number} pair.
     *
     * @param key   field name
     * @param value integer value
     * @return JSON fragment
     */
    public static String field(String key, long value) {
        return "\"" + escape(key) + "\":" + value;
    }

    /**
     * Builds a {@code "key":true/false} pair.
     *
     * @param key   field name
     * @param value boolean value
     * @return JSON fragment
     */
    public static String field(String key, boolean value) {
        return "\"" + escape(key) + "\":" + value;
    }
}
