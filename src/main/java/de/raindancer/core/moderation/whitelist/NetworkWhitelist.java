package de.raindancer.core.moderation.whitelist;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One whitelist for every server of a network that takes part, kept in a network document.
 *
 * <p>The vanilla whitelist stays what is enforced and what {@code /whitelist} edits: a change made here is
 * written to the document ({@link #changedHere}), and every taking part server makes its own list equal to the
 * document ({@link #reload}). The first time a server takes part its own list is added to the document, so
 * nobody whitelisted on one server only is lost. A server that does not take part keeps its own list.
 */
public final class NetworkWhitelist {

    private static final LogChannel log = Log.of("whitelist");

    /** The server's own whitelist. */
    public interface Server {
        Map<UUID, String> whitelisted();

        void whitelist(UUID player, String name, boolean on);
    }

    private final YamlStore shared;
    private final String serverId;
    private final Server server;
    private volatile boolean taking = true;
    /** True while this class itself changes the server's list, so the events that causes are not written back. */
    private boolean applying;

    public NetworkWhitelist(YamlStore shared, String serverId, Server server) {
        this.shared = shared;
        this.serverId = serverId == null || serverId.isBlank() ? "server" : serverId;
        this.server = server;
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

    /** Joins the network's list (once, adding this server's own), then makes this server's list equal to it. */
    public synchronized void start() {
        if (!taking) {
            return;
        }
        Map<UUID, String> mine = server.whitelisted();
        shared.update(yaml -> {
            List<String> joined = new ArrayList<>(yaml.getStringList("servers"));
            if (!joined.contains(serverId)) {
                mine.forEach((player, name) -> yaml.set("players." + player, name == null ? "" : name));
                joined.add(serverId);
                yaml.set("servers", joined);
                log.info("This server's whitelist ({} player(s)) was added to the network's.", mine.size());
            }
        });
        reload();
    }

    /** Makes this server's list equal to the network's. */
    public synchronized void reload() {
        if (!taking) {
            return;
        }
        var read = shared.readIfReadable();
        if (read.isEmpty()) {
            return;
        }
        Map<UUID, String> wanted = players(read.get());
        Map<UUID, String> mine = server.whitelisted();
        applying = true;
        try {
            wanted.forEach((player, name) -> {
                if (!mine.containsKey(player)) {
                    server.whitelist(player, name, true);
                }
            });
            mine.forEach((player, name) -> {
                if (!wanted.containsKey(player)) {
                    server.whitelist(player, name, false);
                }
            });
        } finally {
            applying = false;
        }
    }

    /** Somebody was put on or taken off this server's whitelist — by /whitelist, a plugin, anything. */
    public synchronized void changedHere(UUID player, String name, boolean on) {
        if (!taking || applying || player == null) {
            return;
        }
        shared.update(yaml -> yaml.set("players." + player, on ? (name == null ? "" : name) : null));
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
