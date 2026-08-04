package dev.poleszczuk.ticksentry.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Built-in web panel showing server health.
 *
 * <p>Runs on the {@link HttpServer} shipped with the JDK, so it adds no dependency. The panel
 * lives on the same machine as the game server - there is no external service and nothing to
 * host anywhere.</p>
 *
 * <p><b>Threading:</b> HTTP handlers run off the main server thread, so they never touch the
 * Bukkit API or the database. They only read prepared snapshots handed to them by the main
 * thread through {@link #update(LiveSnapshot)} and {@link #updateIncidents(String)}.</p>
 *
 * <p><b>Security:</b> every request must present a token (either {@code ?token=} or the
 * {@code X-Auth-Token} header). The connection is plain HTTP, which is why the default
 * configuration listens on {@code 127.0.0.1} only. Exposing the panel to the outside world
 * requires a reverse proxy with HTTPS in front of it - otherwise the token travels in clear
 * text.</p>
 */
public final class DashboardServer {

    /** Threads serving HTTP requests - the panel has a handful of users at most. */
    private static final int HTTP_THREADS = 2;

    private final Plugin plugin;
    private final String token;
    private final String page;

    private volatile LiveSnapshot snapshot = LiveSnapshot.empty();
    private volatile MetricsSnapshot metrics = MetricsSnapshot.empty();
    private volatile String incidentsJson = "[]";
    private final MsptHistory history;
    private final boolean metricsEnabled;
    private HttpServer server;

    /**
     * @param plugin         plugin instance (logging)
     * @param token          token required on every request
     * @param history        sample buffer feeding the chart
     * @param metricsEnabled whether to serve the Prometheus endpoint as well
     */
    public DashboardServer(Plugin plugin, String token, MsptHistory history, boolean metricsEnabled) {
        this.plugin = plugin;
        this.token = token;
        this.history = history;
        this.metricsEnabled = metricsEnabled;
        this.page = loadPage();
    }

    /**
     * Starts the HTTP server.
     *
     * @param bind listen address, for example {@code 127.0.0.1}
     * @param port listen port
     * @return {@code true} if the panel came up
     */
    public boolean start(String bind, int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            server.createContext("/", this::handlePage);
            server.createContext("/api/live", exchange -> handleApi(exchange, this::liveJson));
            server.createContext("/api/incidents", exchange -> handleApi(exchange, () -> incidentsJson));
            if (metricsEnabled) {
                server.createContext("/metrics", this::handleMetrics);
            }
            server.setExecutor(Executors.newFixedThreadPool(HTTP_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "TickSentry-Dashboard");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();

            // The token is deliberately kept out of the log. Admins are told to paste their
            // console into bug reports, and a log line holding a working access token is how
            // somebody ends up publishing one - especially after changing bind to 0.0.0.0.
            plugin.getLogger().info("Web panel: http://" + bind + ":" + port
                    + "/ - open it with the token from config.yml (dashboard.token).");
            if (metricsEnabled) {
                plugin.getLogger().info("Prometheus metrics: http://" + bind + ":" + port
                        + "/metrics - same token, as ?token= or an X-Auth-Token header.");
            }
            plugin.getLogger().fine("Panel URL including the token: http://" + bind + ":" + port
                    + "/?token=" + token);
            return true;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not start the web panel", ex);
            return false;
        }
    }

    /** Stops the HTTP server. */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * Replaces the snapshot shown by the panel.
     *
     * @param snapshot fresh snapshot taken on the main thread
     */
    public void update(LiveSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * Replaces the numbers served to Prometheus.
     *
     * @param metrics fresh snapshot taken on the main thread
     */
    public void updateMetrics(MetricsSnapshot metrics) {
        this.metrics = metrics;
    }

    /**
     * Replaces the list of recent incidents.
     *
     * @param json ready JSON array of incidents
     */
    public void updateIncidents(String json) {
        this.incidentsJson = json;
    }

    /**
     * Serves the Prometheus endpoint.
     *
     * <p>Guarded by the same token as everything else. Prometheus can send it either as a query
     * parameter in the scrape URL or as an {@code X-Auth-Token} header.</p>
     */
    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            respond(exchange, 401, "text/plain; charset=utf-8", "Missing or invalid token.\n");
            return;
        }
        respond(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", metrics.render());
    }

    /** Builds the {@code /api/live} response: current state plus chart points. */
    private String liveJson() {
        return "{\"live\":" + snapshot.toJson() + ",\"chart\":" + history.toJsonArray() + "}";
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            respond(exchange, 401, "text/plain; charset=utf-8", "Missing or invalid token.");
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", page);
    }

    private void handleApi(HttpExchange exchange, Supplier body) throws IOException {
        if (!authorized(exchange)) {
            respond(exchange, 401, "application/json; charset=utf-8", "{\"error\":\"unauthorized\"}");
            return;
        }
        respond(exchange, 200, "application/json; charset=utf-8", body.get());
    }

    /** Checks the token from the query string or the header, using a constant-time comparison. */
    private boolean authorized(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst("X-Auth-Token");
        if (provided == null) {
            provided = queryParam(exchange.getRequestURI().getRawQuery(), "token");
        }
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) {
            return null;
        }
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).equals(key)) {
                return java.net.URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        // The panel is local and is not meant to be embedded or fetched from other pages.
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Loads the panel page from the jar resources. */
    private String loadPage() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("dashboard.html")) {
            if (in == null) {
                return "<h1>dashboard.html missing from the jar</h1>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not load the panel page", ex);
            return "<h1>Failed to load the panel</h1>";
        }
    }

    /**
     * Generates a random access token.
     *
     * @return token, later stored in config.yml
     */
    public static String generateToken() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    /** Response body supplier - a local stand-in for {@code Supplier<String>}. */
    @FunctionalInterface
    private interface Supplier {
        String get();
    }
}
