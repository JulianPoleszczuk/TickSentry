package dev.poleszczuk.ticksentry.monitor;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the most invasive thing this plugin does: swapping other plugins' registered listeners
 * for timing wrappers.
 *
 * <p>If that swap loses a handler, reorders priorities or drops the ignore-cancelled flag, the
 * damage lands on somebody else's plugin and the blame lands here. So it is tested against the
 * real {@link HandlerList} and {@link RegisteredListener} - both are ordinary Java objects that
 * need no running server.</p>
 */
class PluginProfilerTest {

    private Plugin owner;
    private Plugin other;

    @BeforeEach
    void setUp() {
        owner = fakePlugin("TickSentry");
        other = fakePlugin("SomeOtherPlugin");
        HandlerList.unregisterAll();
    }

    @AfterEach
    void tearDown() {
        HandlerList.unregisterAll();
    }

    @Test
    void wrappingPreservesEverythingThatDecidesHowAHandlerRuns() {
        Listener listener = new Listener() { };
        RegisteredListener original = new RegisteredListener(listener, (l, e) -> { },
                EventPriority.HIGHEST, other, true);
        ProbeEvent.getHandlerList().register(original);

        PluginProfiler profiler = new PluginProfiler(owner);
        profiler.start();

        RegisteredListener[] after = ProbeEvent.getHandlerList().getRegisteredListeners();
        assertEquals(1, after.length, "the handler must not be lost or duplicated");
        RegisteredListener wrapper = after[0];

        assertEquals(EventPriority.HIGHEST, wrapper.getPriority());
        assertTrue(wrapper.isIgnoringCancelled());
        assertSame(listener, wrapper.getListener());
        assertSame(other, wrapper.getPlugin());
    }

    @Test
    void theDelegateStillReceivesTheEvent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        register(other, (l, e) -> calls.incrementAndGet());

        PluginProfiler profiler = new PluginProfiler(owner);
        profiler.start();
        fire(new ProbeEvent());

