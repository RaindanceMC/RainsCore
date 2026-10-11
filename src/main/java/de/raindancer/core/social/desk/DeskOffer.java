package de.raindancer.core.social.desk;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** One thing a plugin offers at a service desk: bet insurance, death insurance. */
public interface DeskOffer {

    /** Its button at the desk, for this player — say what they have now and what a click does. */
    ItemStack icon(Player viewer);

    /**
     * Clicked at the desk. Open your own screen, or do it and call {@code backToDesk} to show the desk again
     * with the change.
     */
    void use(Player viewer, Runnable backToDesk);

    /** Whether it is on offer at all right now; switched-off features are left out of the desk. */
    default boolean available() {
        return true;
    }
}
