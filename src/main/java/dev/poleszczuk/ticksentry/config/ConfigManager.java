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

    private volatile boolean recoveryAlert;
    private volatile int recoverySeconds;
    private volatile boolean storageEnabled;
    private volatile int storageKeepDays;

    private volatile boolean dashboardEnabled;
    private volatile String dashboardBind;
    private volatile int dashboardPort;
    private volatile String dashboardToken;

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
        this.recoveryAlert = cfg.getBoolean("monitor.recovery-alert", true);
        this.recoverySeconds = Math.max(1, cfg.getInt("monitor.recovery-seconds", 15));

        this.storageEnabled = cfg.getBoolean("storage.enabled", true);
        this.storageKeepDays = Math.max(0, cfg.getInt("storage.keep-days", 30));

        this.dashboardEnabled = cfg.getBoolean("dashboard.enabled", false);
        this.dashboardBind = cfg.getString("dashboard.bind", "127.0.0.1").trim();
        this.dashboardPort = Math.min(65535, Math.max(1, cfg.getInt("dashboard.port", 8080)));
        this.dashboardToken = cfg.getString("dashboard.token", "").trim();

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

    /** @return czy wysylac osobna wiadomosc po powrocie serwera do normy */
    public boolean recoveryAlert() {
        return recoveryAlert;
    }

    /** @return ile sekund ponizej progu oznacza koniec incydentu */
    public int recoverySeconds() {
        return recoverySeconds;
    }

    /** @return czy incydenty maja byc zapisywane na dysk */
    public boolean storageEnabled() {
        return storageEnabled;
    }

    /** @return po ilu dniach kasowac stare incydenty (0 = nigdy) */
    public int storageKeepDays() {
        return storageKeepDays;
    }

    /** @return czy panel webowy ma zostac uruchomiony */
    public boolean dashboardEnabled() {
        return dashboardEnabled;
    }

    /** @return adres nasluchu panelu (domyslnie tylko lokalny) */
    public String dashboardBind() {
        return dashboardBind;
    }

    /** @return port panelu webowego */
    public int dashboardPort() {
        return dashboardPort;
    }

    /** @return token dostepu do panelu; pusty oznacza, ze trzeba go wygenerowac */
    public String dashboardToken() {
        return dashboardToken;
    }

    /**
     * Zapisuje wygenerowany token panelu do {@code config.yml}, zeby przetrwal restart.
     *
     * @param token nowy token dostepu
     */
    public void saveDashboardToken(String token) {
        this.dashboardToken = token;
        plugin.getConfig().set("dashboard.token", token);
        plugin.saveConfig();
    }
}
