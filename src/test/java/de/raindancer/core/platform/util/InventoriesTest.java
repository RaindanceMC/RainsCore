package de.raindancer.core.platform.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoriesTest {

    /** A stack of a kind: two stacks are similar when their kind is the same. */
    private static ItemStack stack(String kind, int amount, int max) {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(amount);
        when(item.getMaxStackSize()).thenReturn(max);
        when(item.getType()).thenReturn(Material.STONE);
        when(item.toString()).thenReturn(kind);
        when(item.isSimilar(any())).thenAnswer(call -> {
            ItemStack other = call.getArgument(0);
            return other != null && other.toString().equals(kind);
        });
        return item;
    }

    private static ItemStack[] full(int size) {
        ItemStack[] storage = new ItemStack[size];
        Arrays.fill(storage, stack("filler", 64, 64));
        return storage;
    }

    @Test
    @DisplayName("an empty inventory has room for a stack")
    void emptyHasRoom() {
        assertThat(Inventories.fits(new ItemStack[36], List.of(stack("a", 64, 64)))).isTrue();
    }

    @Test
    @DisplayName("a full inventory has room for nothing")
    void fullHasNoRoom() {
        assertThat(Inventories.fits(full(36), List.of(stack("a", 1, 64)))).isFalse();
    }

    @Test
    @DisplayName("a half-filled stack of the same kind takes more of it without a free slot")
    void topsUpAStack() {
        ItemStack[] storage = full(36);
        storage[5] = stack("a", 60, 64);
        assertThat(Inventories.fits(storage, List.of(stack("a", 4, 64)))).isTrue();
        assertThat(Inventories.fits(storage, List.of(stack("a", 5, 64)))).isFalse();
    }

    @Test
    @DisplayName("what is offered is counted together: two stacks that need two slots do not fit in one")
    void countsTheWholeOffer() {
        ItemStack[] storage = full(36);
        storage[0] = null;
        assertThat(Inventories.fits(storage, List.of(stack("a", 64, 64), stack("b", 1, 64)))).isFalse();
        assertThat(Inventories.fits(storage, List.of(stack("a", 30, 64), stack("a", 30, 64)))).isTrue();
    }

    @Test
    @DisplayName("an unstackable item needs a slot each")
    void unstackable() {
        ItemStack[] storage = full(36);
        storage[0] = null;
        storage[1] = null;
        assertThat(Inventories.fits(storage, List.of(stack("sword", 1, 1), stack("sword", 1, 1)))).isTrue();
        assertThat(Inventories.fits(storage,
                List.of(stack("sword", 1, 1), stack("sword", 1, 1), stack("sword", 1, 1)))).isFalse();
    }

    @Test
    @DisplayName("the inventory handed in is not changed by asking")
    void doesNotMutate() {
        ItemStack[] storage = new ItemStack[36];
        ItemStack held = stack("a", 10, 64);
        storage[0] = held;
        Inventories.fits(storage, List.of(stack("a", 20, 64)));
        assertThat(storage[0]).isSameAs(held);
    }

    @Test
    @DisplayName("nothing to give always fits")
    void nothing() {
        assertThat(Inventories.fits(full(36), List.of())).isTrue();
    }
}
