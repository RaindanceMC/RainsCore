package de.raindancer.core.data.sql;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * One database a store keeps its tables in: a SQLite file on a server of its own, or a namespace
 * (a Postgres schema) in the network's central database.
 *
 * <h2>Errors are answers</h2>
 * Nothing here throws. A write answers whether it happened, a read answers what it found or nothing,
 * and the reason goes to the log. A store that has to catch {@link SQLException} around every call
 * is a store where one of them will not, and a plugin that dies because a query failed takes a
 * server down over a warp nobody was looking at.
 *
 * <h2>Writes are one transaction</h2>
 * Everything a {@link Work} does commits together or not at all. Writes from one server to one
 * database are queued behind each other, so a read-then-write inside one {@code write} sees no other
 * write of this server in between. Writes from <em>other</em> servers are the database's to order.
 */
public interface Database extends AutoCloseable {

    /** A SQLite file, as every 1.x server had. Opened and brought up to date at once. */
    static Database open(Path file, Schema schema, BooleanSupplier onServerThread) {
        return SqliteDatabase.open(file, schema, onServerThread);
    }

    boolean write(Work work);

    <T> Optional<T> read(Query<T> query);

    /** How many steps of its schema have been applied. */
    int version();

    /** Whether it is open and its tables are all there. */
    boolean isUsable();

    /** The file it lives in, or null when it is a namespace in a central database. */
    Path file();

    /** What to call it in a log line: the file name or {@code postgresql:<namespace>}. */
    String describe();

    Dialect dialect();

    /** How often it has been used from a thread that is running the world. */
    int workOnServerThread();

    @Override
    void close();

    @FunctionalInterface
    interface Work {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    interface Query<T> {
        T run(Connection connection) throws SQLException;
    }
}
