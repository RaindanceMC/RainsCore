package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LockedWorldRulesTest {

    @Test
    @DisplayName("fighting gear, food and potions work against a block; anything that changes or makes something does not")
    void itemsAgainstBlocks() {
        for (String usable : List.of("BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "COOKED_BEEF", "GOLDEN_APPLE",
                "POTION", "SPLASH_POTION", "ENDER_PEARL", "SNOWBALL", "WIND_CHARGE", "TOTEM_OF_UNDYING", "MILK_BUCKET",
                "FISHING_ROD", "AIR", "IRON_CHESTPLATE")) {
            assertThat(LockedWorldRules.usableAgainstBlocks(usable)).as(usable).isTrue();
        }
        for (String changing : List.of("FLINT_AND_STEEL", "FIRE_CHARGE", "BONE_MEAL", "WATER_BUCKET", "LAVA_BUCKET",
                "BUCKET", "ZOMBIE_SPAWN_EGG", "OAK_BOAT", "MINECART", "ARMOR_STAND", "ITEM_FRAME", "PAINTING",
                "END_CRYSTAL", "STONE", "TNT", "IRON_HOE", "IRON_SHOVEL", "IRON_AXE", "DIAMOND_SWORD", "SHEARS", "EGG",
                "LEAD", "BRUSH", "INK_SAC", "HONEYCOMB", "WRITABLE_BOOK")) {
            assertThat(LockedWorldRules.usableAgainstBlocks(changing)).as(changing).isFalse();
        }
    }

    @Test
    @DisplayName("a wither or an ender dragon may never exist in a locked world; a wither skeleton is just a mob")
    void noBosses() {
        assertThat(LockedWorldRules.mayExist("WITHER")).isFalse();
        assertThat(LockedWorldRules.mayExist("ender_dragon")).isFalse();
        assertThat(LockedWorldRules.mayExist("WITHER_SKELETON")).isTrue();
        assertThat(LockedWorldRules.mayExist("ZOMBIE")).isTrue();
    }

    @Test
    @DisplayName("an arrow breaks no pot, lights no TNT, rings no bell and snuffs no candle")
    void fragileBlocks() {
        for (String fragile : List.of("DECORATED_POT", "CHORUS_FLOWER", "POINTED_DRIPSTONE", "TNT", "BELL", "TARGET",
                "CANDLE", "RED_CANDLE", "CAMPFIRE", "SOUL_CAMPFIRE")) {
            assertThat(LockedWorldRules.fragile(fragile)).as(fragile).isTrue();
        }
        assertThat(LockedWorldRules.fragile("STONE")).isFalse();
    }
}
