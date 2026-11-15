package de.raindancer.core.world.safety;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.chunk.ChunkAt;
import de.raindancer.core.world.chunk.ChunkHolds;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Is it safe to put a player there — asked the way a plugin actually needs to ask it.
 *
 * <h2>Why this sits on top of {@link SafeSpots}</h2>
 * Because of the one thing that makes the plain check useless in practice: a spot in an unloaded
 * chunk cannot be judged at all, and {@link SafeSpots} deliberately refuses to load anything, since
 * generating terrain to answer "is this warp safe" stops the server for everybody on it.
 *
 * <p>So the answer to "the chunk is not loaded" is not to shrug and it is not to load it on the main
 * thread. It is to bring the ground in first, off the main thread, and then judge — which is what
 * this does. A plugin calls {@link #findSafe} and gets a spot or an honest nothing; the loading, the
 * threading and the giving up are handled once here rather than badly in nine places.
 *
 * <h2>What it will not do</h2>
 * It never force-loads. A check is a look, and a look is not a reason to tick a chunk for the rest of
 * the server's life — that is {@link ChunkHolds#keep}, and it is a decision a plugin makes
 * deliberately with its name attached.
 *
 * <h2>Threads — read this before calling {@link #findSafe}</h2>
 * It is asynchronous, and you must <b>never</b> {@code join()} or {@code get()} it on the server
 * thread. Loading a chunk completes <em>on</em> that thread, so a thread waiting for it is a thread
 * blocking the very work it is waiting for: the server hangs until the watchdog kills it, with no
 * exception and nothing in the log to say why.
 *
 * <p>That is not hypothetical. It is how this paragraph came to be written — a live-server check
 * called {@code .join()} and the server stopped dead. Use {@code thenAccept}, and note that the
 * callback arrives on whatever thread finished the load, so hop back before touching the world:
 *
 * <pre>{@code
 * core.safety().findSafe(spot, 8).thenAccept(found -> Scheduling.global(plugin, () ->
 *         found.ifPresentOrElse(this::teleportTo, this::refuse)));
 * }</pre>
 */
public final class Safety {

    private final Plugin plugin;
    private final ChunkHolds chunks;
    private final Function<String, Blocks> blocksIn;

    /**
     * @param blocksIn how to read a world by name; null for a world that is not loaded
     */
    public Safety(Plugin plugin, ChunkHolds chunks, Function<String, Blocks> blocksIn) {
        this.plugin = plugin;
        this.chunks = chunks;
        this.blocksIn = blocksIn;
    }

    /** The checker for one world, or empty when there is no such world loaded. */
    public Optional<SafeSpots> in(String world) {
        Blocks blocks = world == null ? null : blocksIn.apply(world);
        return blocks == null ? Optional.empty() : Optional.of(new SafeSpots(blocks));
    }

    /**
     * What is wrong with a spot, without loading anything.
     *
     * <p>{@link Danger#NOT_LOADED} here means "ask {@link #findSafe} instead", not "unsafe".
     */
    public Danger check(Spot spot) {
        return in(spot == null ? null : spot.world())
                .map(spots -> spots.check(spot))
                .orElse(Danger.OUT_OF_THE_WORLD);
    }

    /**
     * Somewhere safe near this spot, loading whatever has to be loaded to find out.
     *
     * <p>The one a teleport should call. Empty means nowhere within the radius was safe, which a
     * caller must treat as a refusal — falling back to the original spot puts the player in the
     * place already known to be dangerous, which is the bug this whole package exists to stop.
     *
     * @param radius how far sideways to look; also how much world is pulled in, so keep it modest
     */
    public CompletableFuture<Optional<Spot>> findSafe(Spot around, int radius) {
        if (around == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        warnIfOnTheServerThread();
        return chunks.forAMoment(chunksAround(around, radius))
                .thenCompose(ignored -> onTheOwningThread(around, () -> in(around.world())
                        .flatMap(spots -> spots.nearestSafe(around, radius))));
    }

    /**
     * The same, but refusing the bottom of a ravine or a cave mouth even when it is technically safe —
     * see {@link SafeSpots#nearestConsistentHeight} for why a scattered arrival wants this and a warp
     * somebody placed deliberately does not.
     *
     * @param heightTolerance how many blocks a spot may differ from its immediate neighbours and
     *                        still count as the same place. Zero means an exact match; a couple of
     *                        blocks is generally enough to allow for ordinary uneven terrain
     */
    public CompletableFuture<Optional<Spot>> findSafeAtConsistentHeight(Spot around, int radius,
                                                                        int heightTolerance) {
        return findSafeAtConsistentHeight(around, radius, heightTolerance, null);
    }

    /** The same, with the checker configured first — for a search that wants natural ground only. */
    public CompletableFuture<Optional<Spot>> findSafeAtConsistentHeight(
            Spot around, int radius, int heightTolerance,
            Consumer<SafeSpots> setUp) {
        if (around == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        warnIfOnTheServerThread();
        return chunks.forAMoment(chunksAround(around, radius))
                .thenCompose(ignored -> onTheOwningThread(around, () -> in(around.world()).flatMap(spots -> {
                    if (setUp != null) {
                        setUp.accept(spots);
                    }
                    return spots.nearestConsistentHeight(around, radius, heightTolerance);
                })));
    }

    /** The same, with the checker configured first — for water, or for looking at the surroundings. */
    public CompletableFuture<Optional<Spot>> findSafe(Spot around, int radius,
                                                      Consumer<SafeSpots> setUp) {
        if (around == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        warnIfOnTheServerThread();
        return chunks.forAMoment(chunksAround(around, radius))
                .thenCompose(ignored -> onTheOwningThread(around, () -> in(around.world()).flatMap(spots -> {
                    if (setUp != null) {
                        setUp.accept(spots);
                    }
                    return spots.nearestSafe(around, radius);
                })));
    }

    /**
     * Runs the given lookup on the thread allowed to read blocks, and answers with a future either
     * way.
     *
     * <p>{@link ChunkHolds#forAMoment} answers immediately, on the calling thread, when every chunk
     * asked for was already loaded — which for a search near a player almost always is. The rest of
     * the time, {@code World.getChunkAtAsync} finishes the load on whichever thread the server's chunk
     * system happens to hand it back on, and that is not guaranteed to be this one. Either way, what
     * runs next reads blocks, and blocks may only be read on the thread that owns them — so this hops
     * there first rather than trusting whichever thread the future happened to continue on.
     */
    private <T> CompletableFuture<T> onTheOwningThread(Spot around, Supplier<T> lookup) {
        World world = Bukkit.getWorld(around.world());
        // The region that owns the search's centre, not the global one: on Folia the global region
        // owns no chunks at all, and reading a block there throws. (On Paper both are the main
        // thread, as before.) A search wider than a region can still reach past it; radii are small.
        if (world == null || Bukkit.isOwnedByCurrentRegion(world, around.x() >> 4, around.z() >> 4)) {
            return CompletableFuture.completedFuture(lookup.get());
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Scheduling.region(plugin, new Location(world, around.x(), around.y(), around.z()), () -> {
            try {
                future.complete(lookup.get());
            } catch (RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    /**
     * Nothing, unless somebody is about to deadlock the server.
     *
     * <p>A plugin that calls this and then blocks on the answer will hang the server until the
     * watchdog kills it — silently, because a deadlock throws nothing. The one thing that can be
     * done from here is to make sure there is a line in the log naming the plugin that did it,
     * rather than a mystery freeze twenty minutes into a session.
     */
    private void warnIfOnTheServerThread() {
        if (Bukkit.isPrimaryThread() && warnedAboutBlocking.compareAndSet(false, true)) {
            Log.of("safety").warn(
                    "findSafe was called on the server thread. That is fine, but if you then "
                            + "join() or get() the result you will deadlock the server, because the "
                            + "chunk load it is waiting for needs this same thread. Use "
                            + "thenAccept.");
        }
    }

    private final AtomicBoolean warnedAboutBlocking =
            new AtomicBoolean();

    /**
     * Every chunk a search of this size could touch.
     *
     * <p>Worked out from the corners rather than a chunk per block: a radius of 32 is four thousand
     * positions and at most nine chunks, and asking for the same chunk four thousand times is how a
     * safety check becomes the slow part.
     */
    private List<ChunkAt> chunksAround(Spot spot, int radius) {
        int reach = Math.max(0, radius) + 1;
        int from = (spot.x() - reach) >> 4;
        int to = (spot.x() + reach) >> 4;
        int fromZ = (spot.z() - reach) >> 4;
        int toZ = (spot.z() + reach) >> 4;

        List<ChunkAt> needed = new ArrayList<>();
        for (int x = from; x <= to; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                needed.add(new ChunkAt(spot.world(), x, z));
            }
        }
        return needed;
    }
}
