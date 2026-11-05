package de.raindancer.core.moderation.whitelist;

import de.raindancer.core.data.sql.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The network's whitelist in the core database — the one list RainsNetworkProxy checks at the door. Database I/O,
 * never on the server thread. Every answer is empty when the database could not be asked, so nobody mistakes an
 * outage for "nobody is on it".
 */
public final class WhitelistTable {

    /** The marker row for the old network document, which is brought in once for the whole network. */
    static final String DOCUMENT = "(document)";

    private final Database database;
    private final LongSupplier clock;

    public WhitelistTable(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    public Optional<Map<UUID, String>> all() {
        return database.read(connection -> {
            Map<UUID, String> players = new LinkedHashMap<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT player, name FROM whitelist ORDER BY added_at");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    try {
                        players.put(UUID.fromString(rows.getString(1)), rows.getString(2));
                    } catch (IllegalArgumentException notAnId) {
                        // written by hand; nobody can join as it
                    }
                }
            }
            return players;
        });
    }

    public Optional<Boolean> contains(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT 1 FROM whitelist WHERE player = ?")) {
                select.setString(1, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next();
                }
            }
        });
    }

    /** @return whether they were newly put on it; empty if it could not be written */
    public Optional<Boolean> add(UUID player, String name, String by) {
        int[] changed = new int[1];
        boolean written = database.write(connection -> changed[0] = insert(connection, player, name, by));
        return written ? Optional.of(changed[0] > 0) : Optional.empty();
    }

    /** @return whether they were on it; empty if it could not be written */
    public Optional<Boolean> remove(UUID player) {
        int[] changed = new int[1];
        boolean written = database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM whitelist WHERE player = ?")) {
                delete.setString(1, player.toString());
                changed[0] = delete.executeUpdate();
            }
        });
        return written ? Optional.of(changed[0] > 0) : Optional.empty();
    }

    /**
     * What was whitelisted before this table, brought in without bringing back anybody taken off since: the old
     * network document once for the whole network (its servers count as brought in — their lists were in it), and
     * {@code server}'s own list once for that server.
     */
    public boolean bringIn(String server, Map<UUID, String> document, Collection<String> documentServers,
                           Map<UUID, String> own) {
        return database.write(connection -> {
            if (join(connection, DOCUMENT)) {
                for (Map.Entry<UUID, String> player : document.entrySet()) {
                    insert(connection, player.getKey(), player.getValue(), "document");
                }
                for (String joined : documentServers) {
                    join(connection, joined);
                }
            }
            if (join(connection, server)) {
                for (Map.Entry<UUID, String> player : own.entrySet()) {
                    insert(connection, player.getKey(), player.getValue(), "server:" + server);
                }
            }
        });
    }

    private boolean join(Connection connection, String server) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO whitelist_joined (server, at) VALUES (?, ?) ON CONFLICT(server) DO NOTHING")) {
            insert.setString(1, server);
            insert.setLong(2, clock.getAsLong());
            return insert.executeUpdate() > 0;
        }
    }

    private int insert(Connection connection, UUID player, String name, String by) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO whitelist (player, name, added_at, added_by) VALUES (?, ?, ?, ?)
                ON CONFLICT(player) DO NOTHING""")) {
            insert.setString(1, player.toString());
            insert.setString(2, name == null ? "" : name);
            insert.setLong(3, clock.getAsLong());
            insert.setString(4, by);
            return insert.executeUpdate();
        }
    }
}
