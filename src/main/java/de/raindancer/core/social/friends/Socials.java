package de.raindancer.core.social.friends;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.party.Parties;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Friends and parties at work on the network: friends are told when somebody joins the network or moves to another
 * server ("Lilly is now playing Capture the Flag"), a party follows its leader from server to server, and somebody gone
 * from the network leaves their party. Every server runs one; each event is handled by the server it happens on.
 */
public final class Socials {

    public enum Arrival { JOINED, SWITCHED }

    /** Arriving this soon after leaving another server is moving between servers, not joining the network. */
    public static final long SWITCH_MILLIS = 15_000;
    /** Gone this long from every server is gone from the network: their party goes on without them. */
    static final long GONE_SECONDS = 20;
    static final String FOLLOW = "party-follow";

    private static volatile Socials installed;

    private final Plugin plugin;
    private final Database database;
    private final Friends friends;
    private final Parties parties;
    private final Presence presence;
    private final NetworkPlayers players;
    private final NetworkBus bus;
    private final String self;
    private final Supplier<String> serverName;
    private final LongSupplier clock;

    public Socials(Plugin plugin, Database database, Presence presence, NetworkPlayers players, NetworkBus bus,
                   String self, Supplier<String> serverName, LongSupplier clock) {
        this.plugin = plugin;
        this.database = database;
        this.friends = new Friends(database, clock);
        this.parties = new Parties(database, clock);
        this.presence = presence;
        this.players = players;
        this.bus = bus;
        this.self = self;
        this.serverName = serverName;
        this.clock = clock;
    }

    public static Optional<Socials> installed() {
        return Optional.ofNullable(installed);
    }

    /** Becomes the network's friends and parties here, and listens for parties following their leader. */
    public void install() {
        installed = this;
        if (bus != null) {
            bus.subscribe(FOLLOW, event -> follow(event.key(), event.payload()));
        }
    }

    public static void uninstall() {
        installed = null;
    }

    public Friends friends() {
        return friends;
    }

    public Parties parties() {
        return parties;
    }

    public Presence presence() {
        return presence;
    }

    public NetworkPlayers players() {
        return players;
    }

    /** Whether arriving now, after last leaving {@code lastServer} at {@code lastAt}, is joining or moving. */
    static Arrival arrival(String lastServer, long lastAt, boolean stillOnline, long now, String self) {
        if (lastServer == null || lastServer.equals(self)) {
            return Arrival.JOINED;
        }
        // the old server may not have written that they left yet: still online there is moving from there
        return stillOnline || now - lastAt <= SWITCH_MILLIS ? Arrival.SWITCHED : Arrival.JOINED;
    }

    /** Somebody arrived here. Off the server thread. */
    public void arrived(UUID player, String name) {
        long now = clock.getAsLong();
        Optional<String[]> last = lastSeen(player);
        Arrival arrival = last.map(seen -> arrival(seen[0], Long.parseLong(seen[1]), "1".equals(seen[3]), now, self))
                .orElse(Arrival.JOINED);
        seen(player, name, now, true);
        String where = serverName.get();
        for (UUID friend : friends.of(player)) {
            if (arrival == Arrival.JOINED) {
                players.tell(friend, "friends.joined-network", "name", name);
            } else {
                players.tell(friend, "friends.now-playing", "name", name, "server", where);
            }
        }
        int asked = friends.requestsTo(player).size();
        if (asked > 0) {
            players.tell(player, "friends.requests-waiting", "count", String.valueOf(asked));
        }
        if (arrival == Arrival.SWITCHED && bus != null) {
            parties.of(player).filter(party -> party.leader().equals(player))
                    .ifPresent(party -> bus.publish(FOLLOW, party.id(), self));
        }
    }

    /** Somebody left here. Off the server thread. */
    public void left(UUID player) {
        long now = clock.getAsLong();
        // Only if the row is still this server's: arriving on the next one may already have been written.
        lastSeen(player).filter(seen -> seen[0].equals(self)).ifPresent(seen -> seen(player, seen[2], now, false));
        Scheduling.asyncLater(plugin, GONE_SECONDS, TimeUnit.SECONDS, () -> {
            if (presence.where(player, true).isPresent()) {
                return;
            }
            parties.of(player).ifPresent(party -> {
                if (parties.leave(player) == Parties.Outcome.LEFT) {
                    String name = lastName(player).orElse("?");
                    party.members().stream().filter(member -> !member.equals(player))
                            .forEach(member -> players.tell(member, "party.member-gone", "name", name));
                }
            });
        });
    }

    /** The members of {@code party} here who are not where its leader went follow. */
    private void follow(String party, String server) {
        if (server == null || server.equals(self)) {
            return;
        }
        Scheduling.async(plugin, () -> {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                parties.of(online.getUniqueId()).filter(found -> found.id().equals(party))
                        .filter(found -> !found.leader().equals(online.getUniqueId()))
                        .ifPresent(found -> Scheduling.entity(plugin, online, () -> {
                            players.tell(online.getUniqueId(), "party.following");
                            ProxyTransfer.send(plugin, online, server);
                        }));
            }
        });
    }

    /** Somebody by name, online or not, as last seen anywhere on the network. */
    public Optional<UUID> idOf(String name) {
        Optional<UUID> online = presence.named(name, false).map(Presence.Here::player);
        if (online.isPresent()) {
            return online;
        }
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT player FROM network_seen WHERE name_lower = ? ORDER BY at DESC")) {
                query.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? Optional.of(UUID.fromString(rows.getString(1))) : Optional.<UUID>empty();
                }
            }
        }).flatMap(found -> found);
    }

    public Optional<String> lastName(UUID player) {
        Optional<String> online = presence.where(player, true).map(Presence.Here::name);
        return online.isPresent() ? online : lastSeen(player).map(seen -> seen[2]);
    }

    /** {server, at, name, online} of the last time somebody was seen arriving or leaving. */
    private Optional<String[]> lastSeen(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT server, at, name, online FROM network_seen WHERE player = ?")) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next()
                            ? Optional.of(new String[]{rows.getString(1), String.valueOf(rows.getLong(2)), rows.getString(3),
                            String.valueOf(rows.getInt(4))})
                            : Optional.<String[]>empty();
                }
            }
        }).flatMap(found -> found);
    }

    private void seen(UUID player, String name, long now, boolean online) {
        database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO network_seen (player, name, name_lower, server, at, online) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(player) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower,
                        server = excluded.server, at = excluded.at, online = excluded.online""")) {
                upsert.setString(1, player.toString());
                upsert.setString(2, name);
                upsert.setString(3, name.toLowerCase(Locale.ROOT));
                upsert.setString(4, self);
                upsert.setLong(5, now);
                upsert.setInt(6, online ? 1 : 0);
                upsert.executeUpdate();
            }
        });
    }
}
