package de.raindancer.core.social.economy;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ItemPricesTest {

    private final org.bukkit.plugin.Plugin owner = mock(org.bukkit.plugin.Plugin.class);
    private final ItemStack fish = mock(ItemStack.class);
    private final ItemStack wand = mock(ItemStack.class);
    private final ItemStack dirt = mock(ItemStack.class);

    @AfterEach
    void reset() {
        ItemPrices.clear();
    }

    private static ItemPricer pricing(ItemStack mine, long price) {
        return stack -> stack == mine ? Optional.of(Money.of(price)) : Optional.empty();
    }

    @Test
    @DisplayName("with nobody pricing, no plugin item has a price")
    void nobody() {
        assertThat(ItemPrices.sellPrice(fish)).isEmpty();
        assertThat(ItemPrices.sellPrice(null)).isEmpty();
    }

    @Test
    @DisplayName("every plugin prices its own items; an item nobody prices has none")
    void each() {
        ItemPrices.provide(owner, pricing(fish, 35));
        ItemPrices.provide(owner, pricing(wand, 500));
        assertThat(ItemPrices.sellPrice(fish)).contains(Money.of(35));
        assertThat(ItemPrices.sellPrice(wand)).contains(Money.of(500));
        assertThat(ItemPrices.sellPrice(dirt)).isEmpty();
    }

    @Test
    @DisplayName("zero, a negative price, a pricer that throws: not for sale")
    void broken() {
        ItemPrices.provide(owner, stack -> {
            throw new IllegalStateException("broken");
        });
        ItemPrices.provide(owner, pricing(wand, 0));
        assertThat(ItemPrices.sellPrice(fish)).isEmpty();
        assertThat(ItemPrices.sellPrice(wand)).isEmpty();
    }

    @Test
    @DisplayName("a retracted pricer prices nothing any more")
    void retract() {
        ItemPricer fishes = pricing(fish, 35);
        ItemPrices.provide(owner, fishes);
        ItemPrices.retract(fishes);
        assertThat(ItemPrices.sellPrice(fish)).isEmpty();
    }
}
