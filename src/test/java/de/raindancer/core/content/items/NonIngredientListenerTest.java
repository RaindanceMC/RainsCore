package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Piglin;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Money is not gold and not paper: a coin made of a gold nugget, or a banknote made of paper, must never
 * be worth its material — crafted, smelted, traded to a villager, bartered to a piglin or paid to a beacon.
 */
@DisplayName("an item marked as no ingredient is never used up as its material")
class NonIngredientListenerTest {

    private final NonIngredientListener listener = new NonIngredientListener();
    private final ItemStack coin = stack(Material.GOLD_NUGGET, true);
    private final ItemStack nugget = stack(Material.GOLD_NUGGET, false);

    private static ItemStack stack(Material type, boolean marked) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(type);
        when(stack.getAmount()).thenReturn(1);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(NonIngredients.KEY, PersistentDataType.BYTE)).thenReturn(marked);
        return stack;
    }

    @Test
    @DisplayName("a marked stack is recognised, and nothing else is")
    void recognised() {
        assertThat(NonIngredients.isMarked(coin)).isTrue();
        assertThat(NonIngredients.isMarked(nugget)).isFalse();
        assertThat(NonIngredients.isMarked(null)).isFalse();
        assertThat(NonIngredients.anyMarked(new ItemStack[]{nugget, null, coin})).isTrue();
        assertThat(NonIngredients.anyMarked(new ItemStack[]{nugget, null})).isFalse();
    }

    @Test
    @DisplayName("a crafting grid holding a coin makes nothing; one holding gold makes what gold makes")
    void crafting() {
        CraftingInventory grid = mock(CraftingInventory.class);
        PrepareItemCraftEvent event = mock(PrepareItemCraftEvent.class);
        when(event.getInventory()).thenReturn(grid);

        when(grid.getMatrix()).thenReturn(new ItemStack[]{coin, nugget});
        listener.onPrepareCraft(event);
        verify(grid).setResult(null);

        CraftingInventory honest = mock(CraftingInventory.class);
        PrepareItemCraftEvent fine = mock(PrepareItemCraftEvent.class);
        when(fine.getInventory()).thenReturn(honest);
        when(honest.getMatrix()).thenReturn(new ItemStack[]{nugget, nugget});
        listener.onPrepareCraft(fine);
        verify(honest, never()).setResult(any());
    }

    private InventoryClickEvent click(InventoryType topType, boolean clickedTop, InventoryAction action,
                                      ItemStack cursor, ItemStack current) {
        Inventory top = mock(Inventory.class);
        Inventory bottom = mock(PlayerInventory.class);
        when(top.getType()).thenReturn(topType);
        when(top.getContents()).thenReturn(new ItemStack[0]);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getBottomInventory()).thenReturn(bottom);
        HumanEntity who = mock(HumanEntity.class);
        PlayerInventory own = mock(PlayerInventory.class);
        when(who.getInventory()).thenReturn(own);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getClickedInventory()).thenReturn(clickedTop ? top : bottom);
        when(event.getAction()).thenReturn(action);
        when(event.getCursor()).thenReturn(cursor);
        when(event.getCurrentItem()).thenReturn(current);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getHotbarButton()).thenReturn(-1);
        when(event.getSlotType()).thenReturn(InventoryType.SlotType.CONTAINER);
        when(event.getWhoClicked()).thenReturn(who);
        return event;
    }

    @Test
    @DisplayName("a coin cannot be put into a furnace, a beacon, a villager's trade or a smithing table")
    void placing() {
        for (InventoryType station : List.of(InventoryType.FURNACE, InventoryType.BEACON,
                InventoryType.MERCHANT, InventoryType.SMITHING, InventoryType.ANVIL, InventoryType.BREWING,
                InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.WORKBENCH)) {
            assertThat(NonIngredientListener.feeds(click(station, true, InventoryAction.PLACE_ALL, coin, null)))
                    .as("placed into a " + station).isTrue();
            assertThat(NonIngredientListener.feeds(click(station, false, InventoryAction.MOVE_TO_OTHER_INVENTORY,
                    null, coin))).as("shift-clicked into a " + station).isTrue();
            assertThat(NonIngredientListener.feeds(click(station, true, InventoryAction.PLACE_ALL, nugget, null)))
                    .as("plain gold into a " + station).isFalse();
        }
    }

    @Test
    @DisplayName("a chest is not a workstation: coins go in and out of one freely")
    void chestsAreFine() {
        assertThat(NonIngredientListener.feeds(click(InventoryType.CHEST, true, InventoryAction.PLACE_ALL,
                coin, null))).isFalse();
        assertThat(NonIngredientListener.feeds(click(InventoryType.CHEST, false,
                InventoryAction.MOVE_TO_OTHER_INVENTORY, null, coin))).isFalse();
        assertThat(NonIngredientListener.feeds(click(InventoryType.FURNACE, false, InventoryAction.PICKUP_ALL,
                null, coin))).as("picking a coin up in your own inventory below a furnace").isFalse();
    }

    @Test
    @DisplayName("taking a result made from a coin is refused — the villager's auto-filled trade included")
    void results() {
        InventoryClickEvent take = click(InventoryType.MERCHANT, true, InventoryAction.PICKUP_ALL, null, nugget);
        when(take.getSlotType()).thenReturn(InventoryType.SlotType.RESULT);
        when(take.getSlot()).thenReturn(2);
        when(take.getView().getTopInventory().getContents()).thenReturn(new ItemStack[]{coin, null, nugget});
        assertThat(NonIngredientListener.feeds(take)).isTrue();

        InventoryClickEvent honest = click(InventoryType.MERCHANT, true, InventoryAction.PICKUP_ALL, null, nugget);
        when(honest.getSlotType()).thenReturn(InventoryType.SlotType.RESULT);
        when(honest.getSlot()).thenReturn(2);
        when(honest.getView().getTopInventory().getContents()).thenReturn(new ItemStack[]{nugget, null, nugget});
        assertThat(NonIngredientListener.feeds(honest)).isFalse();
    }

    @Test
    @DisplayName("a marked thing crafted from plain ingredients can be taken out of the result slot")
    void markedResultFromPlainIngredients() {
        ItemStack aquarium = stack(Material.GLASS, true);
        ItemStack glass = stack(Material.GLASS, false);
        for (InventoryType grid : List.of(InventoryType.WORKBENCH, InventoryType.CRAFTING)) {
            InventoryClickEvent take = click(grid, true, InventoryAction.PICKUP_ALL, null, aquarium);
            when(take.getSlotType()).thenReturn(InventoryType.SlotType.RESULT);
            when(take.getSlot()).thenReturn(0);
            // A crafting inventory's contents start with its result slot.
            when(take.getView().getTopInventory().getContents())
                    .thenReturn(new ItemStack[]{aquarium, glass, glass, glass, null});
            assertThat(NonIngredientListener.feeds(take)).as("taken from a " + grid).isFalse();

            InventoryClickEvent fromCoin = click(grid, true, InventoryAction.PICKUP_ALL, null, aquarium);
            when(fromCoin.getSlotType()).thenReturn(InventoryType.SlotType.RESULT);
            when(fromCoin.getSlot()).thenReturn(0);
            when(fromCoin.getView().getTopInventory().getContents())
                    .thenReturn(new ItemStack[]{aquarium, glass, coin, null, null});
            assertThat(NonIngredientListener.feeds(fromCoin)).as("made from a coin in a " + grid).isTrue();
        }
    }

    @Test
    @DisplayName("a hopper does not feed a coin into a furnace, but still fills a chest")
    void hoppers() {
        Inventory furnace = mock(Inventory.class);
        when(furnace.getType()).thenReturn(InventoryType.FURNACE);
        InventoryMoveItemEvent intoFurnace = mock(InventoryMoveItemEvent.class);
        when(intoFurnace.getItem()).thenReturn(coin);
        when(intoFurnace.getDestination()).thenReturn(furnace);
        listener.onMove(intoFurnace);
        verify(intoFurnace).setCancelled(true);

        Inventory chest = mock(Inventory.class);
        when(chest.getType()).thenReturn(InventoryType.CHEST);
        InventoryMoveItemEvent intoChest = mock(InventoryMoveItemEvent.class);
        when(intoChest.getItem()).thenReturn(coin);
        when(intoChest.getDestination()).thenReturn(chest);
        listener.onMove(intoChest);
        verify(intoChest, never()).setCancelled(true);
    }

    @Test
    @DisplayName("a piglin will not barter for a coin")
    void piglins() {
        PiglinBarterEvent barter = mock(PiglinBarterEvent.class);
        when(barter.getInput()).thenReturn(coin);
        when(barter.getEntity()).thenReturn(mock(Piglin.class));
        listener.onBarter(barter);
        verify(barter).setCancelled(true);
    }

    @Test
    @DisplayName("a brewing stand does not brew with a coin")
    void brewing() {
        BrewerInventory stand = mock(BrewerInventory.class);
        when(stand.getContents()).thenReturn(new ItemStack[]{null, coin});
        BrewEvent brew = mock(BrewEvent.class);
        when(brew.getContents()).thenReturn(stand);
        listener.onBrew(brew);
        verify(brew).setCancelled(true);
    }
}
