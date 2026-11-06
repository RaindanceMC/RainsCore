package de.raindancer.core.world.companion;

/** How ready a companion is to fight. */
public enum Stance {
    /** Joins every fight its owner starts and goes for monsters nearby. */
    AGGRESSIVE,
    /** Only goes for what hurt its owner. */
    DEFENSIVE,
    /** Never fights. */
    PASSIVE
}
