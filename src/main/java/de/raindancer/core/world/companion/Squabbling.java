package de.raindancer.core.world.companion;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * A grumpy companion squaring up to somebody else's now and then: it walks up, stops short, faces it, hops and huffs —
 * for show only, nobody is hurt. How grumpy is its spec's {@code grumpiness}; 0 never.
 */
public final class Squabbling {

    /** Furthest a rival may be. */
    static final double RANGE = 6;
    /** Room it leaves between itself and its rival. */
    public static final double GAP = 0.5;

    /** Somebody's companion nearby. */
    public record Rival(UUID id, UUID owner, Steering.Vec at) {
    }

    private Squabbling() {
    }

    /** Asked once a second while it has nothing else to do. */
    public static boolean picksOn(double grumpiness, Random random) {
        return grumpiness > 0 && random.nextDouble() < 0.05 * Math.min(1, grumpiness);
    }

    /** The nearest of somebody else's companions within reach. */
    public static Optional<Rival> rival(Steering.Vec here, UUID owner, List<Rival> near) {
        return near.stream().filter(other -> !other.owner().equals(owner))
                .filter(other -> flat(other.at(), here) <= RANGE)
                .min(Comparator.comparingDouble(other -> flat(other.at(), here)));
    }

    /** Ticks a squabble lasts. */
    public static long lasts(Random random) {
        return 40 + random.nextInt(81);
    }

    /** Where it stands its ground: {@code reach} plus a gap short of its rival, on its own side. */
    public static Steering.Vec faceOff(Steering.Vec rival, Steering.Vec from, double reach) {
        return Cuddling.beside(rival, from, reach + GAP);
    }

    private static double flat(Steering.Vec a, Steering.Vec b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
