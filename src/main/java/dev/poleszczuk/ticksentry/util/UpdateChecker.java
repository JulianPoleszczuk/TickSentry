package dev.poleszczuk.ticksentry.util;

import dev.poleszczuk.ticksentry.config.MessageBundle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks the GitHub releases page whether a newer version exists.
 *
 * <p>A monitoring plugin that quietly runs a version with a fixed detection bug is worse than
 * useless, and nobody re-reads a release page for a plugin that has been working. One HTTP
 * request at startup is a fair trade.</p>
 *
 * <p>Nothing is downloaded or installed - the admin is told, and decides. The request runs on
 * the JDK HTTP client's own thread pool, so the main thread never waits for it, and every
 * failure is silent apart from a line in the log at fine level: an unreachable GitHub is not a
 * problem the admin needs to hear about.</p>
 */
public final class UpdateChecker implements Listener {

    private final Plugin plugin;
    private final MessageBundle messages;
    private final String releasesApi;
    private final String releasesPage;

    private volatile String latestVersion;

    /**
     * @param plugin     plugin instance, for its version and logging
     * @param repository GitHub repository in {@code owner/name} form
     * @param messages   the notice shown in game, so it can be translated
     */
    public UpdateChecker(Plugin plugin, String repository, MessageBundle messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.releasesApi = "https://api.github.com/repos/" + repository + "/releases/latest";
        this.releasesPage = "https://github.com/" + repository + "/releases";
    }

    /** @return the newer version found, or {@code null} when this one is current */
    public String latestVersion() {
        return latestVersion;
    }

    /** Starts the check. Returns immediately; the result arrives on a background thread. */
    public void checkAsync() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(releasesApi))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TickSentry (Minecraft plugin)")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(this::handle)
                .exceptionally(error -> {
                    // GitHub being unreachable is not the admin's problem to solve.
                    plugin.getLogger().fine("Could not check for updates: " + error);
                    return null;
                });
    }

    private void handle(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            plugin.getLogger().fine("Update check returned HTTP " + response.statusCode() + ".");
            return;
        }
        String tag = Json.readString(response.body(), "tag_name");
        if (tag == null || !Version.isNewer(tag, plugin.getDescription().getVersion())) {
            return;
        }
        this.latestVersion = tag;
        plugin.getLogger().info("A newer TickSentry is available: " + tag
                + " (you have " + plugin.getDescription().getVersion() + "). " + releasesPage);
    }

    /**
     * Tells an admin who joins, once, in case nobody reads the console.
     *
     * @param event join event
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String newer = latestVersion;
        if (newer == null || !event.getPlayer().hasPermission("ticksentry.admin")) {
            return;
        }
        event.getPlayer().sendMessage(messages.get("update.available",
                "latest", newer,
                "current", plugin.getDescription().getVersion(),
                "url", releasesPage));
    }
}
