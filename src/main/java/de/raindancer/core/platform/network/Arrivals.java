package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a player sent to another server is to land. The sending server writes it before the proxy moves the
 * player; the server they arrive on takes it — once, and only if it is still fresh and meant for it — and puts
 * them there. Without it a player sent to a warp on another server lands at that server's spawn.
 */
public final class Arrivals {

    /** Where to put somebody: a world of the arriving server, and a position in it. */
    public record Destination(String world, double x, double y, double z, float yaw, float pitch) {
    }

    private final Database database;

    public Arrivals(Database database) {
        this.database = database;
    }

    /** {@code player}, arriving on {@code server} before {@code expiresAt}, lands at {@code destination}. Replaces any earlier one. */
    public boolean expect(UUID player, String server, Destination destination, long expiresAt) {
        return database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO network_arrival (player, server, world, x, y, z, yaw, pitch, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(player) DO UPDATE SET server = excluded.server, world = excluded.world, x = excluded.x,
                        y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch,
                        expires_at = excluded.expires_at""")) {
                upsert.setString(1, player.toString());
                upsert.setString(2, server);
                upsert.setString(3, destination.world());
                upsert.setDouble(4, destination.x());
                upsert.setDouble(5, destination.y());
                upsert.setDouble(6, destination.z());
                upsert.setDouble(7, destination.yaw());
                upsert.setDouble(8, destination.pitch());
                upsert.setLong(9, expiresAt);
                upsert.executeUpdate();
            }
        });
    }

    /**
     * Takes where {@code player} is to land on {@code server}: gone from the database in the same step, so two
     * joins can never both apply it. Nothing when it was meant for another server or has run out — and then it
     * is left for nobody: an arrival somewhere else is that server's.
     */
    public Optional<Destination> take(UUID player, String server, long now) {
        boolean[] found = new boolean[1];
        Destination[] destination = new Destination[1];
        boolean ran = database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM network_arrival WHERE player = ? AND server = ? AND expires_at >= ?
                    RETURNING world, x, y, z, yaw, pitch""")) {
                delete.setString(1, player.toString());
                delete.setString(2, server);
                delete.setLong(3, now);
                try (ResultSet rows = delete.executeQuery()) {
                    if (rows.next()) {
                        found[0] = true;
                        destination[0] = new Destination(rows.getString(1), rows.getDouble(2), rows.getDouble(3),
                                rows.getDouble(4), (float) rows.getDouble(5), (float) rows.getDouble(6));
                    }
                }
            }
        });
        return ran && found[0] ? Optional.of(destination[0]) : Optional.empty();
    }

    /**
     * Takes back where {@code player} was to land on {@code server}, for a hop that seems not to have happened.
     *
     * @return true when it was still there (they have not arrived: now they never land there); false when it was
     *         already taken — they did arrive — or could not be removed
     */
    public boolean withdraw(UUID player, String server) {
        int[] removed = new int[1];
        boolean ran = database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM network_arrival WHERE player = ? AND server = ?")) {
                delete.setString(1, player.toString());
                delete.setString(2, server);
                removed[0] = delete.executeUpdate();
            }
        });
        return ran && removed[0] > 0;
    }

    /** Forgets arrivals that ran out — anybody may run it, any number of times. */
    public void prune(long now) {
        database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM network_arrival WHERE expires_at < ?")) {
                delete.setLong(1, now);
                delete.executeUpdate();
            }
        });
    }
}
