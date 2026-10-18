package de.raindancer.core.content.enchant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SwitchedOffTest {

    @AfterEach
    void allOnAgain() {
        CustomEnchantments.switchOff(List.of());
    }

    @Test
    @DisplayName("a switched-off enchantment is known as such, by its key in any case, until switched on again")
    void switchedOff() {
        CustomEnchantments.switchOff(List.of("RainsEnchants:Launch"));

        assertThat(CustomEnchantments.isSwitchedOff(RainsEnchants.LAUNCH)).isTrue();
        assertThat(CustomEnchantments.isSwitchedOff(RainsEnchants.BOOST)).isFalse();
        assertThat(CustomEnchantments.isSwitchedOff(null)).isFalse();

        CustomEnchantments.switchOff(List.of());
        assertThat(CustomEnchantments.isSwitchedOff(RainsEnchants.LAUNCH)).isFalse();
    }

    @Test
    @DisplayName("on an item it reads as not there at all, so nothing that asks for its level acts on it")
    void readsAsAbsent() {
        CustomEnchantments.switchOff(List.of(RainsEnchants.LAUNCH));

        assertThat(CustomEnchantments.levelOn(null, RainsEnchants.LAUNCH)).isZero();
    }
}
