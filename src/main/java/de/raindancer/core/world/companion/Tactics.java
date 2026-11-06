package de.raindancer.core.world.companion;

import java.util.Random;

/** Where a fighting companion puts itself, by how it fights ({@link Fighting}) and how its owner wants it to ({@link Tactic}). */
public final class Tactics {

    /** A guard leaves its post for what comes this near its owner. */
    static final double GUARD_ENGAGE = 5;
    /** A healer bites only what is this near its owner. */
    static final double HEALER_ENGAGE = 4;
    private static final double ORBIT_ANGLE = Math.toRadians(50);

    /**
     * @param reach how near it must be to bite
     * @param ready its bite or throw is ready
     * @param side  which way it circles, 1 or −1
     */
    public record Situation(Steering.Vec here, Steering.Vec target, Steering.Vec owner, double reach, boolean ready,
                            int side, Fighting fighting, Tactic tactic) {
    }

    /**
     * @param aim   where to go
     * @param reach how near is near enough
     * @param pace  its speed times this
     */
    public record Plan(Steering.Vec aim, double reach, double pace) {
    }

    private Tactics() {
    }

    public static Plan plan(Situation s) {
        return switch (s.fighting()) {
            case RANGED -> ranged(s);
            case GUARD -> guard(s);
            case HEALER -> healer(s);
            case MELEE -> melee(s);
        };
    }

    private static Plan melee(Situation s) {
        double reach = Math.max(0.3, s.reach() - 0.4);
        if (s.ready() || s.tactic() == Tactic.RELENTLESS) {
            return new Plan(s.target(), reach, s.tactic() == Tactic.RELENTLESS ? 1.25 : 1.15);
        }
        Steering.Vec away = flatUnit(s.target(), s.here(), s.owner());
        if (s.tactic() == Tactic.HIT_AND_RUN) {
            return new Plan(offset(s.target(), away, s.reach() + 2.5), 0.3, 1.1);
        }
        Steering.Vec round = rotate(away, ORBIT_ANGLE * s.side());
        return new Plan(offset(s.target(), round, s.reach() + 0.8), 0.2, 0.8);
    }

    private static Plan ranged(Situation s) {
        double[] band = band(s.tactic());
        double distance = flat(s.here(), s.target());
        Steering.Vec away = flatUnit(s.target(), s.here(), s.owner());
        if (s.tactic() == Tactic.CAUTIOUS && flat(s.here(), s.owner()) > 5) {
            return new Plan(s.owner(), 2.5, 1.0);
        }
        if (distance < band[0]) {
            return new Plan(offset(s.here(), away, band[0] - distance + 1.5), 0.2, 1.05);
        }
        if (distance > band[1]) {
            return new Plan(s.target(), band[1] - 1, 1.0);
        }
        Steering.Vec aside = rotate(away, Math.PI / 2 * s.side());
        return new Plan(offset(s.here(), aside, 2), 0.2, 0.5);
    }

    /** Nearest and furthest a thrower likes its foe. */
    public static double[] band(Tactic tactic) {
        return switch (tactic) {
            case RELENTLESS -> new double[]{3, 7};
            case HIT_AND_RUN, CAUTIOUS -> new double[]{6, 12};
            case BALANCED -> new double[]{5, 10};
        };
    }

    private static Plan guard(Situation s) {
        double threat = flat(s.owner(), s.target());
        if (threat <= GUARD_ENGAGE || (s.ready() && flat(s.here(), s.target()) <= s.reach() + 1)) {
            return melee(s);
        }
        Steering.Vec toward = flatUnit(s.owner(), s.target(), s.here());
        return new Plan(offset(s.owner(), toward, Math.min(2.5, threat / 2)), 0.5, 1.1);
    }

    private static Plan healer(Situation s) {
        if (flat(s.owner(), s.target()) <= HEALER_ENGAGE && flat(s.here(), s.target()) <= s.reach() + 1) {
            return melee(s);
        }
        return new Plan(s.owner(), 2, 1.0);
    }

    /** Ticks until its next bite or throw: its attack time, a little uneven so it does not look wound up. */
    public static int cooldown(int attackTicks, Random random) {
        return (int) Math.round(attackTicks * (0.85 + random.nextDouble() * 0.35));
    }

    /** How far from its owner a fight may take it. */
    public static double leash(Tactic tactic) {
        return switch (tactic) {
            case CAUTIOUS -> 8;
            case RELENTLESS -> 22;
            case BALANCED, HIT_AND_RUN -> 16;
        };
    }

    /** Ticks without getting nearer, or without sight of it for a thrower, before it gives up on a foe. */
    public static int patience(Tactic tactic) {
        return switch (tactic) {
            case CAUTIOUS -> 60;
            case RELENTLESS -> 200;
            case BALANCED, HIT_AND_RUN -> 100;
        };
    }

    /** Below this share of its health it leaves the fight for its owner's side. */
    public static double turnsBack(double fleesBelow, Tactic tactic) {
        return switch (tactic) {
            case CAUTIOUS -> Math.max(fleesBelow, 0.4);
            case RELENTLESS -> fleesBelow / 2;
            case BALANCED, HIT_AND_RUN -> fleesBelow;
        };
    }

    // ------------------------------------------------------------------ flat geometry

    private static double flat(Steering.Vec a, Steering.Vec b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** From {@code from} towards {@code to} on the flat; towards {@code fallback} when they are on top of each other. */
    private static Steering.Vec flatUnit(Steering.Vec from, Steering.Vec to, Steering.Vec fallback) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double length = Math.hypot(dx, dz);
        if (length < 1e-6) {
            dx = fallback.x() - from.x();
            dz = fallback.z() - from.z();
            length = Math.hypot(dx, dz);
            if (length < 1e-6) {
                return new Steering.Vec(1, 0, 0);
            }
        }
        return new Steering.Vec(dx / length, 0, dz / length);
    }

    private static Steering.Vec rotate(Steering.Vec unit, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new Steering.Vec(unit.x() * cos - unit.z() * sin, 0, unit.x() * sin + unit.z() * cos);
    }

    private static Steering.Vec offset(Steering.Vec from, Steering.Vec unit, double by) {
        return new Steering.Vec(from.x() + unit.x() * by, from.y(), from.z() + unit.z() * by);
    }
}
