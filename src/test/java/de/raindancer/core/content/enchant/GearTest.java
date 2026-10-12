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
}
