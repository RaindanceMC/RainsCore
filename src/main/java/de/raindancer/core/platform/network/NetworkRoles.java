package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Who does what exactly once on the network: the economy's bank, the server that draws the lottery, the one
 * that prunes. A role is a lease in the database — held by one server, renewed by it every few seconds, and
 * taken over by another once it has gone stale (its holder crashed or stopped).
 */
public final class NetworkRoles {

    /** How long a lease lasts unrenewed. Renew well inside it — every {@code LEASE_MILLIS / 3}. */
    public static final long LEASE_MILLIS = 15_000;

    private final Database database;
    private final String self;
    private final LongSupplier clock;

    public NetworkRoles(Database database, String self, LongSupplier clock) {
        this.database = database;
        this.self = self;
        this.clock = clock;
    }

    /**
     * Takes {@code role} if nobody holds it or its lease ran out, renews it if this server holds it.
     *
     * @return whether this server holds it now
     */
    public boolean claim(String role) {
        long now = clock.getAsLong();
        boolean written = database.write(connection -> {
            try (PreparedStatement claim = connection.prepareStatement("""
                    INSERT INTO network_role (role, server, heartbeat_at) VALUES (?, ?, ?)
                    ON CONFLICT(role) DO UPDATE SET server = excluded.server, heartbeat_at = excluded.heartbeat_at
                    WHERE network_role.server = excluded.server OR network_role.heartbeat_at < ?""")) {
                claim.setString(1, role);
                claim.setString(2, self);
                claim.setLong(3, now);
                claim.setLong(4, now - LEASE_MILLIS);
                claim.executeUpdate();
            }
        });
        return written && holder(role).filter(self::equals).isPresent();
    }

    /**
     * As {@link #claim}, for a role that belongs to one server: the first server to claim it becomes its home and
     * no other server ever takes it over by itself — an owner moves it with {@link #moveHome}.
     */
    public boolean claimHome(String role) {
        long now = clock.getAsLong();
        boolean written = database.write(connection -> {
            try (PreparedStatement home = connection.prepareStatement(
                    "INSERT INTO network_role_home (role, server, since) VALUES (?, ?, ?) ON CONFLICT(role) DO NOTHING");
                 // The home is checked in the same statement that takes the lease, so a move in between cannot
                 // leave the old home holding it.
                 PreparedStatement claim = connection.prepareStatement("""
                    INSERT INTO network_role (role, server, heartbeat_at)
                    SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM network_role_home WHERE role = ? AND server = ?)
                    ON CONFLICT(role) DO UPDATE SET server = excluded.server, heartbeat_at = excluded.heartbeat_at
                    WHERE network_role.server = excluded.server OR network_role.heartbeat_at < ?""")) {
                home.setString(1, role);
                home.setString(2, self);
                home.setLong(3, now);
                home.executeUpdate();
                claim.setString(1, role);
                claim.setString(2, self);
                claim.setLong(3, now);
                claim.setString(4, role);
                claim.setString(5, self);
                claim.setLong(6, now - LEASE_MILLIS);
                claim.executeUpdate();
            }
        });
        return written && holder(role).filter(self::equals).isPresent();
    }

    /** The server {@code role} belongs to, if one claimed it as its home. */
    public Optional<String> home(String role) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT server FROM network_role_home WHERE role = ?")) {
                select.setString(1, role);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Optional.of(rows.getString(1)) : Optional.<String>empty();
                }
            }
        }).orElse(Optional.empty());
    }

    /** Gives {@code role} a new home — an owner's decision, never the code's. Its current lease ends at once. */
    public boolean moveHome(String role, String server) {
        return database.write(connection -> {
            try (PreparedStatement move = connection.prepareStatement("""
                    INSERT INTO network_role_home (role, server, since) VALUES (?, ?, ?)
                    ON CONFLICT(role) DO UPDATE SET server = excluded.server, since = excluded.since""");
                 PreparedStatement drop = connection.prepareStatement("DELETE FROM network_role WHERE role = ?")) {
                move.setString(1, role);
                move.setString(2, server);
                move.setLong(3, clock.getAsLong());
                move.executeUpdate();
                drop.setString(1, role);
                drop.executeUpdate();
            }
        });
    }

    /** Who holds {@code role} now — nobody when the lease ran out. */
    public Optional<String> holder(String role) {
        long since = clock.getAsLong() - LEASE_MILLIS;
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT server FROM network_role WHERE role = ? AND heartbeat_at >= ?")) {
                select.setString(1, role);
                select.setLong(2, since);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Optional.of(rows.getString(1)) : Optional.<String>empty();
                }
            }
        }).orElse(Optional.empty());
    }

    /** Gives {@code role} up at once, if this server holds it — at a clean shutdown. */
    public void release(String role) {
        database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM network_role WHERE role = ? AND server = ?")) {
                delete.setString(1, role);
                delete.setString(2, self);
                delete.executeUpdate();
            }
        });
    }
}
