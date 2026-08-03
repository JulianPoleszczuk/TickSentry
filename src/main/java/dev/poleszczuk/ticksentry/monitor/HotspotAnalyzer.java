package dev.poleszczuk.ticksentry.monitor;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ocenia i porzadkuje migawki chunkow oraz zgaduje przyczyne lagu.
 *
 * <p>Cala klasa jest czysta (bez Bukkita i bez stanu), zeby dalo sie ja pokryc testami
 * jednostkowymi. To tutaj mieszka jedyna "inteligencja" pluginu: wagi kosztu obiektow
 * i progi decydujace o kategorii.</p>
 *
 * <p>Wagi sa przyblizeniem realnego kosztu ticku wzgledem przecietnego moba (= 1.0).
 * Nie sa wynikiem profilowania - maja jedynie sprawic, ze 40 hopperow wazy wiecej
 * niz 40 skrzyn, a 200 lezacych itemow nie przebija 200 villagerow.</p>
 */
public final class HotspotAnalyzer {

    /**
     * Ponizej tego wyniku chunk nie jest uznawany za podejrzany.
     *
     * <p>Prog skalibrowany na zywym serwerze: przy wartosci 25 zwykly chunk z kilkudziesiecioma
     * spadajacymi blokami podczas generowania terenu dostawal etykiete "farma mobow".
     * Chunk musi wyraznie odstawac, zeby w ogole trafil do raportu.</p>
     */
    public static final double MIN_INTERESTING_SCORE = 80.0D;

    /** Udzial jednego typu encji, od ktorego mowimy o "dominacji" (farma, zwal itemow). */
    private static final double DOMINANCE_SHARE = 0.5D;

    /** Od tylu encji chunk w ogole moze dostac kategorie zwiazana z encjami. */
    private static final int ENTITY_HEAVY_COUNT = 60;

    /** Od tylu graczy mowimy o skupisku graczy. */
    private static final int PLAYER_CLUSTER_COUNT = 5;

    /** Udzial block-entity w wyniku, od ktorego winowajca jest redstone. */
    private static final double TILE_DOMINANCE_SHARE = 0.6D;

    private static final double DEFAULT_ENTITY_WEIGHT = 1.0D;
    private static final double DEFAULT_TILE_WEIGHT = 0.3D;

    private static final Map<String, Double> ENTITY_WEIGHTS = Map.ofEntries(
            // Gracz kosztuje duzo wiecej niz mob: trzyma zaladowane chunki wokol siebie i generuje ruch sieciowy.
            Map.entry("PLAYER", 5.0D),
            Map.entry("ITEM", 0.5D),
            Map.entry("DROPPED_ITEM", 0.5D),
            Map.entry("EXPERIENCE_ORB", 0.6D),
            // Encje krotkotrwale - pojawiaja sie masowo przy generowaniu terenu i walce, ale szybko znikaja.
            Map.entry("FALLING_BLOCK", 0.3D),
            Map.entry("ARROW", 0.3D),
            Map.entry("SNOWBALL", 0.2D),
            Map.entry("ARMOR_STAND", 0.4D),
            Map.entry("ITEM_FRAME", 0.2D),
            Map.entry("GLOW_ITEM_FRAME", 0.2D),
            Map.entry("PAINTING", 0.1D),
            Map.entry("VILLAGER", 3.0D),
            Map.entry("WANDERING_TRADER", 2.0D),
            Map.entry("IRON_GOLEM", 1.5D),
            Map.entry("ALLAY", 1.5D),
            Map.entry("PIGLIN", 1.5D),
            Map.entry("PIGLIN_BRUTE", 1.5D),
            Map.entry("HOGLIN", 1.5D),
            Map.entry("ZOMBIFIED_PIGLIN", 1.5D),
            Map.entry("HOPPER_MINECART", 2.5D),
            Map.entry("MINECART_HOPPER", 2.5D),
            Map.entry("CHEST_MINECART", 1.5D),
            Map.entry("MINECART_CHEST", 1.5D),
            Map.entry("ZOMBIE", 1.2D),
            Map.entry("SKELETON", 1.2D),
            Map.entry("CREEPER", 1.2D),
            Map.entry("SPIDER", 1.2D),
            Map.entry("ENDERMAN", 1.2D)
    );

