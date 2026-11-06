package de.raindancer.core.world.companion;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Companions cuddling: while their owner stands about, one now and then goes to another of theirs that is resting
 * and settles down right against it — touching, never on top of it — until their owner moves on.
 */
public final class Cuddling {

    /** Furthest it goes to cuddle up. */
    static final double RANGE = 6;

    /**
     * One of its owner's other companions.
     *
     * @param free nobody cuddles it yet, and it cuddles nobody
     */
    public record Mate(UUID id, Steering.Vec at, boolean resting, boolean free) {
    }

    private Cuddling() {
    }

    /** The nearest resting, free one within reach. */
    public static Optional<Mate> partner(Steering.Vec here, List<Mate> mates) {
        return mates.stream().filter(mate -> mate.resting() && mate.free())
                .filter(mate -> flat(mate.at(), here) <= RANGE)
                .min(Comparator.comparingDouble(mate -> flat(mate.at(), here)));
    }

    /** Right against {@code partner}, {@code touching} away, on the side it comes from. */
    public static Steering.Vec beside(Steering.Vec partner, Steering.Vec from, double touching) {
        double dx = from.x() - partner.x();
        double dz = from.z() - partner.z();
        double length = Math.hypot(dx, dz);
        if (length < 1e-6) {
            dx = 1;
            dz = 0;
            length = 1;
        }
        return new Steering.Vec(partner.x() + dx / length * touching, partner.y(),
                partner.z() + dz / length * touching);
    }

    /** Asked once a second while it rests. */
    public static boolean feelsLikeIt(Random random) {
        return random.nextDouble() < 0.04;
    }

    /** Ticks a cuddle lasts if its owner stays put. */
    public static long lasts(Random random) {
        return 400 + random.nextInt(801);
    }

    private static double flat(Steering.Vec a, Steering.Vec b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
