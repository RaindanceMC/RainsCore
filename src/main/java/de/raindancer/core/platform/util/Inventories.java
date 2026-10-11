package de.raindancer.core.platform.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether items fit into a player's inventory, and handing them over without losing any.
 *
 * <p>{@code addItem} answers what did <em>not</em> fit only after it has already put the rest in, so a
 * caller that must give all of something or nothing — a trade, a parcel — cannot use it to ask. {@link #fits}
 * asks without changing anything.
 */
public final class Inventories {

    private Inventories() {
    }

    /**
     * Whether every one of these would fit, counting stacks they would top up and slots that are free.
     *
     * @param storage the contents of the slots items may go into — {@code getStorageContents()}, which
     *                leaves out armour and the off hand. Not changed
     */
    public static boolean fits(ItemStack[] storage, List<ItemStack> incoming) {
        ItemStack[] slots = storage.clone();
        int[] amounts = new int[slots.length];
        for (int at = 0; at < slots.length; at++) {
            amounts[at] = isNothing(slots[at]) ? 0 : slots[at].getAmount();
        }
        for (ItemStack offered : incoming) {
            if (isNothing(offered)) {
                continue;
            }
            int max = Math.max(1, offered.getMaxStackSize());
            int left = offered.getAmount();
            for (int at = 0; at < slots.length && left > 0; at++) {
                if (amounts[at] > 0 && amounts[at] < max && slots[at].isSimilar(offered)) {
                    int moved = Math.min(left, max - amounts[at]);
                    amounts[at] += moved;
                    left -= moved;
                }
            }
            for (int at = 0; at < slots.length && left > 0; at++) {
                if (amounts[at] == 0) {
                    int moved = Math.min(left, max);
                    slots[at] = offered;
                    amounts[at] = moved;
                    left -= moved;
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean fits(Player player, List<ItemStack> incoming) {
        return fits(player.getInventory().getStorageContents(), incoming);
    }

    /**
     * Puts the items in the player's inventory and drops whatever does not fit at their feet.
     *
     * @return how many items were dropped, for a line saying so
     */
    public static int giveOrDrop(Player player, List<ItemStack> items) {
        int dropped = 0;
        List<ItemStack> copies = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (!isNothing(item)) {
                copies.add(item.clone());
            }
        }
        for (ItemStack left : player.getInventory().addItem(copies.toArray(ItemStack[]::new)).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
            dropped += left.getAmount();
        }
        return dropped;
    }

    private static boolean isNothing(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}
