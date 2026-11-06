package de.raindancer.core.world.companion;

/** How a companion goes about a fight — its owner's choice; {@link Fighting} is what it can do, this is how. */
public enum Tactic {
    /** Circles its foe and darts in to bite; a thrower keeps a fair distance. */
    BALANCED,
    /** Presses in and stays on its foe, chases further and gives up later, turns back only when nearly beaten. */
    RELENTLESS,
    /** Darts in, bites, and backs out of reach until it is ready again; a thrower keeps well away. */
    HIT_AND_RUN,
    /** Fights only near its owner, keeps to their side, and comes back early when hurt. */
    CAUTIOUS
}
