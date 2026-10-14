package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.Database;

import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Whether a player joining is new to the network — not merely new to this server, which Bukkit's
 * {@code hasPlayedBefore} answers: a welcome or the rules shown once per player, not once per server.
 */
public final class FirstSeen {

    private final Database database;
    private final LongSupplier clock;

    public FirstSeen(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    /**
     * Notes the player as seen. Off the server's threads.
     *
     * @param knownBefore whether they played before this was recorded anywhere (here, or by their playtime) —
     *                    then they are not new, though there is no row yet
     * @return true exactly once per player on the whole network: for the server that saw them first
     */
    public boolean arrived(UUID player, String server, boolean knownBefore) {
        int[] inserted = new int[1];
        boolean written = database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO network_first_seen (player, server, at) VALUES (?, ?, ?) ON CONFLICT(player) DO NOTHING")) {
                insert.setString(1, player.toString());
                insert.setString(2, server);
                insert.setLong(3, clock.getAsLong());
                inserted[0] = insert.executeUpdate();
            }
        });
        return written && inserted[0] == 1 && !knownBefore;
    }
}
