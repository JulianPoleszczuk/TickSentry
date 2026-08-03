package dev.poleszczuk.ticksentry.monitor;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Optional enrichment of the readings with data from the spark plugin.
 *
 * <p>The integration is entirely reflective, so TickSentry has no compile-time or runtime
 * dependency on spark - when spark is absent the plugin behaves exactly the same. Spark exposes
 * {@code me.lucko.spark.api.Spark} through the Bukkit services manager; the bridge takes the
 * tick time distribution over the last minute (mean and 95th percentile), which paints a more
 * accurate picture than a single rolling average.</p>
 */
public final class SparkBridge {

    private final Plugin plugin;
    private Object spark;
    private boolean attemptedHook;

    /**
     * @param plugin plugin instance (services manager and logging)
     */
    public SparkBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    /** @return {@code true} if spark is installed and its API responds */
    public boolean isAvailable() {
        return resolve() != null;
    }

    /**
     * Reads the tick time distribution over the last minute.
     *
     * @return spark statistics, or {@code null} when spark is unavailable or its API changed
     */
    public SparkStats msptLastMinute() {
        Object instance = resolve();
        if (instance == null) {
            return null;
        }
        try {
            Object statistic = instance.getClass().getMethod("mspt").invoke(instance);
            Class<?> windowClass = Class.forName("me.lucko.spark.api.statistic.StatisticWindow$MillisPerTick");
            Object window = enumConstant(windowClass, "MINUTES_1");
            if (window == null) {
                return null;
            }
            Object info = pollMethod(statistic).invoke(statistic, window);
            if (info == null) {
                return null;
            }
            double mean = readDouble(info, "mean");
            double p95 = readDouble(info, "percentile95th");
            return Double.isNaN(mean) && Double.isNaN(p95) ? null : new SparkStats(mean, p95);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Spark changed its API or is shutting down - this is a bonus feature, so stay quiet.
            plugin.getLogger().fine("Could not read spark data: " + ex);
            return null;
        }
    }

    /**
     * @return short description of the spark data, ready to show to an admin, or {@code null}
     */
    public String summary() {
        SparkStats stats = msptLastMinute();
        return stats == null ? null : stats.describe();
    }

    /** Finds the {@code poll} method regardless of the erased window parameter type. */
    private static Method pollMethod(Object statistic) throws NoSuchMethodException {
        for (Method method : statistic.getClass().getMethods()) {
            if ("poll".equals(method.getName()) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("poll");
    }

    private static Object enumConstant(Class<?> enumClass, String name) {
        for (Object constant : enumClass.getEnumConstants()) {
            if (constant.toString().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    private static double readDouble(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Number number ? number.doubleValue() : Double.NaN;
        } catch (ReflectiveOperationException ex) {
            return Double.NaN;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object resolve() {
        if (spark != null) {
            return spark;
        }
        boolean first = !attemptedHook;
        attemptedHook = true;
        try {
            Class<?> sparkClass = Class.forName("me.lucko.spark.api.Spark");
            RegisteredServiceProvider<?> provider =
                    plugin.getServer().getServicesManager().getRegistration((Class) sparkClass);
            spark = provider != null ? provider.getProvider() : staticProvider();

            if (spark == null) {
                if (first) {
                    plugin.getLogger().info("Spark is present but has not exposed its API yet "
                            + "- alerts will use TickSentry's own measurements.");
                }
                return null;
            }
            if (first) {
                plugin.getLogger().info("Spark detected - alerts will include its statistics.");
            }
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            // Spark is not installed - a perfectly normal case.
        } catch (RuntimeException ex) {
            plugin.getLogger().fine("Could not hook into spark: " + ex);
        }
        return spark;
    }

    /**
     * Second route to spark: the static {@code SparkProvider.get()}.
     * The spark build bundled with Paper does not register in the services manager but does set this provider.
     *
     * @return spark instance, or {@code null}
     */
    private static Object staticProvider() {
        try {
            return Class.forName("me.lucko.spark.api.SparkProvider").getMethod("get").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Tick time distribution measured by spark.
     *
     * @param meanMs           average tick time over the last minute
     * @param percentile95thMs tick time that 95% of ticks stay below
     */
    public record SparkStats(double meanMs, double percentile95thMs) {

        /** @return single-line description for chat and embeds */
        public String describe() {
            return String.format(java.util.Locale.ROOT,
                    "spark: mean %.1f ms, 95%% of ticks below %.1f ms", meanMs, percentile95thMs);
        }
    }
}
