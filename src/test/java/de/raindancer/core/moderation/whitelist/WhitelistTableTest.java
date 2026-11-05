package de.raindancer.core.moderation.whitelist;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The network's whitelist as rows: what the proxy reads at the door and every backend mirrors. */
@Tag("database")
class WhitelistTableTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CY = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path folder;

    private Database database;
    private WhitelistTable table;

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        table = new WhitelistTable(database, () -> 1_000L);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("add, look up, take off — and say whether anything changed, so an echo is not announced again")
    void addAndRemove() {
        assertThat(table.add(ADA, "Ada", "discord:1")).contains(true);
        assertThat(table.add(ADA, "Ada", "server:smp")).as("already on it").contains(false);
        assertThat(table.contains(ADA)).contains(true);
        assertThat(table.contains(BO)).contains(false);
        assertThat(table.all()).contains(Map.of(ADA, "Ada"));

        assertThat(table.remove(ADA)).contains(true);
        assertThat(table.remove(ADA)).contains(false);
        assertThat(table.contains(ADA)).contains(false);
    }

    @Test
    @DisplayName("the old network document comes in once for the whole network; each server's own list once for it")
    void bringingIn() {
        Map<UUID, String> document = Map.of(ADA, "Ada");
        table.bringIn("smp", document, List.of("smp", "hub"), Map.of(ADA, "Ada", BO, "Bo"));
        assertThat(table.all().orElseThrow()).as("smp already shared through the document: its list is in it")
                .containsOnlyKeys(ADA);

        table.remove(ADA);
        table.bringIn("hub", document, List.of("smp", "hub"), Map.of(ADA, "Ada"));
        assertThat(table.all().orElseThrow()).as("taken off since: the document does not bring them back").isEmpty();

        table.bringIn("ctf", document, List.of("smp", "hub"), Map.of(CY, "Cy"));
        table.bringIn("ctf", document, List.of("smp", "hub"), Map.of(BO, "Bo"));
        assertThat(table.all().orElseThrow()).as("a server new to sharing adds its own list, once").containsOnlyKeys(CY);
    }

    @Test
    @DisplayName("a database that cannot be read says so instead of answering 'nobody'")
    void unreadable() {
        database.close();
        assertThat(table.all()).isEmpty();
        assertThat(table.contains(ADA)).isEmpty();
        assertThat(table.add(ADA, "Ada", null)).isEmpty();
    }
}
