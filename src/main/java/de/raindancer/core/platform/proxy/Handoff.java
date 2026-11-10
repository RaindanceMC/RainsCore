package de.raindancer.core.platform.proxy;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A short note one server of the network leaves with a player for the next one — why they are being sent over, say.
 *
 * <p>Kept as a cookie on the player's client, which survives the move through the proxy (Velocity passes cookie
 * packets on). {@link #take} reads it once and clears it. A client that never answers (some bots, perhaps Bedrock
 * through Geyser) reads as no note after the patience given, so the next server falls back to its usual welcome.
 */
public final class Handoff {

    /** The most a client keeps per cookie. */
    public static final int MAX_BYTES = 5120;

    private static final byte[] NOTHING = new byte[0];

    private Handoff() {
    }

    /** Before {@link ProxyTransfer#send}: the note goes out on the same connection first. */
    public static void leave(Player player, NamespacedKey key, String note) {
        player.storeCookie(key, encode(note));
    }

    /** The note left for this player, if any, cleared once read. Completes on whatever thread the client answered. */
    public static CompletableFuture<Optional<String>> take(Plugin plugin, Player player, NamespacedKey key,
                                                           Duration patience) {
        return player.retrieveCookie(key)
                .thenApply(Handoff::decode)
                .completeOnTimeout(Optional.empty(), patience.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(failed -> Optional.empty())
                .thenApply(note -> {
                    if (note.isPresent()) {
                        Scheduling.entity(plugin, player, () -> player.storeCookie(key, NOTHING));
                    }
                    return note;
                });
    }

    static byte[] encode(String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("an empty note reads as none");
        }
        byte[] bytes = note.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("a client keeps at most " + MAX_BYTES + " bytes, not " + bytes.length);
        }
        return bytes;
    }

    static Optional<String> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        String note = new String(bytes, StandardCharsets.UTF_8);
        return note.isBlank() ? Optional.empty() : Optional.of(note);
    }
}
