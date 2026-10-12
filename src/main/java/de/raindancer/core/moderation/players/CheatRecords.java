package de.raindancer.core.moderation.players;

import de.raindancer.core.platform.util.PluginCode;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the anti-cheat knows about a player, for whoever shows staff a player's page (moderation): offered
 * by the anti-cheat, read by anybody. Static and process-wide, like {@link AdminModes}: neither knows the other.
 */
public final class CheatRecords {

    /**
     * @param level         every check's level added up — 0 for nothing
     * @param worstCheck    the check with the highest level, or null
     * @param flaggedChecks how many checks have a level at all
     * @param lastCheck     the last check that flagged them, or null
     * @param lastAtMillis  when, or 0
     * @param replays       how many replays are kept of them
     */
    public record Summary(double level, String worstCheck, int flaggedChecks, String lastCheck, long lastAtMillis,
                          int replays) {
    }

    @FunctionalInterface
    public interface Source {

        /** What is known about this player, online or not; empty for nothing. May be called from any thread. */
        Optional<Summary> of(UUID player);
    }

    private static final AtomicReference<Source> source = new AtomicReference<>();

    private CheatRecords() {
    }

    public static void provide(Source answering) {
        source.set(answering);
    }

    public static void withdraw(Source answering) {
        source.compareAndSet(answering, null);
    }

    public static boolean available() {
        return source.get() != null;
    }

    public static Optional<Summary> of(UUID player) {
        Source answering = source.get();
        if (answering == null || player == null) {
            return Optional.empty();
        }
        try {
            Optional<Summary> found = answering.of(player);
            return found == null ? Optional.empty() : found;
        } catch (RuntimeException broken) {
            return Optional.empty();
        }
    }

    public static int forgetFrom(ClassLoader loader) {
        Source answering = source.get();
        if (answering != null && PluginCode.isFrom(answering, loader) && source.compareAndSet(answering, null)) {
            return 1;
        }
        return 0;
    }

    public static void clear() {
        source.set(null);
    }
}
