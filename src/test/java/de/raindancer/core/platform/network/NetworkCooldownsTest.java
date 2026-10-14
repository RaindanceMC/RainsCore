package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class NetworkCooldownsTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Database> databases = new ArrayList<>();

    private NetworkCooldowns server() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return new NetworkCooldowns(database, clock::get, Runnable::run);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("a wait started on one server is still running on the next: switching servers does not dodge it")
    void followsThePlayer() {
        Cooldowns<UUID> smp = server().of("tpa.back", clock::get);
        smp.every(Duration.ofSeconds(60));
        NetworkCooldowns arenaNetwork = server();
        Cooldowns<UUID> arena = arenaNetwork.of("tpa.back", clock::get);
        arena.every(Duration.ofSeconds(60));

        assertThat(smp.tryUse(ALEX)).isTrue();
        arenaNetwork.arrived(ALEX);
        assertThat(arena.isReady(ALEX)).isFalse();
        assertThat(arena.tryUse(ALEX)).isFalse();

        clock.addAndGet(60_001);
        assertThat(arena.tryUse(ALEX)).isTrue();
    }

    @Test
    @DisplayName("only the cooldown of that name follows; another feature's is its own")
    void byName() {
        Cooldowns<UUID> back = server().of("tpa.back", clock::get);
        back.every(Duration.ofSeconds(60));
        back.start(ALEX);
        NetworkCooldowns arenaNetwork = server();
        Cooldowns<UUID> homes = arenaNetwork.of("homes", clock::get);
        homes.every(Duration.ofSeconds(60));
        arenaNetwork.arrived(ALEX);
        assertThat(homes.isReady(ALEX)).isTrue();
    }

    @Test
    @DisplayName("old waits are thrown away")
    void pruned() {
        NetworkCooldowns network = server();
        Cooldowns<UUID> back = network.of("tpa.back", clock::get);
        back.every(Duration.ofSeconds(60));
        back.start(ALEX);
        clock.addAndGet(NetworkCooldowns.KEPT_MILLIS + 1);
        assertThat(network.prune()).isEqualTo(1);
    }
}
