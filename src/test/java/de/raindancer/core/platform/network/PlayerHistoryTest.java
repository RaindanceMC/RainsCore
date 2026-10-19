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
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class PlayerHistoryTest {

    @TempDir
    Path folder;

    private Database database;

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    private void seen(UUID player, String name, String server, long at, boolean online) {
        database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO network_seen (player, name, name_lower, server, at, online) VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, player.toString());
                insert.setString(2, name);
                insert.setString(3, name.toLowerCase());
                insert.setString(4, server);
                insert.setLong(5, at);
                insert.setInt(6, online ? 1 : 0);
                insert.executeUpdate();
            }
        });
    }

    @Test
    @DisplayName("last seen and first seen, for anybody the network ever saw, by id or by name in any case")
    void readsWhatTheNetworkWrote() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        UUID alex = UUID.randomUUID();
        seen(alex, "Alex_99", "arena", 5_000, false);
        new FirstSeen(database, () -> 1_000L).arrived(alex, "smp", false);
        PlayerHistory history = new PlayerHistory(database);

        assertThat(history.lastSeen(alex)).contains(new PlayerHistory.Seen("Alex_99", "arena", 5_000, false));
        assertThat(history.firstSeen(alex)).contains(1_000L);
        assertThat(history.idOf("alex_99")).contains(alex);
        assertThat(history.idOf("ALEX_99")).contains(alex);

        UUID nobody = UUID.randomUUID();
        assertThat(history.lastSeen(nobody)).isEmpty();
        assertThat(history.firstSeen(nobody)).isEmpty();
        assertThat(history.idOf("nobody")).isEmpty();
        assertThat(history.idOf(null)).isEmpty();
    }
}
