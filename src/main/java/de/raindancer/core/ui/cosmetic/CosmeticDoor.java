package de.raindancer.core.ui.cosmetic;

import de.raindancer.core.ui.menu.Menu;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * One plugin's cosmetic on the server's cosmetics page — a door like "Rod skins" next to the cosmetics plugin's own,
 * so everything a player wears is put on in one place. Offered through {@link CosmeticDoors}.
 */
public interface CosmeticDoor {

    /** {@code plugin:what}, e.g. {@code fishing:rod-skins}; doors are shown in this order. */
    String id();

    /** The button, saying what this viewer wears now. */
    ItemStack icon(Player viewer);

    /** Why this viewer may not open it ("A thank-you for supporters — /support"); empty when they may. */
    Optional<String> locked(Player viewer);

    /** Opens the door's own page, with {@code parent} as the page to go back to. */
    void open(Player viewer, Menu parent);

    /** Whether this viewer wears something from this door now, so "Clear my cosmetics" has something to take off. */
    default boolean wearing(Player viewer) {
        return false;
    }

    /**
     * Takes this door's cosmetic off this viewer, for "Clear my cosmetics". Called on the viewer's own thread.
     * Only for what is worn; what they own stays theirs.
     */
    default void takeOff(Player viewer) {
    }
}
