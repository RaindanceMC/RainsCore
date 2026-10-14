package de.raindancer.core.social.economy;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Tag("database")
class BuyableSlotsOnTheNetworkTest {

    @TempDir
    Path plugins;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final UUID ada = UUID.randomUUID();

    private BuyableSlots server(String id) {
        Database database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        databases.add(database);
        Documents documents = new Documents(database, hub.bus(id), plugins, id, System::currentTimeMillis);
        BuyableSlots slots = new BuyableSlots(new YamlStore(plugins.resolve("RainsHomes").resolve("bought-slots.yml"),
                documents, Documents.Scope.NETWORK), "homes.slot", "Extra home slot");
        slots.load();
        return slots;
    }

    @AfterEach
    void close() {
        Economies.clear();
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("bought on two servers, each knowing only its own last read: both slots count, everywhere")
    void bothCount() {
        Economies.provide(mock(Plugin.class), new Rich());
        BuyableSlots smp = server("smp");
        BuyableSlots arena = server("arena");
        BuyableSlots.Terms terms = new BuyableSlots.Terms(true, Money.of(100), 0, 0);

        assertThat(smp.buy(ada, terms).owned()).isEqualTo(1);
        assertThat(arena.buy(ada, terms).owned()).as("counted on top of smp's").isEqualTo(2);
        hub.drain();
        assertThat(smp.bought(ada)).as("smp heard of arena's").isEqualTo(2);
        assertThat(server("hub").bought(ada)).isEqualTo(2);
    }

    @Test
    @DisplayName("the ceiling holds across servers: a slot bought elsewhere counts before charging")
    void ceilingAcrossServers() {
        Rich bank = new Rich();
        Economies.provide(mock(Plugin.class), bank);
        BuyableSlots smp = server("smp");
        BuyableSlots arena = server("arena");
        BuyableSlots.Terms one = new BuyableSlots.Terms(true, Money.of(100), 0, 1);
        assertThat(smp.buy(ada, one).outcome()).isEqualTo(BuyableSlots.Outcome.BOUGHT);
        assertThat(arena.buy(ada, one).outcome()).isEqualTo(BuyableSlots.Outcome.MAXED);
        assertThat(bank.charged).isEqualTo(100);
    }

    private static final class Rich implements Economy {
        long charged;

        @Override
        public String name() {
            return "rich";
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
            return Money.of(1_000_000);
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason) {
            charged -= amount.minor();
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            charged += amount.minor();
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            return EconomyResult.done(amount, balance(from));
        }
    }
}
