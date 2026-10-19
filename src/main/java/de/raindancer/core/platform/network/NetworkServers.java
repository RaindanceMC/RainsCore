package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The servers of the network, each keeping a heartbeat in the database.
 *
 * <p>A server is online while its heartbeat is fresh — {@link #ALIVE_MILLIS} — so a server that crashed drops
 * off on its own, and the players it still lists with it, without anybody cleaning up after it.
 */
public final class NetworkServers {

    /** How old a heartbeat may be before its server counts as gone. Several beats, so one slow beat is no outage. */
    public static final long ALIVE_MILLIS = 20_000;

    /** How long a stopped or crashed server stays listed as offline before it is taken for gone for good. */
    public static final long FORGET_MILLIS = 7L * 24 * 3600 * 1000;

    /**
     * One server as the network sees it.
     *
     * @param proxy what the proxy calls it — its id unless network.yml says otherwise
     * @param tps   ticks per second over the last minute; NaN when the server doesn't say
     * @param mspt  milliseconds a tick takes on average; NaN when the server doesn't say
     */
    public record Server(String id, String group, String name, long startedAt, long heartbeatAt, int players,
                         String proxy, double tps, double mspt) {

        /** As before the numbers: they are unknown. */
        public Server(String id, String group, String name, long startedAt, long heartbeatAt, int players, String proxy) {
            this(id, group, name, startedAt, heartbeatAt, players, proxy, Double.NaN, Double.NaN);
        }
    }

    /** Every server heard of lately, up or down — what a status page lists. {@code downSince} is 0 while it's up. */
    public record Known(String id, String group, String name, boolean online, long startedAt, long downSince, int players,
                        double tps, double mspt) {
    }

    private final Database database;
    private final String self;
    private final String group;
    private final LongSupplier clock;
    private final String proxy;
    private final long startedAt;
    private volatile List<Server> online = List.of();
    private volatile List<Known> known = List.of();

    public NetworkServers(Database database, String self, String group, LongSupplier clock) {
        this(database, self, group, self, clock);
    }

    public NetworkServers(Database database, String self, String group, String proxy, LongSupplier clock) {
        this.database = database;
        this.self = self;
        this.group = group;
        this.proxy = proxy == null || proxy.isBlank() ? self : proxy;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    public String self() {
        return self;
    }

    /** Says this server is alive, with its display name and how many are on, and reads who else is. Off the server's threads. */
    public void heartbeat(String displayName, int players) {
        heartbeat(displayName, players, Double.NaN, Double.NaN);
    }

    /** As {@link #heartbeat(String, int)}, saying how well the server runs (NaN for "can't tell"). */
    public void heartbeat(String displayName, int players, double tps, double mspt) {
        long now = clock.getAsLong();
        database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO network_server (id, grp, name, started_at, heartbeat_at, players, proxy, tps, mspt, stopped_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    ON CONFLICT(id) DO UPDATE SET grp = excluded.grp, name = excluded.name,
                        started_at = excluded.started_at, heartbeat_at = excluded.heartbeat_at, players = excluded.players,
                        proxy = excluded.proxy, tps = excluded.tps, mspt = excluded.mspt, stopped_at = NULL""")) {
                upsert.setString(1, self);
                upsert.setString(2, group);
                upsert.setString(3, displayName == null ? self : displayName);
                upsert.setLong(4, startedAt);
                upsert.setLong(5, now);
                upsert.setInt(6, players);
                upsert.setString(7, proxy);
                setNumber(upsert, 8, tps);
                setNumber(upsert, 9, mspt);
                upsert.executeUpdate();
            }
        });
        refresh();
    }

    /** SQLite can't keep NaN (and Postgres REAL would): an unknown number is NULL either way. */
    private static void setNumber(PreparedStatement statement, int at, double value) throws java.sql.SQLException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            statement.setNull(at, java.sql.Types.REAL);
        } else {
            statement.setDouble(at, value);
        }
    }

    private static double number(ResultSet rows, int at) throws java.sql.SQLException {
        double value = rows.getDouble(at);
        return rows.wasNull() ? Double.NaN : value;
    }

    /** Reads the servers whose heartbeat is fresh, and every server heard of lately. Off the server's threads. */
    public void refresh() {
        long now = clock.getAsLong();
        long since = now - ALIVE_MILLIS;
        database.read(connection -> {
            List<Server> up = new ArrayList<>();
            List<Known> all = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id, grp, name, started_at, heartbeat_at, players, proxy, tps, mspt, stopped_at FROM network_server
                    WHERE heartbeat_at >= ? OR stopped_at >= ? ORDER BY id""")) {
                select.setLong(1, now - FORGET_MILLIS);
                select.setLong(2, now - FORGET_MILLIS);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        String id = rows.getString(1);
                        long beat = rows.getLong(5);
                        long stopped = rows.getLong(10);
                        boolean stoppedCleanly = !rows.wasNull() && stopped > 0;
                        boolean alive = !stoppedCleanly && beat >= since;
                        String proxyName = rows.getString(7);
                        double tps = number(rows, 8);
                        double mspt = number(rows, 9);
                        if (alive) {
                            up.add(new Server(id, rows.getString(2), rows.getString(3), rows.getLong(4), beat, rows.getInt(6),
                                    proxyName == null || proxyName.isBlank() ? id : proxyName, tps, mspt));
                        }
                        all.add(new Known(id, rows.getString(2), rows.getString(3), alive, rows.getLong(4),
                                alive ? 0 : stoppedCleanly ? stopped : beat, alive ? rows.getInt(6) : 0, tps, mspt));
                    }
                }
            }
            return new Object[]{up, all};
        }).ifPresent(found -> {
            @SuppressWarnings("unchecked") List<Server> up = (List<Server>) found[0];
            @SuppressWarnings("unchecked") List<Known> all = (List<Known>) found[1];
            online = List.copyOf(up);
            known = List.copyOf(all);
        });
    }

    /** Every server heard of in the last week, up or down, as last read. */
    public List<Known> known() {
        return known;
    }

    /** The servers online as last read, this one included once it has beaten. */
    public List<Server> online() {
        return online;
    }

    public boolean isOnline(String id) {
        return online.stream().anyMatch(server -> server.id().equals(id));
    }

    /** What the proxy calls the server with this id; the id itself for one it does not know. */
    public String proxyName(String id) {
        return byId(id).map(Server::proxy).orElse(id);
    }

    public Optional<Server> byId(String id) {
        return online.stream().filter(server -> server.id().equals(id)).findFirst();
    }

    /** Takes this server off the list at once — at a clean shutdown, rather than waiting for its heartbeat to go stale. */
    public void leave() {
        database.write(connection -> {
            // Kept, marked stopped: a status page shows it as offline since now rather than forgetting it.
            try (PreparedStatement stop = connection.prepareStatement("UPDATE network_server SET stopped_at = ? WHERE id = ?")) {
                stop.setLong(1, clock.getAsLong());
                stop.setString(2, self);
                stop.executeUpdate();
            }
        });
    }
}
