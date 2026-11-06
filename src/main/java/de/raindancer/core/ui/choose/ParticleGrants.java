package de.raindancer.core.ui.choose;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Which particle effects a player may put on what they own — their pet, say — the same they may wear themselves: the
 * plugin that sells and grants particles (cosmetics) provides it. Empty when nobody does.
 */
public final class ParticleGrants {

    private static final List<Function<Player, List<String>>> providers = new CopyOnWriteArrayList<>();

    private ParticleGrants() {
    }

    /** @return what to hand to {@link #retract} */
    public static Object provide(Function<Player, List<String>> provider) {
        providers.add(provider);
        return provider;
    }

    public static void retract(Object handle) {
        providers.remove(handle);
    }

    public static Optional<List<String>> of(Player player) {
        if (providers.isEmpty()) {
            return Optional.empty();
        }
        List<String> granted = providers.getLast().apply(player);
        return Optional.of(granted == null ? List.of() : List.copyOf(granted));
    }

    public static void clear() {
        providers.clear();
    }
}
