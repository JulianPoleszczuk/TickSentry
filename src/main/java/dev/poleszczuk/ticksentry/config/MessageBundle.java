package dev.poleszczuk.ticksentry.config;

import dev.poleszczuk.ticksentry.monitor.LagCategory;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The text players and moderators see, kept out of the code so it can be translated.
 *
 * <p>Everything here is what appears <em>in chat</em>: the lag warning, the clean-up notice, the
 * update notice, and the names of the causes. Those are the lines a server's own players read,
 * and on a server that does not run in English they are the ones that matter. Console output,
 * command replies and the generated advice sentences are still English in the code.</p>
 *
 * <p>Missing keys fall back to the copy shipped inside the jar rather than to an error or an
 * empty line. A {@code messages.yml} written for an older version keeps working when a new key
 * appears, which means an update never silently blanks a message.</p>
 */
public final class MessageBundle {

    /** Name of the file, both inside the jar and in the plugin folder. */
    public static final String FILE_NAME = "messages.yml";

    private final Plugin plugin;

    private FileConfiguration messages;
    private FileConfiguration fallback;

    /**
     * @param plugin plugin instance, for its data folder and bundled resources
     */
    public MessageBundle(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-reads the file from disk, writing the default one first if it is missing. */
    public void reload() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
        this.fallback = loadBundled();
    }

    /** Reads the copy inside the jar, which is what any key missing from disk falls back to. */
    private FileConfiguration loadBundled() {
        try (InputStream in = plugin.getResource(FILE_NAME)) {
            if (in == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not read the bundled " + FILE_NAME + ": " + ex);
            return new YamlConfiguration();
        }
    }

    /**
     * Looks up a message and fills in its placeholders.
     *
     * @param key         dotted key, for example {@code alert.lagging}
     * @param replacements alternating placeholder names and values, without the braces
     * @return the finished line with {@code &} colour codes translated
     */
    public String get(String key, String... replacements) {
        String template = messages.getString(key);
        if (template == null) {
            template = fallback.getString(key);
        }
        if (template == null) {
            // Showing the key beats showing nothing: it says exactly what to add to the file.
            return ChatColor.RED + "[" + key + "]";
        }
        return ChatColor.translateAlternateColorCodes('&', Placeholders.fill(template, replacements));
    }

    /**
     * @param key dotted key
     * @return whether the message exists and is not blank
     */
    public boolean has(String key) {
        String template = messages.getString(key, fallback.getString(key));
        return template != null && !template.isEmpty();
    }

    /**
     * @param category lag category
     * @return its display name, translated when the file provides one
     */
    public String categoryTitle(LagCategory category) {
        String key = "category." + category.name() + ".title";
        String title = messages.getString(key, fallback.getString(key));
        return title == null ? category.title() : ChatColor.translateAlternateColorCodes('&', title);
    }

    /**
     * @param category lag category
     * @return its one-sentence explanation, translated when the file provides one
     */
    public String categoryDescription(LagCategory category) {
        String key = "category." + category.name() + ".description";
        String text = messages.getString(key, fallback.getString(key));
        return text == null ? category.description() : ChatColor.translateAlternateColorCodes('&', text);
    }
}
