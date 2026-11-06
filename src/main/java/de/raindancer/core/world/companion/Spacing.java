package de.raindancer.core.world.companion;

import java.util.List;
import java.util.Random;

/**
 * Companions keeping out of each other's way: each of an owner's companions follows and rests at a spot of its own
 * behind them, a companion too close to another — anybody's — is pushed off it, and none explores where another is.
 */
public final class Spacing {

    /** How far behind its owner a companion's spot is. */
    public static final double FOLLOW = 2.2;
    /** Strongest push off another companion, blocks per tick. */
    public static final double MOST = 0.15;
    /** Nearest two exploring companions' spots may be. */
    public static final double APART = 1.8;
    /** Degrees between two neighbouring spots. */
    private static final double FAN = 50;
    /** Further apart than this in height, two companions are not in each other's way. */
    private static final double OVERHEAD = 2;
    private static final double GOLDEN = Math.PI * (3 - Math.sqrt(5));

    private Spacing() {
    }

    /**
     * The spot of the {@code index}th of {@code count} companions, fanned out behind an owner facing {@code yaw}.
     */
    public static Steering.Vec spot(Steering.Vec owner, float yaw, int index, int count, double distance) {
        double angle = Math.toRadians(yaw + 180 + (index - (count - 1) / 2.0) * FAN);
        return new Steering.Vec(owner.x() - Math.sin(angle) * distance, owner.y(),
                owner.z() + Math.cos(angle) * distance);
    }

    /**
     * A push off the others nearer than {@code apart} on the flat — the nearer, the harder, never more than
     * {@link #MOST}. {@code seed} tells apart companions standing exactly on one spot.
     */
    public static Steering.Vec away(Steering.Vec here, List<Steering.Vec> others, double apart, int seed) {
        double x = 0;
        double z = 0;
        for (Steering.Vec other : others) {
            if (Math.abs(other.y() - here.y()) >= OVERHEAD) {
                continue;
            }
            double dx = here.x() - other.x();
            double dz = here.z() - other.z();
            double distance = Math.hypot(dx, dz);
            if (distance >= apart) {
                continue;
            }
            double strength = (apart - distance) / apart;
            if (distance < 1e-3) {
                double angle = seed * GOLDEN;
                dx = Math.cos(angle);
                dz = Math.sin(angle);
            } else {
                dx /= distance;
                dz /= distance;
            }
            x += dx * strength * MOST;
            z += dz * strength * MOST;
        }
        double length = Math.hypot(x, z);
        if (length > MOST) {
            x = x / length * MOST;
            z = z / length * MOST;
        }
        return new Steering.Vec(x, 0, z);
    }

    /** A spot to explore that is not where another companion is or is going. */
    public static Steering.Vec freeSpot(Steering.Vec around, double radius, boolean flies, List<Steering.Vec> taken,
                                        Random random) {
        Steering.Vec best = null;
        double bestRoom = -1;
        for (int tries = 0; tries < 16; tries++) {
            Steering.Vec spot = Wandering.spot(around, radius, flies, random);
            double room = Double.MAX_VALUE;
            for (Steering.Vec other : taken) {
                room = Math.min(room, Math.hypot(spot.x() - other.x(), spot.z() - other.z()));
            }
            if (room >= APART) {
                return spot;
            }
            if (room > bestRoom) {
                bestRoom = room;
                best = spot;
            }
        }
        return best;
    }
}
