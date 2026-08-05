package dev.poleszczuk.ticksentry.alert;

import dev.poleszczuk.ticksentry.monitor.LagEvent;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Posts alerts as JSON to any address.
 *
 * <p>One destination that covers most of the requests a Discord-only plugin gets: Slack, Mattermost,
 * n8n, Home Assistant, a Zapier catch hook, or a script of the admin's own. All of them accept a JSON
 * POST, so none of them needs code here - see {@link AlertPayload} for why the body carries both a
 * rendered sentence and the raw numbers.</p>
 *
 * <p>Delivery happens on its own daemon thread, like the Discord client: this is called from the tick
 * that noticed the lag, and waiting on somebody's HTTP endpoint there would make the plugin a cause
 * of the problem it reports.</p>
 */
public final class WebhookSink implements AlertSink {

    private final Plugin plugin;
    private final Supplier<String> url;
    private final Supplier<Map<String, String>> headers;
    private final DoubleSupplier threshold;
    private final HttpClient http;
    private final ExecutorService executor;

    /**
     * @param plugin    plugin instance (logging)
     * @param url       the address to post to, read fresh so {@code /lagwatch reload} takes effect
     * @param headers   extra request headers, for endpoints that want a token
     * @param threshold the tick time currently counted as overloaded
     */
    public WebhookSink(Plugin plugin, Supplier<String> url, Supplier<Map<String, String>> headers,
                       DoubleSupplier threshold) {
        this.plugin = plugin;
        this.url = url;
        this.headers = headers;
        this.threshold = threshold;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "TickSentry-Webhook-Generic");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void incident(LagEvent event) {
        post(AlertPayload.incident(event, threshold.getAsDouble()));
    }

    @Override
    public void recovery(long durationSeconds, double tps, double mspt) {
        post(AlertPayload.recovery(durationSeconds, tps, mspt));
    }

    @Override
    public void remediation(String summary) {
        post(AlertPayload.remediation(summary));
    }

    @Override
    public boolean isConfigured() {
        String target = url.get();
        return target != null && !target.isEmpty();
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Hands the body to the delivery thread. Returns immediately. */
    private void post(String body) {
        String target = url.get();
        Map<String, String> extraHeaders = headers.get();
        executor.execute(() -> send(target, extraHeaders, body));
    }

    private void send(String target, Map<String, String> extraHeaders, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "TickSentry (Minecraft plugin)")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            extraHeaders.forEach(request::header);

            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                // No retry. Unlike Discord this endpoint is unknown - it could be a script that
                // already acted on the alert, and re-sending would have it act twice.
                plugin.getLogger().warning("The webhook at " + hostOf(target)
                        + " answered HTTP " + response.statusCode() + ".");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not deliver the alert to the webhook at " + hostOf(target), ex);
        }
    }

    /**
     * @param target the configured address
     * @return just its host, because the full URL is usually a secret
     *
     * <p>A webhook URL is a bearer token in disguise - anyone holding it can post as you. Logging it
     * on every failure is how one ends up in a pasted console log.</p>
     */
    private static String hostOf(String target) {
        try {
            String host = URI.create(target).getHost();
            return host == null ? "the configured address" : host;
        } catch (RuntimeException ex) {
            return "the configured address";
        }
    }
}
