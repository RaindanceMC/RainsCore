package de.raindancer.core.social.economy;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Whoever makes money into things you can hold — the economy's coins and cheques. Asked through {@link Cash} by
 * anything that hands money out as items rather than onto a balance: a sea chest, a loot drop.
 */
public interface CashDesk {

    /**
     * Takes {@code amount} off the player's balance as cash, without a fee: coins, at most {@code mostCoins} of
     * them, and one cheque in {@code issuer}'s name for whatever is more — or, with cheques off, the rest stays on
     * the balance. The caller has usually just paid the amount in, so the money is never made twice.
     *
     * <p>May complete on another thread.
     *
     * @return the cash, already off the account; empty when nothing was taken out
     */
    CompletableFuture<List<ItemStack>> takeOut(UUID player, Money amount, int mostCoins, String issuer);
}
