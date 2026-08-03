package dev.poleszczuk.ticksentry.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wczytuje i udostepnia ustawienia z {@code config.yml}.
 *
 * <p>Pola sa {@code volatile}, poniewaz czyta je zarowno glowny watek serwera
 * (monitor, komendy), jak i watek wysylajacy webhooki.</p>
 */
public final class ConfigManager {

    private final Plugin plugin;

    private volatile double msptThresholdMs;
    private volatile int sustainedSeconds;
    private volatile int scanCooldownSeconds;
    private volatile int rollingAverageTicks;

    private volatile boolean discordEnabled;
    private volatile String webhookUrl;
    private volatile String mentionRoleId;

    private volatile Set<String> ignoredWorlds;
    private volatile int topChunksCount;

    /**
     * Tworzy managera i od razu wczytuje konfiguracje z dysku.
     *
     * @param plugin instancja pluginu, z ktorej pobierany jest {@link FileConfiguration}
     */
    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Ponownie wczytuje {@code config.yml} z dysku i aktualizuje wszystkie ustawienia.
     * Wartosci spoza rozsadnego zakresu sa przycinane, zeby bledny config nie zawiesil monitora.
     */
    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        this.msptThresholdMs = Math.max(1.0D, cfg.getDouble("monitor.mspt-threshold-ms", 50.0D));
        this.sustainedSeconds = Math.max(1, cfg.getInt("monitor.sustained-seconds", 10));
        this.scanCooldownSeconds = Math.max(0, cfg.getInt("monitor.scan-cooldown-seconds", 300));
        this.rollingAverageTicks = Math.min(6000, Math.max(20, cfg.getInt("monitor.rolling-average-ticks", 100)));

        this.discordEnabled = cfg.getBoolean("discord.enabled", true);
        this.webhookUrl = cfg.getString("discord.webhook-url", "").trim();
        this.mentionRoleId = cfg.getString("discord.mention-role-id", "").trim();

        List<String> worlds = cfg.getStringList("scan.ignored-worlds");
        this.ignoredWorlds = worlds.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.topChunksCount = Math.min(25, Math.max(1, cfg.getInt("scan.top-chunks-count", 5)));
    }

    /** @return prog MSPT w milisekundach, powyzej ktorego serwer uznajemy za przeciazony */
    public double msptThresholdMs() {
        return msptThresholdMs;
    }

    /** @return liczba sekund nieprzerwanego przekroczenia progu wymagana do wywolania alertu */
    public int sustainedSeconds() {
        return sustainedSeconds;
    }

    /** @return minimalny odstep miedzy alertami w sekundach */
    public int scanCooldownSeconds() {
        return scanCooldownSeconds;
    }

    /** @return rozmiar okna sredniej kroczacej MSPT wyrazony w tickach */
    public int rollingAverageTicks() {
        return rollingAverageTicks;
    }

    /** @return czy wysylanie alertow na Discord jest wlaczone i poprawnie skonfigurowane */
    public boolean discordEnabled() {
        return discordEnabled && !webhookUrl.isEmpty();
    }

    /** @return adres webhooka Discorda (moze byc pusty, jesli nie skonfigurowano) */
    public String webhookUrl() {
        return webhookUrl;
    }

    /** @return ID roli do oznaczenia przy alercie lub pusty ciag, jesli nie ustawiono */
    public String mentionRoleId() {
        return mentionRoleId;
    }

    /**
     * Sprawdza, czy dany swiat ma byc pomijany przy skanowaniu.
     *
     * @param worldName nazwa swiata
     * @return {@code true}, jesli swiat jest na liscie wykluczen
     */
    public boolean isWorldIgnored(String worldName) {
        return ignoredWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    /** @return ile najbardziej podejrzanych chunkow ma zwrocic skaner */
    public int topChunksCount() {
        return topChunksCount;
    }
}
