package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.PostgresPool;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The network's bus in its central PostgreSQL.
 *
 * <h2>Durable first, fast second</h2>
 * Every event is a row in {@code public.rains_event}; that row is the event. A {@code NOTIFY} sent in
 * the same transaction only wakes the listeners so they look at once instead of at their next poll.
 * A listener whose connection drops misses notifies, not events: when it is back it reads every row
 * past the last one it saw. A notify that is lost costs one poll interval of latency, never an event.
 *
 * <h2>Ids are handed out before commit</h2>
 * Two servers publishing at once may commit in the opposite order to their ids, so a listener can see
 * 105 before 104 exists for it. Reading "everything past the highest id seen" would then skip 104
 * for good. So the listener remembers the highest id below which it has seen everything, and what it
 * has seen above that; a hole is waited for, and given up on only after {@link #GAP_PATIENCE} — the id
 * of a transaction that rolled back never fills.
 *
 * <h2>One connection of its own</h2>
 * {@code LISTEN} belongs to a session, so the listener keeps its own connection rather than borrowing
 * one from the pool. Through PgBouncer this must be a session-mode connection.
 */
public final class PostgresBus implements NetworkBus {

    private static final LogChannel log = Log.of("network");

    /** How long a hole in the ids is waited for before it is taken to be a rolled-back publish. */
    static final Duration GAP_PATIENCE = Duration.ofSeconds(10);

    private static final long SETUP_LOCK = 0x5241494E5342L;
    private static final int BATCH = 1_000;

    private final PostgresPool pool;
    private final String server;
    private final String channel;
    private final String notifyChannel;
    private final Duration pollInterval;
    private final String listenerName;

    private final Map<String, List<Consumer<NetworkEvent>>> handlers = new ConcurrentHashMap<>();
    private final List<Runnable> resyncs = new CopyOnWriteArrayList<>();
    private final Duration gapPatience;
    private final long setupWaitMillis;
    private boolean startedBlind;
    private boolean everConnected;
    private final ExecutorService dispatch;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread listener;
    private volatile Connection listening;

    // Only touched by the listener thread.
    private long seenUpTo = -1;
    private final TreeSet<Long> seenAbove = new TreeSet<>();
    private final Map<Long, Long> holeNoticedAt = new HashMap<>();

    private PostgresBus(PostgresPool pool, String server, String channel, Duration pollInterval,
                        Duration gapPatience, long setupWaitMillis) {
        this.gapPatience = gapPatience;
        this.setupWaitMillis = setupWaitMillis;
        this.pool = pool;
        this.server = server;
        this.channel = channel;
        this.notifyChannel = "rains_bus_" + channel;
        this.pollInterval = pollInterval;
        this.listenerName = ("RainsCore-bus:" + server + ":" + channel);
        this.dispatch = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rains-bus-dispatch-" + server);
            thread.setDaemon(true);
            return thread;
        });
        this.listener = new Thread(this::listen, "rains-bus-listen-" + server);
        this.listener.setDaemon(true);
    }

    /**
     * Starts listening. Never throws: a database that cannot be reached is retried in the background,
     * and the bus delivers from where it was once it can.
     *
     * @param channel      the network's name on this database — servers hear only their own channel
     * @param pollInterval how often it reads even without a notify; the most a lost notify can delay an event
     */
    public static PostgresBus start(PostgresPool pool, String server, String channel, Duration pollInterval) {
        return start(pool, server, channel, pollInterval, GAP_PATIENCE);
    }

    /** As {@link #start(PostgresPool, String, String, Duration)}, waiting {@code gapPatience} for a hole to fill. */
    public static PostgresBus start(PostgresPool pool, String server, String channel, Duration pollInterval,
                                    Duration gapPatience) {
        return start(pool, server, channel, pollInterval, gapPatience, 10_000);
    }

    static PostgresBus start(PostgresPool pool, String server, String channel, Duration pollInterval,
                             Duration gapPatience, long setupWaitMillis) {
        PostgresBus bus = new PostgresBus(pool, server, NetworkConfig.normaliseId(channel), pollInterval,
                gapPatience, setupWaitMillis);
        bus.prepare();
        // Fixed here, before start() returns, not when the listener's own connection is up: whatever is
        // published after start() is heard, however long that connection takes.
        bus.seenUpTo = bus.newestOrUnknown();
        bus.startedBlind = bus.seenUpTo < 0;
        bus.listener.start();
        return bus;
    }

    @Override
    public Subscription onResync(Runnable handler) {
        resyncs.add(handler);
        return () -> resyncs.remove(handler);
    }

    /** Tells every store to read everything again: events may have been missed. */
    private void resync(String why) {
        if (resyncs.isEmpty()) {
            return;
        }
        log.info("The network bus may have missed events ({}); everything is read again.", why);
        dispatch.execute(() -> resyncs.forEach(handler -> {
            try {
                handler.run();
            } catch (RuntimeException failed) {
                log.error(failed, "Reading everything again after '{}' failed for one store.", why);
            }
        }));
    }

    @Override
    public String server() {
        return server;
    }

    /** The application name of the listening connection, as pg_stat_activity shows it. */
    public String listenerName() {
        return listenerName;
    }

    // -------------------------------------------------------------------------------- tables

    private void prepare() {
        try {
            Connection connection = pool.borrow("public", setupWaitMillis);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(" + SETUP_LOCK + ")");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS public.rains_event (
                            id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            channel TEXT   NOT NULL,
                            at      BIGINT NOT NULL,
                            server  TEXT   NOT NULL,
                            topic   TEXT   NOT NULL,
                            key     TEXT   NOT NULL DEFAULT '',
                            payload TEXT   NOT NULL DEFAULT ''
                        )""");
                statement.execute("CREATE INDEX IF NOT EXISTS rains_event_by_channel ON public.rains_event (channel, id)");
                connection.commit();
            } catch (SQLException failed) {
                connection.rollback();
                throw failed;
            } finally {
                pool.giveBack(connection);
            }
        } catch (SQLException | RuntimeException unreachable) {
            log.error("The network's event log could not be prepared ({}); servers will not hear each other "
                    + "until the database can be reached.", unreachable.getMessage());
        }
    }

    // ------------------------------------------------------------------------------ publish

    @Override
    public boolean publish(String topic, String key, String payload) {
        if (topic == null || topic.isBlank() || !running.get()) {
            return false;
        }
        Connection connection = null;
        try {
            connection = pool.borrow("public", 5_000);
            long id;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO public.rains_event (channel, at, server, topic, key, payload)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id""")) {
                insert.setString(1, channel);
                insert.setLong(2, System.currentTimeMillis());
                insert.setString(3, server);
                insert.setString(4, topic);
                insert.setString(5, key == null ? "" : key);
                insert.setString(6, payload == null ? "" : payload);
                try (ResultSet rows = insert.executeQuery()) {
                    rows.next();
                    id = rows.getLong(1);
                }
            }
            try (PreparedStatement notify = connection.prepareStatement("SELECT pg_notify(?, ?)")) {
                notify.setString(1, notifyChannel);
                notify.setString(2, String.valueOf(id));
                notify.execute();
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failed) {
            log.warn("An event ({} {}) could not be published to the network: {}", topic, key, failed.getMessage());
            return false;
        } finally {
            if (connection != null) {
                pool.giveBack(connection);
            }
        }
    }

    // ---------------------------------------------------------------------------- subscribe

    @Override
    public Subscription subscribe(String topic, Consumer<NetworkEvent> handler) {
        List<Consumer<NetworkEvent>> forTopic = handlers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>());
        forTopic.add(handler);
        return () -> forTopic.remove(handler);
    }

    private void deliver(NetworkEvent event) {
        List<Consumer<NetworkEvent>> forTopic = handlers.get(event.topic());
        if (forTopic == null || forTopic.isEmpty()) {
            return;
        }
        dispatch.execute(() -> {
            for (Consumer<NetworkEvent> handler : forTopic) {
                try {
                    handler.accept(event);
                } catch (RuntimeException failed) {
                    log.error(failed, "A handler for network event {} {} failed.", event.topic(), event.key());
                }
            }
        });
    }

    // ------------------------------------------------------------------------------- listen

    private void listen() {
        long backoff = 500;
        while (running.get()) {
            try (Connection connection = PostgresPool.openConnection(pool.settings().withApplicationName(listenerName))) {
                connection.setAutoCommit(true);
                listening = connection;
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + notifyChannel);
                }
                if (seenUpTo < 0) {
                    seenUpTo = newest(connection);
                }
                if (everConnected) {
                    // Back after losing the connection: notifies sent meanwhile are gone, and if it was away
                    // long, so are the events (pruned). It cannot tell which, so it reads everything.
                    resync("the connection to the database was lost");
                } else if (startedBlind) {
                    resync("it started before the database could be reached");
                }
                everConnected = true;
                backoff = 500;
                var notifications = connection.unwrap(org.postgresql.PGConnection.class);
                while (running.get()) {
                    readNew(connection);
                    // Blocks until a notify arrives or the interval passes; either way, read again.
                    notifications.getNotifications((int) pollInterval.toMillis());
                }
            } catch (SQLException | RuntimeException dropped) {
                if (!running.get()) {
                    return;
                }
                log.warn("The network bus lost its connection ({}); trying again.", dropped.getMessage());
                sleep(backoff);
                backoff = Math.min(backoff * 2, 10_000);
            } finally {
                listening = null;
            }
        }
    }

    private long newestOrUnknown() {
        try {
            Connection connection = pool.borrow("public", setupWaitMillis);
            try {
                return newest(connection);
            } finally {
                pool.giveBack(connection);
            }
        } catch (SQLException | RuntimeException unreachable) {
            return -1;
        }
    }

    private long newest(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT coalesce(max(id), 0) FROM public.rains_event WHERE channel = ?")) {
            select.setString(1, channel);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private void readNew(Connection connection) throws SQLException {
        boolean more = true;
        while (more && running.get()) {
            int read = 0;
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id, at, server, topic, key, payload FROM public.rains_event
                    WHERE channel = ? AND id > ? ORDER BY id LIMIT ?""")) {
                select.setString(1, channel);
                select.setLong(2, seenUpTo);
                select.setInt(3, BATCH);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        read++;
                        long id = rows.getLong("id");
                        if (!seenAbove.add(id)) {
                            continue;
                        }
                        String from = rows.getString("server");
                        if (!server.equals(from)) {
                            deliver(new NetworkEvent(id, rows.getLong("at"), from, rows.getString("topic"),
                                    rows.getString("key"), rows.getString("payload")));
                        }
                    }
                }
            }
            advance();
            more = read == BATCH;
        }
    }

    /** Moves the "seen everything below" mark up over what has been seen, and over holes waited out. */
    private void advance() {
        long now = System.currentTimeMillis();
        while (!seenAbove.isEmpty()) {
            long next = seenUpTo + 1;
            if (seenAbove.remove(next)) {
                seenUpTo = next;
                holeNoticedAt.remove(next);
                continue;
            }
            long noticed = holeNoticedAt.computeIfAbsent(next, ignored -> now);
            if (now - noticed < gapPatience.toMillis()) {
                break;
            }
            // Waited long enough: usually an id handed to a publish that rolled back, which nothing will ever
            // fill — but possibly a very slow transaction that will commit after all, and its event would then
            // be skipped. Not knowing which, everything is read again.
            holeNoticedAt.remove(next);
            seenUpTo = next;
            resync("an event did not arrive within " + gapPatience.toSeconds() + "s");
        }
        holeNoticedAt.keySet().removeIf(id -> id <= seenUpTo);
    }

    // ------------------------------------------------------------------------------ upkeep

    /**
     * Deletes events older than {@code keep}. Any server may do it, any number of times: what is old is
     * old for every server, and a listener that was away longer than that reads the stores afresh anyway.
     *
     * @return how many were deleted, or -1 when the database could not be reached
     */
    public int prune(Duration keep) {
        Connection connection = null;
        try {
            connection = pool.borrow("public", 5_000);
            int deleted;
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM public.rains_event WHERE channel = ? AND at < ?")) {
                delete.setString(1, channel);
                delete.setLong(2, System.currentTimeMillis() - keep.toMillis());
                deleted = delete.executeUpdate();
            }
            connection.commit();
            return deleted;
        } catch (SQLException | RuntimeException failed) {
            log.warn("Old network events could not be pruned: {}", failed.getMessage());
            return -1;
        } finally {
            if (connection != null) {
                pool.giveBack(connection);
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Connection open = listening;
        if (open != null) {
            try {
                open.close();
            } catch (SQLException ignored) {
                // Closing it is how the listener is woken up to stop.
            }
        }
        listener.interrupt();
        try {
            listener.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        dispatch.shutdown();
        try {
            dispatch.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** For tests: what the listener has seen everything up to. */
    long seenUpTo() {
        return seenUpTo;
    }

    Set<Long> seenAbove() {
        return Set.copyOf(seenAbove);
    }
}
