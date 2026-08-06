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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * What the page is allowed to load and talk to.
     *
     * <p>Everything is denied by default and only {@code connect-src 'self'} is opened, which is
     * what stops a token from being posted anywhere else. That is the part worth having: the page
     * carries an access token in its address bar, and a policy that forbids reaching any other host
     * means a mistake in the page cannot become a leak.</p>
     *
     * <p>The style and script blocks are inline, hence {@code 'unsafe-inline'}. Hashing them would
     * be stronger, but a hash that does not match the browser's byte-for-byte leaves an admin
     * staring at a blank panel, and that trade is not worth making for a single-origin page whose
     * dynamic content is written through {@code textContent} rather than {@code innerHTML}.</p>
     */
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; "
            + "script-src 'unsafe-inline'; "
            + "style-src 'unsafe-inline'; "
            + "connect-src 'self'; "
            + "base-uri 'none'; "
            + "form-action 'none'; "
            + "frame-ancestors 'none'";

    private final Plugin plugin;
    private final String token;
    private final String page;

    /**
     * How many rejected requests it takes before the log says anything.
     *
     * <p>This is not brute-force protection - the token is 128 bits of randomness and nobody is
     * guessing it. It is a signal that something is knocking on this port, which is worth knowing
     * the day after an admin changes {@code bind} to {@code 0.0.0.0} and forgets what that
     * exposed.</p>
     */
    private static final int REJECTIONS_BEFORE_LOGGING = 5;

    /** Never more than one line per this many milliseconds, so a scanner cannot fill the log. */
    private static final long REJECTION_LOG_INTERVAL_MILLIS = 60_000L;

    /**
     * How old a snapshot has to be before {@code /healthz} calls the server unavailable.
     *
     * <p>The main thread takes one every five seconds, so this is six missed in a row - long enough
     * that a bad couple of seconds cannot trip it, short enough to notice a hang.</p>
     */
    private static final long STALE_SNAPSHOT_MILLIS = 30_000L;

    private final AtomicInteger rejections = new AtomicInteger();
    private final AtomicLong lastRejectionLogMillis = new AtomicLong();

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
            server.createContext("/healthz", this::handleHealth);
            if (metricsEnabled) {
                server.createContext("/metrics", this::handleMetrics);
            }
            server.setExecutor(Executors.newFixedThreadPool(HTTP_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "TickSentry-Dashboard");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();

            // The token is kept out of the log entirely, at every level. Admins are told to paste
            // their console into bug reports, and a log line holding a working access token is how
            // somebody ends up publishing one - especially after changing bind to 0.0.0.0. Writing
            // it at FINE only moved that risk to whoever turns debug logging on, which is exactly
            // the admin already busy collecting output for somebody else to read.
            plugin.getLogger().info("Web panel: http://" + bind + ":" + port
                    + "/ - open it with the token from config.yml (dashboard.token).");
            if (metricsEnabled) {
                plugin.getLogger().info("Prometheus metrics: http://" + bind + ":" + port
                        + "/metrics - same token, as ?token= or an X-Auth-Token header.");
            }
            plugin.getLogger().info("Health check: http://" + bind + ":" + port
                    + "/healthz - no token, answers 200 while the main thread is alive.");
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
            noteRejection(exchange);
            respond(exchange, 401, "text/plain; charset=utf-8", "Missing or invalid token.\n");
            return;
        }
        respond(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", metrics.render());
    }

    /**
     * Counts a rejected request and, once there have been a few, says so in the log.
     *
     * <p>Deliberately does not log what was presented. An admin who mistypes their own token would
     * otherwise have a near-miss of a working token written into the file they paste into bug
     * reports - the same reason the token itself is kept out of the startup line.</p>
     */
    private void noteRejection(HttpExchange exchange) {
        if (rejections.incrementAndGet() < REJECTIONS_BEFORE_LOGGING) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = lastRejectionLogMillis.get();
        if (now - previous < REJECTION_LOG_INTERVAL_MILLIS
                || !lastRejectionLogMillis.compareAndSet(previous, now)) {
            return;
        }
        plugin.getLogger().warning("The web panel has rejected " + rejections.get()
                + " request(s) for a missing or wrong token, most recently from "
                + exchange.getRemoteAddress().getAddress().getHostAddress()
                + ". If that is not you, something is scanning this port.");
    }

    /**
     * Serves the health check: 200 while the server's main thread is alive, 503 when it is not.
     *
     * <p>Unauthenticated, and deliberately says nothing beyond that one word. An uptime monitor
     * cannot carry a secret, and the endpoint gives away nothing the 401 on every other path does
     * not already give away.</p>
     *
     * <p>What it actually checks is the age of the snapshot. Snapshots are taken by the main thread
     * every few seconds, so a stale one means the main thread has stopped taking them - a deadlock,
     * a stop-the-world pause that never ended, a crash mid-shutdown. That is the outage worth
     * paging somebody about, and it is invisible to anything that only checks whether the port
     * still answers, because this HTTP server has its own threads and will answer cheerfully long
     * after the game has stopped.</p>
     *
     * <p>Lag is deliberately <b>not</b> unhealthy here. A laggy server is still up, alerts already
     * cover it, and returning 503 for it would have uptime monitors paging for something that is
     * not an outage.</p>
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        LiveSnapshot current = snapshot;
        long age = System.currentTimeMillis() - current.generatedAt();
        boolean healthy = current.monitoring() && age < STALE_SNAPSHOT_MILLIS;
        respond(exchange, healthy ? 200 : 503, "text/plain; charset=utf-8",
                healthy ? "ok\n" : "unavailable\n");
    }

    /** Builds the {@code /api/live} response: current state plus chart points. */
    private String liveJson() {
        return "{\"live\":" + snapshot.toJson() + ",\"chart\":" + history.toJsonArray() + "}";
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            noteRejection(exchange);
            respond(exchange, 401, "text/plain; charset=utf-8", "Missing or invalid token.");
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", page);
    }

    private void handleApi(HttpExchange exchange, Supplier body) throws IOException {
        if (!authorized(exchange)) {
            noteRejection(exchange);
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
        headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        headers.set("Referrer-Policy", "no-referrer");
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
