package dev.poleszczuk.ticksentry.util;

/**
 * Comparison of plugin version strings.
 *
 * <p>Pure and tested, because getting this wrong is worse than not checking at all: an admin
 * told to update to a version older than the one they are running will stop trusting the
 * message entirely.</p>
 *
 * <p>Only the numeric parts are compared. A tag like {@code v1.2.0} and a plugin version of
 * {@code 1.2.0} are the same release; anything after the numbers - {@code -SNAPSHOT},
 * {@code -rc1} - is ignored rather than guessed at.</p>
 */
public final class Version {

    private Version() {
    }

    /**
     * @param latest  version offered by the release page
     * @param current version this jar was built as
     * @return whether {@code latest} is genuinely newer
     */
    public static boolean isNewer(String latest, String current) {
        int[] left = parse(latest);
        int[] right = parse(current);
        if (left.length == 0 || right.length == 0) {
            return false;
        }
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    /**
     * Pulls the numeric parts out of a version string.
     *
     * @param version version or tag, may be {@code null}
     * @return the numbers in order, empty when there are none
     */
    static int[] parse(String version) {
        if (version == null) {
            return new int[0];
        }
        String[] parts = version.trim().split("[^0-9]+");
        int count = 0;
        int[] numbers = new int[parts.length];
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                numbers[count++] = Integer.parseInt(part);
            } catch (NumberFormatException ex) {
                // A part too long to be an int is not a version number - stop reading here.
                break;
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(numbers, 0, trimmed, 0, count);
        return trimmed;
    }
}
