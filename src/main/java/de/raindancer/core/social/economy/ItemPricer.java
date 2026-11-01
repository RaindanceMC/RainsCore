package de.raindancer.core.social.economy;

import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/** A plugin's word on what the shop pays for one of its own items — a fish, a dish. Asked through {@link ItemPrices}. */
public interface ItemPricer {

    /**
     * What the shop pays for one of {@code stack} before the seller's own price changes; empty for anything this
     * plugin does not price (every item that is not its own). Cheap, any thread.
     */
    Optional<Money> sellPrice(ItemStack stack);
}
