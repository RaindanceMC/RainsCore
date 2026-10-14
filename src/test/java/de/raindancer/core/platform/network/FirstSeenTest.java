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

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class FirstSeenTest {

    @TempDir
    Path folder;

    private final List<Database> databases = new ArrayList<>();

    private FirstSeen server() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return new FirstSeen(database, () -> 1_000L);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("new to the network once, on the first server they join; not again on the next")
    void once() {
        UUID alex = UUID.randomUUID();
        assertThat(server().arrived(alex, "smp", false)).isTrue();
        assertThat(server().arrived(alex, "smp", false)).isFalse();
        assertThat(server().arrived(alex, "arena", false)).as("first time on the arena, not on the network").isFalse();
    }

    @Test
    @DisplayName("somebody who played before 2.0 is not new, though no row says so yet")
    void playedBefore() {
        assertThat(server().arrived(UUID.randomUUID(), "smp", true)).isFalse();
    }

    @Test
    @DisplayName("two servers seeing the same newcomer at once: only one calls them new")
    void oneWins() {
        UUID alex = UUID.randomUUID();
        FirstSeen smp = server();
        FirstSeen arena = server();
        int newcomer = (smp.arrived(alex, "smp", false) ? 1 : 0) + (arena.arrived(alex, "arena", false) ? 1 : 0);
        assertThat(newcomer).isEqualTo(1);
    }
}
