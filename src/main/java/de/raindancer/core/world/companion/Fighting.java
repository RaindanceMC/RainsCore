package de.raindancer.core.world.companion;

/** How a companion fights. */
public enum Fighting {
    /** Bites what it goes for. */
    MELEE,
    /** Bites, and draws monsters going for its owner onto itself. */
    GUARD,
    /** Keeps its distance and throws its missile. */
    RANGED,
    /** Mends its owner and itself, and bites a little when it has to. */
    HEALER
}
