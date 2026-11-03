package de.raindancer.core.content.items;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ItemGiversTest {

    @AfterEach
    void forget() {
        ItemGivers.clear();
    }

    private static ItemGiver giver(String... ids) {
        return new ItemGiver() {
            @Override
            public Optional<ItemStack> make(String id, int amount) {
                return List.of(ids).contains(id) ? Optional.of(new ItemStack(org.bukkit.Material.STONE, amount))
                        : Optional.empty();
            }

            @Override
            public List<String> ids() {
                return List.of(ids);
            }
        };
    }

    @Test
    @DisplayName("a giver answers for its own namespace, by key; nobody knows the rest")
    void byNamespace() {
        ItemGivers.provide(null, "fishing", giver("rod-void", "bait-voidlure"));
        assertThat(ItemGivers.knows("fishing:rod-void")).isTrue();
        assertThat(ItemGivers.knows("fishing:rod-nothing")).isFalse();
        assertThat(ItemGivers.knows("minecraft:diamond")).isFalse();
        assertThat(ItemGivers.knows("rod-void")).isFalse();
        assertThat(ItemGivers.keys()).containsExactly("fishing:bait-voidlure", "fishing:rod-void");
    }

    @Test
    @DisplayName("retracted, a giver is gone; minecraft is never anybody's")
    void retract() {
        ItemGiver fishing = giver("rod-void");
        ItemGivers.provide(null, "fishing", fishing);
        ItemGivers.retract(fishing);
        assertThat(ItemGivers.knows("fishing:rod-void")).isFalse();
        ItemGivers.provide(null, "minecraft", giver("diamond"));
        assertThat(ItemGivers.knows("minecraft:diamond")).isFalse();
    }
}
