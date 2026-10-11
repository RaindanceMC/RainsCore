package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemTrayMenuTest {

    private ItemStack[] slots;
    private Inventory inventory;
    private Player viewer;
    private org.bukkit.inventory.PlayerInventory playerInventory;
    private List<List<ItemStack>> confirmed;
    private List<List<ItemStack>> abandoned;
    private boolean accept;
    private ItemTrayMenu menu;

    private static ItemStack stack(String kind, int amount) {
        ItemStack item = mock(ItemStack.class);
        int[] now = {amount};
        when(item.getAmount()).thenAnswer(call -> now[0]);
        org.mockito.Mockito.doAnswer(call -> {
            now[0] = call.getArgument(0);
            return null;
        }).when(item).setAmount(anyInt());
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.getType()).thenReturn(Material.STONE);
        when(item.toString()).thenReturn(kind);
        when(item.isSimilar(any())).thenAnswer(call -> {
            ItemStack other = call.getArgument(0);
            return other != null && other.toString().equals(kind);
        });
        when(item.clone()).thenAnswer(call -> stack(kind, now[0]));
        return item;
    }

    private static ItemStack bound(ItemStack item) {
        org.bukkit.inventory.meta.ItemMeta meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
        org.bukkit.persistence.PersistentDataContainer pdc = mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(pdc.has(any(org.bukkit.NamespacedKey.class), any())).thenReturn(true);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        return item;
    }

    @BeforeEach
    void setUp() {
        slots = new ItemStack[ItemTrayMenu.SIZE];
        inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(ItemTrayMenu.SIZE);
        when(inventory.getItem(anyInt())).thenAnswer(call -> slots[call.getArgument(0)]);
        org.mockito.Mockito.doAnswer(call -> {
            slots[call.<Integer>getArgument(0)] = call.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
        viewer = mock(Player.class);
        playerInventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(viewer.getInventory()).thenReturn(playerInventory);
        when(playerInventory.addItem(any(ItemStack[].class))).thenReturn(new java.util.HashMap<>());
        confirmed = new ArrayList<>();
        abandoned = new ArrayList<>();
        accept = true;
        Predicate<List<ItemStack>> onConfirm = items -> {
            if (accept) {
                confirmed.add(items);
            }
            return accept;
        };
        menu = new ItemTrayMenu(viewer, new Brand("Test"), null, "Send", "Send it", List.of(), onConfirm,
                abandoned::add) {
            @Override
            public Inventory getInventory() {
                return inventory;
            }
        };
        menu.open();
    }

    private InventoryClickEvent click(int rawSlot, ClickType type, InventoryAction action, ItemStack current) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getRawSlot()).thenReturn(rawSlot);
        when(event.getClick()).thenReturn(type);
        when(event.getAction()).thenReturn(action);
        when(event.getCurrentItem()).thenReturn(current);
        return event;
    }

    @Test
    @DisplayName("the tray is the top three rows, and nothing else takes items")
    void traySlots() {
        assertThat(menu.acceptsItemsIn(0)).isTrue();
        assertThat(menu.acceptsItemsIn(26)).isTrue();
        assertThat(menu.acceptsItemsIn(27)).isFalse();
        assertThat(menu.acceptsItemsIn(ItemTrayMenu.CONFIRM_SLOT)).isFalse();
        assertThat(menu.allowBottomInventoryInteraction()).isTrue();
    }

    @Test
    @DisplayName("a click on a tray slot is left to the game")
    void trayClickNotCancelled() {
        InventoryClickEvent event = click(4, ClickType.LEFT, InventoryAction.PLACE_ALL, null);
        menu.handleClick(event);
        verify(event, never()).setCancelled(true);
    }

    @Test
    @DisplayName("a click on anything else in the window is cancelled")
    void buttonClickCancelled() {
        InventoryClickEvent event = click(40, ClickType.LEFT, InventoryAction.PICKUP_ALL, null);
        menu.handleClick(event);
        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("shift-clicking from the player's inventory moves the stack into the first free tray slot")
    void shiftClickMovesIn() {
        ItemStack clicked = stack("a", 12);
        InventoryClickEvent event = click(60, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, clicked);
        menu.handleBottomClick(event);
        verify(event).setCancelled(true);
        assertThat(slots[0]).isNotNull();
        assertThat(slots[0].getAmount()).isEqualTo(12);
        verify(event).setCurrentItem(null);
    }

    @Test
    @DisplayName("shift-clicking tops up a stack already in the tray before using a new slot")
    void shiftClickMerges() {
        slots[0] = stack("a", 60);
        ItemStack clicked = stack("a", 10);
        InventoryClickEvent event = click(60, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, clicked);
        menu.handleBottomClick(event);
        assertThat(slots[0].getAmount()).isEqualTo(64);
        assertThat(slots[1].getAmount()).isEqualTo(6);
        verify(event).setCurrentItem(null);
    }

    @Test
    @DisplayName("a full tray leaves what did not fit in the player's inventory")
    void shiftClickFullTray() {
        for (int at = 0; at < ItemTrayMenu.TRAY_SLOTS; at++) {
            slots[at] = stack("other", 64);
        }
        ItemStack clicked = stack("a", 10);
        InventoryClickEvent event = click(60, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, clicked);
        menu.handleBottomClick(event);
        verify(event).setCancelled(true);
        assertThat(clicked.getAmount()).isEqualTo(10);
        verify(event, never()).setCurrentItem(null);
    }

    @Test
    @DisplayName("an ordinary click in the player's own inventory is let through")
    void bottomPlainClick() {
        InventoryClickEvent event = click(60, ClickType.LEFT, InventoryAction.PICKUP_ALL, stack("a", 1));
        menu.handleBottomClick(event);
        verify(event, never()).setCancelled(true);
    }

    @Test
    @DisplayName("a double click that would gather items from the whole window is cancelled")
    void collectCancelled() {
        InventoryClickEvent event = click(60, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, null);
        menu.handleBottomClick(event);
        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("confirming hands over what is in the tray, empties it, and a later close gives nothing back")
    void confirmHandsOver() {
        slots[2] = stack("a", 5);
        slots[9] = stack("b", 1);
        menu.handleClick(click(ItemTrayMenu.CONFIRM_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL, null));
        assertThat(confirmed).hasSize(1);
        assertThat(confirmed.getFirst()).hasSize(2);
        assertThat(slots[2]).isNull();
        assertThat(slots[9]).isNull();
        menu.handleClose(mock(InventoryCloseEvent.class));
        assertThat(abandoned).isEmpty();
    }

    @Test
    @DisplayName("confirming an empty tray still asks the caller, who can say what is missing")
    void emptyConfirmIsAsked() {
        accept = false;
        List<List<ItemStack>> asked = new ArrayList<>();
        menu = new ItemTrayMenu(viewer, new Brand("Test"), null, "Send", "Send it", List.of(),
                items -> {
                    asked.add(items);
                    return false;
                }, abandoned::add) {
            @Override
            public Inventory getInventory() {
                return inventory;
            }
        };
        menu.open();
        menu.handleClick(click(ItemTrayMenu.CONFIRM_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL, null));
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst()).isEmpty();
        verify(viewer, never()).closeInventory();
    }

    @Test
    @DisplayName("a refused confirmation leaves the items where they are")
    void refusedConfirmKeepsItems() {
        accept = false;
        ItemStack held = stack("a", 5);
        slots[2] = held;
        menu.handleClick(click(ItemTrayMenu.CONFIRM_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL, null));
        assertThat(slots[2]).isSameAs(held);
        verify(viewer, never()).closeInventory();
    }

    @Test
    @DisplayName("closing without confirming gives everything back, once")
    void closeGivesBack() {
        slots[1] = stack("a", 5);
        menu.handleClose(mock(InventoryCloseEvent.class));
        menu.handleClose(mock(InventoryCloseEvent.class));
        assertThat(abandoned).hasSize(1);
        assertThat(abandoned.getFirst()).hasSize(1);
        assertThat(slots[1]).isNull();
    }

    @Test
    @DisplayName("closing an empty tray gives nothing back")
    void closeEmpty() {
        menu.handleClose(mock(InventoryCloseEvent.class));
        assertThat(abandoned).isEmpty();
    }

    @Test
    @DisplayName("a drag that reaches a button slot is cancelled; one inside the tray is not")
    void dragOnlyInTray() {
        Inventory top = inventory;
        when(top.getHolder()).thenReturn(menu);
        MenuListener listener = new MenuListener();

        InventoryDragEvent inside = mock(InventoryDragEvent.class);
        when(inside.getInventory()).thenReturn(top);
        when(inside.getRawSlots()).thenReturn(Set.of(3, 4, 60));
        listener.onDrag(inside);
        verify(inside, never()).setCancelled(true);

        InventoryDragEvent onto = mock(InventoryDragEvent.class);
        when(onto.getInventory()).thenReturn(top);
        when(onto.getRawSlots()).thenReturn(Set.of(3, ItemTrayMenu.CONFIRM_SLOT));
        listener.onDrag(onto);
        verify(onto).setCancelled(true);
    }

    @Test
    @DisplayName("a shift-click another handler already cancelled is not carried out by hand")
    void cancelledShiftClickStaysCancelled() {
        ItemStack clicked = stack("a", 12);
        InventoryClickEvent event = click(60, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, clicked);
        when(event.isCancelled()).thenReturn(true);
        menu.handleBottomClick(event);
        assertThat(slots[0]).isNull();
        verify(event, never()).setCurrentItem(any());
    }

    @Test
    @DisplayName("a bound item is never shift-clicked into the tray")
    void boundItemRefused() {
        ItemStack clicked = bound(stack("a", 1));
        InventoryClickEvent event = click(60, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, clicked);
        menu.handleBottomClick(event);
        assertThat(slots[0]).isNull();
        verify(event, never()).setCurrentItem(any());
    }

    @Test
    @DisplayName("a bound item that got into the tray another way is not handed on")
    void boundItemNotHandedOn() {
        slots[0] = bound(stack("a", 1));
        slots[1] = stack("b", 2);
        menu.handleClick(click(ItemTrayMenu.CONFIRM_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL, null));
        assertThat(confirmed).hasSize(1);
        assertThat(confirmed.getFirst()).hasSize(1);
        assertThat(confirmed.getFirst().getFirst().toString()).isEqualTo("b");
        verify(playerInventory).addItem(any(ItemStack[].class));
    }

    @Test
    @DisplayName("a refused confirmation does not put a bound item back in the tray after giving it back")
    void refusedConfirmDoesNotDuplicateBound() {
        accept = false;
        ItemStack heldBack = bound(stack("a", 1));
        slots[0] = heldBack;
        slots[1] = stack("b", 2);
        menu.handleClick(click(ItemTrayMenu.CONFIRM_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL, null));
        assertThat(slots[0]).as("the bound stack is not restored to the tray").isNull();
        assertThat(slots[1]).isNotNull();
        verify(playerInventory).addItem(any(ItemStack[].class));
    }

    @Test
    @DisplayName("a bound item left in the tray when the window closes goes back to the player once")
    void closeReturnsBoundOnce() {
        slots[0] = bound(stack("a", 1));
        slots[1] = stack("b", 2);
        menu.handleClose(mock(InventoryCloseEvent.class));
        assertThat(abandoned).hasSize(1);
        assertThat(abandoned.getFirst()).hasSize(1);
        verify(playerInventory).addItem(any(ItemStack[].class));
    }

    @Test
    @DisplayName("opening it a second time shows the same tray rather than clearing it")
    void openTwiceKeepsItems() {
        slots[0] = stack("a", 5);
        menu.open();
        menu.reopen();
        assertThat(slots[0]).isNotNull();
    }

    @Test
    @DisplayName("a redraw never wipes the tray")
    void refreshKeepsItems() {
        slots[0] = stack("a", 5);
        menu.refresh();
        assertThat(slots[0]).isNotNull();
    }
}
