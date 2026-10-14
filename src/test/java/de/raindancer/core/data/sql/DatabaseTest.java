package de.raindancer.core.data.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A SQLite database, as this project uses one.
 *
 * <h2>Why these tests run against a real database</h2>
 * Because a fake that answers the way we expect proves only that we are consistent with ourselves.
 * Everything worth testing here is one of SQLite's own opinions: that foreign keys are <em>off</em>
 * until asked for, that a second writer gets {@code SQLITE_BUSY} rather than waiting, that a
 * rollback really does undo, and that a schema version survives being closed and reopened. None of
 * that can be mocked into being true.
 *
 * <p>The driver is the same one Paper ships, at the same version, in test scope only — so the engine
 * these tests pass against is the engine the server runs.
 */
@DisplayName("a SQLite database")
class DatabaseTest {

    @TempDir
    Path folder;

    private final List<Database> opened = new ArrayList<>();

    private Database open(Schema schema) {
        return open("test.db", schema, () -> false);
    }

    private Database open(String name, Schema schema, java.util.function.BooleanSupplier onServer) {
        Database database = Database.open(folder.resolve(name), schema, onServer);
        opened.add(database);
        return database;
    }

    @AfterEach
    void closeEverything() {
        opened.forEach(Database::close);
        opened.clear();
    }

    private static final Schema ONE_TABLE = Schema.of(
            "CREATE TABLE thing (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");

