package dev.poleszczuk.ticksentry.monitor;

import org.bukkit.Server;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Where a tick time comes from, and why it matters which.
 *
 * <p>{@link Server#getAverageTickTime()} is the reading every server has, and it is <b>already an
 * average</b> of the last hundred ticks. Sampling it once per tick and averaging a hundred of those
 * smooths the same data twice: a window documented as covering 100 ticks ends up trailing reality by
 * ten seconds or more, and a single 400 ms freeze - the thing players actually notice - barely moves
 * it at all.</p>
 *
 * <p>Paper also exposes {@code getTickTimes()}: the raw duration of each of the last hundred ticks,
 * in nanoseconds. That is the honest input, and where it exists this class hands those numbers
 * through untouched. Spigot has no such method, so there the pre-averaged reading is all there is,
 * and {@link #describe()} says as much rather than pretending the numbers mean more than they
 * do.</p>
 *
 * <p>The method is looked up reflectively, like every other optional hook in the plugin: the jar is
 * built against the Paper API but has to keep running on Spigot, where calling it directly would
 * fail with {@code NoSuchMethodError} on the first tick.</p>
 *
 * <p>Which slot of that array holds the newest tick depends on the server's own tick counter, so
 * this class never assumes. It keeps the previous snapshot and treats every slot whose value changed
 * as a new measurement - normally exactly one per tick, occasionally several when the plugin's task
 * was itself delayed, which is precisely when the readings matter most.</p>
 */
public final class TickTimeSource {

    private static final double NANOS_PER_MILLISECOND = 1_000_000.0D;

    private final Server server;

    /** Paper's raw tick times method, or {@code null} on a server that has none. */
    private Method tickTimes;

    private long[] previous;

    /** False until the first snapshot has been taken; see {@link #drainInto(TickSamples)}. */
    private boolean primed;

    /**
     * False once this server has refused to give a tick time at all.
     *
     * <p>Both readings are optional in practice. A fork can throw
     * {@code UnsupportedOperationException} from either, and on a server that ticks regions
     * independently there may be no single tick time to give. When that happens the honest answer is
     * that this plugin cannot monitor here - not a stack trace twenty times a second, and certainly
     * not a comforting number nobody measured.</p>
     */
    private boolean usable = true;

    /**
     * @param server server to read tick times from
     */
    public TickTimeSource(Server server) {
        this(server, findTickTimes(server));
    }

    /**
     * @param server    server to read tick times from
     * @param tickTimes Paper's raw tick times method, or {@code null} to force the Spigot fallback.
     *                  Passed in by tests, which cannot make {@code Server.class} stop declaring a
     *                  method the compile-time API has.
     */
    TickTimeSource(Server server, Method tickTimes) {
        this.server = server;
        this.tickTimes = tickTimes;
    }

    /**
     * Looks for Paper's raw tick times method.
     *
     * @param server the running server
     * @return the method, or {@code null} when this server does not have it
     */
    private static Method findTickTimes(Server server) {
        Class<?>[] candidates = {Server.class, server.getClass()};
        for (Class<?> candidate : candidates) {
            try {
                Method method = candidate.getMethod("getTickTimes");
                if (method.getReturnType() == long[].class) {
                    return method;
                }
            } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
                // Spigot, or a fork that dropped it. The fallback covers both.
            }
        }
        return null;
    }

    /** @return whether real per-tick durations are available, rather than a pre-averaged reading */
    public boolean isRaw() {
        return tickTimes != null;
    }

    /**
     * @return whether this server gives tick times at all. Once it has refused, nothing more is
     *         asked of it and the plugin says monitoring is unavailable rather than inventing a
     *         number.
     */
    public boolean isUsable() {
        return usable;
    }

    /** @return one line for {@code /lagwatch status}, naming which reading is in use */
    public String describe() {
        return isRaw()
                ? "per-tick durations from the server"
                : "the server's own 100-tick average (Spigot has nothing finer)";
    }

    /**
     * Adds every tick measured since the previous call.
     *
     * <p>The first call only takes a snapshot and records nothing. Paper's array holds ticks from
     * before the plugin was enabled, and startup is always slow - draining that history straight
     * into the window would fill it with the server's worst moment and alert on it.</p>
     *
     * @param samples window to append to
     */
    public void drainInto(TickSamples samples) {
        if (!usable) {
            return;
        }
        long[] raw = readRaw();
        if (raw == null) {
            // Spigot: one pre-averaged reading per tick is the best available.
            try {
                samples.add(server.getAverageTickTime());
            } catch (RuntimeException | LinkageError ex) {
                usable = false;
            }
            return;
        }

        if (!primed) {
            previous = raw;
            primed = true;
            return;
        }

        for (int i = 0; i < raw.length; i++) {
            // A tick never takes zero nanoseconds, so a zero is an untouched slot on a server that
            // has not been up for a hundred ticks yet.
            if (raw[i] > 0L && (i >= previous.length || raw[i] != previous[i])) {
                samples.add(raw[i] / NANOS_PER_MILLISECOND);
            }
        }
        previous = raw;
    }

    /** Forgets the previous snapshot, so the next drain starts over rather than reporting a jump. */
    public void reset() {
        primed = false;
        previous = null;
    }

    /**
     * @return a copy of the server's raw tick times in nanoseconds, or {@code null} when this
     *         server does not offer them
     */
    private long[] readRaw() {
        if (tickTimes == null) {
            return null;
        }
        try {
            long[] values = (long[]) tickTimes.invoke(server);
            if (values == null || values.length == 0) {
                // Declared but not answering. Same treatment as a throw: stop asking, and stop
                // claiming this server gives per-tick numbers when it does not.
                tickTimes = null;
                return null;
            }
            // Paper hands back its live array in some versions; copying keeps the previous
            // snapshot from being mutated underneath the comparison, which would report no
            // change at all and quietly measure nothing.
            return Arrays.copyOf(values, values.length);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            // Give up on it for good rather than retrying twenty times a second, and let
            // describe() stop claiming a precision this server is not giving us.
            tickTimes = null;
            return null;
        }
    }
}
