package de.raindancer.core.data.sql;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * One SQLite database file, as this project uses one.
 *
 * <h2>Why SQLite, and why no dependency</h2>
 * Paper carries {@code org.xerial:sqlite-jdbc} inside its own jar, so the driver is already on every
 * server this runs on. That is the whole reason this is possible without breaking the rule that this
 * library downloads nothing at startup: real SQL, real indexes, real transactions, and nothing
 * declared. Checked on a running server rather than assumed — see the sqlite checks in the test
 * plugin.
 *
 * <p>A database file also survives the thing YAML does not: a server killed mid-save. There is no
 * point at which the file is half a database.
 *
 * <h2>The four settings that matter, and why each one is not the default</h2>
 * <ul>
 *   <li><b>Write-ahead logging.</b> Without it a reader and a writer lock each other out, so one
 *       plugin saving warps stalls another reading them. With it they never meet.</li>
 *   <li><b>Foreign keys on.</b> SQLite ignores them unless asked — a declared relationship is
 *       otherwise a comment, and the row it points at can be deleted underneath it.</li>
 *   <li><b>A busy timeout.</b> SQLite's answer to a contended write is not to wait but to fail with
 *       {@code SQLITE_BUSY}. A timeout turns that into waiting its turn, which is what every caller
 *       assumed it did.</li>
 *   <li><b>{@code synchronous=FULL}.</b> Slower than the alternative by an fsync per commit, and the
 *       alternative loses the last few transactions when the machine loses power. These transactions
 *       are somebody's homes and somebody's ban record; the fsync is worth it, and this is not a
 *       database doing thousands of writes a second.</li>
 * </ul>
 *
 * <h2>One writer, several readers</h2>
 * SQLite allows exactly one writer at a time whatever this class does, so writes are queued on one
 * connection behind one lock. Pretending otherwise does not make it parallel; it makes it fail
 * intermittently under load, which is the worst way for it to fail. Reads come from a small pool and
 * genuinely do run at the same time.
 *
 * <h2>Errors are answers</h2>
 * Nothing here throws. A write answers whether it happened, a read answers what it found or nothing,
 * and the reason goes to the log. A store that has to catch {@link SQLException} around every call
 * is a store where one of them will not, and a plugin that dies because a query failed takes a
 * server down over a warp nobody was looking at.
 */
public final class Database implements AutoCloseable {

    private static final LogChannel log = Log.of("sql");

    /** The driver Paper ships. Asked for by name because nothing here declares it. */
    private static final String DRIVER = "org.sqlite.JDBC";

    /** How long a contended write waits before giving up, rather than failing at once. */
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    /**
     * How many reads may run at once.
     *
     * <p>Small on purpose. Reads here are small and quick, the disk is the limit rather than the
     * connection count, and every open connection is a file handle and a page cache.
     */
    private static final int READERS = 4;

    /** How long a read waits for a free connection before giving up on getting one. */
    private static final long READER_WAIT_SECONDS = 10;

    private final Path file;
    private final BooleanSupplier onServerThread;

    /** The one connection writes go through, and the lock that makes them one at a time. */
    private final ReentrantLock writing = new ReentrantLock(true);
    private Connection writer;

    private final BlockingQueue<Connection> readers = new ArrayBlockingQueue<>(READERS);
    /** Everything opened, so closing closes all of it even if a reader is checked out. */
    private final List<Connection> everyConnection = new ArrayList<>();

    private final AtomicBoolean usable = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int version;

    private final AtomicInteger onServerThreadCount = new AtomicInteger();
    /** Places already complained about, so one mistake in a loop is one log line. */
    private final Set<String> complainedAbout = ConcurrentHashMap.newKeySet();

    /** How many reads are between taking a connection out of the pool and putting it back. */
    private final AtomicInteger readsInFlight = new AtomicInteger();

    /** How long {@link #close()} waits for an in-flight read to finish before checkpointing anyway. */
    private static final long CLOSING_WAIT_MILLIS = 2_000;

    private Database(Path file, BooleanSupplier onServerThread) {
        this.file = file;
        this.onServerThread = onServerThread == null ? () -> false : onServerThread;
    }

    /**
     * Opens a database, creating it and bringing its schema up to date.
     *
     * <p>Never throws. A database that could not be opened or whose schema could not be applied
     * comes back unusable and says so in the log — because the alternative is a plugin that refuses
     * to start, and a server with one broken feature is worth more than a server that is down.
     *
     * @param onServerThread how to tell whether the caller is on a thread that is running the world,
     *                       so that doing database work on one can be reported rather than merely
     *                       regretted
     */
    public static Database open(Path file, Schema schema, BooleanSupplier onServerThread) {
        Database database = new Database(file, onServerThread);
        database.start(schema == null ? Schema.none() : schema);
        return database;
    }

