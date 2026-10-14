package de.raindancer.core.data.sql;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * One namespace — a Postgres schema — in the network's central database.
 *
 * <p>Behaves as {@link SqliteDatabase} does towards its callers: a write is one transaction, writes
 * from this server are queued behind each other, a read sees committed data, nothing throws. What
 * differs is underneath: connections come from the server's shared {@link PostgresPool}, other
 * servers write to the same tables at the same time, and the schema version lives in
 * {@code public.rains_schema} rather than in the file.
 *
 * <h2>Building the schema on a network</h2>
 * Every server of a network starts the same plugin against the same tables, often at the same
 * moment after a restart. Each step is applied under an advisory lock held for the transaction, so
 * exactly one server builds a step and the others find it built. Postgres DDL is transactional, so
 * the steps still to do and the version that records them commit together or not at all: a step
 * that fails leaves the namespace exactly as it was.
 */
public final class PostgresDatabase implements Database {

    private static final LogChannel log = Log.of("sql");

    /** Taken while the namespace and the version table are created, which must not race either. */
    private static final long SETUP_LOCK = 0x5241494E53L;

    private static final long CONNECTION_WAIT_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final long CLOSING_WAIT_MILLIS = 2_000;

    private final PostgresPool pool;
    private final String namespace;
    private final BooleanSupplier onServerThread;
    private final String server;

    private final ReentrantLock writing = new ReentrantLock(true);
    private final AtomicBoolean usable = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger readsInFlight = new AtomicInteger();
    private final AtomicInteger onServerThreadCount = new AtomicInteger();
    private final Set<String> complainedAbout = ConcurrentHashMap.newKeySet();
    private volatile int version;

    private PostgresDatabase(PostgresPool pool, String namespace, BooleanSupplier onServerThread, String server) {
        this.pool = pool;
        this.namespace = namespace;
        this.onServerThread = onServerThread == null ? () -> false : onServerThread;
        this.server = server == null ? "" : server;
    }

    /**
     * The namespace, built and brought up to date. Never throws: a database that cannot be reached
     * or whose schema fails is returned unusable, with the reason in the log.
     *
     * @param server this server's name, recorded beside the version it applied
     */
    public static PostgresDatabase open(PostgresPool pool, String namespace, Schema schema,
                                        BooleanSupplier onServerThread, String server) {
        PostgresDatabase database = new PostgresDatabase(pool, namespace, onServerThread, server);
        database.start(schema == null ? Schema.none() : schema);
        return database;
    }

    private void start(Schema schema) {
        Connection connection;
        try {
            connection = pool.borrow("public", CONNECTION_WAIT_MILLIS);
        } catch (SQLException | RuntimeException unreachable) {
            log.error("The central database at {} could not be reached for {}: {}",
                    pool.settings().describe(), describe(), unreachable.getMessage());
            return;
        }
        try {
            prepare(connection);
            usable.set(migrate(connection, schema));
        } catch (SQLException | RuntimeException failed) {
            log.error(failed, "{} could not be prepared in the central database.", describe());
        } finally {
            pool.giveBack(connection);
        }
    }

