package de.raindancer.core.platform.link;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A player's data kept on one server of the link and shown on the other — their name style, their cosmetics, a mute.
 * Each kind is a named part: the server that keeps it {@link #provide provides} it, the one that shows it
 * {@link #follow follows} it and {@link #fetch fetches} every followed part when the player arrives. A part the other
 * side does not keep is left alone, so the follower shows what it had.
 */
public final class LinkProfiles {

    public static final String TOPIC = "core.profile";
    private static final String MISSING = "__missing";

    private final ServerLink link;
    private final Map<String, Function<UUID, Map<String, String>>> provided = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<UUID, Map<String, String>>> followed = new ConcurrentHashMap<>();

    public LinkProfiles(ServerLink link) {
        this.link = link;
        link.on(TOPIC, data -> {
            Function<UUID, Map<String, String>> part = provided.get(data.getOrDefault("part", ""));
            if (part == null) {
                return Map.of(MISSING, "true");
            }
            try {
                return part.apply(UUID.fromString(data.get("player")));
            } catch (RuntimeException garbled) {
                return Map.of(MISSING, "true");
            }
        });
    }

    /** This server keeps this part: what the other side is answered when it asks about a player. */
    public void provide(String part, Function<UUID, Map<String, String>> answer) {
        provided.put(part, answer);
    }

    /** This server shows this part, kept over there: what to do with it once fetched. Runs on the link's thread. */
    public void follow(String part, BiConsumer<UUID, Map<String, String>> apply) {
        followed.put(part, apply);
    }

    public void forget(String part) {
        provided.remove(part);
        followed.remove(part);
    }

    /** Every followed part of this player, from the other side — completes when all have answered or failed. */
    public CompletableFuture<Void> fetch(UUID player) {
        List<CompletableFuture<Void>> asks = new ArrayList<>();
        followed.forEach((part, apply) -> asks.add(link.request(TOPIC, Map.of("part", part, "player", player.toString()),
                        Duration.ofSeconds(4))
                .thenAccept(answer -> {
                    if (!answer.containsKey(MISSING)) {
                        apply.accept(player, answer);
                    }
                })
                .exceptionally(unreachable -> null)));
        return CompletableFuture.allOf(asks.toArray(CompletableFuture[]::new));
    }

    public void stop() {
        link.off(TOPIC);
    }
}
