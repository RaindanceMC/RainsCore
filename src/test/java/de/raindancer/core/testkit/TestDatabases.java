package de.raindancer.core.testkit;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.Dialect;
import de.raindancer.core.data.sql.PostgresDatabase;
import de.raindancer.core.data.sql.Schema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * The database a store test runs against: SQLite by default, the central PostgreSQL when the build
 * runs with {@code -Drains.test.dialect=postgresql} (the second test run, tagged {@code database}).
 *
 * <p>A test names its database by a file, as it always has. On Postgres the same file is the same
 * namespace every time, so a test that closes its store and opens it again to prove the data survived
 * proves it there too.
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    public static Dialect dialect() {
        return Dialect.named(System.getProperty("rains.test.dialect", "sqlite"));
    }

    public static Database open(Path file, Schema schema, BooleanSupplier onServerThread) {
        if (dialect() == Dialect.POSTGRESQL) {
            return PostgresDatabase.open(TestPostgres.pool(), namespaceFor(file), schema, onServerThread, "test");
        }
        return Database.open(file, schema, onServerThread);
    }

    public static Database open(Path file, Schema schema) {
        return open(file, schema, () -> false);
    }

    /**
     * A database that cannot be used at all: a file of rubbish on SQLite, a database nobody can reach on
     * Postgres. What a store does when the disk is corrupt or the network's database is down.
     */
    public static Database unusable(Path file, Schema schema) throws java.io.IOException {
        if (dialect() == Dialect.POSTGRESQL) {
            de.raindancer.core.data.sql.PostgresPool nowhere = de.raindancer.core.data.sql.PostgresPool.open(
                    de.raindancer.core.data.sql.PostgresSettings.of("127.0.0.1", 1, "nowhere", "nobody", ""));
            return PostgresDatabase.open(nowhere, namespaceFor(file), schema, () -> false, "test");
        }
        java.nio.file.Files.write(file, "not a database".repeat(100).getBytes(StandardCharsets.UTF_8));
        return Database.open(file, schema, () -> false);
    }

    /**
     * Holds the database's writes up until closed, as another writer mid-transaction would — on SQLite an
     * exclusive transaction on the file, on Postgres an exclusive lock on {@code table} (reads go on).
     */
    public static AutoCloseable holdWrites(Path file, String table) throws java.sql.SQLException {
        java.sql.Connection other;
        if (dialect() == Dialect.POSTGRESQL) {
            other = TestPostgres.connect();
            other.setAutoCommit(false);
            try (java.sql.Statement lock = other.createStatement()) {
                lock.execute("LOCK TABLE " + namespaceFor(file) + "." + table + " IN EXCLUSIVE MODE");
            }
        } else {
            other = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
            try (java.sql.Statement lock = other.createStatement()) {
                lock.execute("BEGIN EXCLUSIVE");
            }
        }
        java.sql.Connection held = other;
        return () -> {
            try (held) {
                if (dialect() == Dialect.POSTGRESQL) {
                    held.rollback();
                } else {
                    try (java.sql.Statement release = held.createStatement()) {
                        release.execute("ROLLBACK");
                    }
                }
            }
        };
    }

    /** {@code t_<file name>_<hash of the whole path>}: stable for one path, distinct between test folders. */
    static String namespaceFor(Path file) {
        String name = file.getFileName().toString().replaceFirst("\\.db$", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (name.length() > 20) {
            name = name.substring(0, 20);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return "t_" + name + "_" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
