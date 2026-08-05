package dev.poleszczuk.ticksentry.monitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Whether a plugin now costs more than it used to.
 *
 * <p>This is the one question spark cannot answer. A profiler tells you what is expensive <em>right
 * now</em>; it has no memory of last week, so it cannot tell an expensive plugin apart from a plugin
 * that has become expensive. The second is far more actionable, because something changed and the
 * change is probably an update.</p>
 *
 * <p>The comparison has to survive one obvious objection: handler time scales with how busy the
 * server is. Twice the players means roughly twice the events, so a plugin measured at forty players
 * will always look worse than the same plugin measured at five, and reporting that as a regression
 * would be noise. So only samples taken at a <b>comparable player count</b> are compared, and when
 * there are not enough of those the honest answer is that nothing can be said yet.</p>
 *
 * <p>The baseline is a median, not a mean: one bad afternoon should not become the new normal, which
 * is the same reasoning as {@link AdaptiveThreshold}.</p>
 *
 * <p>Pure and fully testable - no Bukkit, no database, no clock.</p>
 */
public final class PluginBaseline {

    /**
     * How many comparable samples the baseline needs.
     *
     * <p>Below this a "3x increase" is as likely to be two quiet measurements as a real change, and
     * a monitoring plugin that cries wolf gets turned off.</p>
     */
    public static final int MIN_SAMPLES = 8;

    /** How many times its usual cost a plugin has to reach before this is worth saying. */
    public static final double SIGNIFICANT_RATIO = 2.0D;

    /**
     * Below this share of the window, a multiple means nothing.
     *
     * <p>A plugin going from 0.01% to 0.05% of tick time has quintupled and still costs nothing.
     * Reporting it would bury the one case that matters.</p>
     */
    public static final double MINIMUM_SHARE = 0.005D;

    /** How far a sample's player count may differ and still be considered comparable. */
    private static final double PLAYER_TOLERANCE = 0.5D;

    /** Player counts this close together are comparable regardless of ratio. */
    private static final int PLAYER_FLOOR = 2;

    private PluginBaseline() {
    }

    /** One past measurement of one plugin. */
    public static final class Sample {

        private final double share;
        private final int players;

        /**
         * @param share   fraction of the measured window this plugin accounted for
         * @param players how many players were online when it was taken
         */
        public Sample(double share, int players) {
            this.share = share;
            this.players = Math.max(0, players);
        }

        /** @return fraction of the window this plugin accounted for */
        public double share() {
            return share;
        }

        /** @return players online when the sample was taken */
        public int players() {
            return players;
        }
    }

    /** A plugin that now costs materially more than it used to. */
    public static final class Regression {

        private final String plugin;
        private final double currentShare;
        private final double baselineShare;
        private final int samples;

        Regression(String plugin, double currentShare, double baselineShare, int samples) {
            this.plugin = plugin;
            this.currentShare = currentShare;
            this.baselineShare = baselineShare;
            this.samples = samples;
        }

        /** @return name of the plugin */
        public String plugin() {
            return plugin;
        }

        /** @return share of the window it accounts for now */
        public double currentShare() {
            return currentShare;
        }

        /** @return share of the window it usually accounted for */
        public double baselineShare() {
            return baselineShare;
        }

        /** @return how many past samples the baseline rests on */
        public int samples() {
            return samples;
        }

        /** @return how many times its usual cost it has reached */
        public double ratio() {
            return baselineShare <= 0.0D ? 0.0D : currentShare / baselineShare;
        }

        /**
         * @return a sentence for an admin
         *
         * <p>Says what changed and by how much, and stops there. It deliberately does not guess
         * why - the plugin cannot see inside another plugin, and "you probably updated it" is the
         * admin's inference to make, not this one's.</p>
         */
        public String describe() {
            return String.format(Locale.ROOT,
                    "%s now takes %.1fx the tick time it usually does (%.1f%% of the window, "
                            + "against %.1f%% across %d earlier readings at a similar player count).",
                    plugin, ratio(), currentShare * 100.0D, baselineShare * 100.0D, samples);
        }

        @Override
        public String toString() {
            return "Regression[" + plugin + ", " + String.format(Locale.ROOT, "%.1fx", ratio()) + "]";
        }
    }

    /**
     * Compares a plugin's current cost against what it usually costs.
     *
     * @param plugin       plugin name
     * @param currentShare share of the window it accounts for right now
     * @param players      players online right now
     * @param history      past samples of this plugin, in any order
     * @return the regression, or {@code null} when there is nothing worth reporting
     */
    public static Regression compare(String plugin, double currentShare, int players,
                                     List<Sample> history) {
        if (currentShare < MINIMUM_SHARE || history == null) {
            return null;
        }

        List<Double> comparable = new ArrayList<>();
        for (Sample sample : history) {
            if (isComparable(players, sample.players())) {
                comparable.add(sample.share());
            }
        }
        if (comparable.size() < MIN_SAMPLES) {
            return null;
        }

        double baseline = median(comparable);
        if (baseline <= 0.0D || currentShare / baseline < SIGNIFICANT_RATIO) {
            return null;
        }
        return new Regression(plugin, currentShare, baseline, comparable.size());
    }

    /**
     * Whether two readings were taken under similar enough conditions to compare.
     *
     * <p>Within a couple of players, or within half again - so five against seven is comparable and
     * five against forty is not. Without this the report would call every busy evening a
     * regression.</p>
     *
     * @param now   players online now
     * @param then  players online when the old sample was taken
     * @return whether the two may be compared
     */
    static boolean isComparable(int now, int then) {
        if (Math.abs(now - then) <= PLAYER_FLOOR) {
            return true;
        }
        int higher = Math.max(now, then);
        int lower = Math.min(now, then);
        return lower > 0 && (double) (higher - lower) / higher <= PLAYER_TOLERANCE;
    }

    /**
     * @param values values in any order
     * @return the middle value, or 0 when there are none
     */
    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double[] sorted = new double[values.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = values.get(i);
        }
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1
                ? sorted[middle]
                : (sorted[middle - 1] + sorted[middle]) / 2.0D;
    }
}
