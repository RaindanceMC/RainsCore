package de.raindancer.core.data.inventory;

import de.raindancer.core.data.sql.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Every server's copy of every player's inventory, in the network's database (2.0.0).
 *
 * <p>Each server writes its own players' inventories — on quit, when the world is saved, every few minutes,
 * and at shutdown — so staff can look at a player's inventory on any server from any server, and so an
 * inventory survives the loss of the server's files. The game's own player file stays the copy that is
 * loaded on join: inventories are each server's own, and nothing here is applied to a player. Syncing them
 * between servers is a later switch, not a side effect.
 *
 * <p>Items are kept as the game serialises them ({@code ItemStack.serializeItemsAsBytes}); this class only
 * stores bytes, so it needs no server to be tested.
 */
public final class SavedInventories {

    /** One server's copy of one player's inventory. */
    public record Saved(String server, UUID player, long savedAt, byte[] contents, byte[] enderChest,
                        int level, float progress, double health, int food, float saturation, String effects) {
    }

    private final Database database;
    private final LongSupplier clock;

    public SavedInventories(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    /**
     * Writes a server's copy, unless a newer one is already there — a save that queued behind a later one
     * must not put the older inventory back. Off the server's threads.
     */
    public boolean save(Saved saved) {
        if (database == null || !database.isUsable() || saved == null) {
            return false;
        }
        return database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO saved_inventory (server, player, saved_at, contents, ender_chest, level, progress,
                                                 health, food, saturation, effects)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(server, player) DO UPDATE SET saved_at = excluded.saved_at,
                        contents = excluded.contents, ender_chest = excluded.ender_chest, level = excluded.level,
                        progress = excluded.progress, health = excluded.health, food = excluded.food,
                        saturation = excluded.saturation, effects = excluded.effects
                    WHERE excluded.saved_at >= saved_inventory.saved_at""")) {
                upsert.setString(1, saved.server());
                upsert.setString(2, saved.player().toString());
                upsert.setLong(3, saved.savedAt());
                upsert.setBytes(4, saved.contents());
                upsert.setBytes(5, saved.enderChest());
                upsert.setInt(6, saved.level());
                upsert.setDouble(7, saved.progress());
                upsert.setDouble(8, saved.health());
                upsert.setInt(9, saved.food());
                upsert.setDouble(10, saved.saturation());
                upsert.setString(11, saved.effects() == null ? "" : saved.effects());
                upsert.executeUpdate();
            }
        });
    }

    /** The copy {@code server} keeps of {@code player}. */
    public Optional<Saved> on(String server, UUID player) {
        return query("WHERE server = ? AND player = ?", server, player).stream().findFirst();
    }

    /** Every server's copy of {@code player}, newest first. */
    public List<Saved> everywhere(UUID player) {
        return query("WHERE player = ? ORDER BY saved_at DESC", null, player);
    }

    /** The copy written most recently, wherever that was. */
    public Optional<Saved> latest(UUID player) {
        return everywhere(player).stream().findFirst();
    }

    public long now() {
        return clock.getAsLong();
    }

    private List<Saved> query(String where, String server, UUID player) {
        if (database == null || !database.isUsable() || player == null) {
            return List.of();
        }
        return database.read(connection -> {
            List<Saved> found = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT server, player, saved_at, contents, ender_chest, level, progress, health, food, saturation,
                           effects FROM saved_inventory""" + " " + where)) {
                int at = 1;
                if (server != null) {
                    select.setString(at++, server);
                }
                select.setString(at, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        found.add(read(rows));
                    }
                }
            }
            return found;
        }).orElse(List.of());
    }

    private static Saved read(ResultSet rows) throws SQLException {
        return new Saved(rows.getString("server"), UUID.fromString(rows.getString("player")), rows.getLong("saved_at"),
                rows.getBytes("contents"), rows.getBytes("ender_chest"), rows.getInt("level"),
                (float) rows.getDouble("progress"), rows.getDouble("health"), rows.getInt("food"),
                (float) rows.getDouble("saturation"), rows.getString("effects"));
    }
}
