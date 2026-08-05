package dev.poleszczuk.ticksentry.discord;

import dev.poleszczuk.ticksentry.alert.AlertSink;
import dev.poleszczuk.ticksentry.config.ConfigManager;
import dev.poleszczuk.ticksentry.config.MessageBundle;
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
import java.util.function.DoubleSupplier;
import java.util.stream.Collectors;
import java.util.logging.Level;

/**
 * Sends alerts to a Discord webhook.
 *
 * <p>All network traffic runs on a separate daemon thread - the main server thread never waits
 * on I/O. The payload itself is built synchronously from a finished {@link LagEvent}, which is
 * an immutable snapshot, so nothing touches Bukkit off the main thread.</p>
 */
public final class DiscordWebhookClient implements AlertSink {

    private static final int COLOR_CRITICAL = 0xE74C3C;
    private static final int COLOR_WARNING = 0xE67E22;
    private static final int COLOR_NOTICE = 0xF1C40F;
    private static final int COLOR_OK = 0x2ECC71;

    /** How many "other suspicious places" to list below the main culprit. */
    private static final int EXTRA_CHUNKS_SHOWN = 3;

    private final Plugin plugin;
    private final ConfigManager config;
    private final MessageBundle messages;
    private final DoubleSupplier threshold;
    private final HttpClient http;
    private final ExecutorService executor;

    /**
     * @param plugin    plugin instance (logging)
     * @param config    source of the webhook address and mention settings
     * @param messages  translatable labels; colour codes are stripped, Discord has no use for them
     * @param threshold the tick time currently counted as overloaded - read through a supplier
     *                  because the adaptive threshold moves while the server runs
     */
    public DiscordWebhookClient(Plugin plugin, ConfigManager config, MessageBundle messages,
                                DoubleSupplier threshold) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.threshold = threshold;
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
     * Builds and sends an alert about an incident. Returns immediately - delivery happens in the background.
     *
     * @param event incident to report
     */
    public void sendLagAlert(LagEvent event) {
        if (!config.discordEnabled()) {
            return;
        }
        String payload = buildPayload(event);
        String url = config.webhookUrl();
        executor.execute(() -> post(url, payload));
    }

