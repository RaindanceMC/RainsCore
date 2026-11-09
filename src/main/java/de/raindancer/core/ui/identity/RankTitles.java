package de.raindancer.core.ui.identity;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The title of the rank somebody wears ("Owner"), answered by whichever plugin runs ranks and asked by any that
 * wants to name it — a Discord card, a log line. Static and process-wide, like
 * {@link de.raindancer.core.moderation.players.AdminModes}: neither side knows the other.
 */
public final class RankTitles {

    @FunctionalInterface
    public interface Source {

        /** The worn rank's title. May load the player from storage: blocks, never on the server thread. */
        Optional<String> titleOf(UUID player);
    }

    private static final AtomicReference<Source> source = new AtomicReference<>();

    private RankTitles() {
    }

    public static void provide(Source answering) {
        source.set(answering);
    }

    public static void withdraw(Source answering) {
        source.compareAndSet(answering, null);
    }

    /** Empty without a ranks plugin, for a player without a rank, and when asking fails. */
    public static Optional<String> of(UUID player) {
        Source answering = source.get();
        if (answering == null || player == null) {
            return Optional.empty();
        }
        try {
            return answering.titleOf(player).filter(title -> !title.isBlank());
        } catch (RuntimeException broken) {
            return Optional.empty();
        }
    }

    public static void clear() {
        source.set(null);
    }
}
