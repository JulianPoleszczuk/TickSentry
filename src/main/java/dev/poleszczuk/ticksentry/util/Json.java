package dev.poleszczuk.ticksentry.util;

/**
 * Minimalne narzedzia do recznego skladania JSON-a.
 *
 * <p>Plugin nie ciagnie za soba biblioteki do serializacji - wysyla tylko kilka prostych
 * struktur (embed Discorda, odpowiedzi dashboardu), wiec wystarczy poprawne escapowanie.</p>
 */
public final class Json {

    private Json() {
    }

    /**
     * Escapuje tekst zgodnie z wymaganiami JSON-a.
     *
     * @param text tekst wejsciowy, moze byc {@code null}
     * @return tekst bezpieczny do wstawienia miedzy cudzyslowy
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
     * Sklada pare {@code "klucz":"wartosc"} z zaescapowana wartoscia tekstowa.
     *
     * @param key   nazwa pola
     * @param value wartosc, moze byc {@code null} (zapisana jako {@code null})
     * @return fragment JSON-a
     */
    public static String field(String key, String value) {
        return value == null
                ? "\"" + escape(key) + "\":null"
                : "\"" + escape(key) + "\":\"" + escape(value) + "\"";
    }

    /**
     * Sklada pare {@code "klucz":liczba} z zaokragleniem do jednego miejsca po przecinku.
     *
     * @param key   nazwa pola
     * @param value wartosc liczbowa
     * @return fragment JSON-a
     */
    public static String field(String key, double value) {
        return "\"" + escape(key) + "\":" + String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /**
     * Sklada pare {@code "klucz":liczba}.
     *
     * @param key   nazwa pola
     * @param value wartosc calkowita
     * @return fragment JSON-a
     */
    public static String field(String key, long value) {
        return "\"" + escape(key) + "\":" + value;
    }

    /**
     * Sklada pare {@code "klucz":true/false}.
     *
     * @param key   nazwa pola
     * @param value wartosc logiczna
     * @return fragment JSON-a
     */
    public static String field(String key, boolean value) {
        return "\"" + escape(key) + "\":" + value;
    }
}
