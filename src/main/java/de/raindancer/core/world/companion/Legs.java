package de.raindancer.core.world.companion;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cat;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Sittable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * How a companion gets somewhere on its own thread: a walker along a short {@link Pathing} route (around walls, up
 * steps, away from lava), a flyer straight where the air is clear and along a route where it is not, at a sensible
 * {@link Flight} height; and how it rests — sitting, lying, perching, bobbing — and where it looks.
 */
final class Legs {

    private static final int BUDGET = 1500;
    private static final int REPLAN = 30;

    /** Where a companion is on its way, and how it rests. */
    static final class State {
        Pathing.Course course;
        long planned = Long.MIN_VALUE / 2;
        final Progress progress = new Progress();
        Location goal;
        Resting.Pose pose = Resting.Pose.STAND;
        long stillSince = -1;
        long glanceUntil;
        float glanceYaw;
        final double phase = Math.random();
        /** This tick's push off other companions, from {@link Spacing#away}. */
        Steering.Vec nudge = new Steering.Vec(0, 0, 0);
    }

    private final Random random;

    Legs(Random random) {
        this.random = random;
    }

    /**
     * Takes it a tick's way towards {@code aim}.
     *
     * @return ticks it has been getting no nearer; 0 once it is there
     */
    long go(Companion companion, Mob mob, Location aim, double reach, double speed, CompanionSpec spec, long tick,
            Location lookAt) {
        State state = companion.legs;
        standUp(mob, state);
        state.stillSince = -1;
        if (state.goal == null || state.goal.getWorld() != aim.getWorld() || state.goal.distanceSquared(aim) > 2.25) {
            state.progress.reset();
            state.goal = aim.clone();
        }
        Location here = mob.getLocation();
        double flat = Math.hypot(aim.getX() - here.getX(), aim.getZ() - here.getZ());
        double distance = spec.flies() ? Math.hypot(flat, aim.getY() - here.getY()) : flat;
        if (distance <= reach) {
            state.progress.reset();
            state.course = null;
            push(mob, nudged(spec.flies() ? hover(mob, spec, tick, state) : new Steering.Vec(0, fall(mob, spec), 0),
                    state), lookAt, spec, false);
            return 0;
        }
        Steering.Vec waypoint = spec.flies() ? flyTowards(mob, aim, reach, tick, state)
                : walkTowards(mob, aim, reach, tick, state);
        Steering.Vec heading = waypoint == null ? vec(aim) : waypoint;
        double stopAt = waypoint == null ? reach : 0;
        Steering.Vec push;
        if (spec.flies()) {
            push = Steering.towards(vec(here), heading, speed, stopAt, true, 0);
            push = Flight.keepHeight(push, ground(mob), ceiling(mob), speed);
        } else {
            push = Steering.towards(vec(here), heading, speed, stopAt, false, fall(mob, spec));
            if (mob.isOnGround() && heading.y() > here.getY() + 0.4
                    && Math.hypot(heading.x() - here.getX(), heading.z() - here.getZ()) < 1.4) {
                push = new Steering.Vec(push.x(), Steering.JUMP, push.z());
            }
        }
        push(mob, nudged(push, state), lookAt, spec, true);
        state.progress.stuck(distance, tick, Integer.MAX_VALUE);
        return state.progress.stuckFor(tick);
    }

    /** Standing (or hovering) where it is, looking at {@code lookAt}; settling down when {@code settle}. */
    void rest(Companion companion, Mob mob, CompanionSpec spec, long tick, Location lookAt, double vigour,
              boolean settle) {
        State state = companion.legs;
        state.course = null;
        state.progress.reset();
        if (state.stillSince < 0) {
            state.stillSince = tick;
        }
        boolean crowded = state.nudge.length() > 0.03;
        if (!settle || crowded) {
            standUp(mob, state);
            if (crowded) {
                state.stillSince = tick;
            }
        } else if (tick % 20 == 0 && (spec.flies() || mob instanceof Sittable)) {
            Resting.Pose next = Resting.next(state.pose, tick - state.stillSince, vigour, canLie(mob), random);
            if (next != state.pose) {
                state.pose = next;
                pose(mob, next);
            }
        }
        Steering.Vec push;
        if (spec.flies() && state.pose != Resting.Pose.STAND && !(mob instanceof EnderDragon)) {
            double below = ground(mob);
            push = new Steering.Vec(0, below > 0.15 ? -Math.min(0.12, below) : 0, 0);
        } else {
            push = spec.flies() ? hover(mob, spec, tick, state) : new Steering.Vec(0, fall(mob, spec), 0);
        }
        if (state.pose == Resting.Pose.STAND) {
            lookAt = glance(mob, lookAt, tick, state);
        }
        push(mob, nudged(push, state), lookAt, spec, false);
    }

