package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** How well each server runs, and which are down: what a network status page shows. */
@Tag("database")
class NetworkHealthTest {

    @TempDir
    Path folder;
    private Database database;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private NetworkServers server(String id) {
        return new NetworkServers(database, id, "default", now::get);
    }

    @Test
    @DisplayName("a heartbeat carries how the server runs: ticks per second and milliseconds per tick")
    void performance() {
        NetworkServers smp = server("smp");
        smp.heartbeat("Lilly's SMP", 12, 19.7, 31.2);

        NetworkServers.Server seen = smp.online().getFirst();
        assertThat(seen.players()).isEqualTo(12);
        assertThat(seen.tps()).isEqualTo(19.7, org.assertj.core.data.Offset.offset(0.01));
        assertThat(seen.mspt()).isEqualTo(31.2, org.assertj.core.data.Offset.offset(0.01));
        assertThat(seen.startedAt()).isEqualTo(now.get());
    }

    @Test
    @DisplayName("the old heartbeat without numbers still works, the numbers are just unknown")
    void oldHeartbeat() {
        NetworkServers hub = server("hub");
        hub.heartbeat("Hub", 3);
        assertThat(hub.online().getFirst().tps()).isNaN();
    }

    @Test
    @DisplayName("a server that stops cleanly stays known as offline, with when; one gone for a week is forgotten")
    void stoppedServers() {
        NetworkServers smp = server("smp");
        NetworkServers arena = server("arena");
        smp.heartbeat("Lilly's SMP", 5, 20, 10);
        arena.heartbeat("Arena", 0, 20, 4);

        now.addAndGet(1_000);
        arena.leave();
        smp.heartbeat("Lilly's SMP", 5, 20, 10);

        assertThat(smp.online()).extracting(NetworkServers.Server::id).containsExactly("smp");
        assertThat(smp.known()).extracting(NetworkServers.Known::id).containsExactly("arena", "smp");
        NetworkServers.Known stopped = smp.known().getFirst();
        assertThat(stopped.online()).isFalse();
        assertThat(stopped.name()).isEqualTo("Arena");
        assertThat(stopped.downSince()).isEqualTo(now.get());

        now.addAndGet(8L * 24 * 3600 * 1000);
        smp.heartbeat("Lilly's SMP", 5, 20, 10);
        assertThat(smp.known()).extracting(NetworkServers.Known::id).containsExactly("smp");
    }

    @Test
    @DisplayName("a crashed server (heartbeat gone stale) is offline since its last beat")
    void crashed() {
        NetworkServers smp = server("smp");
        NetworkServers ctf = server("ctf");
        ctf.heartbeat("CTF", 2, 20, 8);
        long lastBeat = now.get();
        now.addAndGet(NetworkServers.ALIVE_MILLIS + 5_000);
        smp.heartbeat("Lilly's SMP", 5, 20, 10);

        NetworkServers.Known gone = smp.known().getFirst();
        assertThat(gone.id()).isEqualTo("ctf");
        assertThat(gone.online()).isFalse();
        assertThat(gone.downSince()).isEqualTo(lastBeat);
    }
}
