package dev.poleszczuk.ticksentry.remedy;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that decide whether something gets deleted.
 *
 * <p>{@link RemedyPlanTest} covers what the plugin intends to do; this covers what it refuses to
 * touch once it is actually standing in the chunk. That refusal is the only thing between an
 * automatic clean-up and a player losing a pet they spent an evening taming, so each rule gets
 * its own test rather than being trusted to a code review.</p>
 */
class RemedySafetyTest {

    private static final RemedySettings SETTINGS = new RemedySettings(
            true, false, 30, 600, true, 300, true, 300, 50, List.of("VILLAGER", "IRON_GOLEM"));

    @Test
    void anOrdinaryMobIsFairGame() {
        assertFalse(AutoRemediation.isCaredFor(entity(EntityType.COW, Map.of()), SETTINGS));
    }

    @Test
    void aPlayerIsNeverRemoved() {
        Entity player = proxy(Map.of("getType", EntityType.PLAYER), Player.class);

        assertTrue(AutoRemediation.isCaredFor(player, SETTINGS));
    }

    @Test
    void aNamedMobBelongsToSomebody() {
        // Somebody spent a name tag on this. That is as clear a signal of ownership as it gets.
        assertTrue(AutoRemediation.isCaredFor(
                entity(EntityType.COW, Map.of("getCustomName", "Bessie")), SETTINGS));
    }

    @Test
    void aTypeOnTheProtectedListIsNeverRemoved() {
        assertTrue(AutoRemediation.isCaredFor(entity(EntityType.VILLAGER, Map.of()), SETTINGS));
        assertTrue(AutoRemediation.isCaredFor(entity(EntityType.IRON_GOLEM, Map.of()), SETTINGS));
    }

    @Test
    void aTamedAnimalIsSomebodysPet() {
        Entity tamed = proxy(Map.of("getType", EntityType.WOLF, "isTamed", true),
                Tameable.class, LivingEntity.class);
        Entity wild = proxy(Map.of("getType", EntityType.WOLF, "isTamed", false),
                Tameable.class, LivingEntity.class);

        assertTrue(AutoRemediation.isCaredFor(tamed, SETTINGS));
        assertFalse(AutoRemediation.isCaredFor(wild, SETTINGS));
    }

    @Test
    void aLeashedAnimalIsBeingLedSomewhere() {
        Entity leashed = proxy(Map.of("getType", EntityType.COW, "isLeashed", true),
                LivingEntity.class);

        assertTrue(AutoRemediation.isCaredFor(leashed, SETTINGS));
    }

    @Test
    void anythingRidingOrBeingRiddenIsLeftAlone() {
        Entity mount = entity(EntityType.COW, Map.of());
        Entity ridden = entity(EntityType.PIG, Map.of("getPassengers", List.of(mount)));
        Entity riding = entity(EntityType.COW, Map.of("getVehicle", mount));

        assertTrue(AutoRemediation.isCaredFor(ridden, SETTINGS));
        assertTrue(AutoRemediation.isCaredFor(riding, SETTINGS));
    }

    @Test
    void clearingItemsTakesItemsAndOrbsAndNothingElse() {
        RemedyAction sweep = new RemedyAction(RemedyAction.Kind.CLEAR_ITEMS, "world", 1, 1, null, 500, 500);

        assertTrue(AutoRemediation.matches(proxy(Map.of("getType", EntityType.DROPPED_ITEM), Item.class), sweep));
        assertTrue(AutoRemediation.matches(
                proxy(Map.of("getType", EntityType.EXPERIENCE_ORB), ExperienceOrb.class), sweep));
        assertFalse(AutoRemediation.matches(entity(EntityType.COW, Map.of()), sweep));
    }

    @Test
    void cappingMobsTakesOnlyThePlannedType() {
        RemedyAction cap = new RemedyAction(RemedyAction.Kind.CAP_MOBS, "world", 1, 1, "COW", 800, 750);

        assertTrue(AutoRemediation.matches(entity(EntityType.COW, Map.of()), cap));
        assertFalse(AutoRemediation.matches(entity(EntityType.SHEEP, Map.of()), cap));
        assertFalse(AutoRemediation.matches(
                proxy(Map.of("getType", EntityType.DROPPED_ITEM), Item.class), cap));
    }

    @Test
    void theProtectedListIsConsultedByNameNotByIdentity() {
        RemedySettings lowercase = new RemedySettings(true, false, 30, 600, true, 300, true, 300, 50,
                List.of("cow"));

        assertTrue(AutoRemediation.isCaredFor(entity(EntityType.COW, Map.of()), lowercase));
        assertFalse(AutoRemediation.isCaredFor(entity(EntityType.SHEEP, Map.of()), lowercase));
    }

    /** A plain entity of the given type, with any extra answers layered on top. */
    private static Entity entity(EntityType type, Map<String, Object> answers) {
        Map<String, Object> all = new HashMap<>(answers);
        all.put("getType", type);
        return proxy(all);
    }

    /**
     * Builds a stand-in entity.
     *
     * <p>Bukkit entities are interfaces, so a proxy is enough - and unlike a mock server it
     * cannot quietly answer something the real API would not.</p>
     */
    private static Entity proxy(Map<String, Object> answers, Class<?>... extraInterfaces) {
        Class<?>[] interfaces = new Class<?>[extraInterfaces.length + 1];
        interfaces[0] = Entity.class;
        System.arraycopy(extraInterfaces, 0, interfaces, 1, extraInterfaces.length);

        return (Entity) Proxy.newProxyInstance(
                RemedySafetyTest.class.getClassLoader(),
                interfaces,
                (self, method, args) -> {
                    if (answers.containsKey(method.getName())) {
                        return answers.get(method.getName());
                    }
                    switch (method.getName()) {
                        case "equals":
                            return self == args[0];
                        case "hashCode":
                            return System.identityHashCode(self);
                        case "toString":
                            return "FakeEntity";
                        case "getPassengers":
                            return Collections.emptyList();
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }
}
