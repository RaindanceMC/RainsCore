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
class SharedPlayerDataTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path folder;

    private Database database;
    private SharedPlayerData shared;

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        shared = new SharedPlayerData(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("kept per player and namespace; an older save arriving late does not replace a newer one")
    void keptAndNewestWins() {
        assertThat(shared.save(ALEX, "rainscosmetics", new byte[]{1}, 100)).isTrue();
        shared.save(ALEX, "rainscosmetics", new byte[]{2}, 200);
        shared.save(ALEX, "rainscosmetics", new byte[]{9}, 150);
        shared.save(ALEX, "other", new byte[]{7}, 50);
        assertThat(shared.read(ALEX, "rainscosmetics")).get().satisfies(kept -> {
            assertThat(kept.data()).containsExactly(2);
            assertThat(kept.at()).isEqualTo(200);
        });
        assertThat(shared.read(ALEX, "other")).get().extracting(SharedPlayerData.Kept::at).isEqualTo(50L);
        assertThat(shared.read(UUID.randomUUID(), "rainscosmetics")).isEmpty();
    }
}
