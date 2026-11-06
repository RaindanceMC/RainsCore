package de.raindancer.core.ui.choose;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * What a player may paint the names they own with — their pet's, their guild's — the same as their own name: the
 * plugin that styles players' names (cosmetics) provides the answer, and a {@link StyleEditor} for anything else a
 * player names asks here. Empty when nobody provides one; the asker picks its own fallback.
 */
public final class NameStyleGrants {

    private static final List<Function<Player, StyleGrants>> providers = new CopyOnWriteArrayList<>();

    private NameStyleGrants() {
    }

    /** @return what to hand to {@link #retract} when the provider stops */
    public static Object provide(Function<Player, StyleGrants> provider) {
        providers.add(provider);
        return provider;
    }

    public static void retract(Object handle) {
        providers.remove(handle);
    }

    public static Optional<StyleGrants> of(Player player) {
        if (providers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.getLast().apply(player));
    }

    public static void clear() {
        providers.clear();
    }
}