    private static final Map<String, Double> TILE_WEIGHTS = Map.ofEntries(
            Map.entry("HOPPER", 3.0D),
            Map.entry("DROPPER", 1.0D),
            Map.entry("DISPENSER", 1.0D),
            Map.entry("SPAWNER", 2.5D),
            Map.entry("TRIAL_SPAWNER", 2.5D),
            Map.entry("BEACON", 1.5D),
            Map.entry("CONDUIT", 1.5D),
            Map.entry("BREWING_STAND", 0.5D),
            Map.entry("FURNACE", 0.8D),
            Map.entry("BLAST_FURNACE", 0.8D),
            Map.entry("SMOKER", 0.8D),
            Map.entry("CAMPFIRE", 0.3D),
            Map.entry("SOUL_CAMPFIRE", 0.3D),
            Map.entry("CHEST", 0.15D),
            Map.entry("TRAPPED_CHEST", 0.15D),
            Map.entry("BARREL", 0.15D),
            Map.entry("ENDER_CHEST", 0.15D)
    );

    private HotspotAnalyzer() {
    }

    /**
     * Liczy wazony koszt encji w chunku.
     *
     * @param stat migawka chunka
     * @return suma wag wszystkich encji
     */
    public static double entityScore(ChunkStat stat) {
        double score = 0.0D;
        for (Map.Entry<String, Integer> entry : stat.entityTypeCounts().entrySet()) {
            score += entityWeight(entry.getKey()) * entry.getValue();
        }
        return score;
    }

    /**
     * Liczy wazony koszt block-entity w chunku.
     *
     * @param stat migawka chunka
     * @return suma wag wszystkich block-entity
     */
    public static double tileScore(ChunkStat stat) {
        double score = 0.0D;
        for (Map.Entry<String, Integer> entry : stat.tileTypeCounts().entrySet()) {
            score += tileWeight(entry.getKey()) * entry.getValue();
        }
        return score;
    }

    /**
     * Laczny wynik chunka - im wyzszy, tym bardziej prawdopodobny winowajca.
     *
     * @param stat migawka chunka
     * @return suma wynikow encji i block-entity
     */
    public static double score(ChunkStat stat) {
        return entityScore(stat) + tileScore(stat);
    }

