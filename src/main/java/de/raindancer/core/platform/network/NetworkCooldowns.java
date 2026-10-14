package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.util.Cooldowns;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Cooldowns that follow the player across the network ({@link Cooldowns#shared}): every go is written to the
 * database, and a player arriving on a server has their waits read in — so switching servers is not a way past
 * one. Reads stay in memory; only a go and an arrival touch the database.
 */
public final class NetworkCooldowns {

    /** Waits older than this are thrown away; no cooldown in any plugin is anywhere near a day. */
    public static final long KEPT_MILLIS = 24 * 60 * 60_000L;

    private static volatile NetworkCooldowns installed;

    private final Database database;
    private final LongSupplier clock;
    private final Executor writer;
    private final Map<String, Cooldowns<UUID>> named = new ConcurrentHashMap<>();

    /** @param writer runs the database writes — off the server's threads */
    public NetworkCooldowns(Database database, LongSupplier clock, Executor writer) {
        this.database = database;
        this.clock = clock;
        this.writer = writer;
    }

    /** Core's, on a network. Set once by Core; empty on a server of its own. */
    public static void install(NetworkCooldowns cooldowns) {
        installed = cooldowns;
    }

    public static Optional<NetworkCooldowns> installed() {
        return Optional.ofNullable(installed);
    }

    /** The cooldown called {@code name}; one per name — asked again, the same one. */
    public Cooldowns<UUID> of(String name, LongSupplier clock) {
        return named.computeIfAbsent(name, key -> new Cooldowns<>(clock,
                (player, at) -> writer.execute(() -> save(key, player, at))));
    }

    private void save(String name, UUID player, long at) {
        database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO network_cooldown (name, player, last_used) VALUES (?, ?, ?)
                    ON CONFLICT(name, player) DO UPDATE SET last_used = excluded.last_used""")) {
                upsert.setString(1, name);
                upsert.setString(2, player.toString());
                upsert.setLong(3, at);
                upsert.executeUpdate();
            }
        });
    }

    /** A player arrived on this server: their waits from anywhere are read in. Off the server's threads. */
    public void arrived(UUID player) {
        database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT name, last_used FROM network_cooldown WHERE player = ?")) {
                select.setString(1, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        Cooldowns<UUID> cooldown = named.get(rows.getString(1));
                        if (cooldown != null) {
                            cooldown.restore(player, rows.getLong(2));
                        }
                    }
                }
            }
            return Boolean.TRUE;
        });
    }

    /** Throws away waits long over. A chore: one server runs it. @return how many */
    public int prune() {
        int[] removed = new int[1];
        database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM network_cooldown WHERE last_used < ?")) {
                delete.setLong(1, clock.getAsLong() - KEPT_MILLIS);
                removed[0] = delete.executeUpdate();
            }
        });
        return removed[0];
    }
}
