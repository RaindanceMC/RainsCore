package de.raindancer.core.social.economy;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Whoever buys items off players for the server's money — the shop. Asked through {@link ItemSales} by anything that
 * holds items for a player and offers to sell them: a vault, a backpack.
 */
public interface ItemBuyer {

    /** Whether this stack would be bought at all, for showing a button greyed out. Cheap; no money moves. */
    boolean buys(ItemStack stack);

    /**
     * Buys whatever it takes of these stacks, pays the seller and tells them how it went in its own words.
     *
     * <p>The caller has already taken the stacks out of wherever they were, and puts back every one this does not
     * buy — so a sale that fails half way loses nothing. May complete on another thread, later (a bank on another
     * server).
     *
     * @return per stack, in order, whether it was bought
     */
    CompletableFuture<List<Boolean>> sell(Player seller, List<ItemStack> stacks);
}
