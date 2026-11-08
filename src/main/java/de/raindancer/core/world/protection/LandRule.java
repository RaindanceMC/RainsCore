package de.raindancer.core.world.protection;

import org.bukkit.entity.Player;

/**
 * Something besides the ground itself that may let a player do something there — a raid, a siege, an event. Asked
 * only when the area has already refused, and only ever able to add a yes: a rule that could also say no would make
 * two plugins fight over who decides somebody's own claim.
 *
 * <p>Called on the region thread of the event, often several times a tick: answer from memory, never block.
 */
@FunctionalInterface
public interface LandRule {

    boolean allows(Player player, ProtectedArea area, LandAction action);
}
