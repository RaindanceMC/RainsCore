package de.raindancer.core.world.companion;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * What the plugin that summoned a companion is told — on the companion's own thread, so quickly. Everything is
 * defaulted; a plugin overrides what it cares about.
 */
public interface CompanionHooks {

    /** Why a companion left the world. */
    enum Gone {
        /** Sent home by its plugin. */
        DISMISSED,
        /** Beaten in a fight — it would have died; it never does. */
        BEATEN,
        /** Its owner left the server. */
        OWNER_LEFT,
        /** Its owner vanished; it comes back when they reappear. */
        OWNER_VANISHED,
        /** Its owner went where it may not follow. */
        NOT_ALLOWED_HERE,
        /** Gone without being sent — a command, its chunk; or the plugin stopped. */
        LOST
    }

    /** It finished something off. @param farmed from a spawner or an egg */
    default void killed(Companion companion, LivingEntity victim, boolean boss, boolean farmed) {
    }

    /** It left the world. */
    default void gone(Companion companion, Gone why) {
    }

    /** Its rider took it this many more blocks. */
    default void ridden(Companion companion, double blocks) {
    }

    /** Somebody other than its owner saw it close up — once every few seconds while they stay near. */
    default void seen(Companion companion, Player viewer) {
    }

    /**
     * Its owner fed it what they hold.
     *
     * @return whether it ate — one of the item is then taken
     */
    default boolean fed(Companion companion, Player owner, ItemStack food) {
        return false;
    }

    /**
     * Its owner right-clicked it with nothing to feed.
     *
     * @return whether that was handled; otherwise a rideable companion is climbed on
     */
    default boolean clicked(Companion companion, Player owner, boolean sneaking) {
        return false;
    }

    /** Somebody other than its owner right-clicked it — to learn whose it is. */
    default void inspected(Companion companion, Player viewer) {
    }

    /** Whether it may come along into a world — asked when its owner changes worlds. */
    default boolean allowedIn(Companion companion, String world) {
        return true;
    }
}
