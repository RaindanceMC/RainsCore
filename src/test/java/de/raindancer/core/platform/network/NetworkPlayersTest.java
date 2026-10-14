package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class NetworkPlayersTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    private record Server(NetworkServers servers, Presence presence, NetworkPlayers players, List<String> said) {
    }

    private Server server(String id, Set<UUID> onlineHere) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        NetworkServers servers = new NetworkServers(database, id, "default", System::currentTimeMillis);
        Presence presence = new Presence(database, servers, System::currentTimeMillis);
        LocalBus bus = hub.bus(id);
        presence.connect(bus);
        List<String> said = new CopyOnWriteArrayList<>();
        NetworkPlayers players = new NetworkPlayers(presence, servers, bus, (player, key, values) -> {
            if (!onlineHere.contains(player)) {
                return false;
            }
            said.add(key + values);
            return true;
        });
        servers.heartbeat(id, onlineHere.size());
        return new Server(servers, presence, players, said);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("a message for somebody on another server is said there, with its values")
    void tellsAcrossServers() {
        Server smp = server("smp", Set.of());
        Server arena = server("arena", Set.of(ALEX));
        arena.presence().joined(ALEX, "Alex", false);
        smp.servers().refresh();
        smp.presence().refreshAll();

        assertThat(smp.players().tell(ALEX, "duel.invited", "from", "Bea")).isTrue();
        hub.drain();

        assertThat(arena.said()).containsExactly("duel.invited[from, Bea]");
        assertThat(smp.said()).isEmpty();
    }

    @Test
    @DisplayName("somebody here is told here, and nothing goes over the network")
    void tellsHere() {
        Server smp = server("smp", Set.of(ALEX));
        Server arena = server("arena", Set.of());
        assertThat(smp.players().tell(ALEX, "hello")).isTrue();
        hub.drain();
        assertThat(smp.said()).containsExactly("hello[]");
        assertThat(arena.said()).isEmpty();
    }

    @Test
    @DisplayName("somebody online nowhere cannot be told")
    void nobody() {
        Server smp = server("smp", Set.of());
        assertThat(smp.players().tell(UUID.randomUUID(), "hello")).isFalse();
    }
}
