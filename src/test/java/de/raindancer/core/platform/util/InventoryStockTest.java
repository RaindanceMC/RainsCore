package de.raindancer.core.platform.util;

import de.raindancer.core.testkit.TestInventories;
import de.raindancer.core.testkit.TestItems;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Inventories as stock: counting, room, taking all or nothing")
class InventoryStockTest {

    private static final ItemStack DIAMOND = TestItems.of(Material.DIAMOND);

    @Test
    @DisplayName("counts every similar stack and nothing else")
    void count() {
        Inventory chest = TestInventories.chest(27);
        chest.setItem(0, TestItems.of(Material.DIAMOND, 10));
        chest.setItem(5, TestItems.of(Material.DIAMOND, 64));
        chest.setItem(6, TestItems.of(Material.STONE, 64));
        assertThat(Inventories.count(chest.getStorageContents(), DIAMOND)).isEqualTo(74);
        assertThat(Inventories.count(chest.getStorageContents(), TestItems.of(Material.GOLD_INGOT))).isZero();
    }

    @Test
    @DisplayName("room is the top-up of part stacks plus a full stack per free slot")
    void room() {
        Inventory chest = TestInventories.chest(9);
        for (int slot = 0; slot < 9; slot++) {
            chest.setItem(slot, TestItems.of(Material.STONE, 64));
        }
        assertThat(Inventories.room(chest.getStorageContents(), DIAMOND)).isZero();
        chest.setItem(0, TestItems.of(Material.DIAMOND, 60));
        chest.setItem(1, null);
        assertThat(Inventories.room(chest.getStorageContents(), DIAMOND)).isEqualTo(4 + 64);
        assertThat(Inventories.room(chest.getStorageContents(), TestItems.of(Material.DIAMOND_SWORD)))
                .as("an unstackable item takes a slot each").isEqualTo(1);
    }

    @Test
    @DisplayName("take removes exactly the amount, from as many stacks as it needs")
    void take() {
        Inventory chest = TestInventories.chest(27);
        chest.setItem(0, TestItems.of(Material.DIAMOND, 10));
        chest.setItem(3, TestItems.of(Material.DIAMOND, 64));
        List<ItemStack> taken = Inventories.take(chest, DIAMOND, 30);
        assertThat(taken.stream().mapToInt(ItemStack::getAmount).sum()).isEqualTo(30);
        assertThat(Inventories.count(chest.getStorageContents(), DIAMOND)).isEqualTo(44);
    }

    @Test
    @DisplayName("take with too little in stock takes nothing at all")
    void allOrNothing() {
        Inventory chest = TestInventories.chest(27);
        chest.setItem(0, TestItems.of(Material.DIAMOND, 10));
        assertThat(Inventories.take(chest, DIAMOND, 11)).isEmpty();
        assertThat(Inventories.count(chest.getStorageContents(), DIAMOND)).isEqualTo(10);
        assertThat(Inventories.take(chest, DIAMOND, 0)).isEmpty();
    }

    @Test
    @DisplayName("an amount becomes stacks no larger than the item allows, each a copy")
    void stacksOf() {
        List<ItemStack> stacks = Inventories.stacksOf(DIAMOND, 130);
        assertThat(stacks).extracting(ItemStack::getAmount).containsExactly(64, 64, 2);
        assertThat(stacks).noneMatch(stack -> stack == DIAMOND);
        assertThat(Inventories.stacksOf(TestItems.of(Material.DIAMOND_SWORD), 3)).hasSize(3);
        assertThat(Inventories.stacksOf(DIAMOND, 0)).isEmpty();
    }
}
