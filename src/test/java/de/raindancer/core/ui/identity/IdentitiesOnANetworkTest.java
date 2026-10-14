package de.raindancer.core.ui.identity;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
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
class IdentitiesOnANetworkTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    private Database database() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return database;
    }

    private Identities identities(String server) {
        Identities store = new Identities(database());
        store.load();
        store.connect(hub.bus(server));
        return store;
    }

    private Nicknames nicknames(String server) {
        Nicknames store = new Nicknames(database());
        store.load();
        store.connect(hub.bus(server));
        return store;
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("a prefix set on one server is the prefix on the other")
    void prefixTravels() {
        Identities smp = identities("smp");
        Identities arena = identities("arena");
        assertThat(smp.setPrefix(ALEX, "<gold>[VIP] ")).isTrue();
        smp.flush();
        hub.drain();
        assertThat(arena.prefix(ALEX)).contains("<gold>[VIP] ");
    }

    @Test
    @DisplayName("clearing it on one server clears it on the other")
    void clearTravels() {
        Identities smp = identities("smp");
        Identities arena = identities("arena");
        smp.setPrefix(ALEX, "<gold>[VIP] ");
        smp.flush();
        hub.drain();
        arena.clear(ALEX);
        arena.flush();
        hub.drain();
        assertThat(smp.prefix(ALEX)).isEmpty();
    }

    @Test
    @DisplayName("an unwritten change of this server's is not overwritten by what another server said")
    void ownChangeWins() {
        Identities smp = identities("smp");
        Identities arena = identities("arena");
        smp.setSuffix(ALEX, " <gray>*");
        smp.flush();
        arena.setSuffix(ALEX, " <red>!");
        hub.drain();
        arena.refresh(ALEX);
        assertThat(arena.suffix(ALEX)).contains(" <red>!");
    }

    @Test
    @DisplayName("a nickname given on one server is found by that name on the other")
    void nicknameTravels() {
        Nicknames smp = nicknames("smp");
        Nicknames arena = nicknames("arena");
        smp.remember(ALEX, "Lexi Star");
        smp.flush();
        hub.drain();
        assertThat(arena.ownerOf("lexi_star")).contains(ALEX);
        smp.clear(ALEX);
        smp.flush();
        hub.drain();
        assertThat(arena.ownerOf("lexi_star")).isEmpty();
    }
}