    /** The namespace and the table that records every namespace's version. */
    private void prepare(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_xact_lock(" + SETUP_LOCK + ")");
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + namespace);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS public.rains_schema (
                        namespace  TEXT    PRIMARY KEY,
                        version    INTEGER NOT NULL,
                        updated_at BIGINT  NOT NULL,
                        server     TEXT
                    )""");
            connection.commit();
        } catch (SQLException failed) {
            connection.rollback();
            throw failed;
        }
    }

    private boolean migrate(Connection connection, Schema schema) throws SQLException {
        int at = 0;
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_xact_lock(hashtext('rains_schema'), hashtext('" + namespace + "'))");
            at = readVersion(connection);
            var steps = schema.after(at);
            if (steps.isEmpty()) {
                connection.commit();
                version = at;
                return true;
            }
            statement.execute("SET LOCAL search_path TO " + namespace + ", public");
            for (Schema.Step step : steps) {
                statement.execute(Dialect.POSTGRESQL.translate(step.sql()));
                at = step.version();
            }
            recordVersion(connection, at);
            connection.commit();
            log.info("{} is now at version {}.", describe(), at);
            version = at;
            return true;
        } catch (SQLException | RuntimeException failed) {
            connection.rollback();
            version = readVersionQuietly();
            log.error(failed, "Step {} of the schema for {} failed, so it is staying at version {}. Nothing that "
                    + "needs it will work until this is fixed.", at + 1, describe(), version);
            return false;
        }
    }

    private int readVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version FROM public.rains_schema WHERE namespace = ?")) {
            statement.setString(1, namespace);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private int readVersionQuietly() {
        try {
            Connection connection = pool.borrow("public", CONNECTION_WAIT_MILLIS);
            try {
                return readVersion(connection);
            } finally {
                pool.giveBack(connection);
            }
        } catch (SQLException | RuntimeException unreadable) {
            return 0;
        }
    }

    private void recordVersion(Connection connection, int at) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.rains_schema (namespace, version, updated_at, server) VALUES (?, ?, ?, ?)
                ON CONFLICT (namespace) DO UPDATE SET version = excluded.version,
                    updated_at = excluded.updated_at, server = excluded.server""")) {
            statement.setString(1, namespace);
            statement.setInt(2, at);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, server);
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------------------------------ answers

    @Override
    public int version() {
        return version;
    }

    @Override
    public boolean isUsable() {
        return usable.get() && !closed.get();
    }

    @Override
    public Path file() {
        return null;
    }

    @Override
    public String describe() {
        return "postgresql:" + namespace;
    }

    @Override
    public Dialect dialect() {
        return Dialect.POSTGRESQL;
    }

    public String namespace() {
        return namespace;
    }

    @Override
    public int workOnServerThread() {
        return onServerThreadCount.get();
    }

    // ------------------------------------------------------------------------------ writing

    @Override
    public boolean write(Work work) {
        if (work == null || !ready("write")) {
            return false;
        }
        if (writing.isHeldByCurrentThread()) {
            log.error("A write to {} was attempted from inside another write, at {}. Nesting them would commit "
                    + "the outer write's work early and make it impossible to roll back. Do the whole thing in "
                    + "one write instead.", describe(), whereFrom());
            return false;
        }
        writing.lock();
        Connection connection = null;
        boolean committed = false;
        try {
            if (closed.get()) {
                return false;
            }
            connection = pool.borrow(namespace, CONNECTION_WAIT_MILLIS);
            work.run(connection);
            connection.commit();
            committed = true;
            return true;
        } catch (SQLException | RuntimeException failed) {
            log.error(failed, "A write to {} failed and was rolled back.", describe());
            return false;
        } finally {
            if (connection != null) {
                if (!committed) {
                    rollbackQuietly(connection);
                }
                pool.giveBack(connection);
            }
            writing.unlock();
        }
    }

    // ------------------------------------------------------------------------------ reading

    @Override
    public <T> Optional<T> read(Query<T> query) {
        if (query == null || !ready("read")) {
            return Optional.empty();
        }
        readsInFlight.incrementAndGet();
        Connection connection = null;
        try {
            connection = pool.borrow(namespace, CONNECTION_WAIT_MILLIS);
            return Optional.ofNullable(query.run(connection));
        } catch (SQLException | RuntimeException failed) {
            log.error(failed, "A read from {} failed.", describe());
            return Optional.empty();
        } finally {
            if (connection != null) {
                pool.giveBack(connection);
            }
            readsInFlight.decrementAndGet();
        }
    }

    // ------------------------------------------------------------------------- being careful

    private boolean ready(String what) {
        if (!isUsable()) {
            return false;
        }
        if (onServerThread.getAsBoolean()) {
            onServerThreadCount.incrementAndGet();
            String where = whereFrom();
            if (complainedAbout.add(where)) {
                log.error("A database {} ran on the thread running the world, from {}. Against a database on "
                        + "another machine that is a network round trip inside a tick. It belongs off the "
                        + "server's threads.", what, where);
            }
        }
        return true;
    }

    private static String whereFrom() {
        return StackWalker.getInstance()
                .walk(frames -> frames
                        .filter(frame -> !frame.getClassName().equals(PostgresDatabase.class.getName()))
                        .findFirst()
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                        .orElse("somewhere"));
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The pool finds out on giveBack whether the connection is still any good.
        }
    }

    // ------------------------------------------------------------------------------ closing

    /** Stops this namespace taking work. The pool is the server's and is closed by whoever opened it. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSING_WAIT_MILLIS);
        while (readsInFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // A write in progress finishes: taking the lock waits for it, and the next one sees closed.
        writing.lock();
        writing.unlock();
        usable.set(false);
    }
}
