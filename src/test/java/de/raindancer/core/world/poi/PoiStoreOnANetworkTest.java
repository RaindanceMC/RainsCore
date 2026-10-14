package de.raindancer.core.world.poi;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.ThisServer;
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
class PoiStoreOnANetworkTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    private PoiStore server(String id) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        PoiStore store = new PoiStore(database);
        store.load();
        store.connect(hub.bus(id));
        return store;
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
        ThisServer.set("");
    }

    private static Poi warp(String name) {
        return Poi.builder(name, "world", 10, 64, 10).kind("warp").shared(true).build();
    }

    @Test
    @DisplayName("a place saved on one server is stamped with it, and listed on the other")
    void travelsAndIsStamped() {
        PoiStore smp = server("smp");
        PoiStore arena = server("arena");
        ThisServer.set("smp");
        Poi spawn = warp("spawn");
        smp.save(spawn);
        smp.flush();
        hub.drain();

        assertThat(arena.byId(spawn.id())).get().extracting(Poi::server).isEqualTo("smp");
        assertThat(smp.byId(spawn.id())).get().extracting(Poi::server).isEqualTo("smp");
    }

    @Test
    @DisplayName("a place on another server is not here: its world name is not this server's world")
    void otherServersPlaceIsNotHere() {
        ThisServer.set("arena");
        Poi there = warp("spawn").onServer("smp");
        assertThat(there.isHere()).isFalse();
        assertThat(there.location()).isEmpty();
        assertThat(there.isReachable()).isFalse();
        assertThat(warp("local").isHere()).as("blank is this server, as on 1.x").isTrue();
        assertThat(warp("local").onServer("arena").isHere()).isTrue();
    }

    @Test
    @DisplayName("on a server of its own everything is here, whatever it was stamped with")
    void standaloneIsAllHere() {
        ThisServer.set("");
        assertThat(warp("x").onServer("somewhere").isHere()).isTrue();
    }

    @Test
    @DisplayName("a place deleted on one server is gone on the other")
    void deletionTravels() {
        PoiStore smp = server("smp");
        PoiStore arena = server("arena");
        Poi home = Poi.builder("base", "world", 1, 2, 3).kind("home").owner(ALEX).build();
        smp.save(home);
        smp.flush();
        hub.drain();
        assertThat(arena.byId(home.id())).isPresent();

        arena.delete(home.id());
        arena.flush();
        hub.drain();
        assertThat(smp.byId(home.id())).isEmpty();
    }

    @Test
    @DisplayName("a change to a place travels, tags included")
    void changeTravels() {
        PoiStore smp = server("smp");
        PoiStore arena = server("arena");
        Poi home = Poi.builder("base", "world", 1, 2, 3).kind("home").owner(ALEX).tag("colour", "red").build();
        smp.save(home);
        smp.flush();
        hub.drain();
        arena.save(arena.byId(home.id()).orElseThrow().renamedTo("castle").withTag("colour", "blue"));
        arena.flush();
        hub.drain();
        Poi seen = smp.byId(home.id()).orElseThrow();
        assertThat(seen.name()).isEqualTo("castle");
        assertThat(seen.tag("colour")).contains("blue");
    }

    @Test
    @DisplayName("an unwritten change here survives hearing about the same place from elsewhere")
    void ownChangeWins() {
        PoiStore smp = server("smp");
        PoiStore arena = server("arena");
        Poi home = Poi.builder("base", "world", 1, 2, 3).owner(ALEX).build();
        smp.save(home);
        smp.flush();
        hub.drain();
        arena.save(arena.byId(home.id()).orElseThrow().renamedTo("arena's name"));
        arena.refresh(home.id());
        assertThat(arena.byId(home.id())).get().extracting(Poi::name).isEqualTo("arena's name");
    }
}
