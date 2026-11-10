package de.raindancer.core.world.npc;

import java.util.List;

/**
 * A walk along a way of points, a step a tick at a guide's speed, waiting while the player it leads is too far
 * behind. Pure arithmetic — {@link Guide} moves a body along it.
 */
public final class GuideWalk {

    public record Point(double x, double y, double z) {

        double distance(Point other) {
            return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(y - other.y, 2) + Math.pow(z - other.z, 2));
        }
    }

    private final List<Point> way;
    private final double speed;
    private final double waitBeyond;
    private int next = 1;
    private Point at;
    private float yaw;
    private boolean waiting;

    /**
     * @param speed      blocks a tick — 0.22 is a walk
     * @param waitBeyond how far behind the player may fall before the guide waits for them
     */
    public GuideWalk(List<Point> way, double speed, double waitBeyond) {
        if (way.isEmpty()) {
            throw new IllegalArgumentException("a walk needs a point");
        }
        this.way = List.copyOf(way);
        this.speed = speed;
        this.waitBeyond = waitBeyond;
        this.at = way.getFirst();
    }

    /** @param playerDistance how far the player is from the guide now */
    public Point step(double playerDistance) {
        if (done()) {
            return at;
        }
        if (playerDistance > waitBeyond) {
            waiting = true;
            return at;
        }
        waiting = false;
        double left = speed;
        while (left > 0 && !done()) {
            Point target = way.get(next);
            double gap = at.distance(target);
            if (gap > 1e-9) {
                yaw = yawTowards(at, target);
            }
            if (gap <= left) {
                at = target;
                left -= gap;
                next++;
            } else {
                double share = left / gap;
                at = new Point(at.x() + (target.x() - at.x()) * share, at.y() + (target.y() - at.y()) * share,
                        at.z() + (target.z() - at.z()) * share);
                left = 0;
            }
        }
        return at;
    }

    public Point at() {
        return at;
    }

    public float yaw() {
        return yaw;
    }

    public boolean waiting() {
        return waiting;
    }

    public boolean done() {
        return next >= way.size();
    }

    /** Minecraft's yaw from {@code from} looking at {@code to}: +z is 0, -x is 90. */
    public static float yawTowards(Point from, Point to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x() - from.x()), to.z() - from.z()));
    }
}
