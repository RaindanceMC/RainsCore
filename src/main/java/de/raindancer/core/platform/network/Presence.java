package de.raindancer.core.platform.network;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Who is on which server of the network.
 *
 * <p>Every server writes its own arrivals and departures, announces them on the bus, and reads the whole
 * list again every few seconds. A player listed on a server whose heartbeat stopped is not online — a crash
 * leaves no ghosts. A departure only removes the row this server wrote: a player switching servers may be
 * written by the next server before the last one says they left.
 */
public final class Presence {

    private static final LogChannel log = Log.of("network");

    /** The topic arrivals, departures and vanishing are announced on, keyed by the player. */
    public static final String TOPIC = "presence";

    /** One player, where they are. */
    public record Here(UUID player, String name, String server, boolean vanished, long since) {
    }

    private final Database database;
    private final NetworkServers servers;
    private final LongSupplier clock;
    private final Map<UUID, Here> everyone = new ConcurrentHashMap<>();
    private volatile NetworkBus bus;

    public Presence(Database database, NetworkServers servers, LongSupplier clock) {
        this.database = database;
        this.servers = servers;
        this.clock = clock;
    }

    public void connect(NetworkBus network) {
        this.bus = network;
        if (network != null) {
            network.subscribe(TOPIC, event -> {
                try {
                    refresh(UUID.fromString(event.key()));
                } catch (IllegalArgumentException notAPlayer) {
                    log.warn("A presence event named no player: '{}'", event.key());
                }
            });
            network.onResync(this::refreshAll);
        }
    }

    // ------------------------------------------------------------------------------ this server

    /** Somebody arrived here. Off the server's threads. */
    public void joined(UUID player, String name, boolean vanished) {
        long now = clock.getAsLong();
        Here here = new Here(player, name, servers.self(), vanished, now);
        everyone.put(player, here);
        boolean written = database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO network_presence (player, name, name_lower, server, vanished, since) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(player) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower,
                        server = excluded.server, vanished = excluded.vanished, since = excluded.since""")) {
                upsert.setString(1, player.toString());
                upsert.setString(2, name);
                upsert.setString(3, name.toLowerCase(Locale.ROOT));
                upsert.setString(4, servers.self());
                upsert.setInt(5, vanished ? 1 : 0);
                upsert.setLong(6, now);
                upsert.executeUpdate();
            }
        });
        announce(written, player);
    }

    /** Somebody left here. Only this server's row goes: they may already be on the next one. Off the server's threads. */
    public void left(UUID player) {
        everyone.computeIfPresent(player, (key, here) -> here.server().equals(servers.self()) ? null : here);
        boolean written = database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM network_presence WHERE player = ? AND server = ?")) {
                delete.setString(1, player.toString());
                delete.setString(2, servers.self());
                delete.executeUpdate();
            }
        });
        announce(written, player);
    }

    /** Somebody here vanished or reappeared; remembered for the next server they go to. Off the server's threads. */
    public void vanished(UUID player, boolean vanished) {
        everyone.computeIfPresent(player, (key, here) -> new Here(here.player(), here.name(), here.server(), vanished, here.since()));
        boolean written = database.write(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                         "UPDATE network_presence SET vanished = ? WHERE player = ?");
                 PreparedStatement remember = connection.prepareStatement(vanished
                         ? "INSERT INTO network_vanish (player, since) VALUES (?, ?) ON CONFLICT(player) DO NOTHING"
                         : "DELETE FROM network_vanish WHERE player = ?")) {
                update.setInt(1, vanished ? 1 : 0);
                update.setString(2, player.toString());
                update.executeUpdate();
                remember.setString(1, player.toString());
                if (vanished) {
                    remember.setLong(2, clock.getAsLong());
                }
                remember.executeUpdate();
            }
        });
        announce(written, player);
    }

    /** Whether somebody was vanished when they left their last server — to keep them so as they arrive here. */
    public boolean wasVanished(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT 1 FROM network_vanish WHERE player = ?")) {
                select.setString(1, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next();
                }
            }
        }).orElse(false);
    }

    /** Takes every row this server wrote — at shutdown, or after a crash on the way back up. */
    public void clearThisServer() {
        everyone.values().removeIf(here -> here.server().equals(servers.self()));
        database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM network_presence WHERE server = ?")) {
                delete.setString(1, servers.self());
                delete.executeUpdate();
            }
        });
    }

    private void announce(boolean written, UUID player) {
        NetworkBus network = bus;
        if (written && network != null) {
            network.publish(TOPIC, player.toString());
        }
    }

    // ------------------------------------------------------------------------------ reading

    /** Reads one player again. Off the server's threads. */
    public void refresh(UUID player) {
        database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player, name, server, vanished, since FROM network_presence WHERE player = ?")) {
                select.setString(1, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Optional.of(read(rows)) : Optional.<Here>empty();
                }
            }
        }).ifPresent(found -> {
            if (found.isPresent()) {
                everyone.put(player, found.get());
            } else {
                everyone.remove(player);
            }
        });
    }

    /** Reads everybody again — every few seconds, and when the bus may have missed something. Off the server's threads. */
    public void refreshAll() {
        database.read(connection -> {
            Map<UUID, Here> found = new LinkedHashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player, name, server, vanished, since FROM network_presence");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    try {
                        Here here = read(rows);
                        found.put(here.player(), here);
                    } catch (IllegalArgumentException notAPlayer) {
                        log.warn("A presence row named no player; skipped.");
                    }
                }
            }
            return found;
        }).ifPresent(found -> {
            everyone.keySet().retainAll(found.keySet());
            everyone.putAll(found);
        });
    }

    private static Here read(ResultSet rows) throws java.sql.SQLException {
        return new Here(UUID.fromString(rows.getString("player")), rows.getString("name"), rows.getString("server"),
                rows.getInt("vanished") != 0, rows.getLong("since"));
    }

    /** Everybody on an online server, as last read. {@code withVanished} only for those who may see them. */
    public List<Here> everyone(boolean withVanished) {
        List<Here> found = new ArrayList<>();
        for (Here here : everyone.values()) {
            if (servers.isOnline(here.server()) && (withVanished || !here.vanished())) {
                found.add(here);
            }
        }
        found.sort(java.util.Comparator.comparing(Here::name, String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    /** Where somebody is, if they are on an online server. Vanished players are found only {@code withVanished}. */
    public Optional<Here> where(UUID player, boolean withVanished) {
        Here here = player == null ? null : everyone.get(player);
        return here != null && servers.isOnline(here.server()) && (withVanished || !here.vanished())
                ? Optional.of(here) : Optional.empty();
    }

    /** Somebody online by name, any case. */
    public Optional<Here> named(String name, boolean withVanished) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return everyone(withVanished).stream().filter(here -> here.name().toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }

    /** Everybody online, by the server they are on. */
    public Map<String, List<Here>> byServer(boolean withVanished) {
        Map<String, List<Here>> grouped = new LinkedHashMap<>();
        for (Here here : everyone(withVanished)) {
            grouped.computeIfAbsent(here.server(), ignored -> new ArrayList<>()).add(here);
        }
        return grouped;
    }
}
