package de.raindancer.core.data.inventory;

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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class SavedInventoriesTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000L);
    private Database database;
    private SavedInventories inventories;

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        inventories = new SavedInventories(database, clock::get);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static SavedInventories.Saved saved(String server, long at, byte first) {
        return new SavedInventories.Saved(server, ALEX, at, new byte[]{first, 2, 3}, new byte[]{9}, 30, 0.5f,
                18.5, 17, 4.0f, "effects: []\n");
    }

    @Test
    @DisplayName("each server keeps its own copy of a player's inventory, written over on every save")
    void perServer() {
        assertThat(inventories.save(saved("smp", 10, (byte) 1))).isTrue();
        assertThat(inventories.save(saved("arena", 20, (byte) 7))).isTrue();
        assertThat(inventories.save(saved("smp", 30, (byte) 4))).isTrue();

        assertThat(inventories.on("smp", ALEX)).get().satisfies(found -> {
            assertThat(found.contents()).containsExactly(4, 2, 3);
            assertThat(found.savedAt()).isEqualTo(30);
            assertThat(found.level()).isEqualTo(30);
            assertThat(found.health()).isEqualTo(18.5);
            assertThat(found.effects()).isEqualTo("effects: []\n");
        });
        assertThat(inventories.on("arena", ALEX)).get().extracting(SavedInventories.Saved::server).isEqualTo("arena");
        assertThat(inventories.everywhere(ALEX)).extracting(SavedInventories.Saved::server).containsExactly("smp", "arena");
        assertThat(inventories.latest(ALEX)).get().extracting(SavedInventories.Saved::server).isEqualTo("smp");
    }

    @Test
    @DisplayName("an older save arriving late does not replace a newer one")
    void olderDoesNotWin() {
        inventories.save(saved("smp", 50, (byte) 5));
        inventories.save(saved("smp", 40, (byte) 4));
        assertThat(inventories.on("smp", ALEX)).get().extracting(SavedInventories.Saved::savedAt).isEqualTo(50L);
    }

    @Test
    @DisplayName("somebody never saved anywhere has nothing")
    void nobody() {
        assertThat(inventories.latest(UUID.randomUUID())).isEmpty();
        assertThat(inventories.everywhere(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("no database: a save says no, a read says nothing, neither throws")
    void unreachable() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), CoreSchema.CORE);
        SavedInventories nowhere = new SavedInventories(broken, clock::get);
        assertThat(nowhere.save(saved("smp", 1, (byte) 1))).isFalse();
        assertThat(nowhere.latest(ALEX)).isEmpty();
        broken.close();
    }
}
