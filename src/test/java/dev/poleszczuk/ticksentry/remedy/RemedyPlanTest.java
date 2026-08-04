package dev.poleszczuk.ticksentry.remedy;

import dev.poleszczuk.ticksentry.monitor.ChunkStat;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemedyPlanTest {

    private static RemedySettings settings(boolean clearItems, boolean capMobs) {
        return new RemedySettings(true, false, 30, 600, clearItems, 300, capMobs, 300, 50,
                List.of("VILLAGER", "IRON_GOLEM"));
    }

    private static ChunkStat chunk(Map<String, Integer> entities) {
        return ChunkStat.ofEntities("world", 100, 200, entities);
    }

    @Test
    void nothingHappensWhileRemediationIsDisabled() {
        RemedySettings off = RemedySettings.disabled();

        assertTrue(RemedyPlan.decide(chunk(Map.of("ITEM", 5000)), off).isEmpty());
        assertTrue(RemedyPlan.decide(List.of(chunk(Map.of("ITEM", 5000))), off).isEmpty());
    }

    @Test
    void litterBelowTheThresholdIsLeftAlone() {
        assertTrue(RemedyPlan.decide(chunk(Map.of("ITEM", 299)), settings(true, false)).isEmpty());
    }

    @Test
    void litterAtTheThresholdIsSweptWhole() {
        List<RemedyAction> actions = RemedyPlan.decide(chunk(Map.of("ITEM", 300)), settings(true, false));

        assertEquals(1, actions.size());
        assertEquals(RemedyAction.Kind.CLEAR_ITEMS, actions.get(0).kind());
        assertEquals(300, actions.get(0).toRemove());
        assertTrue(actions.get(0).describe().contains("world @ 1608, 3208"));
    }

    @Test
    void everyKindOfLitterCountsTowardsTheSameThreshold() {
        // Bukkit spells the dropped item type differently across versions, and XP orbs are just
        // as much litter - splitting them would let a chunk stay under the threshold three ways.
        Map<String, Integer> entities = new HashMap<>();
        entities.put("ITEM", 100);
        entities.put("DROPPED_ITEM", 100);
        entities.put("EXPERIENCE_ORB", 150);

        List<RemedyAction> actions = RemedyPlan.decide(chunk(entities), settings(true, false));

        assertEquals(1, actions.size());
        assertEquals(350, actions.get(0).toRemove());
    }

    @Test
    void aMobPileUpIsThinnedDownToTheFloorNotEmptied() {
        List<RemedyAction> actions = RemedyPlan.decide(chunk(Map.of("COW", 800)), settings(false, true));

        assertEquals(1, actions.size());
        assertEquals(RemedyAction.Kind.CAP_MOBS, actions.get(0).kind());
        assertEquals("COW", actions.get(0).entityType());
        assertEquals(800, actions.get(0).present());
        assertEquals(750, actions.get(0).toRemove());
    }

    @Test
    void aProtectedTypeIsNeverTouchedHoweverManyThereAre() {
        assertTrue(RemedyPlan.decide(chunk(Map.of("VILLAGER", 5000)), settings(false, true)).isEmpty());
    }

    @Test
    void aProtectedMajorityDoesNotShieldTheRestOfTheChunk() {
        Map<String, Integer> entities = new HashMap<>();
        entities.put("VILLAGER", 900);
        entities.put("ZOMBIE", 400);

        List<RemedyAction> actions = RemedyPlan.decide(chunk(entities), settings(false, true));

        assertEquals(1, actions.size());
        assertEquals("ZOMBIE", actions.get(0).entityType());
    }

    @Test
    void playersAreNeverCounted() {
        assertTrue(RemedyPlan.decide(chunk(Map.of("PLAYER", 400)), settings(false, true)).isEmpty());
    }

    @Test
    void litterIsNotThinnedAsIfItWereAMob() {
        List<RemedyAction> actions = RemedyPlan.decide(chunk(Map.of("ITEM", 800)), settings(false, true));

        assertTrue(actions.isEmpty());
    }

    @Test
    void aChunkAtExactlyTheKeepCountIsLeftAlone() {
        RemedySettings capAtFifty = new RemedySettings(true, false, 30, 600, false, 300, true, 50, 50, List.of());

        assertTrue(RemedyPlan.decide(chunk(Map.of("COW", 50)), capAtFifty).isEmpty());
    }

    @Test
    void bothKindsOfCleanUpCanApplyToTheSameChunk() {
        Map<String, Integer> entities = new HashMap<>();
        entities.put("ITEM", 500);
        entities.put("COW", 900);

        List<RemedyAction> actions = RemedyPlan.decide(chunk(entities), settings(true, true));

        assertEquals(2, actions.size());
        assertEquals(RemedyAction.Kind.CLEAR_ITEMS, actions.get(0).kind());
        assertEquals(RemedyAction.Kind.CAP_MOBS, actions.get(1).kind());
    }

    @Test
    void onlyTheWorstMobTypeInAChunkIsThinned() {
        Map<String, Integer> entities = new HashMap<>();
        entities.put("COW", 400);
        entities.put("SHEEP", 900);
        entities.put("PIG", 350);

        List<RemedyAction> actions = RemedyPlan.decide(chunk(entities), settings(false, true));

        assertEquals(1, actions.size());
        assertEquals("SHEEP", actions.get(0).entityType());
    }

    @Test
    void tiesBreakOnNameSoThePlanIsReproducible() {
        Map<String, Integer> entities = new HashMap<>();
        entities.put("SHEEP", 400);
        entities.put("COW", 400);

        assertEquals("COW", RemedyPlan.decide(chunk(entities), settings(false, true)).get(0).entityType());
    }

    @Test
    void severalChunksProduceSeveralActions() {
        List<ChunkStat> chunks = List.of(
                ChunkStat.ofEntities("world", 10, 10, Map.of("ITEM", 400)),
                ChunkStat.ofEntities("world", 20, 20, Map.of("ITEM", 400)),
                ChunkStat.ofEntities("world", 30, 30, Map.of("ITEM", 10)));

        assertEquals(2, RemedyPlan.decide(chunks, settings(true, false)).size());
    }

    @Test
    void nullsAndEmptyInputAreSafe() {
        assertTrue(RemedyPlan.decide((ChunkStat) null, settings(true, true)).isEmpty());
        assertTrue(RemedyPlan.decide(chunk(Map.of("ITEM", 5000)), null).isEmpty());
        assertTrue(RemedyPlan.decide(List.<ChunkStat>of(), settings(true, true)).isEmpty());
        assertTrue(RemedyPlan.decide((List<ChunkStat>) null, settings(true, true)).isEmpty());
    }

    @Test
    void theProtectedListIsCaseInsensitive() {
        RemedySettings mixedCase = new RemedySettings(true, false, 30, 600, false, 300, true, 300, 50,
                List.of("villager", "Iron_Golem"));

        assertTrue(mixedCase.isProtected("VILLAGER"));
        assertTrue(mixedCase.isProtected("IRON_GOLEM"));
        assertTrue(RemedyPlan.decide(chunk(Map.of("VILLAGER", 900)), mixedCase).isEmpty());
    }

    @Test
    void descriptionsReadLikeSentences() {
        RemedyAction sweep = RemedyPlan.decide(chunk(Map.of("ITEM", 400)), settings(true, false)).get(0);
        RemedyAction thin = RemedyPlan.decide(chunk(Map.of("COW", 800)), settings(false, true)).get(0);

        assertEquals("clear 400 dropped items at world @ 1608, 3208", sweep.describe());
        assertEquals("remove 750 of 800 cow at world @ 1608, 3208", thin.describe());
        assertTrue(thin.announcement().contains("about to be removed"));
    }
}
