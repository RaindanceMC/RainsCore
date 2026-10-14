package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

/**
 * Namespaces of players' persistent data that follow them across the network: written by the server a player
 * leaves (and every so often while they play), read by the server they arrive on. The bytes are the game's
 * own serialisation of that namespace; this class only keeps them.
 */
public final class SharedPlayerData {

    /** The topic a fresh save is announced on, keyed by the player, carrying the namespace. */
    public static final String TOPIC = "shared-data";

    private final Database database;

    public SharedPlayerData(Database database) {
        this.database = database;
    }

    /** Keeps a player's namespace — unless a newer one is already kept. Off the server's threads. */
    public boolean save(UUID player, String namespace, byte[] data, long at) {
        return database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO shared_player_data (player, namespace, data, updated_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT(player, namespace) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at
                    WHERE excluded.updated_at >= shared_player_data.updated_at""")) {
                upsert.setString(1, player.toString());
                upsert.setString(2, namespace);
                upsert.setBytes(3, data);
                upsert.setLong(4, at);
                upsert.executeUpdate();
            }
        });
    }

    /** What is kept of a player's namespace, and when it was written. Off the server's threads. */
    public Optional<Kept> read(UUID player, String namespace) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT data, updated_at FROM shared_player_data WHERE player = ? AND namespace = ?")) {
                select.setString(1, player.toString());
                select.setString(2, namespace);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Optional.of(new Kept(rows.getBytes(1), rows.getLong(2))) : Optional.<Kept>empty();
                }
            }
        }).orElse(Optional.empty());
    }

    public record Kept(byte[] data, long at) {
    }
}
