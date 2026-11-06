package de.raindancer.core.world.companion;

import java.util.Random;

/**
 * A companion exploring while nothing else is asked of it: its owner standing about, or it told to stay. It sets off
 * for a spot near them now and then — a flyer into the air, a walker over the ground — and how far and how often is
 * its spec's {@code wanderRadius} and {@code wanderEagerness}.
 */
public final class Wandering {

    /** Ticks an owner must stay put to count as idle. */
    public static final int IDLE_AFTER = 60;
    private static final double STILL = 0.75;

    private Steering.Vec stoodAt;
    private long since;

    /** Fed the owner's position every tick; @return whether they have stood about long enough */
    public boolean ownerIdle(Steering.Vec at, long tick) {
        if (stoodAt == null || distance(stoodAt, at) > STILL) {
            stoodAt = at;
            since = tick;
            return false;
        }
        return tick - since >= IDLE_AFTER;
    }

    public static Steering.Vec spot(Steering.Vec around, double radius, boolean flies, Random random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = Math.sqrt(random.nextDouble()) * radius;
        double y = flies ? around.y() + 1 + random.nextDouble() * 4 : around.y();
        return new Steering.Vec(around.x() + Math.cos(angle) * distance, y, around.z() + Math.sin(angle) * distance);
    }

    /** Asked once a second while it idles at a spot. */
    public static boolean setsOff(double eagerness, double radius, Random random) {
        return radius > 0 && random.nextDouble() < 0.05 + 0.45 * eagerness;
    }

    private static double distance(Steering.Vec a, Steering.Vec b) {
        return Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
    }
}
