package dev.poleszczuk.ticksentry.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Anonymous usage statistics for <a href="https://bstats.org">bStats</a>.
 *
 * <p>Written by hand rather than shading the official library, for the same reason the Discord
 * client and the JSON escaping are: the payload is one flat object sent twice an hour, and the
 * plugin's whole shape is "adds nothing to your server except itself". The official library also
 * has to be relocated when shaded, which is a footgun this avoids entirely.</p>
 *
 * <p>What is sent: server software and version, Java version, operating system, core count,
 * player count, and which TickSentry features are switched on. No addresses, no names, no world
 * data. It exists to answer "which Minecraft versions do I still have to support", which is
 * otherwise pure guesswork.</p>
 *
 * <p>The server-wide opt-out in {@code plugins/bStats/config.yml} is honoured. A server owner who
 * has turned bStats off for everything expects that to include plugins that rolled their own,
 * and quietly ignoring it would be a breach of that.</p>
 */
public final class BStatsReporter {

    /**
     * bStats service id for TickSentry, from its page at bstats.org.
     *
     * <p>An author constant, not something a server owner should have to fill in. Zero would
     * mean the plugin is unregistered and nothing gets sent - that is what a fork should set it
     * back to, so its servers do not report into this page.</p>
     */
    public static final int SERVICE_ID = 33145;

    private static final String ENDPOINT = "https://bStats.org/api/v2/data/bukkit";
    private static final String METRICS_VERSION = "3.0.2";

    /** First submission, once the server has settled - the numbers mean nothing at startup. */
    private static final long FIRST_DELAY_TICKS = 20L * 60L * 3L;

    /** bStats expects roughly one submission every half hour. */
    private static final long INTERVAL_TICKS = 20L * 60L * 30L;

    private final Plugin plugin;
    private final Map<String, String> charts = new LinkedHashMap<>();

    private UUID serverUuid;
    private HttpClient http;

    /** Written by the HTTP thread, read by the next submission on it - hence volatile. */
    private volatile boolean firstSubmission = true;

