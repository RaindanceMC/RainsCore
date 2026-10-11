package de.raindancer.core.ui.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * The one listener behind every menu in every plugin.
 *
 * <h2>Why the holder is the identity</h2>
 * A menu <em>is</em> its inventory's {@link InventoryHolder}, so recognising our own windows costs an
 * {@code instanceof} rather than a registry of open views — and a registry is exactly what used to
 * hold {@link Player} references after a player had logged out, pinning a world in the heap.
 *
 * <p>Registered once by RainsCore. The five frameworks this replaces each had one of these, and each
 * had its own answer to shift-clicking from the player's own inventory; three of them got it wrong
 * in a way that let items be posted into a menu with nowhere to put them.
 */
public final class MenuListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu == null) {
            return;
        }
        boolean clickedOwnInventory = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() != menu;
        if (clickedOwnInventory) {
            // Their own inventory. Only a screen that reads items out of it says yes here, and the
            // rest must still cancel — otherwise a shift-click posts an item into a menu that has
            // nowhere to put it and no idea it arrived.
            if (menu.allowBottomInventoryInteraction()) {
                menu.handleBottomClick(event);
            } else {
                event.setCancelled(true);
            }
            return;
        }
        menu.handleClick(event);
    }

    /**
     * A drag can cross both inventories at once, which is how an item reaches a menu without a
     * single click event ever naming that menu's slot.
     */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu == null) {
            return;
        }
        boolean reading = menu.allowBottomInventoryInteraction();
        for (int slot : event.getRawSlots()) {
            if (slot < event.getInventory().getSize() && !(reading && menu.acceptsItemsIn(slot))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu != null) {
            menu.handleClose(event);
        }
    }

    private static Menu menuOf(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Menu menu ? menu : null;
    }
}
