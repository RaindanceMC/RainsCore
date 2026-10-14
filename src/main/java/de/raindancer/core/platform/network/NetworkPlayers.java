package de.raindancer.core.platform.network;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everybody on the network, wherever they are — and saying something to one of them on whichever server
 * that is. What the link's roster, tell and whois were for two servers, for any number.
 */
public final class NetworkPlayers {

    /** The topic a message for one player travels on, keyed by the player. */
    public static final String TELL = "tell";

    private static final char SEPARATOR = '\u001F';

    /** Says a message key to a player on this server; false when they are not here. */
    @FunctionalInterface
    public interface Here {
        boolean tell(UUID player, String key, List<String> values);
    }

    private final Presence presence;
    private final NetworkServers servers;
    private final NetworkBus bus;
    private final Here here;

    public NetworkPlayers(Presence presence, NetworkServers servers, NetworkBus bus, Here here) {
        this.presence = presence;
        this.servers = servers;
        this.bus = bus;
        this.here = here;
        bus.subscribe(TELL, event -> {
            try {
                List<String> parts = Arrays.asList(event.payload().split(String.valueOf(SEPARATOR), -1));
                if (!parts.isEmpty()) {
                    here.tell(UUID.fromString(event.key()), parts.getFirst(), parts.subList(1, parts.size()));
                }
            } catch (IllegalArgumentException notAPlayer) {
                // Not for anybody: nothing to say.
            }
        });
    }

    public Optional<Presence.Here> where(UUID player, boolean withVanished) {
        return presence.where(player, withVanished);
    }

    public Optional<Presence.Here> named(String name, boolean withVanished) {
        return presence.named(name, withVanished);
    }

    public List<Presence.Here> everyone(boolean withVanished) {
        return presence.everyone(withVanished);
    }

    public Map<String, List<Presence.Here>> byServer(boolean withVanished) {
        return presence.byServer(withVanished);
    }

    public List<NetworkServers.Server> servers() {
        return servers.online();
    }

    /** This server's id. */
    public String self() {
        return servers.self();
    }

    /**
     * Says a message key, with its placeholder values in pairs as {@code Messages.send} takes them, to somebody
     * wherever they are on the network.
     *
     * @return whether they are online somewhere it can reach — not whether they have read it
     */
    public boolean tell(UUID player, String key, String... values) {
        if (player == null || key == null) {
            return false;
        }
        if (here.tell(player, key, List.of(values))) {
            return true;
        }
        Optional<Presence.Here> there = presence.where(player, true);
        if (there.isEmpty() || there.get().server().equals(servers.self())) {
            return false;
        }
        StringBuilder payload = new StringBuilder(key);
        for (String value : values) {
            payload.append(SEPARATOR).append(value == null ? "" : value.replace(SEPARATOR, ' '));
        }
        return bus.publish(TELL, player.toString(), payload.toString());
    }
}
