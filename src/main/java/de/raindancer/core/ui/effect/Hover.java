package de.raindancer.core.ui.effect;

import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Display;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A display turning round its upright axis and/or bobbing up and down like a dropped item, in one motion: both
 * live in the same transformation, so two timers would overwrite each other's interpolation. The client slides
 * between samples linearly — a bob is sampled six times, which reads as a sine, a turn alone in thirds
 * ({@link Spin}). Every turning step stays under half a turn; the client slerps the short way.
 *
 * @param ticksPerTurn 0 for no turning
 * @param bob          blocks up and down round where the display was made, 0 for none (a dropped item: 0.1)
 */
public record Hover(int ticksPerTurn, float bob) {

    /** About a dropped item's bob. */
    public static final int BOB_TICKS = 60;

    public Hover {
        if (ticksPerTurn < 0 || ticksPerTurn > 0 && ticksPerTurn < Spin.STEPS) {
            throw new IllegalArgumentException("a turn needs 0 or at least " + Spin.STEPS + " ticks, got " + ticksPerTurn);
        }
        if (bob < 0) {
            throw new IllegalArgumentException("a bob cannot be negative, got " + bob);
        }
    }

    public boolean moves() {
        return ticksPerTurn > 0 || bob > 0;
    }

    public long stepTicks() {
        long turnStep = ticksPerTurn / Spin.STEPS;
        if (bob <= 0) {
            return turnStep;
        }
        return ticksPerTurn > 0 ? Math.min(BOB_TICKS / 6, turnStep) : BOB_TICKS / 6;
    }

    /** Where {@code base} is {@code tick} ticks into the motion. */
    public Transformation at(Transformation base, long tick) {
        Quaternionf left = new Quaternionf(base.getLeftRotation());
        if (ticksPerTurn > 0) {
            float angle = (float) (2 * Math.PI * Math.floorMod(tick, ticksPerTurn) / ticksPerTurn);
            left = new Quaternionf().rotateY(angle).mul(left);
        }
        Vector3f translation = new Vector3f(base.getTranslation());
        if (bob > 0) {
            translation.y += (float) (bob * Math.sin(2 * Math.PI * Math.floorMod(tick, BOB_TICKS) / BOB_TICKS));
        }
        return new Transformation(translation, left, base.getScale(), base.getRightRotation());
    }

    /**
     * Moves {@code display} for as long as it exists; the timer dies with the entity, so a display removed or
     * unloaded leaves nothing running. A turning display's billboard becomes {@link Display.Billboard#FIXED} —
     * any other one would face the viewer instead. Returns null, starting nothing, when there is no motion.
     */
    public ScheduledTask start(Plugin plugin, Display display) {
        if (!moves()) {
            return null;
        }
        long stepTicks = stepTicks();
        Transformation base = display.getTransformation();
        if (ticksPerTurn > 0) {
            display.setBillboard(Display.Billboard.FIXED);
        }
        display.setInterpolationDuration((int) stepTicks);
        AtomicLong step = new AtomicLong();
        // Two ticks late, so the client has seen the display standing before the first step starts sliding.
        return Scheduling.entityTimer(plugin, display, 2, stepTicks, ignored -> {
            display.setInterpolationDelay(0);
            display.setTransformation(at(base, step.incrementAndGet() * stepTicks));
        });
    }
}
