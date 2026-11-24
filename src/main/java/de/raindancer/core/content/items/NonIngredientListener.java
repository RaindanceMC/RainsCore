package de.raindancer.core.content.items;

import org.bukkit.entity.Piglin;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Keeps {@link NonIngredients marked} stacks from being used up as their material.
 *
 * <p>Two layers, because either alone leaks. Placement is refused at the door of every workstation; and
 * every place a result is produced checks its inputs again, for the routes past the door — a villager
 * filling its own trade slots from the player's inventory, a hopper, a stack that was already inside
 * before it was marked.
 */
public final class NonIngredientListener implements Listener {

    /** Inventories whose contents get used up. Chests, barrels and shulkers are deliberately absent. */
    static final Set<InventoryType> WORKSTATIONS = EnumSet.of(
            InventoryType.WORKBENCH, InventoryType.CRAFTER, InventoryType.FURNACE, InventoryType.BLAST_FURNACE,
            InventoryType.SMOKER, InventoryType.ANVIL, InventoryType.SMITHING, InventoryType.GRINDSTONE,
            InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.STONECUTTER, InventoryType.BREWING,
            InventoryType.BEACON, InventoryType.MERCHANT, InventoryType.ENCHANTING);

    // ------------------------------------------------------------------ the door

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (feeds(event)) {
            event.setCancelled(true);
        }
    }

    /** Whether this click would put a marked stack into a workstation, or take a result made from one. */
    static boolean feeds(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top == null) {
            return false;
        }
        boolean intoTop = event.getClickedInventory() != null && event.getClickedInventory().equals(top);

        // Taking what was made is checked for every crafting window, the player's own 2x2 grid included.
        if (intoTop && event.getSlotType() == InventoryType.SlotType.RESULT
                && anyInputMarked(top.getContents(), event.getSlot())) {
            return true;
        }
        if (!WORKSTATIONS.contains(top.getType())) {
            return false;
        }
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return !intoTop && NonIngredients.isMarked(event.getCurrentItem());
        }
        if (!intoTop) {
            return false;
        }
        if (NonIngredients.isMarked(event.getCursor())) {
            return true;
        }
        PlayerInventory own = event.getWhoClicked().getInventory();
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return NonIngredients.isMarked(own.getItemInOffHand());
        }
        int button = event.getHotbarButton();
        return button >= 0 && NonIngredients.isMarked(own.getItem(button));
    }

    /** The result slot itself is skipped: a marked thing made from plain ingredients (an aquarium) is fine to take. */
    private static boolean anyInputMarked(ItemStack[] contents, int resultSlot) {
        for (int i = 0; i < contents.length; i++) {
            if (i != resultSlot && NonIngredients.isMarked(contents[i])) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top == null || !WORKSTATIONS.contains(top.getType()) || !NonIngredients.isMarked(event.getOldCursor())) {
            return;
        }
        int size = top.getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < size) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        Inventory into = event.getDestination();
        if (into != null && WORKSTATIONS.contains(into.getType()) && NonIngredients.isMarked(event.getItem())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ where results are made

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (NonIngredients.anyMarked(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    /** Anvil, smithing table, grindstone, loom, cartography table and stonecutter. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareResult(PrepareResultEvent event) {
        if (NonIngredients.anyMarked(event.getInventory().getContents())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter
                && NonIngredients.anyMarked(crafter.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent event) {
        if (NonIngredients.isMarked(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(FurnaceBurnEvent event) {
        if (NonIngredients.isMarked(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (NonIngredients.anyMarked(event.getContents().getContents())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ piglins

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBarter(PiglinBarterEvent event) {
        if (NonIngredients.isMarked(event.getInput())) {
            event.setCancelled(true);
        }
    }

    /** A piglin picks gold up off the floor before it ever barters; a coin left lying stays a coin. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof PiglinAbstract && NonIngredients.isMarked(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** Right-clicking a piglin with gold hands it over. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHandOver(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Piglin) {
            ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
            if (NonIngredients.isMarked(held)) {
                event.setCancelled(true);
            }
        }
    }
}
