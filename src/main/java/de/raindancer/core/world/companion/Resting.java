package de.raindancer.core.world.companion;

import java.util.Random;

/** A companion at rest: it sits after a while and lies down after a longer one where its mob can, a flyer bobs. */
public final class Resting {

    public enum Pose {
        STAND, SIT, LIE
    }

    /** Ticks still before it may sit. */
    static final long SITS_AFTER = 100;
    /** Ticks still before it may lie down. */
    static final long LIES_AFTER = 400;

    private Resting() {
    }

    /** Asked once a second while it is still; {@code vigour} is {@link Recovery#vigour}. */
    public static Pose next(Pose pose, long still, double vigour, boolean canLie, Random random) {
        double tired = 1 - Math.max(0, Math.min(1, vigour));
        return switch (pose) {
            case STAND -> still >= SITS_AFTER * (1 - tired) && random.nextDouble() < 0.06 + 0.3 * tired
                    ? Pose.SIT : Pose.STAND;
            case SIT -> {
                if (canLie && still >= LIES_AFTER * (1 - tired) && random.nextDouble() < 0.04 + 0.2 * tired) {
                    yield Pose.LIE;
                }
                yield random.nextDouble() < 0.01 * (1 - tired) ? Pose.STAND : Pose.SIT;
            }
            case LIE -> random.nextDouble() < 0.005 * (1 - tired) ? Pose.SIT : Pose.LIE;
        };
    }

    /** A hovering flyer's up-and-down push this tick. */
    public static double bob(long tick, double phase) {
        return 0.04 * Math.sin(tick * 0.12 + phase * Math.PI * 2);
    }
}
