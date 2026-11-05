package de.raindancer.core.moderation.whitelist;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One whitelist for the network, kept in the core database ({@link WhitelistTable}) — the list RainsNetworkProxy
 * checks at the door (2.38.0).
 *
 * <p>Every server that takes part mirrors it into its vanilla whitelist, so {@code /whitelist list} and other plugins'
 * {@code isWhitelisted} stay right, and whatever changes that vanilla list — {@code /whitelist add|remove}, a plugin —
 * is written to the network's ({@link #changedHere}). A server that does not take part keeps its own list. What was
 * whitelisted before (the network document of 2.x, a server's own list) is brought in once.
 */
public final class NetworkWhitelist {

    private static final LogChannel log = Log.of("whitelist");

    /** The server's own whitelist. */
    public interface Server {
        Map<UUID, String> whitelisted();

        /** Puts somebody on or takes them off — on the server's own thread, soon. */
        void whitelist(UUID player, String name, boolean on);

        /** Puts somebody on before returning — for a player at the door, on a login thread. */
        void whitelistNow(UUID player, String name);
    }

    private final WhitelistTable table;
    private final YamlStore formerDocument;
    private final String serverId;
    private final Server server;
    private final Runnable announce;
    private volatile boolean taking = true;
    /** True while this class itself changes the server's list, so the events that causes are not written back. */
    private boolean applying;
    /** Changed on this server's list and not written yet: a reload in between must not undo them. */
    private final Set<UUID> unwritten = ConcurrentHashMap.newKeySet();

    /**
     * @param formerDocument the 2.x network document, brought in once; null for none
     * @param announce       tells the other servers the list changed (after the write)
     */
    public NetworkWhitelist(WhitelistTable table, YamlStore formerDocument, String serverId, Server server,
                            Runnable announce) {
        this.table = table;
        this.formerDocument = formerDocument;
        this.serverId = serverId == null || serverId.isBlank() ? "server" : serverId;
        this.server = server;
        this.announce = announce;
    }

    /** Whether this server takes part. Switching it on brings the list in at once. */
    public void taking(boolean on) {
        boolean was = taking;
        taking = on;
        if (on && !was) {
            start();
        }
    }

    public boolean taking() {
        return taking;
    }

    /** Brings in what was whitelisted before (once), then makes this server's list equal to the network's. */
    public synchronized void start() {
        if (!taking) {
            return;
        }
        Map<UUID, String> documentPlayers = Map.of();
        List<String> documentServers = List.of();
        Optional<YamlConfiguration> document = formerDocument == null ? Optional.empty() : formerDocument.readIfReadable();
        if (document.isPresent()) {
            documentPlayers = players(document.get());
            documentServers = document.get().getStringList("servers");
        }
        Map<UUID, String> mine = server.whitelisted();
        if (table.bringIn(serverId, documentPlayers, documentServers, mine)) {
            reload();
        } else {
            log.warn("The network's whitelist could not be reached; this server keeps its own list until it can.");
        }
    }

    /** Makes this server's list equal to the network's. */
    public synchronized void reload() {
        if (!taking) {
            return;
        }
        Optional<Map<UUID, String>> read = table.all();
        if (read.isEmpty()) {
            return;
        }
        Map<UUID, String> wanted = read.get();
        Map<UUID, String> mine = server.whitelisted();
        applying = true;
        try {
            wanted.forEach((player, name) -> {
                if (!mine.containsKey(player) && !unwritten.contains(player)) {
                    server.whitelist(player, name, true);
                }
            });
            mine.forEach((player, name) -> {
                if (!wanted.containsKey(player) && !unwritten.contains(player)) {
                    server.whitelist(player, name, false);
                }
            });
        } finally {
            applying = false;
        }
    }

    /** This server's list just changed and {@link #changedHere} will follow: until then a reload leaves them alone. */
    public void heard(UUID player) {
        if (taking && !applying && player != null) {
            unwritten.add(player);
        }
    }

    /** Somebody was put on or taken off this server's whitelist — by /whitelist, a plugin, anything. */
    public synchronized void changedHere(UUID player, String name, boolean on) {
        if (player == null) {
            return;
        }
        try {
            if (!taking || applying) {
                return;
            }
            Optional<Boolean> changed = on ? table.add(player, name, "server:" + serverId) : table.remove(player);
            if (changed.isEmpty()) {
                log.warn("{} could not be {} the network's whitelist: the database cannot be reached.",
                        name == null ? player : name, on ? "put on" : "taken off");
            } else if (changed.get()) {
                announce.run();
            }
        } finally {
            unwritten.remove(player);
        }
    }

    /**
     * Puts somebody on the network's whitelist — Discord's {@code /link}, a module. Database I/O.
     *
     * @param by who did it, for the record ({@code discord:<id>}, a player id); may be null
     * @return false if the database could not be written
     */
    public boolean add(UUID player, String name, String by) {
        Optional<Boolean> changed = table.add(player, name, by);
        if (changed.isEmpty()) {
            return false;
        }
        if (changed.get()) {
            announce.run();
        }
        if (taking && !server.whitelisted().containsKey(player)) {
            server.whitelist(player, name, true);
        }
        return true;
    }

    /** Takes somebody off the network's whitelist. Database I/O. @return false if the database could not be written */
    public boolean remove(UUID player) {
        Optional<Boolean> changed = table.remove(player);
        if (changed.isEmpty()) {
            return false;
        }
        if (changed.get()) {
            announce.run();
        }
        if (taking && server.whitelisted().containsKey(player)) {
            server.whitelist(player, null, false);
        }
        return true;
    }

    /** Whether they are on the network's whitelist; this server's own list when the database cannot be asked. */
    public boolean isWhitelisted(UUID player) {
        return table.contains(player).orElseGet(() -> server.whitelisted().containsKey(player));
    }

    /**
     * A player at this server's door: if the network's list has them but this server has not heard yet, they are put
     * on this server's list now, before vanilla looks — the proxy let them in, a stale mirror must not turn them away.
     */
    public void catchUp(UUID player, String name) {
        if (!taking || player == null || server.whitelisted().containsKey(player)) {
            return;
        }
        if (table.contains(player).orElse(false)) {
            server.whitelistNow(player, name);
        }
    }

    private static Map<UUID, String> players(YamlConfiguration yaml) {
        Map<UUID, String> players = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return players;
        }
        for (String id : section.getKeys(false)) {
            try {
                players.put(UUID.fromString(id), section.getString(id, ""));
            } catch (IllegalArgumentException notAnId) {
                log.warn("{} in the network whitelist is not a player id and was skipped.", id);
            }
        }
        return players;
    }
}
