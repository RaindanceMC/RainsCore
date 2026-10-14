package de.raindancer.core.world.teleport;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class ReturnsOnTheNetworkTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Database> databases = new ArrayList<>();

    private Database database() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return database;
    }

    private Returns server(String id) {
        return new Returns(new ReturnsStore(database(), clock::get), id, Runnable::run);
    }

    private static Waypoint at(double x, Waypoint.Cause cause) {
        return new Waypoint("world", x, 64, 0, 0, 0, cause, 0);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("where they were on one server is where /back takes them from the next")
    void followsThePlayer() {
        Returns smp = server("smp");
        Returns arena = server("arena");
        smp.remember(ALEX, at(10, Waypoint.Cause.TELEPORT));
        smp.forget(ALEX);
        arena.arrived(ALEX);

        assertThat(arena.anywhere(ALEX)).get().satisfies(found -> {
            assertThat(found.server()).isEqualTo("smp");
            assertThat(found.where().x()).isEqualTo(10);
            assertThat(found.where().cause()).isEqualTo(Waypoint.Cause.TELEPORT);
        });
        assertThat(arena.of(ALEX)).as("not a place on this server").isEmpty();
        assertThat(arena.takeAnywhere(ALEX)).isPresent();
        assertThat(server("hub").arrived(ALEX).anywhere(ALEX)).as("used up everywhere").isEmpty();
    }

    @Test
    @DisplayName("a death on one server outranks a teleport recorded later on another")
    void deathOutranks() {
        Returns smp = server("smp");
        Returns arena = server("arena");
        smp.remember(ALEX, at(10, Waypoint.Cause.DEATH));
        arena.arrived(ALEX);
        assertThat(arena.remember(ALEX, at(99, Waypoint.Cause.TELEPORT))).isFalse();
        assertThat(server("hub").arrived(ALEX).anywhere(ALEX)).get()
                .extracting(found -> found.where().cause()).isEqualTo(Waypoint.Cause.DEATH);
    }

    @Test
    @DisplayName("somebody gone from the network for a while is forgotten; somebody still on it is not")
    void forgottenWhenGone() throws Exception {
        Database database = database();
        Returns smp = new Returns(new ReturnsStore(database, clock::get), "smp", Runnable::run);
        UUID bea = UUID.randomUUID();
        smp.remember(ALEX, at(10, Waypoint.Cause.TELEPORT));
        smp.remember(bea, at(20, Waypoint.Cause.TELEPORT));
        database.write(connection -> {
            try (PreparedStatement online = connection.prepareStatement("""
                    INSERT INTO network_presence (player, name, name_lower, server, vanished, since)
                    VALUES (?, 'Bea', 'bea', 'arena', 0, 0)""")) {
                online.setString(1, bea.toString());
                online.executeUpdate();
            }
        });
        clock.addAndGet(ReturnsStore.GONE_MILLIS + 1);
        new ReturnsStore(database, clock::get).prune();

        assertThat(server("hub").arrived(ALEX).anywhere(ALEX)).isEmpty();
        assertThat(server("hub").arrived(bea).anywhere(bea)).isPresent();
    }

    @Test
    @DisplayName("a server of its own keeps them in memory only, as before")
    void alone() {
        Returns alone = new Returns();
        alone.remember(ALEX, at(10, Waypoint.Cause.TELEPORT));
        assertThat(alone.of(ALEX)).isPresent();
        assertThat(alone.anywhere(ALEX)).get().extracting(Returns.Elsewhere::server).isEqualTo("");
    }

    @Test
    @DisplayName("a place taken for a trip that did not happen is put back on its own server")
    void restoredOnItsServer() {
        Returns smp = server("smp");
        Returns arena = server("arena");
        smp.remember(ALEX, at(10, Waypoint.Cause.TELEPORT));
        arena.arrived(ALEX);
        Returns.Elsewhere taken = arena.takeAnywhere(ALEX).orElseThrow();
        assertThat(arena.restore(ALEX, taken)).isTrue();
        assertThat(server("hub").arrived(ALEX).anywhere(ALEX)).get().extracting(Returns.Elsewhere::server).isEqualTo("smp");
    }
}
