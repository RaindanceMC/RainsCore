package de.raindancer.core.social.economy;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CashTest {

    private final org.bukkit.plugin.Plugin owner = mock(org.bukkit.plugin.Plugin.class);
    private final UUID ada = UUID.randomUUID();
    private final ItemStack coin = mock(ItemStack.class);
    private final ItemStack cheque = mock(ItemStack.class);

    @AfterEach
    void reset() {
        Cash.clear();
    }

    private static CashDesk giving(ItemStack... items) {
        return (player, amount, mostCoins, issuer) -> CompletableFuture.completedFuture(List.of(items));
    }

    @Test
    @DisplayName("with no desk, nothing is taken out and the money stays on the balance")
    void nobody() {
        assertThat(Cash.isOpen()).isFalse();
        assertThat(Cash.takeOut(ada, Money.of(500), 192, "Void Chest").join()).isEmpty();
    }

    @Test
    @DisplayName("the newest desk answers, and retracting it hands back to the one before")
    void stacking() {
        CashDesk coins = giving(coin);
        CashDesk cheques = giving(cheque);
        Cash.provide(owner, coins);
        Cash.provide(owner, cheques);

        assertThat(Cash.takeOut(ada, Money.of(5), 192, "x").join()).containsExactly(cheque);
        Cash.retract(cheques);
        assertThat(Cash.takeOut(ada, Money.of(5), 192, "x").join()).containsExactly(coin);
    }

    @Test
    @DisplayName("a desk that throws, fails or answers null counts as having taken nothing out")
    void broken() {
        Cash.provide(owner, (player, amount, most, issuer) -> {
            throw new IllegalStateException("broken");
        });
        assertThat(Cash.takeOut(ada, Money.of(5), 192, "x").join()).isEmpty();

        Cash.clear();
        Cash.provide(owner, (player, amount, most, issuer) -> CompletableFuture.failedFuture(new RuntimeException()));
        assertThat(Cash.takeOut(ada, Money.of(5), 192, "x").join()).isEmpty();

        Cash.clear();
        Cash.provide(owner, (player, amount, most, issuer) -> null);
        assertThat(Cash.takeOut(ada, Money.of(5), 192, "x").join()).isEmpty();
    }

    @Test
    @DisplayName("nothing to take out is never asked of the desk")
    void nothing() {
        Cash.provide(owner, giving(coin));
        assertThat(Cash.takeOut(ada, Money.ZERO, 192, "x").join()).isEmpty();
    }
}