    /**
     * Sends a note that the server is healthy again.
     *
     * @param durationSeconds how long the incident lasted
     * @param tps             TPS after recovery
     * @param mspt            tick time after recovery
     */
    public void sendRecovery(long durationSeconds, double tps, double mspt) {
        if (!config.discordEnabled()) {
            return;
        }
        EmbedBuilder embed = new EmbedBuilder()
                .title(messages.plain("discord.title-recovered"))
                .color(COLOR_OK)
                .timestamp(java.time.Instant.now())
                .description(messages.plain("discord.description-recovered"))
                .field(messages.plain("discord.field.duration"), humanDuration(durationSeconds), true)
                .field(messages.plain("discord.field.right-now"),
                        String.format(Locale.ROOT, "TPS: **%.1f** / 20%nTick time: **%.0f ms**", tps, mspt), true)
                .footer("TickSentry");

        String payload = "{\"username\":\"TickSentry\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":["
                + embed.toJson() + "]}";
        String url = config.webhookUrl();
        executor.execute(() -> post(url, payload));
    }

    /**
     * Reports what the automatic clean-up did, or would have done in dry-run.
     *
     * <p>Removing things players own is not something to do quietly, so it gets its own message
     * rather than a footnote on the incident that triggered it.</p>
     *
     * @param summary multi-line description of the actions
     */
    public void sendRemediation(String summary) {
        if (!config.discordEnabled()) {
            return;
        }
        EmbedBuilder embed = new EmbedBuilder()
                .title(messages.plain("discord.title-remediation"))
                .color(COLOR_NOTICE)
                .timestamp(java.time.Instant.now())
                .description(summary)
                .footer("TickSentry");

        String payload = "{\"username\":\"TickSentry\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":["
                + embed.toJson() + "]}";
        String url = config.webhookUrl();
        executor.execute(() -> post(url, payload));
    }

    /**
     * Turns a number of seconds into wording like "4 min 12 s".
     *
     * @param seconds duration in seconds
     * @return readable description
     */
    static String humanDuration(long seconds) {
        if (seconds < 60L) {
            return seconds + " s";
        }
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        if (minutes < 60L) {
            return rest == 0L ? minutes + " min" : minutes + " min " + rest + " s";
        }
        long hours = minutes / 60L;
        return hours + " h " + (minutes % 60L) + " min";
    }

    @Override
    public void incident(LagEvent event) {
        sendLagAlert(event);
    }

    @Override
    public void recovery(long durationSeconds, double tps, double mspt) {
        sendRecovery(durationSeconds, tps, mspt);
    }

    @Override
    public void remediation(String summary) {
        sendRemediation(summary);
    }

    @Override
    public boolean isConfigured() {
        return config.discordEnabled();
    }

    @Override
    public String name() {
        return "Discord";
    }

    /** Shuts down the delivery thread, allowing a moment to finish pending work. */
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

    /** Sends a finished payload, retrying once when Discord applies a rate limit. */
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
                plugin.getLogger().warning("Discord rejected the alert (HTTP " + response.statusCode() + "): "
                        + response.body());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Could not deliver the alert to Discord", ex);
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
     * Assembles the full webhook request body with the embed and an optional role mention.
     *
     * @param event incident to describe
     * @return JSON ready to send
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
     * Turns an incident into an embed written for an admin, free of profiler jargon.
     *
     * @param event incident to describe
     * @return finished embed builder
     */
    EmbedBuilder buildEmbed(LagEvent event) {
        ChunkStat primary = event.primaryChunk();
        boolean healthy = event.manual() && event.averageMspt() <= threshold.getAsDouble();

        EmbedBuilder embed = new EmbedBuilder()
                .title(healthy ? messages.plain("discord.title-healthy") : title(event))
                .color(healthy ? COLOR_OK : color(event.tps()))
                .timestamp(event.timestamp())
                .footer(messages.plain("discord.footer",
                        "chunks", String.valueOf(event.loadedChunks()),
                        "entities", String.valueOf(event.totalEntities())));

        embed.description(healthy
                ? messages.plain("discord.description-healthy")
                : messages.plain("discord.description",
                        "cause", messages.plainCategoryTitle(event.category()),
                        "explanation", messages.plainCategoryDescription(event.category())
                                .toLowerCase(Locale.ROOT)));

        StringBuilder health = new StringBuilder(String.format(Locale.ROOT,
                "TPS: **%.1f** / 20%nTick time: **%.0f ms** (threshold %.0f ms)",
                event.tps(), event.averageMspt(), threshold.getAsDouble()));
        if (event.hasPercentiles()) {
            // The average is what tripped the alert; the percentiles are what the admin needs to
            // tell "generally slow" apart from "stopping dead a few times a second".
            health.append(String.format(Locale.ROOT, "%nBad ticks: **p95 %.0f ms**, p99 %.0f ms",
                    event.p95Ms(), event.p99Ms()));
        }
        health.append(String.format(Locale.ROOT, "%nLongest freeze: **%.0f ms**", event.peakMs()));
        embed.field(messages.plain("discord.field.health"), health.toString(), true);

        if (primary != null) {
            embed.field(messages.plain("discord.field.where"), describe(primary), true);
        }

        if (primary != null && primary.historyNote() != null) {
            embed.field(messages.plain("discord.field.repeat"),
                    messages.plain("discord.repeat", "history", primary.historyNote()), false);
        }

        embed.field(messages.plain("discord.field.what-to-do"), event.suggestedAction(), false);

        if (event.pluginNote() != null) {
            embed.field(messages.plain("discord.field.plugin"), event.pluginNote(), false);
        }

        if (event.chunkLoadNote() != null) {
            embed.field(messages.plain("discord.field.chunk-loading"), event.chunkLoadNote(), false);
        }

        if (event.memoryNote() != null) {
            embed.field(messages.plain("discord.field.memory"), event.memoryNote(), false);
        }

        if (event.sparkSummary() != null) {
            embed.field(messages.plain("discord.field.spark"), event.sparkSummary(), false);
        }

        List<ChunkStat> others = event.topChunks().stream().skip(1).limit(EXTRA_CHUNKS_SHOWN).collect(Collectors.toList());
        if (!others.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (ChunkStat stat : others) {
                list.append("- ").append(stat.prettyLocation())
                        .append(" (").append(stat.entityCount()).append(" entities, ")
                        .append(stat.tileEntityCount()).append(" block entities)\n");
            }
            embed.field(messages.plain("discord.field.others"), list.toString(), false);
        }

        return embed;
    }

    private String title(LagEvent event) {
        return messages.plain(event.manual() ? "discord.title-requested" : "discord.title-alert");
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

    /** Describes a chunk the way an admin needs it: location plus the two most common types. */
    private static String describe(ChunkStat stat) {
        StringBuilder text = new StringBuilder(stat.prettyLocation()).append('\n');
        List<String> parts = new ArrayList<>();
        parts.addAll(topTypes(stat.entityTypeCounts(), 2));
        parts.addAll(topTypes(stat.tileTypeCounts(), 1));
        if (parts.isEmpty()) {
            text.append(stat.entityCount()).append(" entities, ")
                    .append(stat.tileEntityCount()).append(" block entities");
        } else {
            text.append(String.join("\n", parts));
        }
        if (stat.playerCount() > 0) {
            text.append("\nplayers in chunk: ").append(stat.playerCount());
        }
        if (stat.attribution() != null) {
            text.append("\n_").append(stat.attribution()).append('_');
        }
        return text.toString();
    }

    private static List<String> topTypes(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> entry.getValue() + "x " + entry.getKey().toLowerCase(Locale.ROOT).replace('_', ' '))
                .collect(Collectors.toList());
    }
}
