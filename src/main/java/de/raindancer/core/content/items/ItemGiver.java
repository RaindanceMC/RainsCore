package de.raindancer.core.content.items;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * A plugin's word on making its own items for {@code /give}, for items that need more than their {@link CustomItem}
 * definition — a rod with its stats written on, a net that stacks to one. Provided per namespace through
 * {@link ItemGivers}; ids are without the namespace ({@code rod-void} in {@code fishing}).
 */
public interface ItemGiver {

    /** {@code amount} of the item, as one stack however big; empty for an id this giver does not make. */
    Optional<ItemStack> make(String id, int amount);

    /** Every id it makes, for suggestions. */
    List<String> ids();
}
