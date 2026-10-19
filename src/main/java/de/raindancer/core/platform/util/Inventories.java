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

    /** How many items similar to {@code like} these slots hold, whatever their stack sizes. */
    public static int count(ItemStack[] slots, ItemStack like) {
        if (isNothing(like)) {
            return 0;
        }
        long total = 0;
        for (ItemStack slot : slots) {
            if (!isNothing(slot) && slot.isSimilar(like)) {
                total += slot.getAmount();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** How many more items similar to {@code like} would fit: part stacks topped up, a full stack per free slot. */
    public static int room(ItemStack[] slots, ItemStack like) {
        if (isNothing(like)) {
            return 0;
        }
        int max = Math.max(1, like.getMaxStackSize());
        long room = 0;
        for (ItemStack slot : slots) {
            if (isNothing(slot)) {
                room += max;
            } else if (slot.isSimilar(like)) {
                room += Math.max(0, max - slot.getAmount());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, room);
    }

    /**
     * Takes exactly {@code amount} items similar to {@code like} out of the inventory's storage — or, when there are
     * fewer than that, takes nothing at all. A trade must never leave half its goods taken.
     *
     * @return what was taken, as stacks; empty when nothing was
     */
    public static List<ItemStack> take(org.bukkit.inventory.Inventory inventory, ItemStack like, int amount) {
        ItemStack[] slots = inventory.getStorageContents();
        if (amount <= 0 || count(slots, like) < amount) {
            return List.of();
        }
        List<ItemStack> taken = new ArrayList<>();
        int left = amount;
        for (int at = 0; at < slots.length && left > 0; at++) {
            ItemStack slot = slots[at];
            if (isNothing(slot) || !slot.isSimilar(like)) {
                continue;
            }
            int moved = Math.min(left, slot.getAmount());
            ItemStack part = slot.clone();
            part.setAmount(moved);
            taken.add(part);
            left -= moved;
            if (moved == slot.getAmount()) {
                slots[at] = null;
            } else {
                ItemStack rest = slot.clone();
                rest.setAmount(slot.getAmount() - moved);
                slots[at] = rest;
            }
        }
        inventory.setStorageContents(slots);
        return taken;
    }

    /** {@code amount} of {@code like} as stacks no larger than the item allows, every one a copy. */
    public static List<ItemStack> stacksOf(ItemStack like, int amount) {
        if (isNothing(like) || amount <= 0) {
            return List.of();
        }
        int max = Math.max(1, like.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = amount; left > 0; left -= max) {
            ItemStack stack = like.clone();
            stack.setAmount(Math.min(max, left));
            stacks.add(stack);
        }
        return stacks;
    }

    private static boolean isNothing(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}
