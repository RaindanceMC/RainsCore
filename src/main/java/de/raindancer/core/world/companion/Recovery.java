package de.raindancer.core.world.companion;

/**
 * A companion getting its strength back: slowly, once it has neither fought nor been hurt for a while, and only near
 * its owner — so a hurt one keeps close. Hurt, it is less lively, too.
 */
public final class Recovery {

    /** Ticks without fighting or being hurt before it mends. */
    public static final long CALM_TICKS = 160;
    /** It mends only this near its owner, in blocks. */
    public static final double RANGE = 12;
    private static final double TIREDEST = 0.55;

    private Recovery() {
    }

    /** Half hearts it gets back this second. */
    public static double mends(long calmFor, double ownerDistanceSquared, double perSecond) {
        return calmFor >= CALM_TICKS && ownerDistanceSquared <= RANGE * RANGE ? Math.max(0, perSecond) : 0;
    }

    /** Its speed and eagerness times this: 1 when whole, a little over half when nearly beaten. */
    public static double vigour(double healthShare) {
        double share = Math.max(0, Math.min(1, healthShare));
        return TIREDEST + (1 - TIREDEST) * share;
    }

    /** How far from its owner it may explore: hurt, only as far as it still mends. */
    public static double roam(double radius, double healthShare, double perSecond) {
        if (healthShare >= 1 || perSecond <= 0) {
            return radius;
        }
        return Math.min(radius, RANGE - 4);
    }
}
