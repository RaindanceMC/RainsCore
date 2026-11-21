package de.raindancer.core.world.visual;

import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Blocks only one player sees: a building that is not there yet, drawn as small floating copies of its blocks, and a
 * red cage around a block that is wrong. Nobody else sees any of it and the world never changes.
 *
 * <h2>Two ways of drawing, for two jobs</h2>
 * <ul>
 *   <li><b>Block displays</b> ({@link #show}) — entities, spawned hidden and shown to one player. Scaled down, so the
 *   real world shows between them and they read as "this goes here" rather than as blocks; they have no collision
 *   and cannot be clicked, so building into them works. An entity each, so there is a budget, filled nearest first.
 *   Updates are a diff: what stays is not spawned again.</li>
 *   <li><b>Fake blocks</b> ({@link #preview}) — block change packets: as many as you like, full size, but the
 *   client takes them for real blocks (it collides with them), so only for a short look at the finished thing, then
 *   the real blocks are sent back.</li>
 * </ul>
 *
 * <p>Folia-safe: every spawn and every read runs on the region that owns the chunk, grouped by chunk; a player who
 * leaves, or a newer {@code show}, makes the stragglers of an older one remove themselves.
 */
public final class Ghosts {

    /** How a ghost is drawn. */
    public enum Look {
        /** The planned block, small and lit — "put this here". */
        PLANNED,
        /** A red glowing cage around the spot — "what is here is wrong". The state is what to draw inside it. */
        WRONG
    }

    /** One ghost block at a world position. Two ghosts are the same only with the same state and look. */
    public record Ghost(int x, int y, int z, String state, Look look) {
    }

    /** What an update has to do: spawn these, remove those. */
    public record Change(List<Ghost> spawn, List<Ghost> remove) {
    }

    private static final Color WRONG_GLOW = Color.fromRGB(0xFF3B3B);
    private static final String WRONG_CAGE = "minecraft:red_stained_glass";

    private static final class Shown {
        final World world;
        volatile Set<Ghost> wanted = Set.of();
        final Map<Ghost, Entity> entities = new ConcurrentHashMap<>();

        Shown(World world) {
            this.world = world;
        }
    }

    private final Plugin plugin;
    private final Map<UUID, Shown> byPlayer = new ConcurrentHashMap<>();
    private final Map<String, BlockData> parsed = new ConcurrentHashMap<>();
    private volatile float scale;

    /** @param scale how big a planned ghost is, 0.2..1 — small enough to see through, big enough to recognise */
    public Ghosts(Plugin plugin, float scale) {
        this.plugin = plugin;
        scale(scale);
    }

    public void scale(float scale) {
        this.scale = Math.max(0.2f, Math.min(1f, scale));
    }

    // ------------------------------------------------------------------ the arithmetic, no server

    /** The ghosts to draw when there are more than {@code budget}: the nearest to the viewer. */
    public static List<Ghost> within(Collection<Ghost> wanted, double x, double y, double z, int budget) {
        if (wanted.size() <= budget) {
            return List.copyOf(wanted);
        }
        if (budget <= 0) {
            return List.of();
        }
        return wanted.stream()
                .sorted(Comparator.comparingDouble(ghost -> square(ghost.x() + 0.5 - x)
                        + square(ghost.y() + 0.5 - y) + square(ghost.z() + 0.5 - z)))
                .limit(budget)
                .toList();
    }

    public static Change change(Set<Ghost> shown, Collection<Ghost> wanted) {
        Set<Ghost> next = new HashSet<>(wanted);
        List<Ghost> spawn = new ArrayList<>();
        for (Ghost ghost : next) {
            if (!shown.contains(ghost)) {
                spawn.add(ghost);
            }
        }
        List<Ghost> remove = new ArrayList<>();
        for (Ghost ghost : shown) {
            if (!next.contains(ghost)) {
                remove.add(ghost);
            }
        }
        return new Change(spawn, remove);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    public static <T> Map<Long, List<T>> byChunk(Collection<T> items, Function<T, int[]> xz) {
        Map<Long, List<T>> grouped = new LinkedHashMap<>();
        for (T item : items) {
            int[] at = xz.apply(item);
            grouped.computeIfAbsent(chunkKey(at[0] >> 4, at[1] >> 4), key -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    public static Map<Long, List<Ghost>> byChunk(Collection<Ghost> ghosts) {
        return byChunk(ghosts, ghost -> new int[]{ghost.x(), ghost.z()});
    }

    /**
     * Points along the twelve edges of the box from {@code (x0,y0,z0)} to {@code (x1,y1,z1)} (outer faces, block
     * units), one every {@code step}, every corner once.
     */
    public static List<double[]> edges(double x0, double y0, double z0, double x1, double y1, double z1,
                                       double step) {
        Set<List<Double>> points = new java.util.LinkedHashSet<>();
        double[] xs = {x0, x1};
        double[] ys = {y0, y1};
        double[] zs = {z0, z1};
        for (double y : ys) {
            for (double z : zs) {
                line(points, x0, x1, step, value -> List.of(value, y, z));
            }
        }
        for (double x : xs) {
            for (double z : zs) {
                line(points, y0, y1, step, value -> List.of(x, value, z));
            }
        }
        for (double x : xs) {
            for (double y : ys) {
                line(points, z0, z1, step, value -> List.of(x, y, value));
            }
        }
        List<double[]> out = new ArrayList<>(points.size());
        for (List<Double> point : points) {
            out.add(new double[]{point.get(0), point.get(1), point.get(2)});
        }
        return out;
    }

    private static void line(Set<List<Double>> points, double from, double to, double step,
                             Function<Double, List<Double>> at) {
        double gap = Math.max(0.1, step);
        for (double value = from; value < to; value += gap) {
            points.add(at.apply(value));
        }
        points.add(at.apply(to));
    }

    private static double square(double value) {
        return value * value;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Shows {@code wanted} to the player — at most {@code budget} of them, nearest first — replacing whatever this
     * showed them before. Only what changed is spawned or removed.
     */
    public void show(Player player, World world, Collection<Ghost> wanted, int budget) {
        if (player == null || world == null) {
            return;
        }
        UUID id = player.getUniqueId();
        Shown shown = byPlayer.get(id);
        if (shown == null || !shown.world.getUID().equals(world.getUID())) {
            clear(player);
            shown = new Shown(world);
            byPlayer.put(id, shown);
        }
        Location eye = player.getLocation();
        List<Ghost> chosen = within(wanted, eye.getX(), eye.getY(), eye.getZ(), budget);
        Change change = change(shown.wanted, chosen);
        shown.wanted = Set.copyOf(chosen);
        for (Ghost gone : change.remove()) {
            Entity entity = shown.entities.remove(gone);
            if (entity != null) {
                Scheduling.entity(plugin, entity, entity::remove);
            }
        }
        Shown target = shown;
        for (List<Ghost> chunk : byChunk(change.spawn()).values()) {
            Ghost first = chunk.getFirst();
            Scheduling.region(plugin, new Location(world, first.x(), first.y(), first.z()),
                    () -> spawn(player, target, chunk));
        }
    }

    private void spawn(Player player, Shown shown, List<Ghost> ghosts) {
        if (!player.isOnline() || byPlayer.get(player.getUniqueId()) != shown) {
            return;
        }
        World world = shown.world;
        for (Ghost ghost : ghosts) {
            if (!shown.wanted.contains(ghost) || shown.entities.containsKey(ghost)
                    || !world.isChunkLoaded(ghost.x() >> 4, ghost.z() >> 4)) {
                continue;
            }
            BlockData data = data(ghost.look() == Look.WRONG ? WRONG_CAGE : ghost.state());
            if (data == null) {
                continue;
            }
            BlockDisplay display = world.spawn(new Location(world, ghost.x(), ghost.y(), ghost.z()),
                    BlockDisplay.class, made -> {
                        made.setVisibleByDefault(false);
                        made.setPersistent(false);
                        made.setBlock(data);
                        made.setBrightness(new Display.Brightness(15, 15));
                        made.setViewRange(1.5f);
                        float size = ghost.look() == Look.WRONG ? 1.04f : scale;
                        float offset = (1f - size) / 2f;
                        made.setTransformation(new Transformation(new Vector3f(offset, offset, offset),
                                new AxisAngle4f(), new Vector3f(size, size, size), new AxisAngle4f()));
                        if (ghost.look() == Look.WRONG) {
                            made.setGlowing(true);
                            made.setGlowColorOverride(WRONG_GLOW);
                        }
                    });
            shown.entities.put(ghost, display);
            Scheduling.entity(plugin, player, () -> {
                if (byPlayer.get(player.getUniqueId()) == shown && shown.entities.get(ghost) == display) {
                    player.showEntity(plugin, display);
                } else {
                    Scheduling.entity(plugin, display, display::remove);
                }
            });
        }
    }

    /** How many ghost entities the player has standing right now. */
    public int count(Player player) {
        Shown shown = byPlayer.get(player.getUniqueId());
        return shown == null ? 0 : shown.entities.size();
    }

    /** Takes every ghost away from the player and ends a preview early. */
    public void clear(Player player) {
        if (player == null) {
            return;
        }
        Shown shown = byPlayer.remove(player.getUniqueId());
        if (shown == null) {
            return;
        }
        shown.wanted = Set.of();
        for (Entity entity : shown.entities.values()) {
            Scheduling.entity(plugin, entity, entity::remove);
        }
        shown.entities.clear();
    }

    /** For when the plugin stops: nobody keeps ghosts nobody can take away any more. */
    public void clearAll() {
        for (UUID id : List.copyOf(byPlayer.keySet())) {
            Shown shown = byPlayer.remove(id);
            if (shown != null) {
                shown.wanted = Set.of();
                for (Entity entity : shown.entities.values()) {
                    if (Scheduling.isFolia()) {
                        Scheduling.entity(plugin, entity, entity::remove);
                    } else {
                        entity.remove();
                    }
                }
                shown.entities.clear();
            }
        }
    }

    /**
     * Shows the player the whole thing as real-looking blocks for {@code ticks}, where the world has air, then sends
     * the real blocks back. The client collides with them meanwhile — a look, not a place to walk.
     *
     * @param blocks block states by world position
     */
    public void preview(Player player, World world, Map<Position, String> blocks, long ticks) {
        if (player == null || world == null || blocks.isEmpty()) {
            return;
        }
        Map<Long, List<Map.Entry<Position, String>>> chunks = byChunk(blocks.entrySet(),
                entry -> new int[]{entry.getKey().blockX(), entry.getKey().blockZ()});
        for (List<Map.Entry<Position, String>> chunk : chunks.values()) {
            Position first = chunk.getFirst().getKey();
            Location anchor = new Location(world, first.blockX(), first.blockY(), first.blockZ());
            Scheduling.region(plugin, anchor, () -> {
                Map<Position, BlockData> fake = new HashMap<>();
                for (Map.Entry<Position, String> entry : chunk) {
                    Position at = entry.getKey();
                    if (!world.isChunkLoaded(at.blockX() >> 4, at.blockZ() >> 4)
                            || !world.getBlockAt(at.blockX(), at.blockY(), at.blockZ()).isEmpty()) {
                        continue;
                    }
                    BlockData data = data(entry.getValue());
                    if (data != null) {
                        fake.put(at, data);
                    }
                }
                if (!fake.isEmpty()) {
                    Scheduling.entity(plugin, player, () -> player.sendMultiBlockChange(fake));
                }
                Scheduling.regionLater(plugin, anchor, Math.max(1, ticks), () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    Map<Position, BlockData> real = new HashMap<>();
                    for (Position at : fake.keySet()) {
                        real.put(at, world.getBlockAt(at.blockX(), at.blockY(), at.blockZ()).getBlockData());
                    }
                    Scheduling.entity(plugin, player, () -> player.sendMultiBlockChange(real));
                });
            });
        }
    }

    /** Draws the box's edges in particles for the player, once — call it on a timer to keep it standing. */
    public static void outline(Player player, World world, int x0, int y0, int z0, int x1, int y1, int z1,
                               Color colour) {
        if (player == null || world == null || !player.getWorld().getUID().equals(world.getUID())) {
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(colour, 1.0f);
        for (double[] point : edges(x0, y0, z0, x1, y1, z1, 1.0)) {
            player.spawnParticle(Particle.DUST, point[0], point[1], point[2], 1, 0, 0, 0, 0, dust);
        }
    }

    private BlockData data(String state) {
        BlockData known = parsed.get(state);
        if (known != null) {
            return known.clone();
        }
        try {
            BlockData made = Bukkit.createBlockData(state);
            parsed.put(state, made);
            return made.clone();
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
