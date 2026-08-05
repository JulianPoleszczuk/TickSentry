package dev.poleszczuk.ticksentry.monitor;

import dev.poleszczuk.ticksentry.util.Scheduler;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures how long every other plugin spends inside its event handlers.
 *
 * <p>This is what lets TickSentry answer the question a chunk scan never can: "which plugin is
 * eating the tick". Bukkit keeps its listeners in {@link HandlerList}, and every one of them is a
 * {@link RegisteredListener} that can be swapped for another. The profiler unregisters each one
 * and registers a thin wrapper in its place, which times the delegate and hands the event
 * straight through. Priority, listener and ignore-cancelled flag are copied over, so ordering and
 * behaviour are unchanged.</p>
 *
 * <p>Only synchronous events are timed. An asynchronous handler runs on its own thread and cannot
 * delay a tick, so counting it would inflate the numbers against wall clock time.</p>
 *
 * <p>Measurements land in per-plugin counters that are rotated into fixed buckets every few
 * seconds. A report then sums the buckets covering the requested window, which is how an alert
 * can say "in the last 30 seconds" rather than "since the server started".</p>
 */
public final class PluginProfiler {

    /** History older than this is dropped, whatever window a caller asks for. */
    private static final long MAX_HISTORY_MILLIS = 300_000L;

    private final Plugin owner;
    private final Scheduler scheduler;

    /** Live counters, written by whichever thread fires the event, reset on every rotation. */
    private final Map<String, Map<String, Counter>> live = new ConcurrentHashMap<>();

    /** Rotated buckets, only ever touched by the main thread. */
    private final Deque<Bucket> history = new ArrayDeque<>();

    private volatile boolean running;
    private long bucketStartMillis = System.currentTimeMillis();
    private int wrappedListeners;

    /**
     * @param owner     plugin instance, used to skip TickSentry's own listeners
     * @param scheduler the server's scheduler, asked whether its queue can be counted at all
     */
    public PluginProfiler(Plugin owner, Scheduler scheduler) {
        this.owner = owner;
        this.scheduler = scheduler;
    }

    /** @return whether the profiler is installed and measuring */
    public boolean isRunning() {
        return running;
    }

    /** @return how many listeners are currently wrapped */
    public int wrappedListeners() {
        return wrappedListeners;
    }

