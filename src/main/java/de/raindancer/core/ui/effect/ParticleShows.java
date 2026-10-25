package de.raindancer.core.ui.effect;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import de.raindancer.core.platform.util.Scheduling;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A particle worn by a player: drawn in a {@link ParticleShape} around them, every time it is asked,
 * to whoever nearby wants to see it.
 *
 * <p>Not {@link Effects}: that plays named cues and spaces repeats of a cue apart, which is right for a
 * sound on a click and wrong for something drawn several times a second for as long as it is worn.
 */
public final class ParticleShows {

    private ParticleShows() {
    }

    /** Whether this particle can be drawn with nothing but, at most, a colour. */
    public static boolean canShow(String particle) {
        Particle found = particleOf(particle);
        if (found == null) {
            return false;
        }
        Class<?> data = found.getDataType();
        return data == Void.class || data == Particle.DustOptions.class || data == Color.class;
    }

    /** Whether it needs a colour to show — dust and the tinted ones. */
    public static boolean takesColour(String particle) {
        Particle found = particleOf(particle);
        return found != null && found.getDataType() != Void.class && canShow(particle);
    }

    /**
     * Draws it once. Call on the wearer's own thread ({@code Scheduling.entity}) — on Folia the
     * nearby players are only safe to look at from there.
     *
     * @param colour   for a particle that {@link #takesColour}; ignored otherwise, white if missing
     * @param count    the density: how many points the shape is drawn with, one particle each
     * @param range    how far away somebody may be and still see it
     * @param sees     who wants to — a player who has switched other people's particles off is left out
     */
    public static void around(Player wearer, String particle, Integer colour, int count, ParticleShape shape,
                              long tick, double range, Predicate<Player> sees) {
        around(wearer, particle, colour, null, count, shape, tick, range, sees);
    }

    /** The same, in a gradient from {@code colour} to {@code colourTo} along the shape; null for one colour. */
    public static void around(Player wearer, String particle, Integer colour, Integer colourTo, int count,
                              ParticleShape shape, long tick, double range, Predicate<Player> sees) {
        around(wearer, particle, colour, colourTo, count, shape, tick, range, sees, null);
    }

    /**
     * The same, with every point gone after {@code lifetimeTicks} — for a shape redrawn every tick, such as
     * wings, where dust lingering its usual one to two seconds smears the shape into a cloud. Only a
     * particle that takes a colour can be given a lifetime ({@link #drawnAs}); others keep vanilla's.
     */
    public static void around(Player wearer, String particle, Integer colour, Integer colourTo, int count,
                              ParticleShape shape, long tick, double range, Predicate<Player> sees,
                              Integer lifetimeTicks) {
        around(wearer, particle, colour, colourTo, count, shape, tick, range, sees, lifetimeTicks, 1);
    }

    /**
     * The same, drawing only every {@code share}-th point, a different share each time — for a particle
     * left to behave as Minecraft's own (a flame rising, a leaf falling), which lingers: drawn whole every
     * time it piles into a solid block, drawn in turns it flickers like the real thing.
     */
    public static void around(Player wearer, String particle, Integer colour, Integer colourTo, int count,
                              ParticleShape shape, long tick, double range, Predicate<Player> sees,
                              Integer lifetimeTicks, int share) {
        at(wearer.getLocation(), facing(wearer), wearer, particle, colour, colourTo, count, shape, tick, range, sees,
                lifetimeTicks, share);
    }

    /**
     * The same, around a spot with nobody on it — where somebody is about to land. Call on the thread that owns
     * {@code feet}'s region ({@code Scheduling.region}).
     *
     * @param yaw which way the shape faces
     */
    public static void at(Location feet, float yaw, String particle, Integer colour, Integer colourTo, int count,
                          ParticleShape shape, long tick, double range, Predicate<Player> sees,
                          Integer lifetimeTicks) {
        at(feet, yaw, null, particle, colour, colourTo, count, shape, tick, range, sees, lifetimeTicks, 1);
    }

    private static void at(Location feet, float yaw, Player source, String particle, Integer colour,
                           Integer colourTo, int count, ParticleShape shape, long tick, double range,
                           Predicate<Player> sees, Integer lifetimeTicks, int share) {
        Particle found = particleOf(particle);
        if (found == null || !canShow(particle) || feet.getWorld() == null) {
            return;
        }
        List<Player> viewers = feet.getNearbyPlayers(range).stream().filter(sees).toList();
        if (viewers.isEmpty()) {
            return;
        }
        World world = feet.getWorld();
        Particle drawn = drawnAs(particle, lifetimeTicks);
        boolean trail = drawn == Particle.TRAIL && found != Particle.TRAIL;
        int from = colour == null ? 0xFFFFFF : colour;
        Object single = BukkitEffectSink.dataFor(found.getDataType(), from, dustSize(count));
        boolean blended = colourTo != null && takesColour(particle);
        List<double[]> offsets = shape.offsets(tick, yaw, count);
        for (int index = 0; index < offsets.size(); index++) {
            if (!isDrawnNow(index, tick, share)) {
                continue;
            }
            double[] offset = offsets.get(index);
            double x = feet.getX() + offset[0];
            double y = feet.getY() + offset[1];
            double z = feet.getZ() + offset[2];
            int rgb = blended ? colourAlong(from, colourTo, offset[3]) : from;
            Object data = trail ? trailData(new Location(world, x, y, z), rgb, lifetimeTicks)
                    : blended ? BukkitEffectSink.dataFor(found.getDataType(), rgb, dustSize(count)) : single;
            world.spawnParticle(drawn, viewers, source, x, y, z, 1, 0, 0, 0, 0, data);
        }
    }