    private static Steering.Vec nudged(Steering.Vec push, State state) {
        Steering.Vec nudge = state.nudge;
        return nudge.x() == 0 && nudge.z() == 0 ? push
                : new Steering.Vec(push.x() + nudge.x(), push.y(), push.z() + nudge.z());
    }

    // ------------------------------------------------------------------ walking

    /** The next cell of its route; null to head straight for the aim. */
    private Steering.Vec walkTowards(Mob mob, Location aim, double reach, long tick, State state) {
        Steering.Vec goal = vec(aim);
        if (state.course == null || !state.course.leadsTo(goal, 1.5) || state.course.done()
                || tick - state.planned >= REPLAN) {
            Location here = mob.getLocation();
            if (tick - state.planned >= 10 || state.course == null) {
                state.course = new Pathing.Course(Pathing.walk(terrain(mob.getWorld()), cell(here), cell(aim),
                        height(mob), Math.max(0, reach - 0.5), BUDGET), goal);
                state.planned = tick;
            }
        }
        return state.course.next(vec(mob.getLocation()));
    }

    // ------------------------------------------------------------------ flying

    private Steering.Vec flyTowards(Mob mob, Location aim, double reach, long tick, State state) {
        Steering.Vec goal = vec(aim);
        if (clear(mob, aim)) {
            state.course = null;
            return null;
        }
        if (state.course == null || !state.course.leadsTo(goal, 1.5) || state.course.done()
                || tick - state.planned >= REPLAN) {
            if (tick - state.planned >= 10 || state.course == null) {
                state.course = new Pathing.Course(Pathing.fly(terrain(mob.getWorld()), cell(mob.getLocation()),
                        cell(aim), height(mob), Math.max(0, reach - 0.5), BUDGET), goal);
                state.planned = tick;
            }
        }
        return state.course.next(vec(mob.getLocation()));
    }

