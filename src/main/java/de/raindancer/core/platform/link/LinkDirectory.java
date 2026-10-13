package de.raindancer.core.platform.link;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every link of this server — the main one under the key {@code ""}, each peer of link.yml under its own key — with
 * the roster of who is over there. For a plugin that talks to one particular server of the network, or to wherever a
 * player is right now.
 */
public final class LinkDirectory {

    public static final LinkDirectory NONE = new LinkDirectory();

    private record End(ServerLink link, LinkRoster roster) {
    }

    private final Map<String, End> ends = Collections.synchronizedMap(new LinkedHashMap<>());

    public void add(String key, ServerLink link, LinkRoster roster) {
        if (this == NONE) {
            throw new IllegalStateException("NONE stays empty");
        }
        ends.put(key == null ? "" : key, new End(link, roster));
    }

    /** By its key in link.yml, or else by the name the other side gives itself (server-name), case aside. */
    public Optional<ServerLink> named(String name) {
        if (name == null) {
            return Optional.empty();
        }
        synchronized (ends) {
            End byKey = ends.get(name);
            if (byKey == null) {
                byKey = ends.entrySet().stream().filter(each -> each.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
                        .findFirst().orElse(null);
            }
            if (byKey == null) {
                byKey = ends.values().stream().filter(each -> each.roster().serverName().equalsIgnoreCase(name))
                        .findFirst().orElse(null);
            }
            return Optional.ofNullable(byKey).map(End::link);
        }
    }

    /** The link over which somebody of this name is online, as the rosters last heard; case aside. */
    public Optional<ServerLink> reaching(String player) {
        return find(player).map(End::link);
    }

    /** What the server that player is on calls itself; empty when it has not said or they are on none of them. */
    public Optional<String> serverOf(String player) {
        return find(player).map(each -> each.roster().serverName()).filter(name -> !name.isBlank());
    }

    /** Every link by its key, in the order link.yml has them. */
    public Map<String, ServerLink> all() {
        synchronized (ends) {
            Map<String, ServerLink> copy = new LinkedHashMap<>();
            ends.forEach((key, each) -> copy.put(key, each.link()));
            return Collections.unmodifiableMap(copy);
        }
    }

    private Optional<End> find(String player) {
        if (player == null || player.isBlank()) {
            return Optional.empty();
        }
        synchronized (ends) {
            return ends.values().stream()
                    .filter(each -> each.roster().elsewhere().stream().anyMatch(name -> name.equalsIgnoreCase(player)))
                    .findFirst();
        }
    }
}