    /**
     * Which way a worn shape faces: the body's, not the head's — wings on a back must not swing round
     * every time their wearer looks about.
     */
    public static float facing(Player wearer) {
        return wearer.getBodyYaw();
    }

    /** Whether point {@code index} is in this draw's share: every point once in {@code share} draws. */
    public static boolean isDrawnNow(int index, long tick, int share) {
        return share <= 1 || Math.floorMod(index + tick, share) == 0;
    }

    /** What is actually spawned: a coloured particle given a lifetime becomes a trail point, which has one. */
    public static Particle drawnAs(String particle, Integer lifetimeTicks) {
        Particle found = particleOf(particle);
        if (found == null) {
            return null;
        }
        return lifetimeTicks != null && takesColour(particle) ? Particle.TRAIL : found;
    }

    /** A trail point that stays where it is — its target is itself — and is gone after {@code ticks}. */
    public static Object trailData(Location at, int rgb, int ticks) {
        return new Particle.Trail(at, org.bukkit.Color.fromRGB(rgb & 0xFFFFFF), Math.max(1, ticks));
    }

    /**
     * How big dust is drawn at this density: full size alone, smaller as the points come closer —
     * a dense shape drawn in big dust is a cloud, not a shape.
     */
    public static float dustSize(int density) {
        return (float) Math.max(0.35, Math.min(1.0, 1.0 / Math.sqrt(Math.max(1, density)) * 1.1));
    }

    /** The colour {@code along} (0 to 1) of the way from {@code from} to {@code to}; {@code from} when there is no {@code to}. */
    public static int colourAlong(int from, Integer to, double along) {
        if (to == null) {
            return from;
        }
        return net.kyori.adventure.text.format.TextColor.lerp((float) Math.clamp(along, 0, 1),
                net.kyori.adventure.text.format.TextColor.color(from),
                net.kyori.adventure.text.format.TextColor.color(to)).value();
    }

    /**
     * Shows {@code viewer} the particle in its shape a little in front of them for a few seconds, only to
     * them — drawn through an open menu, which is where somebody is choosing.
     */
    public static void preview(Plugin plugin, Player viewer, String particle, Integer colour, int count,
                               ParticleShape shape, int seconds) {
        preview(plugin, viewer, particle, colour, count, shape, seconds, 1.0);
    }

    /** The same, with the shape moving {@code speed} times as fast as normal. */
    public static void preview(Plugin plugin, Player viewer, String particle, Integer colour, int count,
                               ParticleShape shape, int seconds, double speed) {
        preview(plugin, viewer, particle, colour, null, count, shape, seconds, speed);
    }

    /** The same, in a gradient from {@code colour} to {@code colourTo}; null for one colour. */
    public static void preview(Plugin plugin, Player viewer, String particle, Integer colour, Integer colourTo,
                               int count, ParticleShape shape, int seconds, double speed) {
        Particle found = particleOf(particle);
        if (found == null || !canShow(particle)) {
            return;
        }
        int from = colour == null ? 0xFFFFFF : colour;
        Object single = BukkitEffectSink.dataFor(found.getDataType(), from, dustSize(count));
        boolean blended = colourTo != null && takesColour(particle);
        long rounds = Math.max(1, seconds) * 10L;
        long[] drawn = {0};
        Scheduling.entityTimer(plugin, viewer, 1, 2, task -> {
            if (!viewer.isOnline() || drawn[0]++ >= rounds) {
                task.cancel();
                return;
            }
            Location eye = viewer.getEyeLocation();
            Location feet = eye.clone().add(eye.getDirection().setY(0).normalize().multiply(3)).add(0, -1.6, 0);
            long frame = (long) Math.floor(drawn[0] * Math.max(0.1, speed));
            for (double[] offset : shape.offsets(frame, eye.getYaw() + 180, count)) {
                Object data = blended
                        ? BukkitEffectSink.dataFor(found.getDataType(), colourAlong(from, colourTo, offset[3]),
                            dustSize(count))
                        : single;
                viewer.spawnParticle(found, feet.getX() + offset[0], feet.getY() + offset[1],
                        feet.getZ() + offset[2], 1, 0, 0, 0, 0, data);
            }
        });
    }

    /** The data a preview hands the server: white for a particle that takes a colour, else nothing. */
    public static Object previewData(Particle particle) {
        return BukkitEffectSink.dataFor(particle.getDataType(), 0xFFFFFF);
    }

    static Particle particleOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
