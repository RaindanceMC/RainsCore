package de.raindancer.core.ui.effect;

import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Display;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A display slowly turning round its upright axis, smoothly, at the cost of one small update per third of a turn.
 * The client does the turning: every step sets the next third as the target and lets the display's own
 * interpolation slide there. A step must stay under half a turn — the client always slerps the short way, so a
 * bigger step would spin backwards.
 */
public final class Spin {

    /** Thirds: the largest step that still turns the intended way, and the fewest updates per turn. */
    public static final int STEPS = 3;

    private Spin() {
    }

    /**
     * Turns {@code display} for as long as it exists: its billboard becomes {@link Display.Billboard#FIXED}
     * (any other one would face the viewer instead), and the timer dies with the entity, so a display removed
     * or unloaded leaves nothing running.
     */
    public static ScheduledTask start(Plugin plugin, Display display, int ticksPerTurn) {
        long stepTicks = stepTicks(ticksPerTurn);
        Transformation base = display.getTransformation();
        display.setBillboard(Display.Billboard.FIXED);
        display.setInterpolationDuration((int) stepTicks);
        AtomicLong step = new AtomicLong();
        // Two ticks late, so the client has seen the display standing before the first step starts sliding.
        return Scheduling.entityTimer(plugin, display, 2, stepTicks, ignored -> {
            display.setInterpolationDelay(0);
            display.setTransformation(turned(base, step.incrementAndGet()));
        });
    }

    /** {@code base} turned {@code step} thirds round the upright axis, everything else kept. */
    public static Transformation turned(Transformation base, long step) {
        float angle = (float) (Math.floorMod(step, STEPS) * 2 * Math.PI / STEPS);
        Quaternionf left = new Quaternionf().rotateY(angle).mul(base.getLeftRotation());
        return new Transformation(base.getTranslation(), left, base.getScale(), base.getRightRotation());
    }

    static long stepTicks(int ticksPerTurn) {
        if (ticksPerTurn < STEPS) {
            throw new IllegalArgumentException("a turn needs at least " + STEPS + " ticks, got " + ticksPerTurn);
        }
        return ticksPerTurn / STEPS;
    }
}
