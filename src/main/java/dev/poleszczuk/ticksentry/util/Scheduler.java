package dev.poleszczuk.ticksentry.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Somewhere to hand a repeating job, on a server that may or may not have one main thread.
 *
 * <p>Everything this plugin does on a timer went through {@code Bukkit.getScheduler()}, and on Folia
 * that scheduler throws on the first call: there is no single main thread to queue work onto, so the
 * whole API is gone. Folia's replacement for "work that belongs to no particular region" is the
 * global region scheduler, which is what {@link #forPlugin} hands back there.</p>
 *
 * <p>The Folia implementation is reflective on purpose. The jar is built against the Paper API and
 * has to keep loading on Spigot, so it cannot name Folia's classes at compile time - the same
 * approach the plugin already takes to spark and the land protection plugins.</p>
 *
 * <p>This is not a claim that everything works on Folia. It only removes the reason the plugin could
 * not start there at all; what does and does not run in that mode is decided in
 * {@link FoliaSupport}.</p>
 */
public interface Scheduler {

    /** A job that can be stopped again. */
    interface Handle {
        /** Stops the job. Safe to call more than once. */
        void cancel();
    }

    /**
     * Runs a job once, as soon as the server will take it.
     *
     * @param task what to run
     */
    void run(Runnable task);

    /**
     * Runs a job once after a delay.
     *
     * @param task       what to run
     * @param delayTicks how long to wait
     */
    void runLater(Runnable task, long delayTicks);

    /**
     * Runs a job over and over.
     *
     * @param task        what to run
     * @param delayTicks  how long to wait before the first run
     * @param periodTicks how long to wait between runs
     * @return a handle for stopping it
     */
    Handle runTimer(Runnable task, long delayTicks, long periodTicks);

    /**
     * @return whether counting the scheduler's queued jobs is possible.
     *         Folia has no equivalent, so {@code /lagwatch plugins} leaves that line out there
     *         rather than printing zero and implying every plugin is idle.
     */
    boolean canCountPendingTasks();

    /**
     * Picks the scheduler this server actually has.
     *
     * @param plugin plugin the jobs belong to
     * @return a scheduler that works here
     */
    static Scheduler forPlugin(Plugin plugin) {
        if (FoliaSupport.isFolia()) {
            Scheduler folia = FoliaScheduler.create(plugin);
            if (folia != null) {
                return folia;
            }
            // Folia without the scheduler this was written against. Fall through: the Bukkit
            // scheduler will throw, and a stack trace naming the real problem beats a plugin that
            // pretends to be monitoring and silently is not.
            plugin.getLogger().warning("This looks like Folia but has no global region scheduler "
                    + "this plugin recognises - timed work will not start.");
        }
        return new BukkitScheduler(plugin);
    }

    /** Paper and Spigot: the scheduler that has always been there. */
    final class BukkitScheduler implements Scheduler {

        private final Plugin plugin;

        BukkitScheduler(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void run(Runnable task) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }

        @Override
        public void runLater(Runnable task, long delayTicks) {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
        }

        @Override
        public Handle runTimer(Runnable task, long delayTicks, long periodTicks) {
            BukkitTask handle = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, task, delayTicks, periodTicks);
            return handle::cancel;
        }

        @Override
        public boolean canCountPendingTasks() {
            return true;
        }
    }

    /**
     * Folia: the global region scheduler, reached reflectively.
     *
     * <p>Folia's methods take a {@code Consumer<ScheduledTask>} rather than a {@code Runnable}. The
     * parameter type is a plain {@link Consumer}, so a lambda that ignores its argument passes
     * through reflection without this class ever naming a Folia type.</p>
     */
    final class FoliaScheduler implements Scheduler {

        private final Plugin plugin;
        private final Object globalScheduler;
        private final Method run;
        private final Method runDelayed;
        private final Method runAtFixedRate;

        private FoliaScheduler(Plugin plugin, Object globalScheduler,
                               Method run, Method runDelayed, Method runAtFixedRate) {
            this.plugin = plugin;
            this.globalScheduler = globalScheduler;
            this.run = run;
            this.runDelayed = runDelayed;
            this.runAtFixedRate = runAtFixedRate;
        }

        /**
         * @param plugin plugin the jobs belong to
         * @return a working scheduler, or {@code null} when this server does not have the API
         */
        static Scheduler create(Plugin plugin) {
            try {
                Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler")
                        .invoke(null);
                if (scheduler == null) {
                    return null;
                }
                Class<?> type = scheduler.getClass();
                return new FoliaScheduler(plugin, scheduler,
                        find(type, "run", Plugin.class, Consumer.class),
                        find(type, "runDelayed", Plugin.class, Consumer.class, long.class),
                        find(type, "runAtFixedRate", Plugin.class, Consumer.class,
                                long.class, long.class));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                return null;
            }
        }

        /** Looks a method up on the implementation class or on any interface it declares. */
        private static Method find(Class<?> type, String name, Class<?>... parameters)
                throws NoSuchMethodException {
            try {
                Method method = type.getMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ex) {
                for (Class<?> candidate : type.getInterfaces()) {
                    try {
                        return candidate.getMethod(name, parameters);
                    } catch (NoSuchMethodException ignored) {
                        // Keep looking; the next interface may declare it.
                    }
                }
                throw ex;
            }
        }

        @Override
        public void run(Runnable task) {
            invoke(run, ignored -> task.run());
        }

        @Override
        public void runLater(Runnable task, long delayTicks) {
            // Folia rejects a delay of zero, where Bukkit accepts it as "next tick".
            invoke(runDelayed, ignored -> task.run(), Math.max(1L, delayTicks));
        }

        @Override
        public Handle runTimer(Runnable task, long delayTicks, long periodTicks) {
            Object handle = invoke(runAtFixedRate, ignored -> task.run(),
                    Math.max(1L, delayTicks), Math.max(1L, periodTicks));
            if (handle == null) {
                return () -> {
                };
            }
            return () -> {
                try {
                    handle.getClass().getMethod("cancel").invoke(handle);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    plugin.getLogger().warning("Could not cancel a scheduled job: " + ex);
                }
            };
        }

        @Override
        public boolean canCountPendingTasks() {
            return false;
        }

        private Object invoke(Method method, Consumer<Object> body, Object... extras) {
            Object[] arguments = new Object[2 + extras.length];
            arguments[0] = plugin;
            arguments[1] = body;
            System.arraycopy(extras, 0, arguments, 2, extras.length);
            try {
                return method.invoke(globalScheduler, arguments);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                plugin.getLogger().warning("Could not schedule a job: " + ex);
                return null;
            }
        }
    }
}
