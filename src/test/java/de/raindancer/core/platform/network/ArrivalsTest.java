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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class ArrivalsTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Arrivals.Destination WARP = new Arrivals.Destination("world", 10.5, 64, -3.5, 90f, 0f);

    @TempDir
    Path folder;

    private Database database;
    private Arrivals arrivals;

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        arrivals = new Arrivals(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("taken once, by the server it was meant for, while it is fresh")
    void takenOnce() {
        arrivals.expect(ALEX, "arena", WARP, 1_000);
        assertThat(arrivals.take(ALEX, "smp", 500)).as("not meant for smp").isEmpty();
        assertThat(arrivals.take(ALEX, "arena", 500)).contains(WARP);
        assertThat(arrivals.take(ALEX, "arena", 500)).as("taken already").isEmpty();
    }

    @Test
    @DisplayName("an arrival that ran out is not applied")
    void expires() {
        arrivals.expect(ALEX, "arena", WARP, 1_000);
        assertThat(arrivals.take(ALEX, "arena", 1_001)).isEmpty();
    }

    @Test
    @DisplayName("a later send replaces an earlier one")
    void replaced() {
        arrivals.expect(ALEX, "arena", WARP, 1_000);
        Arrivals.Destination other = new Arrivals.Destination("nether", 1, 2, 3, 0f, 0f);
        arrivals.expect(ALEX, "hub", other, 2_000);
        assertThat(arrivals.take(ALEX, "arena", 500)).isEmpty();
        assertThat(arrivals.take(ALEX, "hub", 500)).contains(other);
    }

    @Test
    @DisplayName("pruning forgets only what ran out")
    void prune() {
        arrivals.expect(ALEX, "arena", WARP, 1_000);
        arrivals.prune(999);
        assertThat(arrivals.take(ALEX, "arena", 500)).contains(WARP);
    }

    @Test
    @DisplayName("taking back a place: true while it waits, false once the player arrived and took it")
    void withdraw() {
        UUID who = ALEX;
        Arrivals.Destination place = WARP;
        arrivals.expect(who, "arena", place, Long.MAX_VALUE);
        assertThat(arrivals.withdraw(who, "arena")).isTrue();
        assertThat(arrivals.take(who, "arena", 0)).isEmpty();

        arrivals.expect(who, "arena", place, Long.MAX_VALUE);
        assertThat(arrivals.take(who, "arena", 0)).contains(place);
        assertThat(arrivals.withdraw(who, "arena")).isFalse();
    }
}
