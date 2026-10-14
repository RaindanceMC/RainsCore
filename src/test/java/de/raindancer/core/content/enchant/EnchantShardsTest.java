package de.raindancer.core.content.enchant;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnchantShardsTest {

    private static ItemStack shards(String enchantment, int amount) {
        ItemStack stack = mock(ItemStack.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.get(EnchantShards.KEY, PersistentDataType.STRING)).thenReturn(enchantment);
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(stack.getType()).thenReturn(Material.ECHO_SHARD);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        int[] left = {amount};
        when(stack.getAmount()).thenAnswer(call -> left[0]);
        org.mockito.Mockito.doAnswer(call -> {
            left[0] = call.getArgument(0);
            return null;
        }).when(stack).setAmount(anyInt());
        return stack;
    }

    private static Inventory holding(ItemStack... contents) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getStorageContents()).thenReturn(contents);
        return inventory;
    }

    @Test
    @DisplayName("only the shards of that enchantment count — not another's, not a plain echo shard")
    void counts() {
        ItemStack plain = mock(ItemStack.class);
        when(plain.getType()).thenReturn(Material.ECHO_SHARD);
        Inventory inventory = holding(shards(RainsEnchants.BOOST, 5), null, plain,
                shards(RainsEnchants.LAUNCH, 9), shards(RainsEnchants.BOOST, 3));

        assertThat(EnchantShards.count(inventory, RainsEnchants.BOOST)).isEqualTo(8);
        assertThat(EnchantShards.enchantmentOf(plain)).isEmpty();
    }

    @Test
    @DisplayName("taking is all or nothing, and empties the last slots first")
    void takes() {
        ItemStack first = shards(RainsEnchants.BOOST, 5);
        ItemStack last = shards(RainsEnchants.BOOST, 3);
        Inventory inventory = holding(first, last);

        assertThat(EnchantShards.take(inventory, RainsEnchants.BOOST, 9)).isFalse();
        verify(inventory, never()).setItem(anyInt(), org.mockito.ArgumentMatchers.any());

        assertThat(EnchantShards.take(inventory, RainsEnchants.BOOST, 4)).isTrue();
        verify(inventory).setItem(eq(1), eq(null));
        assertThat(first.getAmount()).isEqualTo(4);
    }
}
