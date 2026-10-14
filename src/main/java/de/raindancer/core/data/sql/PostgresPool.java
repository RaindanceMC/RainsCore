package de.raindancer.core.data.sql;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * This server's connections to the network's central database, shared by every namespace.
 *
 * <p>One pool per server rather than one per namespace: a network of twenty servers with twenty
 * plugins each holding five connections would be two thousand connections, and Postgres' default is
 * a hundred. A connection is pointed at a namespace when it is lent out, by its {@code search_path},
 * so a module's SQL names its tables without a schema in front, exactly as it did on SQLite.
 *
 * <p>Written here rather than taken from HikariCP for the same reason as the rest of this library:
 * nothing is downloaded at startup, and what is needed is small — a bounded set, a wait with a
 * deadline, a check that a connection still works, and a reset between borrowers.
 */
public final class PostgresPool implements AutoCloseable {

    private static final LogChannel log = Log.of("sql");

    /** Plain lower-case names only: a namespace is spliced into SET search_path, never bound. */
    private static final Pattern NAMESPACE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** A connection idle longer than this is asked whether it still works before it is lent out. */
    private static final long VALIDATE_AFTER_MILLIS = 30_000;

    private final PostgresSettings settings;
    private final Semaphore permits;
    private final LinkedBlockingDeque<Connection> idle = new LinkedBlockingDeque<>();
    private final Map<Connection, Lent> state = new ConcurrentHashMap<>();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    private static final class Lent {
        volatile String namespace = "";
        volatile long returnedAt = System.currentTimeMillis();
        volatile boolean validateNext;
        volatile boolean out;
    }

    private PostgresPool(PostgresSettings settings) {
        this.settings = settings;
        this.permits = new Semaphore(settings.poolSize(), true);
    }

    /** Never fails: an unreachable database is a pool whose borrows fail, so the reason reaches the log. */
    public static PostgresPool open(PostgresSettings settings) {
        return new PostgresPool(settings);
    }

    public PostgresSettings settings() {
        return settings;
    }

    /** Whether a connection can be had right now. Used once at startup to say so loudly. */
    public boolean isReachable() {
        try {
            Connection connection = borrow("public", 2_000);
            giveBack(connection);
            return true;
        } catch (SQLException | RuntimeException unreachable) {
            log.debug("The central database at {} cannot be reached: {}", settings.describe(), unreachable.getMessage());
            return false;
        }
    }

    /**
     * A connection pointed at {@code namespace}, not auto-committing. Give it back with
     * {@link #giveBack}, whatever happened.
     *
     * @throws SQLException when none came free within the wait or the database cannot be reached
     */
    public Connection borrow(String namespace, long waitMillis) throws SQLException {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Not a namespace: '" + namespace + "'");
        }
        if (closed.get()) {
            throw new SQLException("The connection pool for " + settings.describe() + " is closed.");
        }
        try {
            if (!permits.tryAcquire(Math.max(0, waitMillis), TimeUnit.MILLISECONDS)) {
                throw new SQLException("No connection to " + settings.describe() + " came free within "
                        + waitMillis + " ms; all " + settings.poolSize() + " are in use.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a connection", interrupted);
        }
        try {
            Connection connection = takeWorking();
            point(connection, namespace);
            state.get(connection).out = true;
            return connection;
        } catch (SQLException | RuntimeException failed) {
            permits.release();
            throw failed;
        }
    }

    /** Takes a connection back: rolls back what the borrower left open, and drops it if it is broken. */
    public void giveBack(Connection connection) {
        if (connection == null) {
            return;
        }
        Lent lent = state.get(connection);
        if (lent == null || !lent.out) {
            // Not one of ours, or already given back. A second release would let the pool grow past its size.
            return;
        }
        lent.out = false;
        boolean keep = !closed.get();
        if (keep) {
            try {
                keep = !connection.isClosed();
                if (keep) {
                    connection.rollback();
                }
            } catch (SQLException broken) {
                keep = false;
            }
        }
        if (keep) {
            lent.returnedAt = System.currentTimeMillis();
            idle.offerFirst(connection);
        } else {
            discard(connection);
        }
        permits.release();
    }

    /** How many physical connections are open right now. */
    public int opened() {
        return opened.get();
    }

    /** Makes every idle connection prove it still works before it is next lent out. */
    void forceValidationOfIdle() {
        for (Connection each : idle) {
            Lent lent = state.get(each);
            if (lent != null) {
                lent.validateNext = true;
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Connection each;
        while ((each = idle.pollFirst()) != null) {
            discard(each);
        }
    }

    // ---------------------------------------------------------------------------- internals

    private Connection takeWorking() throws SQLException {
        Connection candidate;
        while ((candidate = idle.pollFirst()) != null) {
            Lent lent = state.get(candidate);
            boolean stale = lent == null || lent.validateNext
                    || System.currentTimeMillis() - lent.returnedAt > VALIDATE_AFTER_MILLIS;
            if (!stale || stillWorks(candidate)) {
                if (lent != null) {
                    lent.validateNext = false;
                }
                return candidate;
            }
            discard(candidate);
        }
        return connect();
    }

    private static boolean stillWorks(Connection connection) {
        try {
            return connection.isValid(2);
        } catch (SQLException broken) {
            return false;
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = openConnection(settings);
        state.put(connection, new Lent());
        opened.incrementAndGet();
        return connection;
    }

    /** A connection outside any pool, set up the way the pool sets up its own. */
    public static Connection openConnection(PostgresSettings settings) throws SQLException {
        // The driver is asked for directly rather than through DriverManager: inside the plugin jar it is
        // relocated, and DriverManager only finds drivers its caller's class loader registered.
        Connection connection = new org.postgresql.Driver().connect(settings.url(), settings.connectionProperties());
        if (connection == null) {
            throw new SQLException("The driver did not accept " + settings.url());
        }
        connection.setAutoCommit(false);
        return connection;
    }

    private void point(Connection connection, String namespace) throws SQLException {
        Lent lent = state.get(connection);
        if (lent != null && namespace.equals(lent.namespace)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + namespace + ("public".equals(namespace) ? "" : ", public"));
            connection.commit();
        } catch (SQLException failed) {
            discard(connection);
            throw failed;
        }
        if (lent != null) {
            lent.namespace = namespace;
        }
    }

    private void discard(Connection connection) {
        if (state.remove(connection) != null) {
            opened.decrementAndGet();
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Being thrown away because it is broken or the pool is closing; nothing left to do with it.
        }
    }
}
