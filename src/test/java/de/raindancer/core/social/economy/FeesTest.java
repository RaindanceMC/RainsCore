package de.raindancer.core.social.economy;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FeesTest {

    private final Plugin owner = mock(Plugin.class);
    private final UUID payer = UUID.randomUUID();

    @AfterEach
    void reset() {
        Economies.clear();
        EconomyLevers.clear();
        PriceIndex.clear();
    }

    @Test
    @DisplayName("a fee of nothing is paid at once, economy or not — so a switched-off fee needs no economy")
    void free() {
        assertThat(Fees.charge(payer, Money.ZERO, "Teleport", "tpa.fee").succeeded()).isTrue();
        assertThat(Fees.pay(payer, Money.ZERO, "Reward", "reward").succeeded()).isTrue();
    }

    @Test
    @DisplayName("without an economy, amounts are read and written in Core's default currency")
    void defaultCurrency() {
        assertThat(Fees.currency()).isEqualTo(Currency.DEFAULT);
        assertThat(Fees.amount("1.5k")).isEqualTo(Currency.DEFAULT.ofMajor(1500));
    }

    @Test
    @DisplayName("a real fee without any economy is refused as unavailable, never let through for free")
    void noEconomy() {
        assertThat(Fees.charge(payer, Money.of(100), "Teleport", "tpa.fee").outcome())
                .isEqualTo(EconomyResult.Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("a fee is withdrawn with its source, after the price level and the levers have had their say")
    void charged() {
        Recording bank = new Recording(Money.of(10_000));
        Economies.provide(owner, bank);
        PriceIndex.provide(owner, () -> 2.0);
        EconomyLevers.provide(owner, new EconomyLever() {
            @Override
            public int sinkChange(String source) {
                return 50;
            }
        });
        assertThat(Fees.quote("tpa.fee", Money.of(100))).isEqualTo(Money.of(300));
        EconomyResult paid = Fees.charge(payer, Money.of(100), "Teleport", "tpa.fee");
        assertThat(paid.succeeded()).isTrue();
        assertThat(bank.calls).containsExactly("withdraw 300 Teleport tpa.fee");
    }

    @Test
    @DisplayName("a payout goes through the faucet lever and is deposited with its source")
    void paid() {
        Recording bank = new Recording(Money.ZERO);
        Economies.provide(owner, bank);
        EconomyLevers.provide(owner, new EconomyLever() {
            @Override
            public int faucetChange(String source) {
                return -50;
            }
        });
        Fees.pay(payer, Money.of(100), "Goal", "jobs.goal");
        assertThat(bank.calls).containsExactly("deposit 50 Goal jobs.goal");
    }

    @Test
    @DisplayName("a payout the levers cut to nothing is done without asking the economy")
    void cutToNothing() {
        Recording bank = new Recording(Money.ZERO);
        Economies.provide(owner, bank);
        EconomyLevers.provide(owner, new EconomyLever() {
            @Override
            public int faucetChange(String source) {
                return -100;
            }
        });
        assertThat(Fees.pay(payer, Money.of(100), "Goal", "jobs.goal").succeeded()).isTrue();
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("a refund is exactly what was taken — the levers and the price level do not touch it")
    void refund() {
        Recording bank = new Recording(Money.ZERO);
        Economies.provide(owner, bank);
        PriceIndex.provide(owner, () -> 3.0);
        Fees.refund(payer, Money.of(77), "Refund", "tpa.fee");
        assertThat(bank.calls).as("an economy without its own refund deposits it")
                .containsExactly("deposit 77 Refund tpa.fee");
    }

    @Test
    @DisplayName("an economy with its own refund is asked for a refund, not a deposit")
    void ownRefund() {
        Recording bank = new Recording(Money.ZERO) {
            @Override
            public EconomyResult refund(UUID player, Money amount, String reason, String source) {
                calls.add("refund " + amount.minor() + " " + source);
                return EconomyResult.done(amount, money);
            }
        };
        Economies.provide(owner, bank);
        Fees.refund(payer, Money.of(77), "Refund", "tpa.fee");
        assertThat(bank.calls).containsExactly("refund 77 tpa.fee");
    }

    @Test
    @DisplayName("an economy that does not know about sources is still charged, the old way")
    void oldEconomy() {
        Plain bank = new Plain(Money.of(500));
        Economies.provide(owner, bank);
        Fees.charge(payer, Money.of(100), "Teleport", "tpa.fee");
        assertThat(bank.calls).containsExactly("withdraw 100 Teleport");
    }

    @Test
    @DisplayName("a fee written in a settings file is read in the server's currency; blank or nonsense is no fee")
    void written() {
        assertThat(Fees.amount("12.50")).isEqualTo(Money.of(1250));
        assertThat(Fees.amount("1.5k")).isEqualTo(Money.of(150_000));
        assertThat(Fees.amount("")).isEqualTo(Money.ZERO);
        assertThat(Fees.amount(null)).isEqualTo(Money.ZERO);
        assertThat(Fees.amount("lots")).isEqualTo(Money.ZERO);
        assertThat(Fees.amount("-5")).isEqualTo(Money.ZERO);
    }

    /** An economy written before sources existed: only the three-argument methods. */
    private static class Plain implements Economy {
        final List<String> calls = new ArrayList<>();
        Money money;

        Plain(Money money) {
            this.money = money;
        }

        @Override
        public String name() {
            return "recording";
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
            return money;
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason) {
            calls.add("deposit " + amount.minor() + " " + reason);
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            calls.add("withdraw " + amount.minor() + " " + reason);
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            return EconomyResult.done(amount, money);
        }
    }

    private static class Recording extends Plain {
        Recording(Money money) {
            super(money);
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason, String source) {
            calls.add("deposit " + amount.minor() + " " + reason + " " + source);
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason, String source) {
            calls.add("withdraw " + amount.minor() + " " + reason + " " + source);
            return EconomyResult.done(amount, money);
        }

    }
}
