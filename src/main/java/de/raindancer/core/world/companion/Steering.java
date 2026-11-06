package de.raindancer.core.world.companion;

/**
 * The arithmetic of moving a mob a plugin drives — a pet, a mount, a summoned helper: following and riding are a push
 * set on the mob every tick, the same for a wolf, a warden and a dragon, whose own walking is off ({@link CompanionMobs}). Minecraft's angles: yaw 0 faces
 * +z, 90 faces −x; a positive pitch looks down.
 */
public final class Steering {

    /** Upwards push of a jump. */
    public static final double JUMP = 0.5;
    /** Climb and sink speed of a ridden flyer, as a share of its speed. */
    private static final double CLIMB = 0.8;

    public record Vec(double x, double y, double z) {

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    /** What the rider presses. */
    public record Input(boolean forward, boolean backward, boolean left, boolean right, boolean jump) {
    }

    private Steering() {
    }

    /**
     * Towards {@code to}, stopping {@code reach} away.
     *
     * @param fall what a walker keeps of its own vertical motion (gravity)
     */
    public static Vec towards(Vec from, Vec to, double speed, double reach, boolean flies, double fall) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        if (flies) {
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= reach || distance == 0) {
                return new Vec(0, 0, 0);
            }
            double step = Math.min(speed, distance - reach);
            return new Vec(dx / distance * step, dy / distance * step, dz / distance * step);
        }
        double flat = Math.hypot(dx, dz);
        if (flat <= reach || flat == 0) {
            return new Vec(0, fall, 0);
        }
        double step = Math.min(speed, flat - reach);
        return new Vec(dx / flat * step, fall, dz / flat * step);
    }

    /**
     * Where a ridden pet goes.
     *
     * @param onGround whether a walker may jump
     */
    public static Vec ridden(Input input, float yaw, float pitch, double speed, boolean flies, double fall,
                             boolean onGround) {
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        // left of a rider facing +z is +x
        double leftX = forwardZ;
        double leftZ = -forwardX;
        double ahead = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        double aside = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        double x = forwardX * ahead + leftX * aside;
        double z = forwardZ * ahead + leftZ * aside;
        double y;
        if (flies) {
            double tilt = Math.toRadians(pitch);
            double level = Math.cos(tilt);
            x = forwardX * ahead * level + leftX * aside;
            z = forwardZ * ahead * level + leftZ * aside;
            y = -Math.sin(tilt) * ahead + (input.jump() ? CLIMB : 0);
        } else {
            y = 0;
        }
        double flat = Math.sqrt(x * x + z * z + (flies ? y * y : 0));
        if (flat > 1) {
            x /= flat;
            z /= flat;
            if (flies) {
                y /= flat;
            }
        }
        x *= speed;
        z *= speed;
        if (flies) {
            y *= speed;
            if (input.jump() && ahead == 0 && aside == 0) {
                y = speed * CLIMB;
            }
        } else {
            y = input.jump() && onGround ? JUMP : fall;
        }
        return new Vec(x, y, z);
    }

    public static float yawOf(Vec push) {
        return (float) Math.toDegrees(Math.atan2(-push.x(), push.z()));
    }
}
