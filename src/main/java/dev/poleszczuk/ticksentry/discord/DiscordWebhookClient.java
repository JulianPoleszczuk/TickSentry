package dev.poleszczuk.ticksentry.discord;

import dev.poleszczuk.ticksentry.config.ConfigManager;
import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import dev.poleszczuk.ticksentry.monitor.LagEvent;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Wysyla alerty na webhook Discorda.
 *
 * <p>Cala komunikacja sieciowa leci na osobnym watku demona - glowny watek serwera nigdy
 * nie czeka na I/O. Payload budowany jest jednak synchronicznie z gotowego {@link LagEvent},
 * ktory jest niemutowalna migawka danych, wiec nie dotykamy Bukkita spoza glownego watku.</p>
 */
public final class DiscordWebhookClient {

    private static final int COLOR_CRITICAL = 0xE74C3C;
    private static final int COLOR_WARNING = 0xE67E22;
    private static final int COLOR_NOTICE = 0xF1C40F;
    private static final int COLOR_OK = 0x2ECC71;

    /** Ile "innych podejrzanych miejsc" pokazac pod glownym winowajca. */
    private static final int EXTRA_CHUNKS_SHOWN = 3;

    private final Plugin plugin;
    private final ConfigManager config;
    private final HttpClient http;
    private final ExecutorService executor;

    /**
     * @param plugin instancja pluginu (log)
     * @param config zrodlo adresu webhooka i ustawien wzmianki
     */
    public DiscordWebhookClient(Plugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "TickSentry-Webhook");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Buduje i wysyla alert o incydencie. Zwraca natychmiast - wysylka dzieje sie w tle.
     *
     * @param event incydent do zaraportowania
     */
    public void sendLagAlert(LagEvent event) {
        if (!config.discordEnabled()) {
            return;
        }
        String payload = buildPayload(event);
        String url = config.webhookUrl();
        executor.execute(() -> post(url, payload));
    }

    /** Zamyka watek wysylkowy, dajac chwile na dokonczenie zaleglych zadan. */
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

    /** Wysyla gotowy payload, z jedna ponowna proba przy limicie zapytan Discorda. */
    private void post(String url, String payload) {
        try {
            HttpResponse<String> response = execute(url, payload);
            if (response.statusCode() == 429) {
                long waitSeconds = response.headers().firstValue("Retry-After")
                        .map(value -> {
                            try {
                                return Long.parseLong(value.trim());
                            } catch (NumberFormatException ignored) {
                                return 5L;
                            }
                        })
                        .orElse(5L);
                Thread.sleep(Math.min(30L, waitSeconds) * 1000L);
                response = execute(url, payload);
            }
            if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("Discord odrzucil alert (HTTP " + response.statusCode() + "): "
                        + response.body());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Nie udalo sie wyslac alertu na Discorda", ex);
        }
    }

    private HttpResponse<String> execute(String url, String payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "TickSentry (Minecraft plugin)")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Sklada pelne cialo zadania webhooka wraz z embedem i opcjonalna wzmianka roli.
     *
     * @param event incydent do opisania
     * @return JSON gotowy do wyslania
     */
    String buildPayload(LagEvent event) {
        StringBuilder json = new StringBuilder("{\"username\":\"TickSentry\"");

        String roleId = config.mentionRoleId();
        if (!roleId.isEmpty() && !event.manual()) {
            json.append(",\"content\":\"<@&").append(EmbedBuilder.escape(roleId)).append(">\"")
                    .append(",\"allowed_mentions\":{\"roles\":[\"").append(EmbedBuilder.escape(roleId)).append("\"]}");
        } else {
            json.append(",\"allowed_mentions\":{\"parse\":[]}");
        }

        json.append(",\"embeds\":[").append(buildEmbed(event).toJson()).append("]}");
        return json.toString();
    }

    /**
     * Zamienia incydent na embed napisany jezykiem admina, bez zargonu profilerowego.
     *
     * @param event incydent do opisania
     * @return gotowy builder embeda
     */
    EmbedBuilder buildEmbed(LagEvent event) {
        ChunkStat primary = event.primaryChunk();
        boolean healthy = event.manual() && event.averageMspt() <= config.msptThresholdMs();

        EmbedBuilder embed = new EmbedBuilder()
                .title(healthy ? "Raport na zadanie: serwer wyglada zdrowo" : title(event))
                .color(healthy ? COLOR_OK : color(event.tps()))
                .timestamp(event.timestamp())
                .footer("TickSentry - przeskanowano " + event.loadedChunks() + " chunkow w "
                        + event.scanDurationMs() + " ms, lacznie " + event.totalEntities() + " encji");

        embed.description(healthy
                ? "Sprawdzono na zadanie admina. Serwer wyrabia sie z przetwarzaniem swiata."
                : "Serwer nie wyrabia sie z przetwarzaniem swiata - gracze moga odczuwac opoznienia.\n"
                + "**Prawdopodobna przyczyna: " + event.category().title() + "** ("
                + event.category().description().toLowerCase(Locale.ROOT) + ")");

        embed.field("Kondycja serwera", String.format(Locale.ROOT,
                "TPS: **%.1f** / 20%nCzas ticku: **%.0f ms** (norma do %.0f ms)%nNajdluzsza zwiecha: **%.0f ms**",
                event.tps(), event.averageMspt(), config.msptThresholdMs(), event.peakMs()), true);

        if (primary != null) {
            embed.field("Gdzie szukac", describe(primary), true);
        }

        embed.field("Co z tym zrobic", event.suggestedAction(), false);

        List<ChunkStat> others = event.topChunks().stream().skip(1).limit(EXTRA_CHUNKS_SHOWN).toList();
        if (!others.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (ChunkStat stat : others) {
                list.append("- ").append(stat.prettyLocation())
                        .append(" (").append(stat.entityCount()).append(" encji, ")
                        .append(stat.tileEntityCount()).append(" block-entity)\n");
            }
            embed.field("Inne podejrzane miejsca", list.toString(), false);
        }

        return embed;
    }

    private static String title(LagEvent event) {
        return event.manual() ? "Raport na zadanie: serwer laguje" : "Uwaga: serwer laguje";
    }

    private static int color(double tps) {
        if (tps < 15.0D) {
            return COLOR_CRITICAL;
        }
        if (tps < 18.0D) {
            return COLOR_WARNING;
        }
        return COLOR_NOTICE;
    }

    /** Opisuje chunk w formie zrozumialej dla admina: lokalizacja plus dwa najliczniejsze typy. */
    private static String describe(ChunkStat stat) {
        StringBuilder text = new StringBuilder(stat.prettyLocation()).append('\n');
        List<String> parts = new ArrayList<>();
        parts.addAll(topTypes(stat.entityTypeCounts(), 2));
        parts.addAll(topTypes(stat.tileTypeCounts(), 1));
        if (parts.isEmpty()) {
            text.append(stat.entityCount()).append(" encji, ").append(stat.tileEntityCount()).append(" block-entity");
        } else {
            text.append(String.join("\n", parts));
        }
        if (stat.playerCount() > 0) {
            text.append("\ngraczy w chunku: ").append(stat.playerCount());
        }
        return text.toString();
    }

    private static List<String> topTypes(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> entry.getValue() + "x " + entry.getKey().toLowerCase(Locale.ROOT).replace('_', ' '))
                .toList();
    }
}
