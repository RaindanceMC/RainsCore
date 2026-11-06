package de.raindancer.core.world.companion;

/** The arithmetic of a companion's knacks, without a server. */
final class Knacks {

    /** Ticks between two refreshes of the auras on its owner, and how long one lasts — longer, so it never blinks. */
    static final long AURA_EVERY = 60;
    static final int AURA_LASTS = 100;
    /** Ticks between two looks for loose drops, and for monsters near its owner. */
    static final long FETCH_EVERY = 20;
    static final long SPOT_EVERY = 20;
    /** How far from its owner it still keeps auras on them. */
    static final double AURA_REACH = 12;
    /** Another player this near a drop: it is theirs to take, not the companion's to fetch. */
    static final double SOMEBODY_ELSES = 5;

    private Knacks() {
    }

    /**
     * A loose item (or experience) on the ground near a fetching companion.
     *
     * @param ageTicks       how long it has lain
     * @param heldForOwner   nobody, or its owner, is the one the game holds it for
     * @param thrownByOwner  nobody, or its owner, threw it
     * @param somebodyNear   another player stands by it
     * @param mayPickUp      the land lets its owner pick up there
     */
    record Loot(long ageTicks, boolean heldForOwner, boolean thrownByOwner, boolean somebodyNear, boolean mayPickUp) {
    }

    static boolean fetches(Loot loot) {
        return loot.ageTicks() >= 20 && loot.heldForOwner() && loot.thrownByOwner() && !loot.somebodyNear()
                && loot.mayPickUp();
    }

    static double taken(double damage, double toughness) {
        return damage * (1 - toughness);
    }

    static double share(double amount, double share) {
        return amount * share;
    }

    static boolean due(long tick, long every) {
        return tick % every == 0;
    }
}
