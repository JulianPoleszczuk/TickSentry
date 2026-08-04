package dev.poleszczuk.ticksentry.config;

/**
 * Substitution of {@code {name}} placeholders in translatable text.
 *
 * <p>Its own class, free of Bukkit, so the substitution rules can be tested directly - the
 * bundle around it cannot be loaded without a server.</p>
 */
public final class Placeholders {

    private Placeholders() {
    }

    /**
     * Replaces every {@code {name}} for which a value was supplied.
     *
     * <p>Unknown placeholders are left as they are rather than blanked. A typo in a translated
     * line should look like a typo, not like a message with a hole in it.</p>
     *
     * @param template     text holding placeholders, may be {@code null}
     * @param replacements alternating placeholder names and values, without the braces
     * @return the filled-in text
     */
    public static String fill(String template, String... replacements) {
        if (template == null || replacements == null || replacements.length < 2) {
            return template;
        }

        // One pass over the template rather than one pass per replacement. Replacing in turn
        // would let a value that happens to contain braces be substituted again by a later
        // replacement, which is how a chat message ends up saying something nobody wrote.
        StringBuilder result = new StringBuilder(template.length() + 32);
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf('{', cursor);
            int close = open < 0 ? -1 : template.indexOf('}', open + 1);
            if (close < 0) {
                result.append(template, cursor, template.length());
                break;
            }
            result.append(template, cursor, open);
            String value = lookup(template.substring(open + 1, close), replacements);
            result.append(value == null ? template.substring(open, close + 1) : value);
            cursor = close + 1;
        }
        return result.toString();
    }

    /**
     * @return the value supplied for a placeholder name, or {@code null} when none was
     */
    private static String lookup(String name, String[] replacements) {
        // An odd trailing argument means a name with no value; ignoring it beats throwing at
        // the moment an admin most wants to read the message.
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            if (name.equals(replacements[i])) {
                return replacements[i + 1] == null ? "" : replacements[i + 1];
            }
        }
        return null;
    }
}
