package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * When the network last and first saw somebody — also when they are offline, and whichever server it was on.
 * Reads what {@code Socials} (network_seen) and {@link FirstSeen} (network_first_seen) write; Bukkit's own
 * {@code getLastSeen}/{@code getFirstPlayed} only know this one server. Every call is database I/O: never on the
 * server thread.
 */
public final class PlayerHistory {

    /** @param online whether they were online at {@code at} (and so, most likely, still are) */
    public record Seen(String name, String server, long at, boolean online) {
    }

    private final Database database;

    public PlayerHistory(Database database) {
        this.database = database;
    }

    public Optional<Seen> lastSeen(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT name, server, at, online FROM network_seen WHERE player = ?")) {
                query.setString(1, player.toString());
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? new Seen(row.getString(1), row.getString(2), row.getLong(3), row.getInt(4) == 1) : null;
                }
            }
        });
    }

    /** When the network first saw them; empty for somebody who has not joined since this was recorded. */
    public Optional<Long> firstSeen(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT at FROM network_first_seen WHERE player = ?")) {
                query.setString(1, player.toString());
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? row.getLong(1) : null;
                }
            }
        });
    }

    /** The player last seen under this name, in any case. */
    public Optional<UUID> idOf(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT player FROM network_seen WHERE name_lower = ? ORDER BY at DESC")) {
                query.setString(1, name.strip().toLowerCase(Locale.ROOT));
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? UUID.fromString(row.getString(1)) : null;
                }
            }
        });
    }
}
