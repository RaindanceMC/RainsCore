package de.raindancer.core.social.economy;

import de.raindancer.core.platform.link.LinkGuest;
import de.raindancer.core.platform.link.LinkHost;
import de.raindancer.core.platform.link.ServerLink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The survival server's money, used from the arena: one balance, moved where it is kept. */
class LinkedEconomyTest {

    private static final String SECRET = "a-shared-secret-of-reasonable-length";
    private final List<ServerLink> open = new ArrayList<>();
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();

    @AfterEach
    void closeAll() {
        open.forEach(ServerLink::close);
    }

    @Test
    @DisplayName("balance, deposit, withdraw and a payment happen on survival; the currency reads the same; too much is refused")
    void money() throws Exception {
        LinkHost arenaEnd = LinkHost.listen("127.0.0.1", 0, SECRET, Duration.ofSeconds(2));
        open.add(arenaEnd);
        LinkGuest survivalEnd = LinkGuest.connect(URI.create("http://127.0.0.1:" + arenaEnd.port()), SECRET,
                Duration.ofSeconds(2), Duration.ofMillis(50));
        open.add(survivalEnd);
        Wallets survival = new Wallets(Map.of(ada, 1_000L, bo, 50L));
        LinkedEconomy.serve(survivalEnd, () -> java.util.Optional.of(survival));
        LinkedEconomy arena = new LinkedEconomy(arenaEnd);

        assertThat(arena.currency().symbol()).isEqualTo("✦");
        assertThat(arena.balance(ada)).isEqualTo(Money.of(1_000));
        assertThat(arena.transfer(ada, bo, Money.of(300), "pay").outcome()).isEqualTo(EconomyResult.Outcome.DONE);
        assertThat(survival.balance(ada)).isEqualTo(Money.of(700));
        assertThat(survival.balance(bo)).isEqualTo(Money.of(350));
        assertThat(arena.transfer(bo, ada, Money.of(5_000), "pay").outcome()).isEqualTo(EconomyResult.Outcome.NOT_ENOUGH);
        assertThat(arena.deposit(bo, Money.of(10), "wave reward").outcome()).isEqualTo(EconomyResult.Outcome.DONE);
        assertThat(arena.withdraw(bo, Money.of(60), "fee").balance()).isEqualTo(Money.of(300));
        assertThat(arena.balance(bo)).isEqualTo(Money.of(300));

        survivalEnd.close();
        assertThat(arena.transfer(ada, bo, Money.of(1), "pay").outcome())
                .as("survival gone: refused, never guessed").isEqualTo(EconomyResult.Outcome.UNAVAILABLE);
    }

    /** A plain wallet per player, refusing to go below zero. */
    private static final class Wallets implements Economy {
        private final Map<UUID, Long> held = new ConcurrentHashMap<>();
        private final Currency currency = new Currency("Star", "Stars", "✦", Currency.Placement.BEFORE, 0,
                ',', '.', false, null, null, null);

        Wallets(Map<UUID, Long> start) {
            held.putAll(start);
        }

        @Override
        public String name() {
            return "wallets";
        }

        @Override
        public Currency currency() {
            return currency;
        }

        @Override
        public boolean hasAccount(UUID player) {
            return held.containsKey(player);
        }

        @Override
        public Money balance(UUID player) {
            return Money.of(held.getOrDefault(player, 0L));
        }

        @Override
        public synchronized EconomyResult deposit(UUID player, Money amount, String reason) {
            held.merge(player, amount.minor(), Long::sum);
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public synchronized EconomyResult withdraw(UUID player, Money amount, String reason) {
            if (held.getOrDefault(player, 0L) < amount.minor()) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
            }
            held.merge(player, -amount.minor(), Long::sum);
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public synchronized EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            EconomyResult taken = withdraw(from, amount, reason);
            if (taken.outcome() != EconomyResult.Outcome.DONE) {
                return taken;
            }
            deposit(to, amount, reason);
            return EconomyResult.done(amount, balance(from));
        }
    }
}
