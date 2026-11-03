package de.raindancer.core.ui.tree;

import de.raindancer.core.ui.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * A skill as a menu that lists every skill shows it — whichever plugin it belongs to. A skills plugin lists
 * {@link Skills#all()}; fishing, say, registers its own here and shows up beside the rest.
 */
public interface SkillView {

    /** Lower case and unique: "mining", "fishing". */
    String id();

    String title();

    Material icon();

    /** Where it is listed: lower first, then by title. */
    int order();

    /** The player's level in it — or, for skills that share one level, that shared level. */
    int level(UUID player);

    /** How far through the current level, 0 to 1. */
    double progress(UUID player);

    /** Points the player has to spend in it now. */
    int pointsLeft(UUID player);

    /** How many branches end in a mastery badge. */
    int branches();

    /** How many of those the player has mastered. */
    int mastered(UUID player);

    default boolean fullyMastered(UUID player) {
        return branches() > 0 && mastered(player) >= branches();
    }

    /** Opens the skill's tree, with {@code parent} to go back to (null for none). */
    void open(Player viewer, Menu parent);
}
