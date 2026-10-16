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

    /** One server as the network sees it. */
    /** @param proxy what the proxy calls it — its id unless network.yml says otherwise */
    public record Server(String id, String group, String name, long startedAt, long heartbeatAt, int players,
                         String proxy) {
    }

    private final Database database;
    private final String self;
    private final String group;
    private final LongSupplier clock;
    private final String proxy;
    private final long startedAt;
    private volatile List<Server> online = List.of();

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
        long now = clock.getAsLong();
        database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO network_server (id, grp, name, started_at, heartbeat_at, players, proxy) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET grp = excluded.grp, name = excluded.name,
                        started_at = excluded.started_at, heartbeat_at = excluded.heartbeat_at, players = excluded.players,
                        proxy = excluded.proxy""")) {
                upsert.setString(1, self);
                upsert.setString(2, group);
                upsert.setString(3, displayName == null ? self : displayName);
                upsert.setLong(4, startedAt);
                upsert.setLong(5, now);
                upsert.setInt(6, players);
                upsert.setString(7, proxy);
                upsert.executeUpdate();
            }
        });
        refresh();
    }

    /** Reads the servers whose heartbeat is fresh. Off the server's threads. */
    public void refresh() {
        long since = clock.getAsLong() - ALIVE_MILLIS;
        database.read(connection -> {
            List<Server> found = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, grp, name, started_at, heartbeat_at, players, proxy FROM network_server WHERE heartbeat_at >= ? ORDER BY id")) {
                select.setLong(1, since);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        String proxyName = rows.getString(7);
                        found.add(new Server(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4),
                                rows.getLong(5), rows.getInt(6),
                                proxyName == null || proxyName.isBlank() ? rows.getString(1) : proxyName));
                    }
                }
            }
            return found;
        }).ifPresent(found -> online = List.copyOf(found));
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
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM network_server WHERE id = ?")) {
                delete.setString(1, self);
                delete.executeUpdate();
            }
        });
    }
}