        assertEquals(1, calls.get(), "wrapping must not swallow the event");
    }

    @Test
    void uninstallPutsTheOriginalObjectBack() {
        RegisteredListener original = new RegisteredListener(new Listener() { }, (l, e) -> { },
                EventPriority.NORMAL, other, false);
        ProbeEvent.getHandlerList().register(original);

        PluginProfiler profiler = new PluginProfiler(owner);
        profiler.start();
        profiler.stop();

        RegisteredListener[] after = ProbeEvent.getHandlerList().getRegisteredListeners();
        assertEquals(1, after.length);
        assertSame(original, after[0], "a /reload must leave the server exactly as we found it");
    }

    @Test
    void installingAgainDoesNotWrapTheWrapper() {
        register(other, (l, e) -> { });

        PluginProfiler profiler = new PluginProfiler(owner);
        profiler.start();
        int afterFirst = profiler.wrappedListeners();
        // install() runs on a timer to catch listeners registered later; running it against
        // ones already wrapped must be a no-op rather than another layer.
        profiler.install();
        profiler.install();

        assertEquals(afterFirst, profiler.wrappedListeners());
        assertEquals(1, ProbeEvent.getHandlerList().getRegisteredListeners().length);
    }

    @Test
    void ourOwnListenersAreLeftAlone() {
        RegisteredListener ours = new RegisteredListener(new Listener() { }, (l, e) -> { },
                EventPriority.MONITOR, owner, false);
        ProbeEvent.getHandlerList().register(ours);

        PluginProfiler profiler = new PluginProfiler(owner);
        profiler.start();

        assertSame(ours, ProbeEvent.getHandlerList().getRegisteredListeners()[0]);
        assertEquals(0, profiler.wrappedListeners());
    }

    @Test
    void synchronousHandlerTimeIsAttributedToTheRightPlugin() throws Exception {
        register(other, (l, e) -> burnAMillisecond());

        PluginProfiler profiler = new PluginProfiler(owner);
        profiler.start();
        fire(new ProbeEvent());

        PluginReport report = profiler.report(60);
        assertFalse(report.isEmpty(), "a measured handler must show up in the report");
        PluginTiming worst = report.worst();
        assertEquals("SomeOtherPlugin", worst.pluginName());
        assertEquals("ProbeEvent", worst.worstEvent());
        assertEquals(1L, worst.calls());
        assertTrue(worst.totalNanos() > 0L, "the handler took time, so the reading cannot be zero");
    }

    @Test
    void asynchronousEventsArePassedThroughWithoutBeingTimed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        register(other, (l, e) -> {
            burnAMillisecond();
            calls.incrementAndGet();
        });

        PluginProfiler profiler = new PluginProfiler(owner);
        profiler.start();
        fire(new ProbeEvent(true));

        assertEquals(1, calls.get(), "an async handler still has to run");
        // Counting it would inflate the share against wall clock: it never delayed a tick.
        assertTrue(profiler.report(60).isEmpty());
    }

    @Test
    void severalCallsAccumulateIntoOneEntry() throws Exception {
        register(other, (l, e) -> burnAMillisecond());

        PluginProfiler profiler = new PluginProfiler(owner);
        profiler.start();
        for (int i = 0; i < 5; i++) {
            fire(new ProbeEvent());
        }

        assertEquals(5L, profiler.report(60).worst().calls());
    }

    @Test
    void stoppingForgetsEveryMeasurement() throws Exception {
        register(other, (l, e) -> burnAMillisecond());

        PluginProfiler profiler = new PluginProfiler(owner);
        profiler.start();
        fire(new ProbeEvent());
        assertFalse(profiler.report(60).isEmpty());

        profiler.stop();

        assertFalse(profiler.isRunning());
        assertEquals(0, profiler.wrappedListeners());
        assertTrue(profiler.report(60).isEmpty());
    }

    @Test
    void aProfilerThatWasNeverStartedMeasuresNothing() {
        PluginProfiler profiler = new PluginProfiler(owner);

        assertFalse(profiler.isRunning());
        assertTrue(profiler.report(60).isEmpty());
        // install() before start() must not touch anything either.
        register(other, (l, e) -> { });
        profiler.install();
        assertEquals(0, profiler.wrappedListeners());
    }

    @Test
    void aServerThatCannotAnswerCostsTheCountNotTheAlert() {
        // The fake plugin has no server behind it, exactly like one shutting down.
        assertNotNull(new PluginProfiler(owner).pendingTasks());
        assertTrue(new PluginProfiler(owner).pendingTasks().isEmpty());
    }

    /** Registers a handler owned by the given plugin on the probe event. */
    private static void register(Plugin plugin, EventExecutor executor) {
        ProbeEvent.getHandlerList().register(new RegisteredListener(new Listener() { },
                executor, EventPriority.NORMAL, plugin, false));
    }

    /** Delivers an event the way the server would, straight to the registered listeners. */
    private static void fire(Event event) throws Exception {
        for (RegisteredListener registered : ProbeEvent.getHandlerList().getRegisteredListeners()) {
            registered.callEvent(event);
        }
    }

    /** Spends long enough that System.nanoTime() cannot report zero. */
    private static void burnAMillisecond() {
        long until = System.nanoTime() + 1_000_000L;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    /**
     * A stand-in plugin. Only its identity and name matter here - the profiler stores it,
     * compares it against its owner and reads its name.
     */
    private static Plugin fakePlugin(String name) {
        return (Plugin) Proxy.newProxyInstance(
                PluginProfilerTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getName":
                            return name;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "FakePlugin[" + name + "]";
                        case "isEnabled":
                            return true;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }

    /** An event that exists only for these tests, with its own handler list. */
    public static final class ProbeEvent extends Event {

        private static final HandlerList HANDLERS = new HandlerList();

        ProbeEvent() {
            super(false);
        }

        ProbeEvent(boolean async) {
            super(async);
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
