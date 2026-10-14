package de.raindancer.core.social.economy;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkRoles;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class NetworkEconomyTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BEA = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<NetworkCalls> calls = new ArrayList<>();

    /** A bank that keeps balances in a map, as the economy module would. */
    private static final class Bank implements Economy {
        final Map<UUID, Long> balances = new ConcurrentHashMap<>();

        @Override
        public String name() {
            return "bank";
        }

        @Override
        public Currency currency() {
            return Currency.DEFAULT;
        }

        @Override
        public boolean hasAccount(UUID player) {
            return balances.containsKey(player);
        }

        @Override
        public Money balance(UUID player) {
            return Money.of(balances.getOrDefault(player, 0L));
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason) {
            long now = balances.merge(player, amount.minor(), Long::sum);
            return new EconomyResult(EconomyResult.Outcome.DONE, amount, Money.of(now));
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            long had = balances.getOrDefault(player, 0L);
            if (had < amount.minor()) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, Money.of(had));
            }
            balances.put(player, had - amount.minor());
            return new EconomyResult(EconomyResult.Outcome.DONE, amount, Money.of(had - amount.minor()));
        }

        @Override
        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            EconomyResult taken = withdraw(from, amount, reason);
            if (taken.outcome() != EconomyResult.Outcome.DONE) {
                return taken;
            }
            deposit(to, amount, reason);
            return taken;
        }
    }

    private NetworkRoles roles(String server) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return new NetworkRoles(database, server, System::currentTimeMillis);
    }

    private BankRequests requests() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return new BankRequests(database, System::currentTimeMillis);
    }

    private NetworkCalls calls(String server) {
        NetworkCalls made = new NetworkCalls(hub.bus(server));
        calls.add(made);
        return made;
    }

    @AfterEach
    void close() {
        calls.forEach(NetworkCalls::close);
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("a server without the economy moves money at the server that keeps it")
    void movesAtTheBank() {
        Bank bank = new Bank();
        bank.balances.put(ALEX, 1_000L);
        NetworkRoles smpRoles = roles("smp");
        assertThat(smpRoles.claim(NetworkEconomy.ROLE)).isTrue();
        NetworkEconomy.serve(calls("smp"), () -> Optional.of(bank), () -> true, requests());
        NetworkEconomy arena = new NetworkEconomy(calls("arena"), roles("arena"));

        assertThat(arena.balance(ALEX)).isEqualTo(Money.of(1_000));
        assertThat(arena.hasAccount(ALEX)).isTrue();
        assertThat(arena.withdraw(ALEX, Money.of(300), "entry fee").outcome()).isEqualTo(EconomyResult.Outcome.DONE);
        assertThat(arena.transfer(ALEX, BEA, Money.of(200), "bet").outcome()).isEqualTo(EconomyResult.Outcome.DONE);
        assertThat(arena.withdraw(BEA, Money.of(999), "too much").outcome()).isEqualTo(EconomyResult.Outcome.NOT_ENOUGH);
        assertThat(bank.balances).containsEntry(ALEX, 500L).containsEntry(BEA, 200L);
        assertThat(arena.currency()).isEqualTo(Currency.DEFAULT);
    }

    @Test
    @DisplayName("nobody keeping the money: nothing moves, and it says the bank is unavailable")
    void noBank() {
        NetworkEconomy arena = new NetworkEconomy(calls("arena"), roles("arena"));
        assertThat(arena.deposit(ALEX, Money.of(5), "x").outcome()).isEqualTo(EconomyResult.Outcome.UNAVAILABLE);
        assertThat(arena.withdraw(ALEX, Money.of(5), "x").outcome()).isEqualTo(EconomyResult.Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("an answer lost on the way: the retry with the same request id is not applied a second time")
    void retryIsNotAppliedTwice() {
        Bank bank = new Bank();
        bank.balances.put(ALEX, 1_000L);
        NetworkRoles smpRoles = roles("smp");
        smpRoles.claim(NetworkEconomy.ROLE);
        NetworkCalls bankCalls = calls("smp");
        java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
        NetworkEconomy.serve(bankCalls, () -> Optional.of(bank), () -> true, requests());
        // The same request asked twice — what a retry after a lost answer is.
        Map<String, String> request = Map.of("request", "r-1", "kind", "withdraw", "player", ALEX.toString(),
                "amount", "300", "reason", "fee");
        NetworkCalls caller = calls("arena");
        Optional<Map<String, String>> first = caller.ask("smp", LinkedEconomy.MOVE, request, java.time.Duration.ofSeconds(5)).join();
        Optional<Map<String, String>> second = caller.ask("smp", LinkedEconomy.MOVE, request, java.time.Duration.ofSeconds(5)).join();
        assertThat(first).isEqualTo(second);
        assertThat(bank.balances).containsEntry(ALEX, 700L);
    }

    @Test
    @DisplayName("a server that is not the bank (any more) moves nothing, whoever asks it")
    void notTheBankAnswersUnavailable() {
        Bank bank = new Bank();
        bank.balances.put(ALEX, 1_000L);
        NetworkEconomy.serve(calls("smp"), () -> Optional.of(bank), () -> false, requests());
        Optional<Map<String, String>> answer = calls("arena").ask("smp", LinkedEconomy.MOVE,
                Map.of("request", "r-2", "kind", "deposit", "player", ALEX.toString(), "amount", "5", "reason", ""),
                java.time.Duration.ofSeconds(5)).join();
        assertThat(answer).get().extracting(found -> found.get("outcome")).isEqualTo("UNAVAILABLE");
        assertThat(bank.balances).containsEntry(ALEX, 1_000L);
    }

    @Test
    @DisplayName("the bank restarted between a request and its retry: the retry is still not applied again")
    void retryAfterRestart() {
        Bank bank = new Bank();
        bank.balances.put(ALEX, 1_000L);
        Map<String, String> request = Map.of("request", "r-3", "kind", "withdraw", "player", ALEX.toString(),
                "amount", "300", "reason", "fee");
        NetworkCalls before = calls("smp");
        NetworkBus.Subscription serving = NetworkEconomy.serve(before, () -> Optional.of(bank), () -> true, requests());
        NetworkCalls caller = calls("arena");
        Optional<Map<String, String>> first = caller.ask("smp", LinkedEconomy.MOVE, request, java.time.Duration.ofSeconds(5)).join();
        serving.close();
        before.close();

        NetworkEconomy.serve(calls("smp"), () -> Optional.of(bank), () -> true, requests());
        Optional<Map<String, String>> again = caller.ask("smp", LinkedEconomy.MOVE, request, java.time.Duration.ofSeconds(5)).join();
        assertThat(again).isEqualTo(first);
        assertThat(bank.balances).containsEntry(ALEX, 700L);
    }

    @Test
    @DisplayName("a movement the bank began but did not finish is answered unavailable and never applied again")
    void unfinishedIsNotRetried() {
        Bank bank = new Bank();
        bank.balances.put(ALEX, 1_000L);
        BankRequests requests = requests();
        assertThat(requests.begin("r-4")).isInstanceOf(BankRequests.GoAhead.class);
        NetworkEconomy.serve(calls("smp"), () -> Optional.of(bank), () -> true, requests);
        Optional<Map<String, String>> answer = calls("arena").ask("smp", LinkedEconomy.MOVE,
                Map.of("request", "r-4", "kind", "withdraw", "player", ALEX.toString(), "amount", "300", "reason", ""),
                java.time.Duration.ofSeconds(5)).join();
        assertThat(answer).get().extracting(found -> found.get("outcome")).isEqualTo("UNAVAILABLE");
        assertThat(bank.balances).containsEntry(ALEX, 1_000L);
    }

    @Test
    @DisplayName("a movement without a request id moves nothing")
    void noRequestId() {
        Bank bank = new Bank();
        bank.balances.put(ALEX, 1_000L);
        NetworkEconomy.serve(calls("smp"), () -> Optional.of(bank), () -> true, requests());
        Optional<Map<String, String>> answer = calls("arena").ask("smp", LinkedEconomy.MOVE,
                Map.of("kind", "withdraw", "player", ALEX.toString(), "amount", "300", "reason", ""),
                java.time.Duration.ofSeconds(5)).join();
        assertThat(answer).get().extracting(found -> found.get("outcome")).isEqualTo("UNAVAILABLE");
        assertThat(bank.balances).containsEntry(ALEX, 1_000L);
    }
}
