package de.raindancer.core.data.sql;

import de.raindancer.core.testkit.TestPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresDatabaseTest {

    private static final Schema TWO_STEPS = Schema.of(
            "CREATE TABLE item (id INTEGER PRIMARY KEY, name TEXT NOT NULL, weight REAL, data BLOB)",
            "CREATE INDEX item_by_name ON item (name)");

    private static PostgresDatabase open(String namespace, Schema schema) {
        return PostgresDatabase.open(TestPostgres.pool(), namespace, schema, () -> false, "test");
    }

    private static int count(Database database, String table) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                rows.next();
                return rows.getInt(1);
            }
        }).orElse(-1);
    }

    private static Optional<Integer> recordedVersion(String namespace) throws SQLException {
        try (Connection connection = TestPostgres.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT version FROM public.rains_schema WHERE namespace = ?")) {
            statement.setString(1, namespace);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getInt(1)) : Optional.empty();
            }
        }
    }

    @Test
    @DisplayName("the schema is built in its own namespace, translated, and its version recorded centrally")
    void builtAndRecorded() throws SQLException {
        String namespace = TestPostgres.freshNamespace("built");
        try (PostgresDatabase database = open(namespace, TWO_STEPS)) {
            assertThat(database.isUsable()).isTrue();
            assertThat(database.version()).isEqualTo(2);
            assertThat(database.dialect()).isEqualTo(Dialect.POSTGRESQL);
            assertThat(database.file()).isNull();
            assertThat(database.describe()).isEqualTo("postgresql:" + namespace);
            assertThat(database.write(connection -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO item (name, weight, data) VALUES (?, ?, ?)")) {
                    insert.setString(1, "sword");
                    insert.setDouble(2, 1.5);
                    insert.setBytes(3, new byte[]{1, 2, 3});
                    insert.executeUpdate();
                    insert.setString(1, "shield");
                    insert.executeUpdate();
                }
            })).isTrue();
            assertThat(count(database, "item")).isEqualTo(2);
            // INTEGER PRIMARY KEY hands out ids by itself, as it did on SQLite.
            assertThat(database.read(connection -> {
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT min(id), max(id) FROM item")) {
                    rows.next();
                    return rows.getLong(2) - rows.getLong(1);
                }
            })).contains(1L);
        }
        assertThat(recordedVersion(namespace)).contains(2);
    }

    @Test
    @DisplayName("opened again, nothing is applied twice; a step added since is applied alone")
    void upgrades() throws SQLException {
        String namespace = TestPostgres.freshNamespace("upgrade");
        try (PostgresDatabase first = open(namespace, TWO_STEPS)) {
            first.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO item (name) VALUES ('kept')");
                }
            });
        }
        Schema three = Schema.of(TWO_STEPS.steps().get(0), TWO_STEPS.steps().get(1),
                "ALTER TABLE item ADD COLUMN ranked INTEGER NOT NULL DEFAULT 1");
        try (PostgresDatabase again = open(namespace, three)) {
            assertThat(again.isUsable()).isTrue();
            assertThat(again.version()).isEqualTo(3);
            assertThat(count(again, "item")).isEqualTo(1);
        }
        assertThat(recordedVersion(namespace)).contains(3);
    }

    @Test
    @DisplayName("a failing step leaves the namespace as it was — Postgres DDL is transactional — and unusable")
    void failingStep() throws SQLException {
        String namespace = TestPostgres.freshNamespace("failing");
        Schema broken = Schema.of(
                "CREATE TABLE fine (n INTEGER)",
                "CREATE TABLE broken (n NOT_A_TYPE)");
        try (PostgresDatabase database = open(namespace, broken)) {
            assertThat(database.isUsable()).isFalse();
            assertThat(database.write(connection -> { })).isFalse();
            assertThat(database.read(connection -> 1)).isEmpty();
        }
        assertThat(recordedVersion(namespace)).isEmpty();
        try (Connection connection = TestPostgres.connect(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
                     + namespace + "'")) {
            rows.next();
            assertThat(rows.getInt(1)).as("the first step was rolled back with the second").isZero();
        }
    }

    @Test
    @DisplayName("several servers starting at once build the schema exactly once")
    void concurrentStart() throws Exception {
        String namespace = TestPostgres.freshNamespace("race");
        Schema counted = Schema.of(
                "CREATE TABLE built (n INTEGER)",
                "INSERT INTO built (n) VALUES (1)");
        ExecutorService servers = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PostgresDatabase>> opened = new ArrayList<>();
        for (int at = 0; at < 6; at++) {
            // Each its own pool, as each server of a network has.
            opened.add(servers.submit(() -> {
                go.await();
                PostgresPool own = PostgresPool.open(TestPostgres.require().withPoolSize(2));
                return PostgresDatabase.open(own, namespace, counted, () -> false, "server");
            }));
        }
        go.countDown();
        List<PostgresDatabase> databases = new ArrayList<>();
        for (Future<PostgresDatabase> each : opened) {
            databases.add(each.get(60, TimeUnit.SECONDS));
        }
        servers.shutdownNow();
        for (PostgresDatabase database : databases) {
            assertThat(database.isUsable()).isTrue();
            assertThat(database.version()).isEqualTo(2);
        }
        assertThat(count(databases.getFirst(), "built")).as("the insert step ran once").isEqualTo(1);
        databases.forEach(PostgresDatabase::close);
    }

    @Test
    @DisplayName("namespaces do not see each other's tables, though both are called the same")
    void isolated() {
        try (PostgresDatabase left = open(TestPostgres.freshNamespace("left"), TWO_STEPS);
             PostgresDatabase right = open(TestPostgres.freshNamespace("right"), TWO_STEPS)) {
            left.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO item (name) VALUES ('only left')");
                }
            });
            assertThat(count(left, "item")).isEqualTo(1);
            assertThat(count(right, "item")).isZero();
        }
    }

    @Test
    @DisplayName("a write is one transaction: a failure part way leaves nothing behind")
    void writeIsAtomic() {
        try (PostgresDatabase database = open(TestPostgres.freshNamespace("atomic"), TWO_STEPS)) {
            boolean written = database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO item (name) VALUES ('half')");
                    statement.execute("INSERT INTO item (name) VALUES (NULL)");
                }
            });
            assertThat(written).isFalse();
            assertThat(count(database, "item")).isZero();
            assertThat(database.write(connection -> {
                throw new IllegalStateException("caller fell over");
            })).isFalse();
            assertThat(database.isUsable()).isTrue();
        }
    }

    @Test
    @DisplayName("a write inside a write is refused, as on SQLite")
    void nestedWriteRefused() {
        try (PostgresDatabase database = open(TestPostgres.freshNamespace("nested"), TWO_STEPS)) {
            AtomicBoolean inner = new AtomicBoolean(true);
            boolean outer = database.write(connection -> inner.set(database.write(nothing -> { })));
            assertThat(outer).isTrue();
            assertThat(inner).isFalse();
        }
    }

    @Test
    @DisplayName("a read that fails answers nothing and leaves the database usable")
    void failingRead() {
        try (PostgresDatabase database = open(TestPostgres.freshNamespace("failread"), TWO_STEPS)) {
            assertThat(database.read(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeQuery("SELECT * FROM no_such_table");
                }
                return 1;
            })).isEmpty();
            assertThat(count(database, "item")).isZero();
        }
    }

    @Test
    @DisplayName("work on a thread running the world is counted, as on SQLite")
    void serverThreadCounted() {
        AtomicBoolean onServer = new AtomicBoolean();
        try (PostgresDatabase database = PostgresDatabase.open(TestPostgres.pool(),
                TestPostgres.freshNamespace("thread"), TWO_STEPS, onServer::get, "test")) {
            count(database, "item");
            assertThat(database.workOnServerThread()).isZero();
            onServer.set(true);
            count(database, "item");
            assertThat(database.workOnServerThread()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("many threads writing at once each get their row in, none lost")
    void concurrentWrites() throws Exception {
        try (PostgresDatabase database = open(TestPostgres.freshNamespace("writes"), TWO_STEPS)) {
            ExecutorService threads = Executors.newFixedThreadPool(8);
            AtomicInteger failed = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int at = 0; at < 200; at++) {
                int n = at;
                futures.add(threads.submit(() -> {
                    if (!database.write(connection -> {
                        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO item (name) VALUES (?)")) {
                            insert.setString(1, "n" + n);
                            insert.executeUpdate();
                        }
                    })) {
                        failed.incrementAndGet();
                    }
                    count(database, "item");
                }));
            }
            for (Future<?> each : futures) {
                each.get(60, TimeUnit.SECONDS);
            }
            threads.shutdownNow();
            assertThat(failed).hasValue(0);
            assertThat(count(database, "item")).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("after close it answers no and nothing, and the shared pool lives on")
    void closed() {
        PostgresDatabase database = open(TestPostgres.freshNamespace("closed"), TWO_STEPS);
        database.close();
        assertThat(database.isUsable()).isFalse();
        assertThat(database.write(connection -> { })).isFalse();
        assertThat(database.read(connection -> 1)).isEmpty();
        database.close();
        try (PostgresDatabase other = open(TestPostgres.freshNamespace("after"), TWO_STEPS)) {
            assertThat(other.isUsable()).isTrue();
        }
    }

    @Test
    @DisplayName("an unreachable database gives an unusable one, not an exception")
    void unreachable() {
        try (PostgresPool nowhere = PostgresPool.open(PostgresSettings.of("127.0.0.1", 1, "x", "y", ""));
             PostgresDatabase database = PostgresDatabase.open(nowhere, "anything", TWO_STEPS, () -> false, "test")) {
            assertThat(database.isUsable()).isFalse();
        }
    }

    @Test
    @DisplayName("Core's own schemas build on Postgres as they are")
    void coreSchemas() {
        try (PostgresDatabase core = open(TestPostgres.freshNamespace("core"), CoreSchema.CORE);
             PostgresDatabase audit = open(TestPostgres.freshNamespace("audit"), CoreSchema.AUDIT)) {
            assertThat(core.isUsable()).isTrue();
            assertThat(core.version()).isEqualTo(CoreSchema.CORE.size());
            assertThat(audit.isUsable()).isTrue();
            assertThat(audit.version()).isEqualTo(CoreSchema.AUDIT.size());
        }
    }
}
