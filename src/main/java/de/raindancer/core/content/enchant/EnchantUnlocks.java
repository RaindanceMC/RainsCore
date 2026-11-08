package de.raindancer.core.content.enchant;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

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

    private static volatile BooleanSupplier shards;

    /** The enchants plugin says whether shards can be earned here (its shard world on or off), asked live. */
    public static synchronized void shardsFrom(Plugin owner, BooleanSupplier active) {
        shards = active;
    }

    public static synchronized void retractShards(BooleanSupplier active) {
        if (shards == active) {
            shards = null;
        }
    }

    /**
     * Whether shards can be earned on this server, so guilds can hide unlocking with them. Nobody saying so counts
     * as yes: a server whose enchants plugin predates this keeps its menus.
     */
    public static boolean shardsOffered() {
        BooleanSupplier source = shards;
        if (source == null) {
            return true;
        }
        try {
            return source.getAsBoolean();
        } catch (RuntimeException broken) {
            log.error(broken, "The enchants plugin could not say whether shards are offered; they are treated as offered.");
            return true;
        }
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
