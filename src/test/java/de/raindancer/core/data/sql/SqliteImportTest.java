package de.raindancer.core.data.sql;

import de.raindancer.core.testkit.TestPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;

/** A 1.x server's SQLite files, brought into the network's PostgreSQL when it joins one. */
class SqliteImportTest {

    @TempDir
    Path folder;

    private static final Schema SCHEMA = Schema.of(
            "CREATE TABLE thing (id TEXT PRIMARY KEY, name TEXT NOT NULL, server TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE note (id INTEGER PRIMARY KEY, at INTEGER NOT NULL, text TEXT NOT NULL)",
            "CREATE TABLE note_tag (note INTEGER NOT NULL REFERENCES note(id) ON DELETE CASCADE, tag TEXT NOT NULL, "
                    + "PRIMARY KEY (note, tag))");

    /** A 1.x file with two things, two notes and their tags. */
    private Path oldFile(String name, Schema schema, String thingId, String thingName) {
        Path file = folder.resolve(name + ".db");
        try (Database old = Database.open(file, schema, () -> false)) {
            assertThat(old.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO thing (id, name) VALUES ('" + thingId + "', '" + thingName + "')");
                    statement.execute("INSERT INTO note (at, text) VALUES (1, 'first')");
                    statement.execute("INSERT INTO note (at, text) VALUES (2, 'second')");
                    statement.execute("INSERT INTO note_tag (note, tag) VALUES (1, 'a'), (2, 'b'), (2, 'c')");
                }
            })).isTrue();
        }
        return file;
    }

    private static <T> T query(PostgresDatabase database, Database.Query<T> query) {
        // typed by the caller's query
        return database.read(query).orElseThrow();
    }

    @Test
    @DisplayName("everything comes over; generated ids are given anew with their children; this server is stamped")
    void bringsEverythingOver() throws Exception {
        String namespace = TestPostgres.freshNamespace("import");
        Path file = oldFile(namespace, SCHEMA, "t1", "Alpha");
        try (PostgresDatabase central = PostgresDatabase.open(TestPostgres.pool(), namespace, SCHEMA, () -> false, "smp")) {
            SqliteImport.Report report = SqliteImport.into(central, file, SCHEMA, "smp");

            assertThat(report.copied()).isEqualTo(Map.of("thing", 1, "note", 2, "note_tag", 3));
            assertThat(report.kept()).isEmpty();
            assertThatObject(query(central, connection -> {
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT name, server FROM thing")) {
                    rows.next();
                    return rows.getString(1) + "@" + rows.getString(2);
                }
            })).isEqualTo("Alpha@smp");
            assertThatObject(query(central, connection -> {
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery(
                             "SELECT n.text, string_agg(t.tag, ',' ORDER BY t.tag) FROM note n JOIN note_tag t ON t.note = n.id "
                                     + "GROUP BY n.text ORDER BY n.text")) {
                    StringBuilder found = new StringBuilder();
                    while (rows.next()) {
                        found.append(rows.getString(1)).append('=').append(rows.getString(2)).append(';');
                    }
                    return found.toString();
                }
            })).isEqualTo("first=a;second=b,c;");
        }
        assertThat(file).doesNotExist();
        assertThat(file.resolveSibling(file.getFileName() + ".imported-2.0.0")).exists();
    }

    @Test
    @DisplayName("a second server's file: rows the network has are kept, new ones added, generated ids never collide")
    void secondServerMerges() throws Exception {
        String namespace = TestPostgres.freshNamespace("merge");
        Path smpFile = oldFile(namespace + "_smp", SCHEMA, "shared", "From SMP");
        Path arenaFile = oldFile(namespace + "_arena", SCHEMA, "shared", "From arena");
        try (PostgresDatabase central = PostgresDatabase.open(TestPostgres.pool(), namespace, SCHEMA, () -> false, "x")) {
            SqliteImport.into(central, smpFile, SCHEMA, "smp");
            SqliteImport.Report second = SqliteImport.into(central, arenaFile, SCHEMA, "arena");

            assertThat(second.kept()).containsEntry("thing", 1);
            assertThatObject(query(central, connection -> {
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT name FROM thing WHERE id = 'shared'")) {
                    rows.next();
                    return rows.getString(1);
                }
            })).isEqualTo("From SMP");
            assertThatObject(query(central, connection -> {
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT count(*) FROM note")) {
                    rows.next();
                    return rows.getInt(1);
                }
            })).as("both servers' notes, each with its own new id").isEqualTo(4);
            assertThatObject(query(central, connection -> {
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT count(*) FROM note_tag")) {
                    rows.next();
                    return rows.getInt(1);
                }
            })).isEqualTo(6);
            // A note written afterwards gets an id past everything brought in.
            assertThat(central.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO note (at, text) VALUES (3, 'new')");
                }
            })).isTrue();
        }
    }

    @Test
    @DisplayName("an older file is brought up to the current schema first, then copied")
    void olderSchemaIsUpgradedFirst() throws Exception {
        String namespace = TestPostgres.freshNamespace("older");
        Schema older = Schema.of(SCHEMA.steps().get(0));
        Path file = oldFile(namespace, Schema.of(SCHEMA.steps().get(0), SCHEMA.steps().get(1), SCHEMA.steps().get(2)), "t", "x");
        Schema newer = Schema.of(SCHEMA.steps().get(0), SCHEMA.steps().get(1), SCHEMA.steps().get(2),
                "ALTER TABLE thing ADD COLUMN colour TEXT NOT NULL DEFAULT 'red'");
        try (PostgresDatabase central = PostgresDatabase.open(TestPostgres.pool(), namespace, newer, () -> false, "smp")) {
            SqliteImport.Report report = SqliteImport.into(central, file, newer, "smp");
            assertThat(report.copied()).containsEntry("thing", 1);
            assertThatObject(query(central, connection -> {
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT colour FROM thing")) {
                    rows.next();
                    return rows.getString(1);
                }
            })).isEqualTo("red");
        }
        assertThat(older.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a file that is not a database is left exactly where it is")
    void brokenFileLeftAlone() throws Exception {
        String namespace = TestPostgres.freshNamespace("broken");
        Path file = folder.resolve("broken.db");
        Files.writeString(file, "not a database".repeat(50));
        try (PostgresDatabase central = PostgresDatabase.open(TestPostgres.pool(), namespace, SCHEMA, () -> false, "smp")) {
            SqliteImport.Report report = SqliteImport.into(central, file, SCHEMA, "smp");
            assertThat(report.failed()).isTrue();
        }
        assertThat(file).exists();
    }

    @Test
    @DisplayName("Core's own schema comes over whole, the audit journal with its fields")
    void coreSchemas() throws Exception {
        String core = TestPostgres.freshNamespace("core");
        String audit = TestPostgres.freshNamespace("audit");
        Path coreFile = folder.resolve(core + ".db");
        Path auditFile = folder.resolve(audit + ".db");
        try (Database old = Database.open(coreFile, CoreSchema.CORE, () -> false)) {
            old.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO place (id, name, kind, world, x, y, z) VALUES ('p', 'home', 'home', 'world', 1, 2, 3)");
                    statement.execute("INSERT INTO punishment (id, target, kind, reason, given_at) VALUES ('b', 'u', 'BAN', 'x', 1)");
                    statement.execute("INSERT INTO world_seed (world, seed, at, cause) VALUES ('farm', -42, 5, 'CREATED')");
                }
            });
        }
        try (Database old = Database.open(auditFile, CoreSchema.AUDIT, () -> false)) {
            old.write(connection -> {
                try (PreparedStatement entry = connection.prepareStatement(
                        "INSERT INTO entry (at, feature, action) VALUES (?, 'invsee', 'opened')")) {
                    entry.setLong(1, 10);
                    entry.executeUpdate();
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO entry_field (entry, name, value) VALUES (1, 'slot', '3')");
                }
            });
        }
        try (PostgresDatabase centralCore = PostgresDatabase.open(TestPostgres.pool(), core, CoreSchema.CORE, () -> false, "smp");
             PostgresDatabase centralAudit = PostgresDatabase.open(TestPostgres.pool(), audit, CoreSchema.AUDIT, () -> false, "smp")) {
            assertThat(SqliteImport.into(centralCore, coreFile, CoreSchema.CORE, "smp").copied())
                    .containsEntry("place", 1).containsEntry("punishment", 1).containsEntry("world_seed", 1);
            assertThat(SqliteImport.into(centralAudit, auditFile, CoreSchema.AUDIT, "smp").copied())
                    .containsEntry("entry", 1).containsEntry("entry_field", 1);
            List<String> stamped = query(centralCore, connection -> {
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT server FROM place UNION ALL SELECT server FROM world_seed")) {
                    List<String> servers = new java.util.ArrayList<>();
                    while (rows.next()) {
                        servers.add(rows.getString(1));
                    }
                    return servers;
                }
            });
            assertThat(stamped).containsOnly("smp");
        }
    }

    @Test
    @DisplayName("a server joining a network brings its old file along the first time the namespace is opened, once")
    void databasesBringTheFileAlong() throws Exception {
        String namespace = TestPostgres.freshNamespace("joining");
        Path file = oldFile(namespace, SCHEMA, "t1", "Alpha");
        PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(3));
        try (Databases databases = Databases.central(pool, "smp", () -> false, folder)) {
            Database central = databases.of(namespace, SCHEMA);
            assertThat(central.read(connection -> {
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT count(*) FROM note")) {
                    rows.next();
                    return rows.getInt(1);
                }
            })).contains(2);
        }
        assertThat(file).doesNotExist();
        PostgresPool again = PostgresPool.open(TestPostgres.require().withPoolSize(3));
        try (Databases databases = Databases.central(again, "smp", () -> false, folder)) {
            assertThat(databases.of(namespace, SCHEMA).read(connection -> {
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT count(*) FROM note")) {
                    rows.next();
                    return rows.getInt(1);
                }
            })).as("not brought in a second time").contains(2);
        }
    }

    @Test
    @DisplayName("of() is this server's own namespace on a network; shared() the network's one")
    void ofIsThisServersSharedIsTheNetworks() throws Exception {
        String name = TestPostgres.freshNamespace("scoped");
        PostgresPool pool = PostgresPool.open(TestPostgres.require().withPoolSize(3));
        try (Databases smp = Databases.central(pool, "smp", () -> false, folder)) {
            smp.of(name, SCHEMA).write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO thing (id, name) VALUES ('mine', 'smp only')");
                }
            });
            smp.shared(name, SCHEMA).write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO thing (id, name) VALUES ('ours', 'everybody')");
                }
            });
        }
        PostgresPool other = PostgresPool.open(TestPostgres.require().withPoolSize(3));
        try (Databases arena = Databases.central(other, "arena", () -> false, folder)) {
            Database.Query<Integer> count = connection -> {
                try (Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM thing")) {
                    rows.next();
                    return rows.getInt(1);
                }
            };
            assertThat(arena.of(name, SCHEMA).read(count)).as("arena's own is empty").contains(0);
            assertThat(arena.shared(name, SCHEMA).read(count)).contains(1);
        }
    }
}
