package de.raindancer.core.moderation.audit;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Entries with extra fields, written in batches, alongside the pruning that deletes old ones. */
@org.junit.jupiter.api.Tag("database")
class AuditFieldsTest {

    @TempDir
    Path folder;
    private Database database;

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("every entry's fields are attached to that entry, batch after batch, across a prune")
    void fieldsAttach() {
        database = TestDatabases.open(folder.resolve("audit.db"), CoreSchema.AUDIT, () -> false);
        AtomicLong clock = new AtomicLong(1_000_000L);
        Audit audit = new Audit(database, clock::get);
        UUID who = UUID.randomUUID();
        for (int batch = 0; batch < 5; batch++) {
            for (int at = 0; at < 30; at++) {
                AuditEntry.Builder entry = AuditEntry.of("invsee", "changed").by(who, "Steve")
                        .with("slot", at);
                if (at % 3 == 0) {
                    entry.with("section", "STORAGE").with("was", "nothing").with("now", "dirt");
                }
                audit.record(entry);
            }
            assertThat(audit.flush()).isEqualTo(30);
            clock.addAndGet(Duration.ofDays(40).toMillis());
            audit.forgetOlderThan(Duration.ofDays(30));
        }
        assertThat(audit.droppedEntries()).isZero();
    }
}
