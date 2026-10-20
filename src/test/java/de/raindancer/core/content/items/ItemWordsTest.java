package de.raindancer.core.content.items;

import de.raindancer.core.ui.choose.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("What a search can find in one item")
class ItemWordsTest {

    @Test
    @DisplayName("found by its material, its given name, an enchantment or a lore line")
    void finds() {
        ItemWords sword = new ItemWords("Diamond Sword", "Excalibur", List.of("Sharpness", "Forged in the Fold"),
                Category.COMBAT);
        assertThat(sword.matches("diamond")).isTrue();
        assertThat(sword.matches("excal")).isTrue();
        assertThat(sword.matches("sharp sword")).isTrue();
        assertThat(sword.matches("fold")).isTrue();
        assertThat(sword.matches("gold")).isFalse();
        assertThat(sword.shown()).isEqualTo("Excalibur");
        assertThat(new ItemWords("Oak Log", null, List.of(), Category.BUILDING_BLOCKS).shown()).isEqualTo("Oak Log");
    }

    private static org.bukkit.inventory.ItemStack shard(String itemName, String customName) {
        org.bukkit.inventory.ItemStack stack = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.inventory.meta.ItemMeta meta = org.mockito.Mockito.mock(org.bukkit.inventory.meta.ItemMeta.class);
        org.mockito.Mockito.when(stack.getType()).thenReturn(org.bukkit.Material.ECHO_SHARD);
        org.mockito.Mockito.when(stack.hasItemMeta()).thenReturn(true);
        org.mockito.Mockito.when(stack.getItemMeta()).thenReturn(meta);
        org.mockito.Mockito.when(meta.hasItemName()).thenReturn(itemName != null);
        org.mockito.Mockito.when(meta.itemName()).thenReturn(itemName == null ? null
                : net.kyori.adventure.text.Component.text(itemName));
        org.mockito.Mockito.when(meta.hasCustomName()).thenReturn(customName != null);
        org.mockito.Mockito.when(meta.customName()).thenReturn(customName == null ? null
                : net.kyori.adventure.text.Component.text(customName));
        org.mockito.Mockito.when(meta.getEnchants()).thenReturn(java.util.Map.of());
        return stack;
    }

    @Test
    @DisplayName("an item's own name — a plugin's item, an enchant shard — is read and found, a rename still wins")
    void ownName() {
        ItemWords thor = ItemWords.of(shard("Thor Shard", null));
        assertThat(thor.shown()).isEqualTo("Thor Shard");
        assertThat(thor.matches("thor")).isTrue();
        assertThat(thor.matches("echo shard")).as("the material still finds it").isTrue();
        assertThat(ItemWords.of(shard("Thor Shard", "Lucky")).shown()).isEqualTo("Lucky");
        assertThat(ItemWords.of(shard(null, null)).shown()).isEqualTo("Echo Shard");
    }
}
