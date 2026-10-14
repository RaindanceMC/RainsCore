package de.raindancer.core.content.enchant;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether a player has unlocked one of Rain's enchantments — asked by whoever sells them (the economy's
 * shop), answered by whoever keeps the unlocks (the guilds). Neither knows the other.
 *
 * <p>With nobody answering, everything is locked: the enchantments are earned, never handed out by default.
 */
public final class EnchantUnlocks {

    /** Answers for a player and an enchantment key ({@code rainsenchants:telekinesis}). */
    @FunctionalInterface
    public interface Source {
        boolean unlocked(UUID player, String enchantment);
    }

    private record Provided(Plugin owner, Source source) {
    }

    private static final LogChannel log = Log.of("enchantments");
    private static final List<Provided> sources = new CopyOnWriteArrayList<>();

    private EnchantUnlocks() {
    }

    public static synchronized void provide(Plugin owner, Source source) {
        if (source != null && sources.stream().noneMatch(each -> each.source() == source)) {
            sources.add(new Provided(owner, source));
        }
    }

    public static synchronized void retract(Source source) {
        sources.removeIf(each -> each.source() == source);
    }

    /** Whether anybody keeps unlocks at all — without, a shop has nothing to offer and can say why. */
    public static boolean isAnswered() {
        return !sources.isEmpty();
    }

    /** True if any keeper says so. A keeper that throws unlocks nothing: a broken one must not give it away. */
    public static boolean isUnlocked(UUID player, String enchantment) {
        if (player == null || enchantment == null) {
            return false;
        }
        for (Provided each : sources) {
            try {
                if (each.source().unlocked(player, enchantment)) {
                    return true;
                }
            } catch (RuntimeException broken) {
                log.error(broken, "An unlock keeper could not say whether {} has {}; it is treated as locked.",
                        player, enchantment);
            }
        }
        return false;
    }
}