    /**
     * @param plugin plugin instance, for the scheduler, the data folder and its version
     */
    public BStatsReporter(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Records a value shown as a pie chart on the bStats page.
     *
     * <p>Call before {@link #start()}; values are read again at each submission through the
     * suppliers the caller has already resolved, so keep them cheap and constant.</p>
     *
     * @param chartId chart identifier, as configured on bstats.org
     * @param value   value for this server
     */
    public void chart(String chartId, String value) {
        charts.put(chartId, value);
    }

    /**
     * Starts submitting, unless the plugin is unregistered or bStats is switched off.
     *
     * @return whether anything will actually be sent
     */
    public boolean start() {
        if (SERVICE_ID <= 0) {
            return false;
        }
        if (!readGlobalSettings()) {
            plugin.getLogger().fine("bStats is disabled server-wide - sending nothing.");
            return false;
        }
        // One client for the life of the plugin. Building one per submission would spin up a
        // fresh connection pool and thread every half hour for a single request.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::submit, FIRST_DELAY_TICKS, INTERVAL_TICKS);
        return true;
    }

    /**
     * Reads (and creates, if missing) the shared bStats configuration.
     *
     * @return whether bStats is allowed to send anything on this server
     */
    private boolean readGlobalSettings() {
        try {
            File file = new File(plugin.getDataFolder().getParentFile(), "bStats/config.yml");
            if (!file.exists()) {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return false;
                }
                YamlConfiguration fresh = new YamlConfiguration();
                fresh.options().header("bStats collects some basic information for plugin authors,"
                        + " like how many people use their plugin and their total player count."
                        + " It's recommended to keep bStats enabled, but if you're not comfortable"
                        + " with this, you can turn this setting off. There is no performance"
                        + " penalty associated with having metrics enabled, and data sent to"
                        + " bStats is fully anonymous.");
                fresh.set("enabled", true);
                fresh.set("serverUuid", UUID.randomUUID().toString());
                fresh.save(file);
            }

            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            if (!config.getBoolean("enabled", true)) {
                return false;
            }
            String uuid = config.getString("serverUuid");
            this.serverUuid = uuid == null ? UUID.randomUUID() : UUID.fromString(uuid);
            return true;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().fine("Could not read the bStats settings: " + ex);
            return false;
        }
    }

    /** Collects the numbers on the main thread, then hands the sending to a background thread. */
    private void submit() {
        String payload = buildPayload(plugin.getServer().getOnlinePlayers().size());
        http.sendAsync(request(payload), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(this::report)
                .exceptionally(error -> {
                    report(null, String.valueOf(error));
                    return null;
                });
    }

    /**
     * Says whether the submission landed.
     *
     * <p>The first one is announced either way. Without that, a payload bStats rejects fails
     * silently for ever: no data appears on the page and nothing in the log says why. Later
     * submissions stay quiet - statistics are a courtesy to the author, never something an
     * admin should have to read about twice an hour.</p>
     */
    private void report(HttpResponse<String> response) {
        if (response != null && response.statusCode() / 100 == 2) {
            if (firstSubmission) {
                firstSubmission = false;
                plugin.getLogger().info("Sent anonymous statistics to bStats (plugin id "
                        + SERVICE_ID + "). Turn this off with updates.bstats in config.yml.");
            }
            return;
        }
        report(response, response == null ? "no response" : "HTTP " + response.statusCode()
                + (response.body() == null || response.body().isEmpty() ? "" : ": " + response.body()));
    }

    private void report(HttpResponse<String> response, String detail) {
        if (firstSubmission) {
            firstSubmission = false;
            plugin.getLogger().warning("Could not send statistics to bStats (" + detail
                    + "). Nothing else is affected - turn this off with updates.bstats in config.yml.");
        } else {
            plugin.getLogger().fine("Could not send statistics to bStats: " + detail);
        }
    }

    private HttpRequest request(String payload) {
        return HttpRequest.newBuilder(URI.create(ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .header("Accept", "application/json")
                .header("User-Agent", "Metrics-Service/1")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(payload)))
                .build();
    }

    /** Builds the flat object bStats expects. */
    private String buildPayload(int players) {
        List<String> chartJson = new ArrayList<>(charts.size());
        for (Map.Entry<String, String> entry : charts.entrySet()) {
            chartJson.add("{\"chartId\":\"" + Json.escape(entry.getKey())
                    + "\",\"data\":{\"value\":\"" + Json.escape(entry.getValue()) + "\"}}");
        }

        return "{"
                + "\"serverUUID\":\"" + serverUuid + "\","
                + "\"metricsVersion\":\"" + METRICS_VERSION + "\","
                + "\"playerAmount\":" + players + ","
                + "\"onlineMode\":" + (plugin.getServer().getOnlineMode() ? 1 : 0) + ","
                + "\"bukkitVersion\":\"" + Json.escape(plugin.getServer().getBukkitVersion()) + "\","
                + "\"bukkitName\":\"" + Json.escape(plugin.getServer().getName()) + "\","
                + "\"javaVersion\":\"" + Json.escape(System.getProperty("java.version")) + "\","
                + "\"osName\":\"" + Json.escape(System.getProperty("os.name")) + "\","
                + "\"osArch\":\"" + Json.escape(System.getProperty("os.arch")) + "\","
                + "\"osVersion\":\"" + Json.escape(System.getProperty("os.version")) + "\","
                + "\"coreCount\":" + Runtime.getRuntime().availableProcessors() + ","
                + "\"service\":{"
                + "\"id\":" + SERVICE_ID + ","
                + "\"pluginVersion\":\"" + Json.escape(plugin.getDescription().getVersion()) + "\","
                + "\"customCharts\":[" + String.join(",", chartJson) + "]}"
                + "}";
    }

    /** bStats only accepts gzipped bodies. */
    private static byte[] gzip(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream(raw.length / 2)) {
            try (GZIPOutputStream out = new GZIPOutputStream(buffer)) {
                out.write(raw);
            }
            return buffer.toByteArray();
        } catch (IOException ex) {
            // Compressing an in-memory string cannot really fail; if it somehow does, sending
            // the uncompressed body would just be rejected, so send nothing meaningful instead.
            return new byte[0];
        }
    }
}
