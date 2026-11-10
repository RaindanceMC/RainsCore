package de.raindancer.core.ui.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * A camera's glide through keyframes, for a cutscene: a Catmull-Rom curve through every key's position, yaw and
 * pitch interpolated the same way (yaw always the short way round), and the whole flight eased in and out so it
 * starts and lands gently. Pure arithmetic — {@link Cutscene} moves an entity along it.
 *
 * @see #through(List, double)
 */
public final class CameraPath {

    /** One place the camera passes, looking a certain way; the caption is shown as it gets there (blank: none). */
    public record Key(double x, double y, double z, float yaw, float pitch, String caption) {
    }

    /** Where the camera is and how it looks at one moment. Yaw is continuous here, not wrapped to ±180. */
    public record Frame(double x, double y, double z, float yaw, float pitch) {
    }

    private final List<Key> keys;
    private final double[] yaws;
    private final double gap;

    private CameraPath(List<Key> keys, double gap) {
        this.keys = List.copyOf(keys);
        this.gap = gap;
        this.yaws = new double[keys.size()];
        for (int i = 0; i < yaws.length; i++) {
            double yaw = keys.get(i).yaw();
            yaws[i] = i == 0 ? yaw : yaws[i - 1] + wrap((float) (yaw - yaws[i - 1]));
        }
    }

    /** @param secondsBetween how long the glide from one key to the next takes */
    public static CameraPath through(List<Key> keys, double secondsBetween) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("a camera path needs a key");
        }
        if (!(secondsBetween > 0)) {
            throw new IllegalArgumentException("a camera path needs some time between keys");
        }
        return new CameraPath(new ArrayList<>(keys), secondsBetween);
    }

    public List<Key> keys() {
        return keys;
    }

    public double seconds() {
        return gap * (keys.size() - 1);
    }

    public Frame at(double seconds) {
        double p = param(seconds);
        int last = keys.size() - 1;
        int i = Math.min((int) Math.floor(p), Math.max(0, last - 1));
        double f = last == 0 ? 0 : p - i;
        Key a = key(i - 1);
        Key b = key(i);
        Key c = key(i + 1);
        Key d = key(i + 2);
        return new Frame(
                spline(a.x(), b.x(), c.x(), d.x(), f),
                spline(a.y(), b.y(), c.y(), d.y(), f),
                spline(a.z(), b.z(), c.z(), d.z(), f),
                (float) spline(yaw(i - 1), yaw(i), yaw(i + 1), yaw(i + 2), f),
                (float) spline(a.pitch(), b.pitch(), c.pitch(), d.pitch(), f));
    }

    /** The last key the camera has got to by {@code seconds}. */
    public int keyReached(double seconds) {
        return Math.min(keys.size() - 1, (int) Math.floor(param(seconds) + 1e-9));
    }

    /** When the camera gets to key {@code index}. */
    public double secondsAt(int index) {
        int last = keys.size() - 1;
        if (last == 0 || index <= 0) {
            return 0;
        }
        if (index >= last) {
            return seconds();
        }
        double target = (double) index / last;
        double low = 0;
        double high = 1;
        for (int step = 0; step < 60; step++) {
            double mid = (low + high) / 2;
            if (ease(mid) < target) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2 * seconds();
    }

    /** An angle in degrees as Minecraft wants it, -180 to 180. */
    public static float wrap(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        } else if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    /** How far along the keys, 0 to keys-1, eased so the flight starts and lands gently. */
    private double param(double seconds) {
        double total = seconds();
        if (total <= 0) {
            return 0;
        }
        double u = Math.max(0, Math.min(1, seconds / total));
        return ease(u) * (keys.size() - 1);
    }

    private static double ease(double u) {
        return u * u * (3 - 2 * u);
    }

    private Key key(int index) {
        return keys.get(Math.max(0, Math.min(keys.size() - 1, index)));
    }

    private double yaw(int index) {
        return yaws[Math.max(0, Math.min(yaws.length - 1, index))];
    }

    private static double spline(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}