    /** Installs the wrappers and starts measuring. Safe to call twice. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        bucketStartMillis = System.currentTimeMillis();
        install();
    }

    /** Removes the wrappers and forgets every measurement. Safe to call twice. */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        uninstall();
        live.clear();
        history.clear();
        wrappedListeners = 0;
    }

    /**
     * Wraps every listener that is not wrapped yet.
     *
     * <p>Called again on a timer, because plugins may register listeners long after they were
     * enabled, and a plugin loaded after TickSentry would otherwise never be measured.</p>
     */
    public void install() {
        if (!running) {
            return;
        }
        int added = 0;
        for (HandlerList list : HandlerList.getHandlerLists()) {
            // getRegisteredListeners() hands back a baked snapshot, so swapping entries while
            // walking it is safe - the array we iterate is not the one being modified.
            for (RegisteredListener registered : list.getRegisteredListeners()) {
                if (registered instanceof TimedListener) {
                    continue;
                }
                Plugin plugin = registered.getPlugin();
                if (plugin == null || plugin.equals(owner)) {
                    continue;
                }
                TimedListener wrapper;
                try {
                    wrapper = new TimedListener(registered);
                } catch (RuntimeException | LinkageError ex) {
                    // A listener we cannot wrap is left exactly as it was - a missing
                    // measurement is a far smaller problem than a missing handler.
                    continue;
                }
                list.unregister(registered);
                list.register(wrapper);
                added++;
            }
        }
        wrappedListeners += added;
    }

    /** Puts the original listeners back. Runs on disable, so the server is left as we found it. */
    public void uninstall() {
        for (HandlerList list : HandlerList.getHandlerLists()) {
            for (RegisteredListener registered : list.getRegisteredListeners()) {
                if (!(registered instanceof TimedListener)) {
                    continue;
                }
                list.unregister(registered);
                list.register(((TimedListener) registered).delegate);
            }
        }
    }

    /**
     * Closes the current bucket and starts a new one.
     *
     * <p>Must run on the main thread - it is the only writer of the bucket history.</p>
     */
    public void rotate() {
        long now = System.currentTimeMillis();
        Map<String, Map<String, long[]>> snapshot = new HashMap<>();
        for (Map.Entry<String, Map<String, Counter>> plugin : live.entrySet()) {
            Map<String, long[]> events = new HashMap<>();
            for (Map.Entry<String, Counter> event : plugin.getValue().entrySet()) {
                long nanos = event.getValue().nanos.getAndSet(0L);
                long calls = event.getValue().calls.getAndSet(0L);
                if (nanos > 0L || calls > 0L) {
                    events.put(event.getKey(), new long[] {nanos, calls});
                }
            }
            if (!events.isEmpty()) {
                snapshot.put(plugin.getKey(), events);
            }
        }

        history.addLast(new Bucket(bucketStartMillis, now, snapshot));
        bucketStartMillis = now;
        while (!history.isEmpty() && now - history.peekFirst().endMillis > MAX_HISTORY_MILLIS) {
            history.removeFirst();
        }
    }

    /**
     * Sums the buckets covering the last {@code seconds} into a ranking.
     *
     * <p>The window reported back is the span the buckets actually cover, not the span that was
     * asked for, so the percentages always match the measurements they came from.</p>
     *
     * @param seconds how far back to look
     * @return ranking of plugins, or {@link PluginReport#empty()} when nothing was measured
     */
    public PluginReport report(int seconds) {
        if (!running || seconds <= 0) {
            return PluginReport.empty();
        }
        rotate();

        long now = System.currentTimeMillis();
        long cutoff = now - seconds * 1000L;
        Map<String, long[]> totals = new HashMap<>();
        Map<String, Map<String, Long>> perEvent = new HashMap<>();
        long earliest = Long.MAX_VALUE;

        for (Bucket bucket : history) {
            if (bucket.endMillis <= cutoff) {
                continue;
            }
            earliest = Math.min(earliest, bucket.startMillis);
            for (Map.Entry<String, Map<String, long[]>> plugin : bucket.measurements.entrySet()) {
                long[] total = totals.computeIfAbsent(plugin.getKey(), key -> new long[2]);
                Map<String, Long> events = perEvent.computeIfAbsent(plugin.getKey(), key -> new HashMap<>());
                for (Map.Entry<String, long[]> event : plugin.getValue().entrySet()) {
                    total[0] += event.getValue()[0];
                    total[1] += event.getValue()[1];
                    events.merge(event.getKey(), event.getValue()[0], Long::sum);
                }
            }
        }

        if (earliest == Long.MAX_VALUE || totals.isEmpty()) {
            return PluginReport.empty();
        }

        List<PluginTiming> timings = new ArrayList<>(totals.size());
        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            String worstEvent = null;
            long worstNanos = 0L;
            for (Map.Entry<String, Long> event : perEvent.get(entry.getKey()).entrySet()) {
                if (event.getValue() > worstNanos) {
                    worstEvent = event.getKey();
                    worstNanos = event.getValue();
                }
            }
            timings.add(new PluginTiming(entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                    worstEvent, worstNanos));
        }
        return PluginReport.of(Math.max(1L, now - earliest) * 1_000_000L, timings);
    }

    /**
     * Counts the scheduler tasks each plugin has queued.
     *
     * <p>Bukkit does not let anyone time scheduled tasks from the outside, so this is a count,
     * not a measurement. It is still worth showing: a plugin sitting on thousands of pending
     * tasks is doing something wrong even if each one is cheap.</p>
     *
     * @return plugin name to number of pending tasks, never {@code null}
     */
    public Map<String, Integer> pendingTasks() {
        Map<String, Integer> counts = new HashMap<>();
        if (!scheduler.canCountPendingTasks()) {
            // Folia has no queue to count. An empty map leaves the line out of the report, rather
            // than printing zeroes and implying every plugin on the server is idle.
            return counts;
        }
        try {
            for (BukkitTask task : owner.getServer().getScheduler().getPendingTasks()) {
                Plugin plugin = task.getOwner();
                if (plugin != null) {
                    counts.merge(plugin.getName(), 1, Integer::sum);
                }
            }
        } catch (RuntimeException ex) {
            // The scheduler is shutting down or a task lost its owner - not worth an alert.
        }
        return counts;
    }

    /** Records one handler call. Kept as small as possible; it runs on every single event. */
    private void record(String pluginName, String eventName, long nanos) {
        Counter counter = live
                .computeIfAbsent(pluginName, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(eventName, key -> new Counter());
        counter.nanos.addAndGet(nanos);
        counter.calls.incrementAndGet();
    }

    /** Accumulator for one plugin and one event type. */
    private static final class Counter {
        private final AtomicLong nanos = new AtomicLong();
        private final AtomicLong calls = new AtomicLong();
    }

    /** One closed slice of measurements: plugin name to event name to {nanos, calls}. */
    private static final class Bucket {
        private final long startMillis;
        private final long endMillis;
        private final Map<String, Map<String, long[]>> measurements;

        private Bucket(long startMillis, long endMillis, Map<String, Map<String, long[]>> measurements) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.measurements = measurements;
        }
    }

    /**
     * Stand-in for a plugin's real listener that times it and passes the event straight on.
     *
     * <p>{@link RegisteredListener#callEvent(Event)} is overridden completely, so the
     * ignore-cancelled check happens once, inside the delegate, exactly as before.</p>
     */
    private final class TimedListener extends RegisteredListener {

        private final RegisteredListener delegate;
        private final String pluginName;

        private TimedListener(RegisteredListener delegate) {
            super(delegate.getListener(), (listener, event) -> {
            }, delegate.getPriority(), delegate.getPlugin(), delegate.isIgnoringCancelled());
            this.delegate = delegate;
            this.pluginName = delegate.getPlugin().getName();
        }

        @Override
        public void callEvent(Event event) throws EventException {
            if (!running || event.isAsynchronous()) {
                delegate.callEvent(event);
                return;
            }
            long start = System.nanoTime();
            try {
                delegate.callEvent(event);
            } finally {
                record(pluginName, event.getEventName(), System.nanoTime() - start);
            }
        }
    }
}