    private static boolean clear(Mob mob, Location aim) {
        Location from = mob.getLocation().add(0, mob.getHeight() / 2, 0);
        Vector way = aim.toVector().add(new Vector(0, 0.2, 0)).subtract(from.toVector());
        double length = way.length();
        if (length < 0.5) {
            return true;
        }
        RayTraceResult hit = mob.getWorld().rayTraceBlocks(from, way.normalize(), length,
                FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null;
    }

    private static Steering.Vec hover(Mob mob, CompanionSpec spec, long tick, State state) {
        if (mob instanceof EnderDragon) {
            return new Steering.Vec(0, Resting.bob(tick, state.phase) * 0.5, 0);
        }
        return Flight.keepHeight(new Steering.Vec(0, Resting.bob(tick, state.phase), 0), ground(mob), ceiling(mob),
                spec.speed());
    }

    /** Blocks down to something solid, up to 10. */
    static double ground(Mob mob) {
        Location at = mob.getLocation();
        RayTraceResult hit = mob.getWorld().rayTraceBlocks(at.clone().add(0, 0.05, 0), new Vector(0, -1, 0), 10,
                FluidCollisionMode.ALWAYS, true);
        return hit == null ? 10 : at.getY() - hit.getHitPosition().getY();
    }

    /** Blocks up from its head to something solid, up to 3. */
    private static double ceiling(Mob mob) {
        Location top = mob.getLocation().add(0, mob.getHeight(), 0);
        RayTraceResult hit = mob.getWorld().rayTraceBlocks(top, new Vector(0, 1, 0), 3, FluidCollisionMode.NEVER,
                true);
        return hit == null ? 3 : hit.getHitPosition().getY() - top.getY();
    }

    // ------------------------------------------------------------------ resting

    private static boolean canLie(Mob mob) {
        return mob instanceof Cat || mob instanceof Fox || mob instanceof Panda;
    }

    private static void pose(Mob mob, Resting.Pose pose) {
        if (mob instanceof Sittable sitter) {
            sitter.setSitting(pose != Resting.Pose.STAND && !(mob instanceof Panda && pose == Resting.Pose.LIE));
        }
        boolean lying = pose == Resting.Pose.LIE;
        if (mob instanceof Cat cat) {
            cat.setLyingDown(lying);
        } else if (mob instanceof Fox fox) {
            fox.setSleeping(lying);
        } else if (mob instanceof Panda panda) {
            panda.setOnBack(lying);
        }
    }

    private static void standUp(Mob mob, State state) {
        if (state.pose != Resting.Pose.STAND) {
            state.pose = Resting.Pose.STAND;
            pose(mob, Resting.Pose.STAND);
        }
    }

    /** Now and then it looks about for a moment instead of at what it watches. */
    private Location glance(Mob mob, Location lookAt, long tick, State state) {
        if (tick >= state.glanceUntil && tick % 20 == 0 && random.nextDouble() < 0.12) {
            state.glanceUntil = tick + 30 + random.nextInt(30);
            state.glanceYaw = mob.getYaw() + (random.nextBoolean() ? 1 : -1) * (40 + random.nextInt(80));
        }
        if (tick < state.glanceUntil) {
            double radians = Math.toRadians(state.glanceYaw);
            return mob.getEyeLocation().add(-Math.sin(radians) * 4, 0, Math.cos(radians) * 4);
        }
        return lookAt;
    }

    // ------------------------------------------------------------------ moving

    /** A walker blocked by one block ahead hops it. */
    static Steering.Vec stepUp(Mob mob, Steering.Vec push, CompanionSpec spec) {
        if (spec.flies() || !mob.isOnGround() || Math.hypot(push.x(), push.z()) < 0.05) {
            return push;
        }
        Vector ahead = new Vector(push.x(), 0, push.z()).normalize().multiply(mob.getWidth() / 2 + 0.4);
        Block front = mob.getLocation().add(ahead).getBlock();
        if (front.isSolid() && front.getRelative(0, 1, 0).isPassable()) {
            return new Steering.Vec(push.x(), Steering.JUMP, push.z());
        }
        return push;
    }

    /** Sets the push, turning to where it goes — or, standing, to {@code lookAt}. */
    static void push(Mob mob, Steering.Vec push, Location lookAt, CompanionSpec spec, boolean walking) {
        push = stepUp(mob, push, spec);
        boolean moving = walking && Math.hypot(push.x(), push.z()) > 0.01;
        Location eye = mob.getEyeLocation();
        float yaw = moving ? Steering.yawOf(push) : yawTowards(eye, lookAt);
        float pitch = moving || lookAt == null ? 0 : pitchTowards(eye, lookAt);
        if (mob instanceof EnderDragon) {
            Location next = mob.getLocation().add(push.x(), push.y(), push.z());
            next.setYaw(yaw + 180);
            next.setPitch(0);
            mob.teleportAsync(next, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN,
                    TeleportFlag.EntityState.RETAIN_PASSENGERS);
            return;
        }
        mob.setVelocity(new Vector(push.x(), push.y(), push.z()));
        mob.setRotation(yaw, pitch);
        if (moving) {
            mob.setBodyYaw(yaw);
        }
    }

    static double fall(Mob mob, CompanionSpec spec) {
        return spec.flies() ? 0 : mob.getVelocity().getY();
    }

    private static float yawTowards(Location from, Location to) {
        if (to == null) {
            return from.getYaw();
        }
        return Steering.yawOf(new Steering.Vec(to.getX() - from.getX(), 0, to.getZ() - from.getZ()));
    }

    private static float pitchTowards(Location from, Location to) {
        double flat = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        double pitch = -Math.toDegrees(Math.atan2(to.getY() - from.getY(), Math.max(0.01, flat)));
        return (float) Math.max(-40, Math.min(40, pitch));
    }

    // ------------------------------------------------------------------ blocks

    private static int height(Mob mob) {
        return Math.max(1, (int) Math.ceil(mob.getHeight() - 0.05));
    }

    private static Pathing.Cell cell(Location at) {
        return new Pathing.Cell(at.getBlockX(), (int) Math.floor(at.getY() + 0.05), at.getBlockZ());
    }

    static Steering.Vec vec(Location at) {
        return new Steering.Vec(at.getX(), at.getY(), at.getZ());
    }

    private static Pathing.Terrain terrain(World world) {
        return Terrains.of(world);
    }
}
