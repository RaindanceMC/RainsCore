package de.raindancer.core.world.build;

import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Putting a schematic into a world one chunk at a time: each chunk is loaded without blocking, then filled on the
 * thread that owns it — on Folia that is its region, on Paper the main thread — with a few chunks in flight at once.
 *
 * <p>A chunk ticket holds each chunk between its load and its filling, so it cannot unload in between and swallow the
 * blocks meant for it.
 */
public final class PasteJob {

    /** Chunks loading or being filled at once. A void chunk loads in a moment; more in flight is only more memory. */
    private static final int IN_FLIGHT = 6;

    private final Plugin plugin;
    private final World world;
    private final Deque<Map.Entry<Long, List<BatchBuilder.Placement>>> waiting;
    private final int chunks;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger finished = new AtomicInteger();
    private final AtomicInteger placed = new AtomicInteger();
    private ScheduledTask timer;
    private volatile boolean cancelled;

    public PasteJob(Plugin plugin, World world, List<BatchBuilder.Placement> placements) {
        this.plugin = plugin;
        this.world = world;
        this.waiting = new ArrayDeque<>(byChunk(placements).entrySet());
        this.chunks = waiting.size();
    }

    public int chunks() {
        return chunks;
    }

    /** Stops sending chunks; the ones already in flight still finish, {@code done} is never told. */
    public void cancel() {
        cancelled = true;
        waiting.clear();
        if (timer != null) {
            timer.cancel();
        }
    }

    /** @param done told how many blocks went in, on the global thread, once every chunk is filled */
    public void start(IntConsumer done) {
        if (chunks == 0) {
            done.accept(0);
            return;
        }
        timer = Scheduling.globalTimer(plugin, 1L, 1L, task -> {
            while (inFlight.get() < IN_FLIGHT && !waiting.isEmpty()) {
                send(waiting.poll(), done);
            }
        });
    }

    private void send(Map.Entry<Long, List<BatchBuilder.Placement>> chunk, IntConsumer done) {
        int x = chunkX(chunk.getKey());
        int z = chunkZ(chunk.getKey());
        inFlight.incrementAndGet();
        world.addPluginChunkTicket(x, z, plugin);
        world.getChunkAtAsync(x, z, true).whenComplete((loaded, failure) ->
                Scheduling.region(plugin, new Location(world, x * 16 + 8, 64, z * 16 + 8), () -> {
                    try {
                        BukkitGround ground = BukkitGround.of(world);
                        for (BatchBuilder.Placement placement : chunk.getValue()) {
                            if (ground != null && ground.set(placement.spot(), placement.material())) {
                                placed.incrementAndGet();
                            }
                        }
                    } finally {
                        world.removePluginChunkTicket(x, z, plugin);
                        inFlight.decrementAndGet();
                        if (finished.incrementAndGet() == chunks || (cancelled && inFlight.get() == 0)) {
                            if (timer != null) {
                                timer.cancel();
                            }
                            if (!cancelled) {
                                Scheduling.global(plugin, () -> done.accept(placed.get()));
                            }
                        }
                    }
                }));
    }

    /** Placements by chunk, in the order the chunks are first met and with the order inside each kept. */
    static Map<Long, List<BatchBuilder.Placement>> byChunk(List<BatchBuilder.Placement> placements) {
        Map<Long, List<BatchBuilder.Placement>> chunks = new LinkedHashMap<>();
        for (BatchBuilder.Placement placement : placements) {
            chunks.computeIfAbsent(key(placement.spot().x() >> 4, placement.spot().z() >> 4),
                    key -> new ArrayList<>()).add(placement);
        }
        return chunks;
    }

    static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    static int chunkZ(long key) {
        return (int) key;
    }
}
