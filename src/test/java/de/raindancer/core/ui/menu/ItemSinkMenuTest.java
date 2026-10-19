package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemSinkMenuTest {

    private ItemStack[] slots;
    private Player viewer;
    private org.bukkit.inventory.PlayerInventory playerInventory;
    private List<List<ItemStack>> eaten;
    private List<ItemStack> refuse;
    private ItemSinkMenu menu;

    private static ItemStack stack(String kind) {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(1);
        when(item.getType()).thenReturn(Material.STONE);
        when(item.toString()).thenReturn(kind);
        when(item.clone()).thenReturn(item);
        return item;
    }

    @BeforeEach
    void setUp() {
        slots = new ItemStack[ItemTrayMenu.SIZE];
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(ItemTrayMenu.SIZE);
        when(inventory.getItem(anyInt())).thenAnswer(call -> slots[call.<Integer>getArgument(0)]);
        org.mockito.Mockito.doAnswer(call -> {
            slots[call.<Integer>getArgument(0)] = call.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
        viewer = mock(Player.class);
        playerInventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(viewer.getInventory()).thenReturn(playerInventory);
        when(playerInventory.addItem(any(ItemStack[].class))).thenReturn(new java.util.HashMap<>());
        eaten = new ArrayList<>();
        refuse = new ArrayList<>();
        menu = new ItemSinkMenu(null, viewer, new Brand("Test"), null, "Sell", List.of("Drop it in."), items -> {
            eaten.add(List.copyOf(items));
            return items.stream().filter(refuse::contains).toList();
        }) {
            @Override
            public Inventory getInventory() {
                return inventory;
            }
        };
        menu.open();
    }

    @Test
    @DisplayName("what lands in the tray is gone on the next tick, handed to whoever swallows it")
    void swallows() {
        ItemStack stone = stack("stone");
        ItemStack dirt = stack("dirt");
        slots[0] = stone;
        slots[13] = dirt;

        menu.swallow();

        assertThat(eaten).containsExactly(List.of(stone, dirt));
        assertThat(slots[0]).isNull();
        assertThat(slots[13]).isNull();
        verify(playerInventory, never()).addItem(any(ItemStack[].class));
    }

    @Test
    @DisplayName("what it will not swallow goes back to the player, never back into the tray to be offered again")
    void refusedGoesBack() {
        ItemStack sword = stack("sword");
        refuse.add(sword);
        slots[2] = sword;

        menu.swallow();

        assertThat(slots[2]).isNull();
        verify(playerInventory).addItem(new ItemStack[]{sword});
    }

    @Test
    @DisplayName("an empty tray asks nobody, and the buttons below the tray are never swallowed")
    void emptyAndButtons() {
        ItemStack button = stack("button");
        slots[ItemTrayMenu.TRAY_SLOTS + 2] = button;

        menu.swallow();

        assertThat(eaten).isEmpty();
        assertThat(slots[ItemTrayMenu.TRAY_SLOTS + 2]).isSameAs(button);
    }

    @Test
    @DisplayName("closing swallows what arrived in the very last tick too")
    void closingSwallows() {
        ItemStack stone = stack("stone");
        slots[5] = stone;

        menu.handleClose(mock(InventoryCloseEvent.class));

        assertThat(eaten).containsExactly(List.of(stone));
        assertThat(slots[5]).isNull();
    }
}
