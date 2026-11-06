package de.raindancer.core.world.companion;

/** A flyer keeping a sensible height: off the ground, out of the ceiling, not off into the sky. */
public final class Flight {

    /** Nearer the ground than this, it lifts. */
    public static final double LOWEST = 1.0;
    /** Further over the ground than this, it climbs no more and sinks. */
    public static final double HIGHEST = 7;
    /** Nearer a ceiling than this, it climbs no more. */
    private static final double HEADROOM = 0.6;

    private Flight() {
    }

    /**
     * @param ground  blocks down to the ground
     * @param ceiling blocks up to a ceiling
     */
    public static Steering.Vec keepHeight(Steering.Vec push, double ground, double ceiling, double speed) {
        double y = push.y();
        if (ceiling < HEADROOM) {
            y = Math.min(y, -0.02);
        } else if (ground < LOWEST) {
            y = Math.max(y, Math.min(0.12, speed * 0.5));
        } else if (ground > HIGHEST) {
            y = Math.min(y, -0.06);
        }
        return y == push.y() ? push : new Steering.Vec(push.x(), y, push.z());
    }
}