    private void start(Schema schema) {
        if (file == null) {
            log.error("A database was asked for with no file to keep it in.");
            return;
        }
        try {
            Class.forName(DRIVER);
        } catch (ClassNotFoundException | LinkageError missing) {
            log.error("The SQLite driver ({}) is not on the classpath, so nothing can be stored. "
                    + "Paper normally provides it; a server that has had its libraries stripped "
                    + "will not.", DRIVER);
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writer = connect();
            everyConnection.add(writer);
            for (int at = 0; at < READERS; at++) {
                Connection reader = connect();
                everyConnection.add(reader);
                readers.add(reader);
            }
            usable.set(true);
            if (!applySchema(schema)) {
                // Deliberately left usable=false rather than half-open: a store handed a database
                // whose tables are not all there fails one query at a time, in a different place
                // each time, and the reason is nowhere near the cause.
                //
                // And closed, not merely marked. Five connections to a database nothing may use are
                // five file handles held until the server stops.
                usable.set(false);
                closeConnections();
            }
        } catch (SQLException | IOException | RuntimeException unopenable) {
            log.error(unopenable, "The database {} could not be opened.", file);
            usable.set(false);
            closeConnections();
        }
    }

    private Connection connect() throws SQLException {
        Connection open = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = open.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
        }
        // Transactions are this class's business, not the driver's: a write is one transaction from
        // the caller's first statement to their last, which auto-commit would break into as many
        // transactions as there are statements.
        open.setAutoCommit(false);
        return open;
    }

    // ------------------------------------------------------------------------------- schema

    /**
     * Runs the steps this database has not run yet, one transaction each.
     *
     * <p>One at a time, with the version written inside the same transaction as the change it
     * describes. Bumping the version separately — or applying every step under one transaction and
     * one final bump — means a failure halfway leaves a database whose version is a lie in one
     * direction or the other: either it re-runs work it has done, or it skips work it has not.
     */
    private boolean applySchema(Schema schema) {
        version = readVersion();
        for (Schema.Step step : schema.after(version)) {
            writing.lock();
            try (Statement statement = writer.createStatement()) {
                statement.execute(step.sql());
                // Not a prepared parameter: PRAGMA does not take one, and the value is an int this
                // class produced rather than anything a caller supplied.
                statement.execute("PRAGMA user_version=" + step.version());
                writer.commit();
                version = step.version();
                log.info("{} is now at version {}.", file.getFileName(), version);
            } catch (SQLException | RuntimeException failed) {
                rollbackQuietly();
                log.error(failed, "Step {} of the schema for {} failed, so it is staying at "
                        + "version {}. Nothing that needs it will work until this is fixed.",
                        step.version(), file.getFileName(), version);
                return false;
            } finally {
                writing.unlock();
            }
        }
        return true;
    }

    private int readVersion() {
        try (Statement statement = writer.createStatement();
             var rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : 0;
        } catch (SQLException unreadable) {
            log.error(unreadable, "Could not read the schema version of {}.", file);
            return 0;
        }
    }

    /** How many schema steps this database has run. */
    public int version() {
        return version;
    }

    /** Whether this database opened and its schema is complete. */
    public boolean isUsable() {
        return usable.get() && !closed.get();
    }

    public Path file() {
        return file;
    }

    // ------------------------------------------------------------------------------ writing

    /**
     * Runs some work as one transaction.
     *
     * <p>All of it happens or none of it does. The work may run any number of statements and they are
     * one unit — which is the reason to have a database at all rather than several files that can
     * disagree with each other.
     *
     * @return whether it was committed; false means the database is exactly as it was
     */
    public boolean write(Work work) {
        if (work == null || !ready("write")) {
            return false;
        }
        if (writing.isHeldByCurrentThread()) {
            // A write inside a write. The lock is reentrant, so this would be let through — and the
            // inner call would commit the outer call's work halfway, which means an outer write that
            // fails afterwards cannot be rolled back and the database is left half-changed.
            //
            // Refused rather than quietly joined onto the outer transaction: the two behave
            // differently when the inner one fails, and a caller who wrote this did not decide
            // which they wanted. Nesting is a mistake in the caller and is reported as one.
            log.error("A write to {} was attempted from inside another write, at {}. Nesting them "
                    + "would commit the outer write's work early and make it impossible to roll "
                    + "back. Do the whole thing in one write instead.",
                    file.getFileName(), whereFrom());
            return false;
        }
        writing.lock();
        boolean committed = false;
        try {
            Connection open = writer;
            if (open == null) {
                // Checked again inside the lock. Between ready() and here, close() may have run:
                // it takes the same lock, so by the time this line runs the connection is gone
                // rather than merely closing.
                return false;
            }
            work.run(open);
            open.commit();
            committed = true;
            return true;
        } catch (SQLException | RuntimeException failed) {
            log.error(failed, "A write to {} failed and was rolled back.", file.getFileName());
            return false;
        } finally {
            if (!committed) {
                // In the finally rather than the catch, so that an Error gets it too. An
                // OutOfMemoryError or a StackOverflowError inside the caller's work would otherwise
                // skip the rollback entirely and leave the transaction open — and the next writer
                // to come along would commit the abandoned work as part of its own.
                rollbackQuietly();
            }
            writing.unlock();
        }
    }

    // ------------------------------------------------------------------------------ reading

    /**
     * Asks a question.
     *
     * @return what the query answered, or empty when it answered nothing or could not be run. Those
     *         two are deliberately the same answer: a caller that has to tell them apart wants
     *         {@link #isUsable()}, and every other caller wants to show an empty list either way
     */
    public <T> Optional<T> read(Query<T> query) {
        if (query == null || !ready("read")) {
            return Optional.empty();
        }
        Connection reader = null;
        // Counted for the whole call, not just the moment a connection is checked out: what close()
        // needs to know is whether a snapshot is still open somewhere, and that is true from here
        // until giveBack rolls it back, not only while poll() itself is running.
        readsInFlight.incrementAndGet();
        try {
            reader = readers.poll(READER_WAIT_SECONDS, TimeUnit.SECONDS);
            if (reader == null) {
                log.error("No connection to {} came free within {}s, so a read was given up on. "
                        + "Something is holding one much longer than a read should take.",
                        file.getFileName(), READER_WAIT_SECONDS);
                return Optional.empty();
            }
            return Optional.ofNullable(query.run(reader));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (SQLException | RuntimeException failed) {
            log.error(failed, "A read from {} failed.", file.getFileName());
            return Optional.empty();
        } finally {
            if (reader != null) {
                giveBack(reader);
            }
            readsInFlight.decrementAndGet();
        }
    }

    /**
     * Puts a read connection back, unless it is no longer worth having.
     *
     * <p>Two things happen here, and the second was missing.
     *
     * <p>The rollback is because a read opens a transaction all the same, and one left open holds a
     * snapshot of the database — which stops the write-ahead log from ever being folded back in, so
     * the file grows for ever.
     *
     * <p>The check is because a connection that has died — a fatal I/O error, or {@link #close()}
     * shutting it while this read was running — must not go back in the pool. Returned, it would be
     * handed to the next reader, fail immediately, and be returned again: one dead connection
     * permanently costs a quarter of the read capacity, and four of them cost all of it.
     */
    private void giveBack(Connection reader) {
        boolean alive;
        try {
            alive = !reader.isClosed();
        } catch (SQLException gone) {
            alive = false;
        }
        if (!alive || closed.get()) {
            // Dropped rather than replaced. Opening a new one here would mean opening connections
            // from whatever thread happened to hit the error, on a database that may be shutting
            // down; the pool being short is reported by the caller that waits for one.
            try {
                reader.close();
            } catch (SQLException ignored) {
                // Already gone, which is the point.
            }
            return;
        }
        rollbackQuietly(reader);
        readers.add(reader);
    }

    // ------------------------------------------------------------------------- being careful

    /**
     * Notes work on a thread that is running the world.
     *
     * <p>Reported rather than refused. Refusing would turn a stall into lost data, which is the wrong
     * way round, and the fix belongs where the call is rather than here. Once per place, because the
     * mistake is usually in a loop and a thousand identical lines hide the one that matters.
     */
    private boolean ready(String what) {
        if (!isUsable()) {
            return false;
        }
        if (onServerThread.getAsBoolean()) {
            onServerThreadCount.incrementAndGet();
            String where = whereFrom();
            if (complainedAbout.add(where)) {
                log.error("A database {} ran on the thread running the world, from {}. This is a "
                        + "stall nobody notices in testing and everybody feels on a full server. "
                        + "It belongs off the server's threads.", what, where);
            }
        }
        return true;
    }

    /** How often work has run on a server thread — the count a live check asserts is zero. */
    public int workOnServerThread() {
        return onServerThreadCount.get();
    }

    /** The first frame outside this class, which is the caller worth naming. */
    private static String whereFrom() {
        return StackWalker.getInstance()
                .walk(frames -> frames
                        .filter(frame -> !frame.getClassName().equals(Database.class.getName()))
                        .findFirst()
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName()
                                + ":" + frame.getLineNumber())
                        .orElse("somewhere"));
    }

    // ------------------------------------------------------------------------------ closing

    /**
     * Closes it, folding the write-ahead log back into the file first.
     *
     * <p>The checkpoint is the part worth doing deliberately. A database left with a {@code -wal}
     * beside it is complete only as long as both files stay together, and somebody copying "the
     * database" out for a backup will take one of them.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // The readers go first, before the checkpoint rather than after it. A checkpoint cannot fold
        // the log back in while any connection still holds a read snapshot: SQLite waits for the
        // busy timeout and then gives up — and it gives up by *answering* busy rather than by
        // throwing, so the failure is invisible unless the answer is read. The result is a -wal file
        // left beside the database, and somebody who copies "the database" for a backup takes only
        // half of it.
        closeReaders();
        // Idle ones are gone, but a read genuinely in flight right now — a task that started a moment
        // before disable ran — still holds its connection and its snapshot with it. Closing the
        // server thread down does not stop it; it finishes on its own thread as soon as SQLite hands
        // back the rows, which is normally milliseconds. Worth a short, bounded wait rather than the
        // warning this class exists to explain but not to avoid.
        awaitReadsFinished();
        // Again: a read that checked closed() a moment before it was set has put its connection
        // back into the pool since the first sweep, and it would stay open for good.
        closeReaders();
        writing.lock();
        try {
            if (writer != null) {
                boolean folded = checkpoint();
                try {
                    writer.close();
                } catch (SQLException ignored) {
                    // On the way out, and the file is already checkpointed.
                }
                // Closing the last connection makes SQLite fold the log in by itself, so a checkpoint that
                // could not finish above is only worth a warning if a log really is left beside the file.
                if (!folded && walLeftBehind()) {
                    log.warn("The write-ahead log of {} could not be folded back in. The .db-wal file beside it "
                            + "is part of the database and has to be kept with it.", file.getFileName());
                }
                // Cleared while the lock is held, so a write that was waiting for it sees null and
                // answers false instead of dereferencing a connection that has just been closed.
                writer = null;
            }
        } finally {
            writing.unlock();
        }
        everyConnection.clear();
        usable.set(false);
    }

    /**
     * Folds the write-ahead log back into the file, and says so if it could not.
     *
     * <p>The answer is read rather than discarded. {@code wal_checkpoint} reports being unable to
     * finish as a row of three numbers whose first is 1, not as an exception, so the obvious way to
     * call it cannot tell success from failure.
     */
    private boolean checkpoint() {
        try (Statement statement = writer.createStatement();
             var rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            return !rows.next() || rows.getInt(1) == 0;
        } catch (SQLException failed) {
            // SQLITE_LOCKED when a statement is still open on this connection: closing it folds the log in
            // anyway, and close() checks afterwards whether anything was really left.
            log.debug("The checkpoint of {} did not finish before closing ({}).", file.getFileName(),
                    failed.getMessage());
            return false;
        }
    }

    /** Whether a non-empty write-ahead log is still beside the file. */
    private boolean walLeftBehind() {
        java.nio.file.Path wal = file.resolveSibling(file.getFileName() + "-wal");
        try {
            return java.nio.file.Files.exists(wal) && java.nio.file.Files.size(wal) > 0;
        } catch (java.io.IOException unreadable) {
            return true;
        }
    }

    /**
     * Waits, briefly, for every read already in progress to give its connection back.
     *
     * <p>Bounded rather than open-ended: a read that never finishes is a bug somewhere else, and this
     * class's job on the way out is to checkpoint what it reasonably can, not to hang a shutdown over
     * it. Reads here are small and quick, so the ordinary case is this returns within a millisecond
     * or two of being called at all.
     */
    private void awaitReadsFinished() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSING_WAIT_MILLIS);
        while (readsInFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Closes the read connections that are idle, and leaves the rest to their readers.
     *
     * <p>Only the ones in the pool. A connection that is checked out has a statement running on
     * another thread, and closing a SQLite connection from underneath an executing statement is
     * unsafe in the driver's native half — the failure mode is not an exception but a crash of the
     * whole server. {@link #closed} is already set by the time this runs, so whoever holds that
     * connection closes it themselves when they hand it back; see {@link #giveBack}.
     */
    private void closeReaders() {
        Connection idle;
        while ((idle = readers.poll()) != null) {
            try {
                idle.close();
            } catch (SQLException ignored) {
                // On the way out.
            }
        }
    }

    /** Shuts everything again after a failed open, where there is nothing to checkpoint. */
    private void closeConnections() {
        for (Connection open : everyConnection) {
            try {
                open.close();
            } catch (SQLException ignored) {
                // Nothing was ever usable. Nothing useful left to do about it.
            }
        }
        everyConnection.clear();
        readers.clear();
        writer = null;
    }

    private void rollbackQuietly() {
        rollbackQuietly(writer);
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The connection is already unusable, which the next call will report.
        }
    }

    /** Work that changes something. */
    @FunctionalInterface
    public interface Work {
        void run(Connection connection) throws SQLException;
    }

    /** A question. */
    @FunctionalInterface
    public interface Query<T> {
        T run(Connection connection) throws SQLException;
    }
}