    /**
     * Wybiera najbardziej podejrzane chunki.
     *
     * @param stats wszystkie zeskanowane chunki
     * @param limit maksymalna liczba wynikow
     * @return lista posortowana malejaco po wyniku, bez chunkow ponizej progu istotnosci
     */
    public static List<ChunkStat> topChunks(List<ChunkStat> stats, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return stats.stream()
                .filter(stat -> score(stat) >= MIN_INTERESTING_SCORE)
                // Remisy rozstrzygamy liczba encji, a potem lokalizacja - wynik ma byc powtarzalny.
                .sorted(Comparator.comparingDouble(HotspotAnalyzer::score).reversed()
                        .thenComparing(Comparator.comparingInt(ChunkStat::entityCount).reversed())
                        .thenComparing(ChunkStat::prettyLocation))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Zgaduje przyczyne lagu dla pojedynczego chunka.
     *
     * @param stat migawka chunka
     * @return dopasowana kategoria, nigdy {@code null}
     */
    public static LagCategory categorize(ChunkStat stat) {
        double entityScore = entityScore(stat);
        double tileScore = tileScore(stat);
        double total = entityScore + tileScore;
        if (total < MIN_INTERESTING_SCORE) {
            return LagCategory.UNKNOWN;
        }

        if (tileScore >= total * TILE_DOMINANCE_SHARE) {
            return LagCategory.REDSTONE;
        }

        Map.Entry<String, Integer> dominant = stat.dominantEntityType();
        double dominantShare = dominant == null || stat.entityCount() == 0
                ? 0.0D
                : (double) dominant.getValue() / stat.entityCount();

        if (dominant != null && dominantShare >= DOMINANCE_SHARE && stat.entityCount() >= ENTITY_HEAVY_COUNT) {
            String type = dominant.getKey().toUpperCase(Locale.ROOT);
            if (isItemLike(type)) {
                return LagCategory.ITEM_CLUTTER;
            }
            if ("PLAYER".equals(type)) {
                return LagCategory.PLAYER_CLUSTER;
            }
            return LagCategory.MOB_FARM;
        }

        if (stat.playerCount() >= PLAYER_CLUSTER_COUNT) {
            return LagCategory.PLAYER_CLUSTER;
        }

        if (stat.entityCount() >= ENTITY_HEAVY_COUNT) {
            return LagCategory.ENTITY_OVERLOAD;
        }

        return LagCategory.UNKNOWN;
    }

    /**
     * Buduje podpowiedz dla admina - konkretna komende albo krotka instrukcje.
     *
     * @param stat     migawka chunka
     * @param category kategoria wyliczona przez {@link #categorize(ChunkStat)}
     * @return jednozdaniowa sugestia dzialania
     */
    public static String suggestedAction(ChunkStat stat, LagCategory category) {
        String tp = "/tp " + stat.blockX() + " ~ " + stat.blockZ();
        Map.Entry<String, Integer> dominantEntity = stat.dominantEntityType();
        Map.Entry<String, Integer> dominantTile = stat.dominantTileType();

        return switch (category) {
            case MOB_FARM -> dominantEntity == null
                    ? "Skocz na miejsce (" + tp + ") i sprawdz, co sie tam nazbieralo."
                    : "Skocz na miejsce (" + tp + "). Podejrzana farma: " + dominantEntity.getValue() + "x "
                    + friendly(dominantEntity.getKey()) + ". Doraznie: " + killCommand(stat, dominantEntity.getKey());
            case ITEM_CLUTTER -> "Posprzataj lezace przedmioty: " + killCommand(stat, "item")
                    + " (najpierw " + tp + ", zeby zobaczyc czyje to).";
            case REDSTONE -> dominantTile == null
                    ? "Sprawdz maszyne redstone w tym miejscu (" + tp + ")."
                    : "Sprawdz maszyne redstone (" + tp + "): " + dominantTile.getValue() + "x "
                    + friendly(dominantTile.getKey()) + ". Hoppery warto ograniczyc lub zastapic wodnym transportem.";
            case PLAYER_CLUSTER -> "W tym chunku jest " + stat.playerCount()
                    + " graczy - jesli to spawn albo event, lag jest spodziewany. Sprawdz: " + tp + ".";
            case ENTITY_OVERLOAD -> "Duzo roznych encji (" + stat.entityCount() + ") w jednym chunku. Zajrzyj tam: " + tp + ".";
            case UNKNOWN -> "Zaden pojedynczy chunk sie nie wyroznia - przyczyna moze byc poza swiatem gry "
                    + "(plugin, zapis mapy, generowanie terenu). Warto odpalic spark profiler.";
        };
    }

    /**
     * Buduje polecenie usuwajace encje danego typu w obrebie wskazanego chunka.
     *
     * @param stat migawka chunka
     * @param type typ encji (nazwa Bukkitowa lub identyfikator wanilii)
     * @return gotowa do wklejenia komenda {@code /kill}
     */
    public static String killCommand(ChunkStat stat, String type) {
        int cornerX = stat.chunkX() * 16;
        int cornerZ = stat.chunkZ() * 16;
        return "/kill @e[type=" + vanillaId(type) + ",x=" + cornerX + ",y=-64,z=" + cornerZ
                + ",dx=16,dy=384,dz=16]";
    }

    /** @return waga kosztu encji danego typu */
    public static double entityWeight(String entityType) {
        return ENTITY_WEIGHTS.getOrDefault(entityType.toUpperCase(Locale.ROOT), DEFAULT_ENTITY_WEIGHT);
    }

    /** @return waga kosztu block-entity danego typu */
    public static double tileWeight(String tileType) {
        String type = tileType.toUpperCase(Locale.ROOT);
        Double exact = TILE_WEIGHTS.get(type);
        if (exact != null) {
            return exact;
        }
        // Dekoracje wystepuja w setkach wariantow (tabliczki, banery, glowy) i sa praktycznie darmowe.
        if (type.endsWith("SIGN") || type.endsWith("BANNER") || type.endsWith("HEAD")
                || type.endsWith("SKULL") || type.endsWith("BED") || type.endsWith("POT")) {
            return 0.05D;
        }
        if (type.endsWith("SHULKER_BOX")) {
            return 0.15D;
        }
        return DEFAULT_TILE_WEIGHT;
    }

    private static boolean isItemLike(String type) {
        return "ITEM".equals(type) || "DROPPED_ITEM".equals(type) || "EXPERIENCE_ORB".equals(type);
    }

    private static String vanillaId(String type) {
        String lower = type.toLowerCase(Locale.ROOT);
        // Bukkit historycznie nazywa lezacy przedmiot DROPPED_ITEM, wanilia zna tylko "item".
        return "dropped_item".equals(lower) ? "item" : lower;
    }

    private static String friendly(String type) {
        return type.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
