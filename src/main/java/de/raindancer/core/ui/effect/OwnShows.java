package de.raindancer.core.ui.effect;

import org.bukkit.Particle;

import java.util.Locale;

/**
 * How Core's own particles other than the coffee beans are drawn, point by point: pure maths, so it is the same on
 * every server and testable without one. {@link ParticleShows} puts the points in the world.
 */
public final class OwnShows {

    /** Twitch's brand purple. */
    public static final int TWITCH_PURPLE = 0x9146FF;
    private static final int[] TWITCH_PIXELS = {TWITCH_PURPLE, 0xBF94FF, 0x772CE8};
    private static final int[] GLITCH_TINTS = {0x00E5FF, 0xFF3C78};
    private static final int[] GOLD = {0xFFD966, 0xFFF4C2};

    private OwnShows() {
    }

    /**
     * One point of a show.
     *
     * @param rgb  the dust's colour, null for a particle that takes none
     * @param size the dust's size
     */
    public record Point(Particle particle, double dx, double dy, double dz, Integer rgb, float size) {
    }

    /** Every fifth point of the Twitch trail jumps aside — a different one each tick, so it reads as a glitch. */
    public static boolean isGlitch(int index, long tick) {
        return Math.floorMod(index + tick, 5) == 0;
    }

    /** @param particle {@link ParticleShows#TWITCH} or {@link ParticleShows#STARDUST}, in any case */
    public static Point point(String particle, int index, long tick) {
        String name = particle == null ? "" : particle.trim().toUpperCase(Locale.ROOT);
        return switch (name) {
            case ParticleShows.TWITCH -> twitch(index, tick);
            case ParticleShows.STARDUST -> stardust(index);
            default -> throw new IllegalArgumentException(particle + " is not drawn point by point");
        };
    }

    private static Point twitch(int index, long tick) {
        if (!isGlitch(index, tick)) {
            return new Point(Particle.DUST, 0, 0, 0, TWITCH_PIXELS[Math.floorMod(index, TWITCH_PIXELS.length)], 0.7f);
        }
        double side = Math.floorMod(index + tick, 2L) == 0 ? 1 : -1;
        int tint = GLITCH_TINTS[(int) Math.floorMod(tick, (long) GLITCH_TINTS.length)];
        return new Point(Particle.DUST, 0.18 * side, 0, index % 2 == 0 ? 0.1 : -0.1, tint, 0.6f);
    }

    private static Point stardust(int index) {
        if (index % 3 == 0) {
            return new Point(Particle.END_ROD, 0, 0, 0, null, 1f);
        }
        return new Point(Particle.DUST, 0, 0, 0, GOLD[index % GOLD.length], 0.5f);
    }
}
