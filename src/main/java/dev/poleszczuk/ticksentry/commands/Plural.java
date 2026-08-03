package dev.poleszczuk.ticksentry.commands;

/**
 * Tiny helper for pluralising nouns after a number.
 *
 * <p>Without it messages read like "1 incidents". English only needs a singular and a plural
 * form, so the rule is a one-liner - but keeping it in one place stops the check from being
 * forgotten in half the messages.</p>
 */
public final class Plural {

    private Plural() {
    }

    /**
     * Joins a count with the matching noun form.
     *
     * @param count  the number
     * @param one    form used for exactly 1, e.g. "incident"
     * @param many   form used for every other count, e.g. "incidents"
     * @return the count followed by the right form, e.g. {@code "3 incidents"}
     */
    public static String of(int count, String one, String many) {
        return count + " " + form(count, one, many);
    }

    /**
     * Picks the noun form alone, without the number.
     *
     * @param count the number
     * @param one   form used for exactly 1
     * @param many  form used for every other count
     * @return matching form
     */
    public static String form(int count, String one, String many) {
        return Math.abs(count) == 1 ? one : many;
    }

    /**
     * Shortcut for the case this plugin uses most often.
     *
     * @param count number of incidents
     * @return e.g. {@code "1 incident"} or {@code "12 incidents"}
     */
    public static String incidents(int count) {
        return of(count, "incident", "incidents");
    }
}
