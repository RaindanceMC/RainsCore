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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class PresenceTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BEA = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    private record Server(NetworkServers servers, Presence presence) {
        void beat(int players) {
            servers.heartbeat(servers.self().toUpperCase(), players);
        }
    }

    private Server server(String id) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        NetworkServers servers = new NetworkServers(database, id, "default", clock::get);
        Presence presence = new Presence(database, servers, clock::get);
        presence.connect(hub.bus(id));
        return new Server(servers, presence);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("a server is online while its heartbeat is fresh, and drops off by itself when it stops")
    void heartbeats() {
        Server smp = server("smp");
        Server arena = server("arena");
        smp.beat(3);
        arena.beat(1);
        smp.servers().refresh();
        assertThat(smp.servers().online()).extracting(NetworkServers.Server::id).containsExactly("arena", "smp");
        assertThat(smp.servers().byId("arena")).get().extracting(NetworkServers.Server::name).isEqualTo("ARENA");

        clock.addAndGet(NetworkServers.ALIVE_MILLIS + 1);
        smp.beat(3);
        assertThat(smp.servers().online()).extracting(NetworkServers.Server::id).containsExactly("smp");
    }

    @Test
    @DisplayName("who joined where is seen from every server, by id and by name in any case")
    void whereEverybodyIs() {
        Server smp = server("smp");
        Server arena = server("arena");
        smp.beat(1);
        arena.beat(1);
        smp.presence().joined(ALEX, "Alex", false);
        arena.presence().joined(BEA, "Bea", false);
        hub.drain();
        arena.servers().refresh();
        smp.servers().refresh();

        assertThat(arena.presence().where(ALEX, false)).get().extracting(Presence.Here::server).isEqualTo("smp");
        assertThat(smp.presence().named("bEA", false)).get().extracting(Presence.Here::server).isEqualTo("arena");
        assertThat(smp.presence().byServer(false)).containsOnlyKeys("smp", "arena");
    }

    @Test
    @DisplayName("switching servers: the old server saying goodbye late does not take them off the new one")
    void lateGoodbyeAfterSwitch() {
        Server smp = server("smp");
        Server arena = server("arena");
        smp.beat(1);
        arena.beat(1);
        smp.presence().joined(ALEX, "Alex", false);
        arena.presence().joined(ALEX, "Alex", false);
        smp.presence().left(ALEX);
        hub.drain();
        smp.servers().refresh();
        smp.presence().refreshAll();
        assertThat(smp.presence().where(ALEX, false)).get().extracting(Presence.Here::server).isEqualTo("arena");
    }

    @Test
    @DisplayName("players listed on a crashed server are not online")
    void noGhostsAfterACrash() {
        Server smp = server("smp");
        Server arena = server("arena");
        smp.beat(1);
        arena.beat(1);
        arena.presence().joined(BEA, "Bea", false);
        smp.presence().refreshAll();
        smp.servers().refresh();
        assertThat(smp.presence().where(BEA, false)).isPresent();

        clock.addAndGet(NetworkServers.ALIVE_MILLIS + 1);
        smp.beat(0);
        assertThat(smp.presence().where(BEA, false)).isEmpty();
    }

    @Test
    @DisplayName("vanished players are only found by those who may see them, and stay vanished on the next server")
    void vanish() {
        Server smp = server("smp");
        Server arena = server("arena");
        smp.beat(1);
        arena.beat(1);
        smp.presence().joined(ALEX, "Alex", false);
        smp.presence().vanished(ALEX, true);
        hub.drain();
        arena.servers().refresh();
        assertThat(arena.presence().where(ALEX, false)).isEmpty();
        assertThat(arena.presence().where(ALEX, true)).isPresent();
        assertThat(arena.presence().wasVanished(ALEX)).isTrue();

        smp.presence().vanished(ALEX, false);
        assertThat(arena.presence().wasVanished(ALEX)).isFalse();
    }

    @Test
    @DisplayName("a server coming back up after a crash clears what it had listed")
    void clearsItsOwnRows() {
        Server smp = server("smp");
        smp.beat(1);
        smp.presence().joined(ALEX, "Alex", false);
        smp.presence().clearThisServer();
        smp.presence().refreshAll();
        assertThat(smp.presence().where(ALEX, true)).isEmpty();
    }
}
