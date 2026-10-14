package de.raindancer.core.content.enchant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GearTest {

    @Test
    @DisplayName("veinminer tools are pickaxes, axes and shovels — and not a pickaxe by accident of its name")
    void veinTools() {
        for (String tool : new String[]{"wooden_pickaxe", "netherite_pickaxe", "iron_axe", "golden_axe",
                "diamond_shovel", "copper_shovel"}) {
            assertThat(Gear.VEIN_TOOLS.matches(tool)).as(tool).isTrue();
        }
        for (String other : new String[]{"diamond_hoe", "diamond_sword", "mace", "iron_helmet", "stone"}) {
            assertThat(Gear.VEIN_TOOLS.matches(other)).as(other).isFalse();
        }
    }

    @Test
    @DisplayName("hand tools are what breaks blocks or kills mobs — a sword and a bow, not armour")
    void handTools() {
        for (String tool : new String[]{"stone_pickaxe", "iron_axe", "wooden_shovel", "netherite_hoe",
                "diamond_sword", "bow", "crossbow", "mace", "trident", "shears"}) {
            assertThat(Gear.HAND_TOOLS.matches(tool)).as(tool).isTrue();
        }
        for (String other : new String[]{"iron_helmet", "elytra", "fishing_rod", "stick", "rainbow"}) {
            assertThat(Gear.HAND_TOOLS.matches(other)).as(other).isFalse();
        }
        assertThat(Gear.HAND_TOOLS.slot()).isEqualTo(org.bukkit.inventory.EquipmentSlotGroup.MAINHAND);
    }

    @Test
    @DisplayName("digging, excavating, hoes, leggings, and any gear at all")
    void theRest() {
        assertThat(Gear.DIGGING_TOOLS.matches("iron_hoe")).isTrue();
        assertThat(Gear.DIGGING_TOOLS.matches("iron_sword")).isFalse();
        assertThat(Gear.EXCAVATING_TOOLS.matches("diamond_shovel")).isTrue();
        assertThat(Gear.EXCAVATING_TOOLS.matches("diamond_axe")).isFalse();
        assertThat(Gear.HOE.matches("golden_hoe")).isTrue();
        assertThat(Gear.LEGGINGS.matches("chainmail_leggings")).isTrue();
        assertThat(Gear.LEGGINGS.slot()).isEqualTo(org.bukkit.inventory.EquipmentSlotGroup.LEGS);
        for (String kept : new String[]{"elytra", "shield", "bow", "netherite_sword", "turtle_helmet", "iron_boots"}) {
            assertThat(Gear.ANY_GEAR.matches(kept)).as(kept).isTrue();
        }
        assertThat(Gear.ANY_GEAR.matches("diamond")).isFalse();
        assertThat(Gear.ANY_GEAR.slot()).isEqualTo(org.bukkit.inventory.EquipmentSlotGroup.ANY);
    }

    @Test
    @DisplayName("exclusive enchantments are named by their full key")
    void exclusives() {
        EnchantDef def = EnchantDef.builder("rainsenchants:smelting").name("Smelting").gear(Gear.DIGGING_TOOLS)
                .exclusiveWith("minecraft:silk_touch").build();
        assertThat(def.exclusiveWith()).containsExactly("minecraft:silk_touch");
        assertThatThrownBy(() -> EnchantDef.builder("a:b").name("x").gear(Gear.HOE).exclusiveWith("silk_touch").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new EnchantDef("a", "b", "x", 1, Gear.HOE, 1, 1).exclusiveWith()).isEmpty();
    }

    @Test
    @DisplayName("the elytra is its own gear, worn in the chest slot")
    void elytra() {
        assertThat(Gear.ELYTRA.matches("elytra")).isTrue();
        assertThat(Gear.ELYTRA.matches("diamond_chestplate")).isFalse();
        assertThat(Gear.ELYTRA.slot()).isEqualTo(org.bukkit.inventory.EquipmentSlotGroup.CHEST);
    }

    @Test
    @DisplayName("armour pieces are matched by their slot's suffix")
    void armour() {
        assertThat(Gear.HELMET.matches("iron_helmet")).isTrue();
        assertThat(Gear.HELMET.matches("turtle_helmet")).isTrue();
        assertThat(Gear.CHESTPLATE.matches("diamond_chestplate")).isTrue();
        assertThat(Gear.CHESTPLATE.matches("elytra")).isFalse();
        assertThat(Gear.BOOTS.matches("leather_boots")).isTrue();
        assertThat(Gear.BOOTS.matches("iron_leggings")).isFalse();
    }

    @Test
    @DisplayName("every gear says which slot it is active in")
    void slots() {
        assertThat(Gear.HELMET.slot()).isEqualTo(org.bukkit.inventory.EquipmentSlotGroup.HEAD);
        assertThat(Gear.CHESTPLATE.slot()).isEqualTo(org.bukkit.inventory.EquipmentSlotGroup.CHEST);
        assertThat(Gear.BOOTS.slot()).isEqualTo(org.bukkit.inventory.EquipmentSlotGroup.FEET);
        assertThat(Gear.VEIN_TOOLS.slot()).isEqualTo(org.bukkit.inventory.EquipmentSlotGroup.MAINHAND);
    }

    @Test
    @DisplayName("a definition needs a namespaced key, a name and a sane level range")
    void defValidation() {
        EnchantDef ok = EnchantDef.builder("rainsenchants:flight").name("<aqua>Flight").maxLevel(3)
                .gear(Gear.CHESTPLATE).build();
        assertThat(ok.namespace()).isEqualTo("rainsenchants");
        assertThat(ok.path()).isEqualTo("flight");

        assertThatThrownBy(() -> EnchantDef.builder("flight").name("x").gear(Gear.BOOTS).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("namespace");
        assertThatThrownBy(() -> EnchantDef.builder("a:b").gear(Gear.BOOTS).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> EnchantDef.builder("a:b").name("x").maxLevel(0).gear(Gear.BOOTS).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnchantDef.builder("a:b").name("x").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gear");
    }

    @Test
    @DisplayName("ranged weapons are what shoots or is thrown: bow, crossbow, trident — not a mace or a sword")
    void ranged() {
        for (String weapon : new String[]{"bow", "crossbow", "trident"}) {
            org.assertj.core.api.Assertions.assertThat(Gear.RANGED.matches(weapon)).as(weapon).isTrue();
        }
        for (String other : new String[]{"mace", "iron_sword", "fishing_rod", "shears"}) {
            org.assertj.core.api.Assertions.assertThat(Gear.RANGED.matches(other)).as(other).isFalse();
        }
        org.assertj.core.api.Assertions.assertThat(RainsEnchants.HEAT_SEEKING).isEqualTo("rainsenchants:heat_seeking");
    }

    @Test
    @DisplayName("what Thor goes on: axes and the mace (thrown) and the trident (a storm where it lands)")
    void thunder() {
        for (String weapon : new String[]{"iron_axe", "netherite_axe", "mace", "trident"}) {
            org.assertj.core.api.Assertions.assertThat(Gear.THUNDER.matches(weapon)).as(weapon).isTrue();
        }
        for (String other : new String[]{"iron_pickaxe", "bow", "iron_sword"}) {
            org.assertj.core.api.Assertions.assertThat(Gear.THUNDER.matches(other)).as(other).isFalse();
        }
        org.assertj.core.api.Assertions.assertThat(RainsEnchants.THOR).isEqualTo("rainsenchants:thor");
    }

    @Test
    @DisplayName("a trident is a trident: what Volley goes on")
    void trident() {
        org.assertj.core.api.Assertions.assertThat(Gear.TRIDENT.matches("trident")).isTrue();
        org.assertj.core.api.Assertions.assertThat(Gear.TRIDENT.matches("bow")).isFalse();
        org.assertj.core.api.Assertions.assertThat(RainsEnchants.VOLLEY).isEqualTo("rainsenchants:volley");
    }

    @Test
    @DisplayName("melee weapons: swords, axes, the mace and spears; axes alone for Cleave")
    void melee() {
        for (String weapon : new String[]{"iron_sword", "netherite_axe", "mace", "iron_spear"}) {
            org.assertj.core.api.Assertions.assertThat(Gear.MELEE.matches(weapon)).as(weapon).isTrue();
        }
        for (String other : new String[]{"iron_pickaxe", "bow", "trident", "iron_shovel"}) {
            org.assertj.core.api.Assertions.assertThat(Gear.MELEE.matches(other)).as(other).isFalse();
        }
        org.assertj.core.api.Assertions.assertThat(Gear.AXES.matches("golden_axe")).isTrue();
        org.assertj.core.api.Assertions.assertThat(Gear.AXES.matches("golden_pickaxe")).isFalse();
        org.assertj.core.api.Assertions.assertThat(Gear.AXES.matches("iron_sword")).isFalse();
        org.assertj.core.api.Assertions.assertThat(java.util.List.of(RainsEnchants.LIFESTEAL, RainsEnchants.EXECUTIONER,
                RainsEnchants.BLEED, RainsEnchants.VENOM, RainsEnchants.CLEAVE, RainsEnchants.ARMOR_PIERCE,
                RainsEnchants.BERSERK, RainsEnchants.OVERPOWER, RainsEnchants.UNDYING, RainsEnchants.FUSION))
                .allMatch(key -> key.startsWith("rainsenchants:")).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("what Heat-seeking goes on: what shoots or is thrown, Thor's hammers included")
    void seeking() {
        for (String weapon : new String[]{"bow", "crossbow", "trident", "mace", "iron_axe"}) {
            org.assertj.core.api.Assertions.assertThat(Gear.SEEKING.matches(weapon)).as(weapon).isTrue();
        }
        for (String other : new String[]{"iron_pickaxe", "iron_sword", "fishing_rod"}) {
            org.assertj.core.api.Assertions.assertThat(Gear.SEEKING.matches(other)).as(other).isFalse();
        }
    }
}
