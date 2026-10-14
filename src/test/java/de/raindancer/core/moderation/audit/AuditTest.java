package de.raindancer.core.moderation.audit;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of what was done, and who did it.
 *
 * <h2>What these tests are protecting</h2>
 * The two questions an audit log exists to answer — what has this moderator been doing, and what has
 * been done to this player — and the property that makes it usable at all: that recording something
 * never touches the disk on the thread it was called from.
 *
 * <p>Against a real database, because the searching is SQL and a fake would only prove that the
 * queries agree with a fake.
 */
@org.junit.jupiter.api.Tag("database")
@DisplayName("the audit journal")
class AuditTest {

    @TempDir
    Path folder;

    private final AtomicLong now = new AtomicLong(Instant.parse("2026-08-03T12:00:00Z").toEpochMilli());
    private Database database;
    private Audit audit;

    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("audit.db"), CoreSchema.AUDIT, () -> false);
        audit = new Audit(database, now::get);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void minutesPass(long minutes) {
        now.addAndGet(Duration.ofMinutes(minutes).toMillis());
    }

    /** Records and writes in one go, for a test that is not about the queue. */
    private void happened(AuditEntry.Builder entry) {
        audit.record(entry);
        audit.flush();
    }

    @Nested
    @DisplayName("recording something")
    class Recording {

        @Test
        @DisplayName("recording does not touch the database, so it cannot stall a region")
        void recordingIsFree() {
            audit.record(AuditEntry.of("invsee", "opened").by(steve, "Steve"));

            assertThat(audit.waiting()).isEqualTo(1);
            assertThat(audit.count())
                    .as("if recording wrote, it would be an fsync on whatever thread the click "
                            + "handler was on")
                    .isZero();
        }

        @Test
        @DisplayName("flushing writes what has gathered")
        void flushWrites() {
            audit.record(AuditEntry.of("invsee", "opened").by(steve, "Steve"));
            audit.record(AuditEntry.of("punishment", "banned").by(steve, "Steve"));

            assertThat(audit.flush()).isEqualTo(2);
            assertThat(audit.waiting()).isZero();
            assertThat(audit.count()).isEqualTo(2);
            assertThat(audit.writtenEntries()).isEqualTo(2);
        }

        @Test
        @DisplayName("flushing with nothing waiting does nothing at all")
        void flushingNothing() {
            assertThat(audit.flush()).isZero();
        }

        @Test
        @DisplayName("everything about an entry survives being written and read back")
        void roundTrip() {
            happened(AuditEntry.of("invsee", "took an item")
                    .by(steve, "Steve")
                    .to(alex, "Alex")
                    .saying("a diamond sword out of their backpack")
                    .in("world")
                    .with("section", "STORAGE")
                    .with("slot", 4));

            AuditEntry back = audit.search(AuditSearch.everything()).get(0);
            assertThat(back.feature()).isEqualTo("invsee");
            assertThat(back.action()).isEqualTo("took an item");
            assertThat(back.actor()).isEqualTo(steve);
            assertThat(back.actorName()).isEqualTo("Steve");
            assertThat(back.subject()).isEqualTo(alex);
            assertThat(back.subjectName()).isEqualTo("Alex");
            assertThat(back.detail()).isEqualTo("a diamond sword out of their backpack");
            assertThat(back.world()).isEqualTo("world");
            assertThat(back.field("section")).contains("STORAGE");
            assertThat(back.field("slot")).contains("4");
            assertThat(back.at().toEpochMilli()).isEqualTo(now.get());
        }

        @Test
        @DisplayName("an entry with nobody doing it is the server, and is still recorded")
        void theServerItself() {
            happened(AuditEntry.of("punishment", "expired").to(alex, "Alex"));

            AuditEntry back = audit.search(AuditSearch.everything()).get(0);
            assertThat(back.actor()).isNull();
            assertThat(back.actorDescription()).isEqualTo("the server");
            assertThat(back.saying()).isEqualTo("the server expired Alex");
        }

        @Test
        @DisplayName("nothing is recorded for nothing")
        void nulls() {
            assertThat(audit.record((AuditEntry) null)).isFalse();
            assertThat(audit.record((AuditEntry.Builder) null)).isFalse();
            assertThat(audit.waiting()).isZero();
        }

        @Test
        @DisplayName("an entry with no feature or action still says something rather than nothing")
        void blanksAreFilledIn() {
            happened(AuditEntry.of("  ", null).by(steve, "Steve"));

            AuditEntry back = audit.search(AuditSearch.everything()).get(0);
            assertThat(back.feature()).isEqualTo("unknown");
            assertThat(back.action()).isEqualTo("did something");
        }
    }

    @Nested
    @DisplayName("searching it")
    class Searching {

        @BeforeEach
        void someHistory() {
            audit.record(AuditEntry.of("invsee", "opened").by(steve, "Steve").to(alex, "Alex"));
            minutesPass(1);
            audit.record(AuditEntry.of("invsee", "took an item").by(steve, "Steve").to(alex, "Alex"));
            minutesPass(1);
            audit.record(AuditEntry.of("punishment", "banned").by(other, "Notch").to(alex, "Alex"));
            minutesPass(1);
            audit.record(AuditEntry.of("vanish", "vanished").by(steve, "Steve"));
            audit.flush();
        }

        @Test
        @DisplayName("everything, newest first")
        void everything() {
            List<AuditEntry> found = audit.search(AuditSearch.everything());

            assertThat(found).hasSize(4);
            assertThat(found.get(0).action())
                    .as("the first thing anybody wants is what just happened")
                    .isEqualTo("vanished");
            assertThat(found.get(3).action()).isEqualTo("opened");
        }

        @Test
        @DisplayName("by moderator — the question this exists for")
        void byActor() {
            assertThat(audit.search(AuditSearch.by(steve)))
                    .extracting(AuditEntry::action)
                    .containsExactly("vanished", "took an item", "opened");
            assertThat(audit.search(AuditSearch.by(other)))
                    .extracting(AuditEntry::action)
                    .containsExactly("banned");
        }

        @Test
        @DisplayName("by feature — the other question this exists for")
        void byFeature() {
            assertThat(audit.search(AuditSearch.in("invsee")))
                    .extracting(AuditEntry::action)
                    .containsExactly("took an item", "opened");
            assertThat(audit.search(AuditSearch.in("vanish"))).hasSize(1);
            assertThat(audit.search(AuditSearch.in("nothing-like-this"))).isEmpty();
        }

        @Test
        @DisplayName("by who it was done to")
        void bySubject() {
            assertThat(audit.search(AuditSearch.to(alex))).hasSize(3);
            assertThat(audit.search(AuditSearch.to(steve)))
                    .as("Steve did three things and had none done to him")
                    .isEmpty();
        }

        @Test
        @DisplayName("the parts narrow together, which is the whole point of one object")
        void combined() {
            assertThat(audit.search(AuditSearch.by(steve).withFeature("invsee")))
                    .extracting(AuditEntry::action)
                    .containsExactly("took an item", "opened");
            assertThat(audit.search(AuditSearch.by(steve).withFeature("punishment"))).isEmpty();
            assertThat(audit.search(AuditSearch.by(steve).withAction("vanished"))).hasSize(1);
        }

        @Test
        @DisplayName("by when")
        void byTime() {
            Instant start = Instant.ofEpochMilli(now.get()).minus(Duration.ofMinutes(1));

            assertThat(audit.search(AuditSearch.everything().since(start)))
                    .as("only the last minute's worth")
                    .hasSize(2);
            assertThat(audit.search(AuditSearch.everything().until(start)))
                    .as("both ends are inclusive, so the entry exactly on the boundary is in both "
                            + "halves rather than in neither — which is the way round that cannot "
                            + "lose an entry from a report")
                    .hasSize(3);
        }

        @Test
        @DisplayName("how many come back is capped, whatever was asked for")
        void limits() {
            assertThat(audit.search(AuditSearch.everything().limit(2))).hasSize(2);
            assertThat(AuditSearch.everything().limit(0).limit())
                    .as("nobody meant zero")
                    .isEqualTo(AuditSearch.DEFAULT_LIMIT);
            assertThat(AuditSearch.everything().limit(999_999).limit())
                    .as("an unbounded read of a year of history is how a screen holds a million "
                            + "rows to show twenty")
                    .isEqualTo(AuditSearch.MAX_LIMIT);
        }

        @Test
        @DisplayName("a name with a quote in it is a name, not an injection")
        void quotesAreSafe() {
            happened(AuditEntry.of("chat", "said").by(steve, "Bobby'); DROP TABLE entry;--"));

            assertThat(audit.count())
                    .as("if that had been concatenated into the SQL the table would be gone")
                    .isEqualTo(5);
            assertThat(audit.search(AuditSearch.in("chat")).get(0).actorName())
                    .isEqualTo("Bobby'); DROP TABLE entry;--");
        }

        @Test
        @DisplayName("one entry by its id, with its fields")
        void byId() {
            happened(AuditEntry.of("invsee", "put an item back").with("slot", 7));
            AuditEntry found = audit.search(AuditSearch.in("invsee")).get(0);

            assertThat(audit.byId(found.id()))
                    .isPresent()
                    .get()
                    .extracting(entry -> entry.field("slot"))
                    .isEqualTo(java.util.Optional.of("7"));
            assertThat(audit.byId(999_999L)).isEmpty();
        }

        @Test
        @DisplayName("the extra fields of a whole page are read in one query, not one each")
        void fieldsComeBackForEveryEntry() {
            for (int at = 0; at < 20; at++) {
                audit.record(AuditEntry.of("invsee", "changed a slot").with("slot", at));
            }
            audit.flush();

            List<AuditEntry> found = audit.search(AuditSearch.in("invsee"));

            assertThat(found).hasSize(22);
            assertThat(found.stream().filter(entry -> entry.field("slot").isPresent()).count())
                    .as("a page whose fields are fetched one entry at a time is a hundred queries "
                            + "to show one screen")
                    .isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("forgetting")
    class Forgetting {

        @Test
        @DisplayName("entries older than the retention period go, and their fields with them")
        void forgetsOldEntries() {
            happened(AuditEntry.of("invsee", "ancient").with("note", "from long ago"));
            minutesPass(60 * 24 * 40);
            happened(AuditEntry.of("invsee", "recent"));

            assertThat(audit.forgetOlderThan(Duration.ofDays(30))).isEqualTo(1);
            assertThat(audit.search(AuditSearch.everything()))
                    .extracting(AuditEntry::action)
                    .containsExactly("recent");
            assertThat(fieldRowCount())
                    .as("a field row whose entry is gone is a row nothing points at — which is "
                            + "what ON DELETE CASCADE and foreign_keys=ON are for")
                    .isZero();
        }

        @Test
        @DisplayName("nothing is forgotten for a nonsensical age")
        void refusesSillyAges() {
            happened(AuditEntry.of("invsee", "kept"));

            assertThat(audit.forgetOlderThan(null)).isZero();
            assertThat(audit.forgetOlderThan(Duration.ZERO)).isZero();
            assertThat(audit.forgetOlderThan(Duration.ofDays(-1))).isZero();
            assertThat(audit.count()).isEqualTo(1);
        }

        private long fieldRowCount() {
            return database.read(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT count(*) FROM entry_field");
                     var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : -1L;
                }
            }).orElse(-1L);
        }
    }

    @Nested
    @DisplayName("when things go wrong")
    class Trouble {

        @Test
        @DisplayName("a database that will not open loses the entries and says so, rather than growing")
        void unusableDatabase() {
            Database broken = TestDatabases.open(folder.resolve("broken.db"),
                    de.raindancer.core.data.sql.Schema.of("CREATE TABLE nope this is not sql"),
                    () -> false);
            Audit onBroken = new Audit(broken, now::get);

            onBroken.record(AuditEntry.of("invsee", "opened"));

            assertThat(onBroken.flush()).isZero();
            assertThat(onBroken.search(AuditSearch.everything()))
                    .as("an empty list rather than an exception: a broken audit log must not take "
                            + "the screen looking at it down with it")
                    .isEmpty();
            assertThat(onBroken.count()).isZero();
            broken.close();
        }

        @Test
        @DisplayName("a batch whose write fails is dropped rather than retried for ever")
        void failedBatchIsNotRequeued() {
            // A write that genuinely fails, rather than a database that refuses up front: the
            // table the insert names is gone, so the batch is drained and then cannot be written.
            // That is the shape of the real failure — a full disk, a corrupt file — and the one
            // where re-queueing would be a loop.
            database.write(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("DROP TABLE entry_field");
                    statement.executeUpdate("DROP TABLE entry");
                }
            });
            audit.record(AuditEntry.of("invsee", "opened"));

            assertThat(audit.flush()).isZero();
            assertThat(audit.waiting())
                    .as("re-queueing a batch that will fail again grows the queue until the server "
                            + "runs out of memory over an audit log")
                    .isZero();
            assertThat(audit.droppedEntries())
                    .as("dropped silently is how nobody finds out the audit log stopped working")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("entries waiting for a database that never opened are kept, not thrown away")
        void keepsEntriesWhileUnusable() {
            database.close();
            audit.record(AuditEntry.of("invsee", "opened"));

            assertThat(audit.flush()).isZero();
            assertThat(audit.waiting())
                    .as("a database that is not ready is different from a write that failed: "
                            + "draining the queue into nothing throws away entries that were "
                            + "never given a chance, and the bounded queue already stops this "
                            + "from growing without limit")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("under load")
    class Concurrency {

        @Test
        @DisplayName("entries recorded from many threads at once all arrive")
        void concurrentRecording() throws InterruptedException {
            int recorders = 16;
            int each = 50;
            CountDownLatch go = new CountDownLatch(1);

            try (ExecutorService pool = Executors.newFixedThreadPool(recorders)) {
                for (int recorder = 0; recorder < recorders; recorder++) {
                    int mine = recorder;
                    pool.execute(() -> {
                        try {
                            go.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int at = 0; at < each; at++) {
                            audit.record(AuditEntry.of("invsee", "click")
                                    .by(steve, "Steve").with("who", mine).with("n", at));
                        }
                    });
                }
                go.countDown();
            }

            assertThat(audit.waiting()).isEqualTo(recorders * each);
            audit.flush();
            assertThat(audit.count())
                    .as("a queue that loses entries under load loses them exactly when the log "
                            + "matters most")
                    .isEqualTo((long) recorders * each);
        }

        @Test
        @DisplayName("flushing while entries keep arriving writes a bounded batch and loses none")
        void flushWhileRecording() throws InterruptedException {
            Thread recording = new Thread(() -> {
                for (int at = 0; at < 500; at++) {
                    audit.record(AuditEntry.of("invsee", "click").with("n", at));
                }
            });
            recording.start();
            int firstBatch = audit.flush();
            recording.join();
            int secondBatch = audit.flush();

            assertThat(firstBatch + secondBatch)
                    .as("everything recorded is eventually written, whichever side of a flush it "
                            + "arrived on")
                    .isEqualTo(500);
            assertThat(audit.count()).isEqualTo(500);
        }
    }
}
