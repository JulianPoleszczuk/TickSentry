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
 * Wbudowany panel webowy z podgladem kondycji serwera.
 *
 * <p>Korzysta z {@link HttpServer} dostarczanego przez JDK, wiec nie dokłada zadnej zaleznosci.
 * Panel dziala na tej samej maszynie co serwer gry - nie ma zadnej uslugi zewnetrznej,
 * niczego nie trzeba hostowac.</p>
 *
 * <p><b>Watki:</b> handlery HTTP dzialaja poza glownym watkiem serwera, dlatego nie siegaja
 * po Bukkit API ani po baze. Czytaja wylacznie gotowe migawki, ktore podaje im glowny watek
 * przez {@link #update(LiveSnapshot)} i {@link #updateIncidents(String)}.</p>
 *
 * <p><b>Bezpieczenstwo:</b> kazde zadanie musi podac token (parametr {@code ?token=} albo
 * naglowek {@code X-Auth-Token}). Polaczenie idzie czystym HTTP, wiec domyslna konfiguracja
 * nasluchuje tylko na {@code 127.0.0.1}. Wystawienie panelu na swiat wymaga postawienia przed
 * nim reverse proxy z HTTPS - inaczej token leci otwartym tekstem.</p>
 */
public final class DashboardServer {

    /** Ile watkow obsluguje zadania HTTP - panel jest jednoosobowy, wiecej nie potrzeba. */
    private static final int HTTP_THREADS = 2;

    private final Plugin plugin;
    private final String token;
    private final String page;

    private volatile LiveSnapshot snapshot = LiveSnapshot.empty();
    private volatile String incidentsJson = "[]";
    private final MsptHistory history;
    private HttpServer server;

    /**
     * @param plugin  instancja pluginu (log)
     * @param token   token wymagany przy kazdym zadaniu
     * @param history bufor probek zasilajacy wykres
     */
    public DashboardServer(Plugin plugin, String token, MsptHistory history) {
        this.plugin = plugin;
        this.token = token;
        this.history = history;
        this.page = loadPage();
    }

    /**
     * Uruchamia serwer HTTP.
     *
     * @param bind adres nasluchu, np. {@code 127.0.0.1}
     * @param port port nasluchu
     * @return {@code true}, jesli panel wystartowal
     */
    public boolean start(String bind, int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            server.createContext("/", this::handlePage);
            server.createContext("/api/live", exchange -> handleApi(exchange, this::liveJson));
            server.createContext("/api/incidents", exchange -> handleApi(exchange, () -> incidentsJson));
            server.setExecutor(Executors.newFixedThreadPool(HTTP_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "TickSentry-Dashboard");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            plugin.getLogger().info("Panel webowy: http://" + bind + ":" + port + "/?token=" + token);
            return true;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Nie udalo sie uruchomic panelu webowego", ex);
            return false;
        }
    }

    /** Zatrzymuje serwer HTTP. */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * Podmienia migawke pokazywana w panelu.
     *
     * @param snapshot swieza migawka zebrana na glownym watku
     */
    public void update(LiveSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * Podmienia liste ostatnich incydentow.
     *
     * @param json gotowa tablica JSON z incydentami
     */
    public void updateIncidents(String json) {
        this.incidentsJson = json;
    }

    /** Sklada odpowiedz dla {@code /api/live}: biezacy stan plus punkty do wykresu. */
    private String liveJson() {
        return "{\"live\":" + snapshot.toJson() + ",\"chart\":" + history.toJsonArray() + "}";
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            respond(exchange, 401, "text/plain; charset=utf-8", "Brak lub bledny token.");
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

    /** Sprawdza token z parametru zapytania albo z naglowka, odporny na roznice czasu porownania. */
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
        // Panel jest lokalny i nie ma byc osadzany ani odpytywany z innych stron.
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Wczytuje strone panelu z zasobow jara. */
    private String loadPage() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("dashboard.html")) {
            if (in == null) {
                return "<h1>Brak dashboard.html w jarze</h1>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Nie udalo sie wczytac strony panelu", ex);
            return "<h1>Blad wczytywania panelu</h1>";
        }
    }

    /**
     * Generuje losowy token dostepu.
     *
     * @return token zapisywany potem w config.yml
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

    /** Dostawca tresci odpowiedzi - wlasny odpowiednik {@code Supplier<String>} rzucajacy IO. */
    @FunctionalInterface
    private interface Supplier {
        String get();
    }
}
