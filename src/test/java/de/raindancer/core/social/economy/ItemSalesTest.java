package de.raindancer.core.social.economy;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ItemSalesTest {

    private final org.bukkit.plugin.Plugin owner = mock(org.bukkit.plugin.Plugin.class);
    private final Player seller = mock(Player.class);
    private final ItemStack dirt = mock(ItemStack.class);
    private final ItemStack sword = mock(ItemStack.class);

    @AfterEach
    void reset() {
        ItemSales.clear();
    }

    private static ItemBuyer buyingOnly(ItemStack wanted) {
        return new ItemBuyer() {
            @Override
            public boolean buys(ItemStack stack) {
                return stack == wanted;
            }

            @Override
            public CompletableFuture<List<Boolean>> sell(Player who, List<ItemStack> stacks) {
                return CompletableFuture.completedFuture(stacks.stream().map(stack -> stack == wanted).toList());
            }
        };
    }

    @Test
    @DisplayName("with nobody buying, nothing is sold and every stack is handed back")
    void nobody() {
        assertThat(ItemSales.isOpen()).isFalse();
        assertThat(ItemSales.buys(dirt)).isFalse();
        assertThat(ItemSales.sell(seller, List.of(dirt, sword)).join()).containsExactly(false, false);
    }

    @Test
    @DisplayName("the newest buyer answers, and retracting it hands back to the one before")
    void stacking() {
        ItemBuyer dirtOnly = buyingOnly(dirt);
        ItemBuyer swordOnly = buyingOnly(sword);
        ItemSales.provide(owner, dirtOnly);
        ItemSales.provide(owner, swordOnly);

        assertThat(ItemSales.sell(seller, List.of(dirt, sword)).join()).containsExactly(false, true);
        ItemSales.retract(swordOnly);
        assertThat(ItemSales.sell(seller, List.of(dirt, sword)).join()).containsExactly(true, false);
    }

    @Test
    @DisplayName("a buyer that throws, fails or answers the wrong length sells nothing")
    void broken() {
        ItemSales.provide(owner, new ItemBuyer() {
            @Override
            public boolean buys(ItemStack stack) {
                throw new IllegalStateException("prices are being recomputed");
            }

            @Override
            public CompletableFuture<List<Boolean>> sell(Player who, List<ItemStack> stacks) {
                throw new IllegalStateException("prices are being recomputed");
            }
        });
        assertThat(ItemSales.buys(dirt)).isFalse();
        assertThat(ItemSales.sell(seller, List.of(dirt)).join()).containsExactly(false);

        ItemSales.clear();
        ItemSales.provide(owner, new ItemBuyer() {
            @Override
            public boolean buys(ItemStack stack) {
                return true;
            }

            @Override
            public CompletableFuture<List<Boolean>> sell(Player who, List<ItemStack> stacks) {
                return CompletableFuture.failedFuture(new IllegalStateException("the bank went away"));
            }
        });
        assertThat(ItemSales.sell(seller, List.of(dirt)).join()).containsExactly(false);

        ItemSales.clear();
        ItemSales.provide(owner, new ItemBuyer() {
            @Override
            public boolean buys(ItemStack stack) {
                return true;
            }

            @Override
            public CompletableFuture<List<Boolean>> sell(Player who, List<ItemStack> stacks) {
                return CompletableFuture.completedFuture(List.of(true));
            }
        });
        assertThat(ItemSales.sell(seller, List.of(dirt, sword)).join())
                .as("an answer that does not line up cannot say which stack went, so none did")
                .containsExactly(false, false);
    }

    @Test
    @DisplayName("an answer in an immutable list is read, not choked on")
    void immutableAnswer() {
        ItemSales.provide(owner, new ItemBuyer() {
            @Override
            public boolean buys(ItemStack stack) {
                return true;
            }

            @Override
            public CompletableFuture<List<Boolean>> sell(Player who, List<ItemStack> stacks) {
                return CompletableFuture.completedFuture(List.of(true, false));
            }
        });
        assertThat(ItemSales.sell(seller, List.of(dirt, sword)).join()).containsExactly(true, false);
    }

    @Test
    @DisplayName("an empty sale asks nobody")
    void emptySale() {
        ItemSales.provide(owner, buyingOnly(dirt));
        assertThat(ItemSales.sell(seller, List.of()).join()).isEmpty();
    }

    @Test
    @DisplayName("what a disabled plugin's code provided is forgotten")
    void forgetFrom() {
        ItemBuyer mine = buyingOnly(dirt);
        ItemSales.provide(owner, mine);
        assertThat(ItemSales.forgetFrom(mine.getClass().getClassLoader())).isEqualTo(1);
        assertThat(ItemSales.isOpen()).isFalse();
    }
}
