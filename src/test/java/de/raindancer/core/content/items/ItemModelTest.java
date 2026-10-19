package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemModelTest {

    @Test
    @DisplayName("an item may wear a resource pack's own item model, named by its full key")
    void wearsAModel() {
        CustomItem wand = CustomItem.builder("economy", "sell-wand").material(Material.BLAZE_ROD)
                .itemModel("rainseconomy:sell_wand").build();
        assertThat(wand.itemModel()).contains("rainseconomy:sell_wand");
        assertThat(wand.tag(CustomItem.TAG_ITEM_MODEL)).contains("rainseconomy:sell_wand");
        assertThat(CustomItem.builder("a", "b").material(Material.STICK).build().itemModel()).isEmpty();
    }

    @Test
    @DisplayName("a model key that is not one is refused when it is written, not when a player is handed the item")
    void refusesNonsense() {
        assertThatThrownBy(() -> CustomItem.builder("a", "b").material(Material.STICK).itemModel("Not A Key").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomItem.builder("a", "b").material(Material.STICK).itemModel("sell_wand").build())
                .as("a bare path would be read as minecraft:, which is never what a plugin meant")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
