package de.raindancer.core.ui.identity;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Somebody's own join and leave line. Whoever says those lines (essentials' welcome) asks here for a template to use
 * instead of the server's; whoever lets players pick one (cosmetics) answers. A template is written like the server's:
 * {@code <player>} stands for the name.
 *
 * <p>Asked on the thread that is saying the line, so an answer must be quick and never block; one that throws or says
 * nothing costs nobody their line.
 */
public final class JoinLines {

    public enum Kind { JOIN, QUIT }

    private static volatile BiFunction<UUID, Kind, Optional<String>> provider;

    private JoinLines() {
    }

    /** Sets the one provider; null takes it away. */
    public static void provider(BiFunction<UUID, Kind, Optional<String>> fresh) {
        provider = fresh;
    }

    public static Optional<String> custom(UUID player, Kind kind) {
        BiFunction<UUID, Kind, Optional<String>> current = provider;
        if (current == null || player == null) {
            return Optional.empty();
        }
        try {
            Optional<String> line = current.apply(player, kind);
            return line == null ? Optional.empty() : line.filter(text -> !text.isBlank());
        } catch (RuntimeException failed) {
            return Optional.empty();
        }
    }

    /** The line to say: theirs if they have one, else {@code fallback}. */
    public static String template(UUID player, Kind kind, String fallback) {
        return custom(player, kind).orElse(fallback);
    }
}
