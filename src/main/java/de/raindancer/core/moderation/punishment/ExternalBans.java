package de.raindancer.core.moderation.punishment;

import de.raindancer.core.platform.util.PluginCode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A ban or unban asked for from outside the game (Hush mirroring a Discord ban), handed to whichever plugin bans
 * players (moderation), so it is recorded, mirrored, kicks and is announced like one a moderator gives. Static and
 * process-wide, like {@link de.raindancer.core.moderation.rules.RuleBreaches}: neither side knows the other.
 */
public final class ExternalBans {

    public enum Outcome {
        /** Banned or unbanned now. */
        DONE,
        /** Already banned, or not banned to begin with. */
        ALREADY,
        /** Nobody on this server bans players. */
        NOBODY,
        FAILED
    }

    /** Called from any thread; the banner moves to the main thread itself where it has to. */
    public interface Banner {

        /** @param by who asked, as the record and the announcement name them, e.g. "Hush" */
        Outcome ban(UUID subject, String name, String by, String reason);

        Outcome unban(UUID subject, String name, String by, String reason);
    }

    private static final AtomicReference<Banner> banner = new AtomicReference<>();

    private ExternalBans() {
    }

    public static void provide(Banner handler) {
        banner.set(handler);
    }

    public static void withdraw(Banner handler) {
        banner.compareAndSet(handler, null);
    }

    public static boolean available() {
        return banner.get() != null;
    }

    public static Outcome ban(UUID subject, String name, String by, String reason) {
        return ask(subject, handler -> handler.ban(subject, name, by, reason));
    }

    public static Outcome unban(UUID subject, String name, String by, String reason) {
        return ask(subject, handler -> handler.unban(subject, name, by, reason));
    }

    private static Outcome ask(UUID subject, java.util.function.Function<Banner, Outcome> question) {
        Banner handler = banner.get();
        if (handler == null) {
            return Outcome.NOBODY;
        }
        if (subject == null) {
            return Outcome.FAILED;
        }
        try {
            Outcome outcome = question.apply(handler);
            return outcome == null ? Outcome.FAILED : outcome;
        } catch (RuntimeException broken) {
            return Outcome.FAILED;
        }
    }

    public static int forgetFrom(ClassLoader loader) {
        Banner handler = banner.get();
        if (handler != null && PluginCode.isFrom(handler, loader) && banner.compareAndSet(handler, null)) {
            return 1;
        }
        return 0;
    }

    public static void clear() {
        banner.set(null);
    }
}