    private static int countOf(Database database, String table) {
        return database.read(connection -> {
            try (var statement = connection.prepareStatement("SELECT count(*) FROM " + table);
                 var rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }).orElse(-1);
    }

    private static void insert(Database database, String name) {
        database.write(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO thing (name) VALUES (?)")) {
                statement.setString(1, name);
                statement.executeUpdate();
            }
        });
    }

    @Nested
    @DisplayName("opening one")
    class Opening {

        @Test
        @DisplayName("a database that does not exist yet is created, with its folder")
        void createsTheFile() {
            Database database = open("deeper/still/fresh.db", ONE_TABLE, () -> false);

            assertThat(database.file()).isRegularFile();
            assertThat(countOf(database, "thing")).isZero();
        }

        @Test
        @DisplayName("the schema is applied and remembered")
        void appliesTheSchema() {
            Database database = open(ONE_TABLE);

            assertThat(database.version())
                    .as("the version is how the next start knows what it has already done")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("opening it again does not apply the schema twice")
        void isIdempotent() {
            Database first = open(ONE_TABLE);
            insert(first, "kept");
            first.close();

            Database again = open(ONE_TABLE);

            assertThat(again.version()).isEqualTo(1);
            assertThat(countOf(again, "thing"))
                    .as("re-running a CREATE TABLE would either throw or, worse, empty it")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a schema that has grown applies only the steps that are new")
        void appliesOnlyNewSteps() {
            Database first = open(ONE_TABLE);
            insert(first, "from before the change");
            first.close();

            Database upgraded = open("test.db", Schema.of(
                    "CREATE TABLE thing (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
                    "ALTER TABLE thing ADD COLUMN note TEXT",
                    "CREATE TABLE other (id INTEGER PRIMARY KEY)"), () -> false);

            assertThat(upgraded.version()).isEqualTo(3);
            assertThat(countOf(upgraded, "thing"))
                    .as("an upgrade that loses the rows it was upgrading is not an upgrade")
                    .isEqualTo(1);
            assertThat(countOf(upgraded, "other")).isZero();
        }

        @Test
        @DisplayName("a step that fails leaves the version where it was, not halfway")
        void aFailedStepDoesNotCount() {
            Database database = open("broken.db", Schema.of(
                    "CREATE TABLE fine (id INTEGER PRIMARY KEY)",
                    "CREATE TABLE ohdear (this is not sql)"), () -> false);

            assertThat(database.version())
                    .as("claiming a version whose step never ran means the next start skips it "
                            + "for ever")
                    .isEqualTo(1);
            assertThat(database.isUsable())
                    .as("a database whose schema is incomplete must say so rather than be handed "
                            + "out and fail one query at a time")
                    .isFalse();
        }

        @Test
        @DisplayName("foreign keys are on, which they are not by default")
        void foreignKeysAreOn() {
            Database database = open("keys.db", Schema.of(
                    "CREATE TABLE parent (id INTEGER PRIMARY KEY)",
                    "CREATE TABLE child (id INTEGER PRIMARY KEY, "
                            + "parent INTEGER REFERENCES parent(id))"), () -> false);

            boolean refused = !database.write(connection -> {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO child (parent) VALUES (404)")) {
                    statement.executeUpdate();
                }
            });

            assertThat(refused)
                    .as("SQLite ignores foreign keys unless asked, which turns a declared "
                            + "relationship into a comment")
                    .isTrue();
        }

        @Test
        @DisplayName("it is in write-ahead mode, so a reader is never blocked by a writer")
        void usesWriteAheadLogging() {
            Database database = open(ONE_TABLE);

            assertThat(database.read(connection -> {
                try (var statement = connection.prepareStatement("PRAGMA journal_mode");
                     var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : "";
                }
            })).contains("wal");
        }
    }

    @Nested
    @DisplayName("reading and writing")
    class Working {

        @Test
        @DisplayName("what was written comes back")
        void roundTrip() {
            Database database = open(ONE_TABLE);
            insert(database, "a thing");

            assertThat(database.read(connection -> {
                try (var statement = connection.prepareStatement("SELECT name FROM thing");
                     var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            })).contains("a thing");
        }

        @Test
        @DisplayName("a write that throws halfway leaves nothing behind")
        void writesAreAllOrNothing() {
            Database database = open(ONE_TABLE);
            insert(database, "first");

            boolean written = database.write(connection -> {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO thing (name) VALUES ('second')")) {
                    statement.executeUpdate();
                }
                throw new IllegalStateException("changed my mind");
            });

            assertThat(written).isFalse();
            assertThat(countOf(database, "thing"))
                    .as("half a write is how a save leaves a player owning an item that no longer "
                            + "has an owner")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a write that breaks a constraint is refused rather than thrown at the caller")
        void badWritesAnswerFalse() {
            Database database = open(ONE_TABLE);

            assertThat(database.write(connection -> {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO thing (name) VALUES (NULL)")) {
                    statement.executeUpdate();
                }
            })).isFalse();
            assertThat(countOf(database, "thing")).isZero();
        }

        @Test
        @DisplayName("a query that fails is empty rather than an exception at the caller")
        void badReadsAnswerEmpty() {
            Database database = open(ONE_TABLE);

            assertThat(database.read(connection -> {
                try (var statement = connection.prepareStatement("SELECT nope FROM nowhere");
                     var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            })).isEmpty();
        }

        @Test
        @DisplayName("a query that legitimately finds nothing is also empty, and says nothing broke")
        void nothingFoundIsNotAFailure() {
            Database database = open(ONE_TABLE);

            assertThat(database.read(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT name FROM thing WHERE id = 999");
                     var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            })).isEmpty();
            assertThat(database.isUsable()).isTrue();
        }

        @Test
        @DisplayName("nothing is written to a database that failed to open")
        void unusableDatabaseRefusesWork() {
            Database database = open("bad.db", Schema.of("CREATE TABLE not valid sql at all"),
                    () -> false);

            assertThat(database.isUsable()).isFalse();
            assertThat(database.write(connection -> { })).isFalse();
            assertThat(database.read(connection -> "anything")).isEmpty();
        }
    }

    /**
     * The two ways a transaction can silently stop being one. Both were found by a second review
     * rather than by these tests, which is the argument for having both.
     */
    @Nested
    @DisplayName("a transaction stays a transaction")
    class TransactionIntegrity {

        @Test
        @DisplayName("a write inside a write is refused rather than committing the outer one early")
        void refusesNestedWrites() {
            Database database = open(ONE_TABLE);
            AtomicBoolean innerRan = new AtomicBoolean();

            boolean outer = database.write(connection -> {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO thing (name) VALUES ('outer')")) {
                    statement.executeUpdate();
                }
                innerRan.set(database.write(inner -> {
                    try (var statement = inner.prepareStatement(
                            "INSERT INTO thing (name) VALUES ('inner')")) {
                        statement.executeUpdate();
                    }
                }));
                throw new IllegalStateException("the outer write fails after the inner one");
            });

            assertThat(innerRan.get())
                    .as("the lock is reentrant, so a nested write is let through and commits the "
                            + "outer write's rows halfway — after which the outer failure cannot "
                            + "be rolled back")
                    .isFalse();
            assertThat(outer).isFalse();
            assertThat(countOf(database, "thing"))
                    .as("neither row belongs in the database: the outer write failed")
                    .isZero();
        }

        @Test
        @DisplayName("an Error in a write still rolls back, and does not leak into the next write")
        void rollsBackOnError() {
            Database database = open(ONE_TABLE);

            try {
                database.write(connection -> {
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO thing (name) VALUES ('never meant to be here')")) {
                        statement.executeUpdate();
                    }
                    // Not a RuntimeException. An OutOfMemoryError or a StackOverflowError in a
                    // caller's work arrives exactly like this, and a catch of RuntimeException does
                    // not see it.
                    throw new AssertionError("something no catch of RuntimeException would see");
                });
            } catch (AssertionError expected) {
                // Passed on to the caller, which is right: an Error is not this class's to swallow.
            }

            assertThat(countOf(database, "thing"))
                    .as("without a rollback the transaction stays open and the next write commits "
                            + "this row as part of its own")
                    .isZero();

            insert(database, "the next write, which is innocent");

            assertThat(database.read(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT group_concat(name) FROM thing");
                     var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : "";
                }
            })).contains("the next write, which is innocent");
        }

        @Test
        @DisplayName("a write that arrives while the database is closing is refused, not a crash")
        void writeRacingClose() throws InterruptedException {
            for (int attempt = 0; attempt < 50; attempt++) {
                Database database = Database.open(folder.resolve("race" + attempt + ".db"),
                        ONE_TABLE, () -> false);
                CountDownLatch go = new CountDownLatch(1);
                AtomicBoolean threw = new AtomicBoolean();

                Thread writing = new Thread(() -> {
                    await(go);
                    try {
                        database.write(connection -> {
                            try (var statement = connection.prepareStatement(
                                    "INSERT INTO thing (name) VALUES ('racing')")) {
                                statement.executeUpdate();
                            }
                        });
                    } catch (RuntimeException crashed) {
                        threw.set(true);
                    }
                });
                Thread closing = new Thread(() -> {
                    await(go);
                    database.close();
                });
                writing.start();
                closing.start();
                go.countDown();
                writing.join();
                closing.join();

                assertThat(threw.get())
                        .as("close() clears the connection, so a write that got past the usable "
                                + "check has to find that out rather than dereference it")
                        .isFalse();
            }
        }

        private static void await(CountDownLatch gate) {
            try {
                gate.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Test
        @DisplayName("closing waits for a read genuinely in progress, so the checkpoint still folds in")
        void readRacingClose() throws InterruptedException {
            // The bug this proves fixed: a read caught mid-flight used to leave its snapshot open
            // while close() checkpointed straight past it, which is exactly what a -wal file left
            // beside the real database on a live server looked like — reported as "could not fold the
            // write-ahead log back in" on every restart that happened to race one.
            Database database = open(ONE_TABLE);
            insert(database, "already here");
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch readerHasAConnection = new CountDownLatch(1);

            Thread reading = new Thread(() -> database.read(connection -> {
                readerHasAConnection.countDown();
                await(go);
                try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM thing");
                     var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            }));
            reading.start();
            readerHasAConnection.await();

            Thread closing = new Thread(database::close);
            closing.start();
            // Long enough that close(), started concurrently, is genuinely somewhere inside
            // awaitReadsFinished by the time the read is let through rather than having already
            // given up and moved on to the checkpoint.
            Thread.sleep(20);
            go.countDown();

            reading.join();
            closing.join();

            assertThat(Files.exists(folder.resolve("test.db-wal")))
                    .as("a read let through mid-close must still be checkpointed away, not left "
                            + "as evidence the wait did nothing")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("more than one caller at a time")
    class Concurrency {

        @Test
        @DisplayName("many threads writing at once all get their rows in")
        void concurrentWrites() throws InterruptedException {
            Database database = open(ONE_TABLE);
            int writers = 24;
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger written = new AtomicInteger();

            try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
                for (int writer = 0; writer < writers; writer++) {
                    int mine = writer;
                    pool.execute(() -> {
                        try {
                            go.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (database.write(connection -> {
                            try (var statement = connection.prepareStatement(
                                    "INSERT INTO thing (name) VALUES (?)")) {
                                statement.setString(1, "writer " + mine);
                                statement.executeUpdate();
                            }
                        })) {
                            written.incrementAndGet();
                        }
                    });
                }
                go.countDown();
            }

            assertThat(written.get())
                    .as("SQLite allows one writer at a time and answers the second with BUSY. "
                            + "Writes have to be queued or timed out into waiting, or a busy "
                            + "moment silently drops somebody's data")
                    .isEqualTo(writers);
            assertThat(countOf(database, "thing")).isEqualTo(writers);
        }

        @Test
        @DisplayName("reading while another thread writes is not blocked and never sees half a write")
        void readsAreNotBlockedByWrites() throws InterruptedException {
            Database database = open(ONE_TABLE);
            AtomicBoolean readSomethingOdd = new AtomicBoolean();
            CountDownLatch done = new CountDownLatch(1);

            Thread writing = new Thread(() -> {
                for (int at = 0; at < 200; at++) {
                    int mine = at;
                    database.write(connection -> {
                        try (var statement = connection.prepareStatement(
                                "INSERT INTO thing (name) VALUES (?)")) {
                            statement.setString(1, "row " + mine);
                            statement.executeUpdate();
                        }
                    });
                }
                done.countDown();
            });
            Thread reading = new Thread(() -> {
                while (done.getCount() > 0) {
                    if (countOf(database, "thing") < 0) {
                        readSomethingOdd.set(true);
                    }
                }
            });
            writing.start();
            reading.start();
            writing.join();
            reading.join();

            assertThat(readSomethingOdd.get())
                    .as("a read that fails while somebody writes is a read that will fail on a "
                            + "busy server, which is the only time it matters")
                    .isFalse();
            assertThat(countOf(database, "thing")).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("what it refuses to do on a server thread")
    class OffTheServerThread {

        @Test
        @DisplayName("work on a server thread is complained about, loudly and once per place")
        void complainsOnTheServerThread() {
            Database database = open("onthread.db", ONE_TABLE, () -> true);

            insert(database, "written anyway");

            assertThat(database.workOnServerThread())
                    .as("a query on the thread running the world is a stall nobody sees in "
                            + "testing and everybody feels on a full server; it has to be "
                            + "reported, with a stack, or it is never found")
                    .isEqualTo(1);
            assertThat(countOf(database, "thing"))
                    .as("complained about, not refused: losing somebody's data is worse than a "
                            + "stall, and the fix belongs in the caller rather than here")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("work off the server thread is not complained about")
        void quietOffTheServerThread() {
            Database database = open(ONE_TABLE);
            insert(database, "fine");

            assertThat(database.workOnServerThread()).isZero();
        }
    }

    @Nested
    @DisplayName("closing it")
    class Closing {

        @Test
        @DisplayName("everything written before closing is still there afterwards")
        void survivesClosing() throws Exception {
            Database database = open(ONE_TABLE);
            insert(database, "durable");
            database.close();

            assertThat(countOf(open(ONE_TABLE), "thing")).isEqualTo(1);
            assertThat(Files.size(folder.resolve("test.db"))).isPositive();
        }

        @Test
        @DisplayName("closing twice is not an error")
        void closingTwice() {
            Database database = open(ONE_TABLE);
            database.close();

            database.close();

            assertThat(database.isUsable()).isFalse();
        }

        @Test
        @DisplayName("work after closing is refused rather than throwing")
        void refusesWorkAfterClosing() {
            Database database = open(ONE_TABLE);
            database.close();

            assertThat(database.write(connection -> { })).isFalse();
            assertThat(database.read(connection -> "no")).isEmpty();
        }

        @Test
        @DisplayName("the write-ahead log is folded back in, so the file is the whole database")
        void checkpointsOnClose() throws Exception {
            Database database = open(ONE_TABLE);
            for (int at = 0; at < 50; at++) {
                insert(database, "row " + at);
            }
            database.close();

            assertThat(Files.exists(folder.resolve("test.db-wal")))
                    .as("a leftover -wal file is a database somebody will copy without it and "
                            + "believe they have a backup")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("several databases at once")
    class Several {

        @Test
        @DisplayName("two databases are two databases")
        void areIndependent() {
            Database one = open("one.db", ONE_TABLE, () -> false);
            Database other = open("other.db", ONE_TABLE, () -> false);

            insert(one, "only in one");

            assertThat(countOf(one, "thing")).isEqualTo(1);
            assertThat(countOf(other, "thing")).isZero();
        }
    }

    /** Errors are answers here, not exceptions, so a caller cannot forget to handle one. */
    @Nested
    @DisplayName("misuse")
    class Misuse {

        @Test
        @DisplayName("no file, no schema, no work — all answered rather than thrown")
        void nulls() {
            Database database = open(ONE_TABLE);

            assertThat(database.write(null)).isFalse();
            assertThat(database.read(null)).isEmpty();
            assertThat(Schema.of().steps()).isEmpty();
        }

        @Test
        @DisplayName("a query answering null is empty, not a broken Optional")
        void queryReturningNull() {
            Database database = open(ONE_TABLE);

            assertThat(database.read(connection -> null)).isEmpty();
        }

        @Test
        @DisplayName("a schema is what it was given, in order")
        void schemaKeepsItsOrder() {
            Schema schema = Schema.of("first", "second", "third");

            assertThat(schema.steps()).containsExactly("first", "second", "third");
            assertThat(schema.size()).isEqualTo(3);
        }
    }

    /** The one thing a store built on this needs and SQLite will not give: a real transaction. */
    @Nested
    @DisplayName("transactions")
    class Transactions {

        @Test
        @DisplayName("several statements in one write either all happen or none do")
        void oneWriteIsOneTransaction() throws SQLException {
            Database database = open("tx.db", Schema.of(
                    "CREATE TABLE account (id INTEGER PRIMARY KEY, balance INTEGER NOT NULL "
                            + "CHECK (balance >= 0))"), () -> false);
            database.write(connection -> {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO account (id, balance) VALUES (1, 100), (2, 0)")) {
                    statement.executeUpdate();
                }
            });

            boolean moved = database.write(connection -> {
                try (var take = connection.prepareStatement(
                        "UPDATE account SET balance = balance - 150 WHERE id = 1")) {
                    take.executeUpdate();
                }
                try (var give = connection.prepareStatement(
                        "UPDATE account SET balance = balance + 150 WHERE id = 2")) {
                    give.executeUpdate();
                }
            });

            assertThat(moved).isFalse();
            assertThat(database.read(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT balance FROM account WHERE id = 2");
                     var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getInt(1) : -1;
                }
            })).as("the half that succeeded must not stand on its own").contains(0);
        }
    }

    @Test
    @DisplayName("a database whose schema was already up to date can write after another connection wrote")
    void upToDateSchemaLeavesNoSnapshotOpen() {
        Schema schema = Schema.of("CREATE TABLE t (n INTEGER)");
        try (Database first = open(schema); Database second = open(schema)) {
            assertThat(first.write(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("INSERT INTO t VALUES (1)");
                }
            })).isTrue();
            // The second found the schema current and only read its version. That read must not have left a
            // snapshot open on its writer: SQLite refuses a write from a snapshot older than the last commit.
            assertThat(second.write(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("INSERT INTO t VALUES (2)");
                }
            })).isTrue();
        }
    }
}
