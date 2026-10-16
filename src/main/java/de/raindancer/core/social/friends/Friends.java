package de.raindancer.core.social.friends;

import de.raindancer.core.data.sql.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Friends across the network, kept in Core's database: a request, then a yes or a no. Asking somebody who already
 * asked you is a yes. Off the server thread — every call is a query.
 */
public final class Friends {

    /** More would be a list nobody reads. */
    public static final int MOST = 100;

    public enum Outcome { SENT, ACCEPTED, DENIED, REMOVED, ALREADY_FRIENDS, ALREADY_ASKED, NO_REQUEST, NOT_FRIENDS, SELF, TOO_MANY, FAILED }

    private final Database database;
    private final LongSupplier clock;

    public Friends(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    public Outcome ask(UUID asker, UUID asked) {
        if (asker.equals(asked)) {
            return Outcome.SELF;
        }
        if (are(asker, asked)) {
            return Outcome.ALREADY_FRIENDS;
        }
        if (requestsTo(asker).contains(asked)) {
            return accept(asker, asked);
        }
        if (requestsTo(asked).contains(asker)) {
            return Outcome.ALREADY_ASKED;
        }
        if (of(asker).size() >= MOST) {
            return Outcome.TOO_MANY;
        }
        return database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO friend_request (asker, asked, at) VALUES (?, ?, ?)")) {
                insert.setString(1, asker.toString());
                insert.setString(2, asked.toString());
                insert.setLong(3, clock.getAsLong());
                insert.executeUpdate();
            }
        }) ? Outcome.SENT : Outcome.FAILED;
    }

    /** {@code player} says yes to {@code asker}. */
    public Outcome accept(UUID player, UUID asker) {
        if (!requestsTo(player).contains(asker)) {
            return Outcome.NO_REQUEST;
        }
        if (of(player).size() >= MOST || of(asker).size() >= MOST) {
            return Outcome.TOO_MANY;
        }
        long now = clock.getAsLong();
        return database.write(connection -> {
            dropRequests(connection, player, asker);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO friend (player, friend, since) VALUES (?, ?, ?)")) {
                for (UUID[] pair : new UUID[][]{{player, asker}, {asker, player}}) {
                    insert.setString(1, pair[0].toString());
                    insert.setString(2, pair[1].toString());
                    insert.setLong(3, now);
                    insert.executeUpdate();
                }
            }
        }) ? Outcome.ACCEPTED : Outcome.FAILED;
    }

    public Outcome deny(UUID player, UUID asker) {
        if (!requestsTo(player).contains(asker)) {
            return Outcome.NO_REQUEST;
        }
        return database.write(connection -> dropRequests(connection, player, asker)) ? Outcome.DENIED : Outcome.FAILED;
    }

    public Outcome remove(UUID player, UUID friend) {
        if (!are(player, friend)) {
            return Outcome.NOT_FRIENDS;
        }
        return database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM friend WHERE (player = ? AND friend = ?) OR (player = ? AND friend = ?)")) {
                delete.setString(1, player.toString());
                delete.setString(2, friend.toString());
                delete.setString(3, friend.toString());
                delete.setString(4, player.toString());
                delete.executeUpdate();
            }
        }) ? Outcome.REMOVED : Outcome.FAILED;
    }

    public boolean are(UUID player, UUID other) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT 1 FROM friend WHERE player = ? AND friend = ?")) {
                query.setString(1, player.toString());
                query.setString(2, other.toString());
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next();
                }
            }
        }).orElse(false);
    }

    /** Oldest friendship first. */
    public List<UUID> of(UUID player) {
        return uuids("SELECT friend FROM friend WHERE player = ? ORDER BY since, friend", player);
    }

    /** Who asked {@code player}, oldest first. */
    public List<UUID> requestsTo(UUID player) {
        return uuids("SELECT asker FROM friend_request WHERE asked = ? ORDER BY at, asker", player);
    }

    private List<UUID> uuids(String sql, UUID player) {
        return database.read(connection -> {
            List<UUID> found = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        found.add(UUID.fromString(rows.getString(1)));
                    }
                }
            }
            return found;
        }).orElse(List.of());
    }

    private static void dropRequests(java.sql.Connection connection, UUID a, UUID b) throws java.sql.SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM friend_request WHERE (asker = ? AND asked = ?) OR (asker = ? AND asked = ?)")) {
            delete.setString(1, a.toString());
            delete.setString(2, b.toString());
            delete.setString(3, b.toString());
            delete.setString(4, a.toString());
            delete.executeUpdate();
        }
    }
}
