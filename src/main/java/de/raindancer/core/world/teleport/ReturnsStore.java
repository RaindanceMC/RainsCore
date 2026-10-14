package de.raindancer.core.world.teleport;

import de.raindancer.core.data.sql.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** {@link Returns} in the network's database: where /back takes each player, and on which server. */
public final class ReturnsStore {

    /** Off the network this long, and where they were is forgotten — /back means "where I just was". */
    public static final long GONE_MILLIS = 2 * 60_000L;

    private final Database database;
    private final LongSupplier clock;

    public ReturnsStore(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    void save(UUID player, String server, Waypoint where) {
        database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO network_return (player, server, world, x, y, z, yaw, pitch, cause, at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(player) DO UPDATE SET server = excluded.server, world = excluded.world, x = excluded.x,
                        y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch,
                        cause = excluded.cause, at = excluded.at""")) {
                upsert.setString(1, player.toString());
                upsert.setString(2, server);
                upsert.setString(3, where.world());
                upsert.setDouble(4, where.x());
                upsert.setDouble(5, where.y());
                upsert.setDouble(6, where.z());
                upsert.setDouble(7, where.yaw());
                upsert.setDouble(8, where.pitch());
                upsert.setString(9, where.cause().name());
                upsert.setLong(10, clock.getAsLong());
                upsert.executeUpdate();
            }
        });
    }

    Optional<Returns.Elsewhere> load(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT server, world, x, y, z, yaw, pitch, cause, at FROM network_return WHERE player = ?")) {
                select.setString(1, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    Waypoint.Cause cause;
                    try {
                        cause = Waypoint.Cause.valueOf(rows.getString(8));
                    } catch (IllegalArgumentException unknown) {
                        cause = Waypoint.Cause.TELEPORT;
                    }
                    return new Returns.Elsewhere(rows.getString(1), new Waypoint(rows.getString(2), rows.getDouble(3),
                            rows.getDouble(4), rows.getDouble(5), (float) rows.getDouble(6), (float) rows.getDouble(7),
                            cause, rows.getLong(9)));
                }
            }
        });
    }

    void delete(UUID player) {
        database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM network_return WHERE player = ?")) {
                delete.setString(1, player.toString());
                delete.executeUpdate();
            }
        });
    }

    /** Forgets everybody who has been off the network for {@link #GONE_MILLIS}. A chore: one server runs it. */
    public void prune() {
        database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM network_return WHERE at < ?
                    AND player NOT IN (SELECT player FROM network_presence)""")) {
                delete.setLong(1, clock.getAsLong() - GONE_MILLIS);
                delete.executeUpdate();
            }
        });
    }
}
