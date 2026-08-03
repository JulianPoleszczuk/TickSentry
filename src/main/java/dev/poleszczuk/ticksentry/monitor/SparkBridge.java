package dev.poleszczuk.ticksentry.monitor;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Opcjonalne wzbogacenie odczytow o dane z pluginu spark.
 *
 * <p>Integracja jest w calosci refleksyjna, dzieki czemu TickSentry nie ma zadnej zaleznosci
 * kompilacyjnej ani runtime'owej od sparka - gdy go nie ma, plugin dziala bez zmian.
 * Spark udostepnia interfejs {@code me.lucko.spark.api.Spark} przez Bukkitowy ServicesManager;
 * bierzemy z niego rozklad czasu ticku (srednia i 95. percentyl) z ostatniej minuty, bo to
 * dokladniejszy obraz niz pojedyncza srednia krocząca.</p>
 */
public final class SparkBridge {

    private final Plugin plugin;
    private Object spark;
    private boolean attemptedHook;

    /**
     * @param plugin instancja pluginu (dostep do ServicesManagera i logu)
     */
    public SparkBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    /** @return {@code true}, jesli spark jest zainstalowany i jego API odpowiada */
    public boolean isAvailable() {
        return resolve() != null;
    }

    /**
     * Pobiera rozklad czasu ticku z ostatniej minuty.
     *
     * @return statystyki sparka albo {@code null}, gdy spark jest niedostepny lub zmienil API
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
            // Spark zmienil API albo jest w trakcie wylaczania - integracja jest dodatkiem, wiec milczymy.
            plugin.getLogger().fine("Nie udalo sie odczytac danych ze sparka: " + ex);
            return null;
        }
    }

    /**
     * @return krotki opis danych ze sparka gotowy do pokazania adminowi albo {@code null}
     */
    public String summary() {
        SparkStats stats = msptLastMinute();
        return stats == null ? null : stats.describe();
    }

    /**
     * Druga droga do sparka: statyczny {@code SparkProvider.get()}.
     * Spark wbudowany w Papera nie rejestruje sie w ServicesManagerze, ale ustawia ten provider.
     *
     * @return instancja sparka albo {@code null}
     */
    private static Object staticProvider() {
        try {
            return Class.forName("me.lucko.spark.api.SparkProvider").getMethod("get").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /** Znajduje metode {@code poll} niezaleznie od wymazanego typu parametru okna. */
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
                    plugin.getLogger().info("Spark jest obecny, ale jeszcze nie udostepnil API "
                            + "- alerty korzystaja z wlasnych pomiarow.");
                }
                return null;
            }
            if (first) {
                plugin.getLogger().info("Wykryto spark - alerty beda wzbogacone o jego statystyki.");
            }
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            // Spark nie jest zainstalowany - calkowicie normalny przypadek, milczymy.
        } catch (RuntimeException ex) {
            plugin.getLogger().fine("Nie udalo sie podpiac pod spark: " + ex);
        }
        return spark;
    }

    /**
     * Rozklad czasu ticku zmierzony przez spark.
     *
     * @param meanMs           sredni czas ticku z ostatniej minuty
     * @param percentile95thMs czas ticku, ponizej ktorego miesci sie 95% tickow
     */
    public record SparkStats(double meanMs, double percentile95thMs) {

        /** @return jednolinijkowy opis dla czatu i embeda */
        public String describe() {
            return String.format(java.util.Locale.ROOT,
                    "spark: srednia %.1f ms, 95%% tickow ponizej %.1f ms", meanMs, percentile95thMs);
        }
    }
}
