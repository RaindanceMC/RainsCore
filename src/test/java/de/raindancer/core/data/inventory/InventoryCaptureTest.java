package de.raindancer.core.data.inventory;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryCaptureTest {

    @Test
    @DisplayName("captures the inventory, the ender chest and the rest of the player, for this server")
    void capturesEverything() {
        UUID id = UUID.randomUUID();
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Inventory ender = mock(Inventory.class);
        ItemStack[] main = new ItemStack[41];
        ItemStack[] chest = new ItemStack[27];
        when(player.getUniqueId()).thenReturn(id);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(ender);
        when(inventory.getContents()).thenReturn(main);
        when(ender.getContents()).thenReturn(chest);
        when(player.getLevel()).thenReturn(12);
        when(player.getExp()).thenReturn(0.25f);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getFoodLevel()).thenReturn(19);
        when(player.getSaturation()).thenReturn(3.5f);
        when(player.getActivePotionEffects()).thenReturn(List.of());
        SavedInventories store = new SavedInventories(null, () -> 777L);

        InventoryCapture capture = new InventoryCapture(null, store, () -> "smp",
                items -> new byte[]{(byte) items.length});
        SavedInventories.Saved saved = capture.capture(player);

        assertThat(saved.server()).isEqualTo("smp");
        assertThat(saved.player()).isEqualTo(id);
        assertThat(saved.savedAt()).isEqualTo(777L);
        assertThat(saved.contents()).containsExactly(41);
        assertThat(saved.enderChest()).containsExactly(27);
        assertThat(saved.level()).isEqualTo(12);
        assertThat(saved.progress()).isEqualTo(0.25f);
        assertThat(saved.food()).isEqualTo(19);
        assertThat(saved.effects()).contains("effects: []");
    }
}
