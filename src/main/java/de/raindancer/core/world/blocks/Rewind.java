package de.raindancer.core.world.blocks;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Everything that changes in a watched place, put back afterwards: a game round where players may build and break,
 * then the map exactly as it was. Each block's state is kept the first time anything changes it — placed, broken,
 * blown up, burnt, flowed into, pushed — and {@link #rewind} writes those first states back.
 *
 * <p>Register it as a listener while it should watch. Blocks changed by a plugin without an event are not seen;
 * such a plugin calls {@link #remember(Block)} first.
 */
public final class Rewind implements Listener {

    /** A block as it was before the first change. */
    public record Original(String world, int x, int y, int z, String data) {
    }

    /** First states by spot, bounded. The part with no server in it. */
    public static final class Ledger {
        /** Two million blocks: past this a round is not a game any more, and memory matters more. */
        public static final int MOST = 2_000_000;

        private final int most;
        private final Map<String, Map<Long, String>> byWorld = new HashMap<>();
        private int size;

        public Ledger() {
            this(MOST);
        }

        public Ledger(int most) {
            this.most = most;
        }

        public synchronized void remember(String world, int x, int y, int z, String data) {
            if (size >= most) {
                return;
            }
            Map<Long, String> spots = byWorld.computeIfAbsent(world, name -> new LinkedHashMap<>());
            if (spots.putIfAbsent(key(x, y, z), data) == null) {
                size++;
            }
        }

        public synchronized List<Original> drain() {
            List<Original> all = new ArrayList<>(size);
            byWorld.forEach((world, spots) -> spots.forEach((key, data) ->
                    all.add(new Original(world, x(key), y(key), z(key), data))));
            byWorld.clear();
            size = 0;
            return all;
        }

        public synchronized int size() {
            return size;
        }

        public synchronized boolean isFull() {
            return size >= most;
        }

        private static long key(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }

        private static int x(long key) {
            return (int) (key >> 38);
        }

        private static int y(long key) {
            int y = (int) (key & 0xFFF);
            return y >= 0x800 ? y - 0x1000 : y;
        }

        private static int z(long key) {
            return (int) (key << 26 >> 38);
        }
    }

    private final Plugin plugin;
    private final Predicate<Block> watched;
    private final Ledger ledger = new Ledger();
    private volatile boolean armed;

    /** @param watched which blocks are kept — the inside of a map, say; asked on the block's own thread */
    public Rewind(Plugin plugin, Predicate<Block> watched) {
        this.plugin = plugin;
        this.watched = watched;
    }

    /** From now on, changes are remembered. */
    public void arm() {
        armed = true;
    }

    public boolean isArmed() {
        return armed;
    }

    public int remembered() {
        return ledger.size();
    }

    public void remember(Block block) {
        if (armed && watched.test(block)) {
            ledger.remember(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                    block.getBlockData().getAsString());
        }
    }

    private void remember(BlockState state) {
        if (armed && watched.test(state.getBlock())) {
            ledger.remember(state.getWorld().getName(), state.getX(), state.getY(), state.getZ(),
                    state.getBlockData().getAsString());
        }
    }

    /**
     * Stops watching and puts every remembered block back, chunk by chunk on the thread that owns it.
     *
     * @param done told on the global thread with how many blocks went back
     */
    public void rewind(java.util.function.IntConsumer done) {
        armed = false;
        List<Original> originals = ledger.drain();
        if (originals.isEmpty()) {
            Scheduling.global(plugin, () -> done.accept(0));
            return;
        }
        Map<String, List<Original>> byChunk = new LinkedHashMap<>();
        for (Original original : originals) {
            byChunk.computeIfAbsent(original.world() + ":" + (original.x() >> 4) + ":" + (original.z() >> 4),
                    key -> new ArrayList<>()).add(original);
        }
        AtomicInteger left = new AtomicInteger(byChunk.size());
        AtomicInteger restored = new AtomicInteger();
        for (List<Original> chunk : byChunk.values()) {
            Original first = chunk.getFirst();
            World world = Bukkit.getWorld(first.world());
            if (world == null) {
                if (left.decrementAndGet() == 0) {
                    Scheduling.global(plugin, () -> done.accept(restored.get()));
                }
                continue;
            }
            world.getChunkAtAsync(first.x() >> 4, first.z() >> 4, true).whenComplete((loaded, failure) ->
                    Scheduling.region(plugin, new Location(world, first.x(), first.y(), first.z()), () -> {
                        try {
                            for (Original original : chunk) {
                                try {
                                    world.getBlockAt(original.x(), original.y(), original.z())
                                            .setBlockData(Bukkit.createBlockData(original.data()), false);
                                    restored.incrementAndGet();
                                } catch (IllegalArgumentException unknown) {
                                    // A state this server cannot read cannot be put back; the rest still is.
                                }
                            }
                        } finally {
                            if (left.decrementAndGet() == 0) {
                                Scheduling.global(plugin, () -> done.accept(restored.get()));
                            }
                        }
                    }));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multi) {
            multi.getReplacedBlockStates().forEach(this::remember);
        } else {
            remember(event.getBlockReplacedState());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        remember(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::remember);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::remember);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        remember(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        remember(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketFillEvent event) {
        remember(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        remember(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        remember(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        remember(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        remember(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        remember(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        remember(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent event) {
        event.getBlocks().forEach(block -> {
            remember(block);
            remember(block.getRelative(event.getDirection()));
        });
        remember(event.getBlock().getRelative(event.getDirection()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent event) {
        event.getBlocks().forEach(block -> {
            remember(block);
            remember(block.getRelative(event.getDirection()));
        });
        remember(event.getBlock().getRelative(event.getDirection().getOppositeFace()));
    }
}
