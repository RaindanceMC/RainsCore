package de.raindancer.core.social.economy;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BuyableSlotsTest {

    @TempDir
    Path folder;

    private final Plugin owner = mock(Plugin.class);
    private final UUID ada = UUID.randomUUID();

    @AfterEach
    void reset() {
        Economies.clear();
    }

    private BuyableSlots slots() {
        BuyableSlots slots = new BuyableSlots(folder.resolve("bought-slots.yml"), "homes.slot", "Extra home slot");
        slots.load();
        return slots;
    }

    private static BuyableSlots.Terms terms(boolean on, long price, int growth, int most) {
        return new BuyableSlots.Terms(on, Money.of(price), growth, most);
    }

    @Test
    @DisplayName("the price grows by its percent with every slot bought, rounded down, compounding")
    void pricing() {
        assertThat(BuyableSlots.priceOfNext(Money.of(1000), 50, 0)).isEqualTo(Money.of(1000));
        assertThat(BuyableSlots.priceOfNext(Money.of(1000), 50, 3)).isEqualTo(Money.of(3375));
        assertThat(BuyableSlots.priceOfNext(Money.of(101), 10, 1)).isEqualTo(Money.of(111));
        assertThat(BuyableSlots.priceOfNext(Money.of(100), 29, 1)).isEqualTo(Money.of(129));
        assertThat(BuyableSlots.priceOfNext(Money.of(1_000_000), 100, 500).minor()).isPositive();
        assertThat(BuyableSlots.priceOfNext(Money.of(1000), -5, 4)).isEqualTo(Money.of(1000));
    }

    @Test
    @DisplayName("off, or on without a price, nothing is for sale; a ceiling stops buying")
    void offering() {
        BuyableSlots slots = slots();
        assertThat(slots.why(ada, terms(false, 100, 0, 0))).contains(BuyableSlots.Outcome.OFF);
        assertThat(slots.why(ada, terms(true, 0, 0, 0))).contains(BuyableSlots.Outcome.NO_PRICE);
        assertThat(slots.why(ada, terms(true, 100, 0, 0))).isEmpty();
    }

    @Test
    @DisplayName("buying charges the next price, counts the slot, and the count survives a restart")
    void buying() {
        Recorder bank = new Recorder(10_000);
        Economies.provide(owner, bank);
        BuyableSlots slots = slots();
        BuyableSlots.Terms terms = terms(true, 1000, 50, 2);
        assertThat(slots.buy(ada, terms).outcome()).isEqualTo(BuyableSlots.Outcome.BOUGHT);
        assertThat(slots.buy(ada, terms).paid()).isEqualTo(Money.of(1500));
        assertThat(slots.bought(ada)).isEqualTo(2);
        assertThat(slots.buy(ada, terms).outcome()).as("the ceiling").isEqualTo(BuyableSlots.Outcome.MAXED);
        assertThat(bank.calls).containsExactly("withdraw 1000 homes.slot", "withdraw 1500 homes.slot");
        assertThat(slots().bought(ada)).isEqualTo(2);
    }

    @Test
    @DisplayName("a buyer who cannot pay gets no slot; without an economy nothing is given away")
    void refusals() {
        BuyableSlots slots = slots();
        assertThat(slots.buy(ada, terms(true, 100, 0, 0)).outcome()).isEqualTo(BuyableSlots.Outcome.NO_ECONOMY);
        Economies.provide(owner, new Recorder(50));
        assertThat(slots.buy(ada, terms(true, 100, 0, 0)).outcome()).isEqualTo(BuyableSlots.Outcome.NOT_ENOUGH);
        assertThat(slots.bought(ada)).isZero();
    }

    @Test
    @DisplayName("a slot that cannot be written down is refunded, never paid for and lost")
    void notSaved() throws Exception {
        Recorder bank = new Recorder(10_000);
        Economies.provide(owner, bank);
        Path blocked = folder.resolve("blocked");
        Files.createDirectories(blocked.resolve("bought-slots.yml").resolve("inside"));
        BuyableSlots slots = new BuyableSlots(blocked.resolve("bought-slots.yml"), "homes.slot", "Extra home slot");
        slots.load();
        assertThat(slots.buy(ada, terms(true, 100, 0, 0)).outcome()).isEqualTo(BuyableSlots.Outcome.NOT_SAVED);
        assertThat(slots.bought(ada)).isZero();
        assertThat(bank.calls).containsExactly("withdraw 100 homes.slot", "refund 100 homes.slot");
    }

    private static final class Recorder implements Economy {
        final List<String> calls = new ArrayList<>();
        long money;

        Recorder(long money) {
            this.money = money;
        }

        @Override
        public String name() {
            return "recorder";
        }

        @Override
        public Currency currency() {
            return Currency.DEFAULT;
        }

        @Override
        public boolean hasAccount(UUID player) {
            return true;
        }

        @Override
        public Money balance(UUID player) {
            return Money.of(money);
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason) {
            money += amount.minor();
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            return withdraw(player, amount, reason, "");
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason, String source) {
            if (money < amount.minor()) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
            }
            money -= amount.minor();
            calls.add("withdraw " + amount.minor() + " " + source);
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public EconomyResult refund(UUID player, Money amount, String reason, String source) {
            money += amount.minor();
            calls.add("refund " + amount.minor() + " " + source);
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            return EconomyResult.done(amount, balance(from));
        }
    }

    @Test
    @DisplayName("bought off the server's threads: the purchase runs where it is handed, and nowhere before")
    void offThread() {
        Economies.provide(owner, new Recorder(10_000));
        BuyableSlots slots = slots();
        java.util.List<Runnable> queued = new java.util.ArrayList<>();

        java.util.concurrent.CompletableFuture<BuyableSlots.Purchase> bought =
                slots.buyOffThread(ada, terms(true, 1000, 0, 0), queued::add);

        assertThat(bought).isNotDone();
        assertThat(slots.bought(ada)).as("nothing happened on the calling thread").isZero();
        queued.forEach(Runnable::run);
        assertThat(bought.join().outcome()).isEqualTo(BuyableSlots.Outcome.BOUGHT);
        assertThat(slots.bought(ada)).isEqualTo(1);
    }
}
