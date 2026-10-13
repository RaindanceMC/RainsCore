package de.raindancer.core.platform.link;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Every link of this server — the main one under the key {@code ""}, each peer of link.yml under its own key — with
 * the roster of who is over there. For a plugin that talks to one particular server of the network, or to wherever a
 * player is right now.
 */
public final class LinkDirectory {

    public static final LinkDirectory NONE = new LinkDirectory();

    /** A message key said to a player on the other side: {@code to}, {@code key}, {@code n} values {@code v0..}. */
    public static final String TELL = "core.tell";
    /** Who a name is over there: {@code name} → {@code id}, {@code name}, {@code server}; empty when not there. */
    public static final String WHOIS = "core.whois";
    private static final Duration WAIT = Duration.ofSeconds(3);

    /** Somebody online on some server of the network, as that server knows them. */
    public record Found(UUID id, String name, String server) {
    }

    /** This server's side of {@link #tell} and {@link #whois}: its players, vanished staff not among them. */
    public interface Here {

        Optional<Found> find(String name);

        /** Says the line to them; false when nobody of that name is here. */
        boolean tell(String name, String key, List<String> values);
    }

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

    /** Answers {@link #tell} and {@link #whois} from the other servers, on every link. Handlers run on link threads. */
    public void serve(Here here) {
        synchronized (ends) {
            ends.values().forEach(each -> {
                each.link().on(TELL, data -> {
                    List<String> values = new ArrayList<>();
                    int count = parseCount(data.get("n"));
                    for (int i = 0; i < count; i++) {
                        values.add(data.getOrDefault("v" + i, ""));
                    }
                    boolean told = here.tell(data.getOrDefault("to", ""), data.getOrDefault("key", ""), values);
                    return Map.of("ok", String.valueOf(told));
                });
                each.link().on(WHOIS, data -> here.find(data.getOrDefault("name", ""))
                        .map(found -> Map.of("id", found.id().toString(), "name", found.name(), "server", found.server()))
                        .orElse(Map.of()));
            });
        }
    }

    /** Stops answering — what {@link #serve} started. */
    public void stopServing() {
        synchronized (ends) {
            ends.values().forEach(each -> {
                each.link().off(TELL);
                each.link().off(WHOIS);
            });
        }
    }

    /**
     * Says a message key, with its placeholder values in pairs as {@code Messages.send} takes them, to somebody on
     * whichever linked server they are on. Completes with whether it was said; false when they are on none, or that
     * server did not answer.
     */
    public CompletableFuture<Boolean> tell(String player, String key, String... values) {
        Optional<ServerLink> over = reaching(player);
        if (over.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        Map<String, String> data = new HashMap<>();
        data.put("to", player);
        data.put("key", key);
        data.put("n", String.valueOf(values.length));
        for (int i = 0; i < values.length; i++) {
            data.put("v" + i, values[i] == null ? "" : values[i]);
        }
        return over.get().request(TELL, data, WAIT).handle((answer, failed) ->
                failed == null && answer != null && "true".equals(answer.get("ok")));
    }

    /** Who this name is on the linked server they are on — empty when on none, or it did not answer. */
    public CompletableFuture<Optional<Found>> whois(String player) {
        Optional<ServerLink> over = reaching(player);
        if (over.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return over.get().request(WHOIS, Map.of("name", player), WAIT).handle((answer, failed) -> {
            if (failed != null || answer == null || !answer.containsKey("id")) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Found(UUID.fromString(answer.get("id")), answer.getOrDefault("name", player),
                        answer.getOrDefault("server", "")));
            } catch (IllegalArgumentException garbled) {
                return Optional.empty();
            }
        });
    }

    private static int parseCount(String count) {
        try {
            return Math.clamp(Integer.parseInt(count), 0, 32);
        } catch (RuntimeException garbled) {
            return 0;
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
