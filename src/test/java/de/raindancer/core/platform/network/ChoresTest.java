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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class ChoresTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Database> databases = new ArrayList<>();

    private Chores server(String id) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return new Chores(new NetworkRoles(database, id, clock::get));
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("one server of the network does the chores; another takes over when it stops")
    void oneServer() {
        Chores smp = server("smp");
        Chores arena = server("arena");
        AtomicInteger done = new AtomicInteger();
        smp.tick();
        arena.tick();
        smp.ifMine(done::incrementAndGet);
        arena.ifMine(done::incrementAndGet);
        assertThat(done).hasValue(1);
        assertThat(smp.isMine()).isTrue();

        clock.addAndGet(NetworkRoles.LEASE_MILLIS + 1);
        arena.tick();
        assertThat(arena.isMine()).isTrue();
        smp.tick();
        assertThat(smp.isMine()).isFalse();
    }

    @Test
    @DisplayName("a server of its own does its own chores")
    void alone() {
        AtomicInteger done = new AtomicInteger();
        Chores.ALONE.ifMine(done::incrementAndGet);
        assertThat(done).hasValue(1);
    }
}
