package de.raindancer.core.world.build;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Reads a box of a world into a {@link Schematic} — what staff built or changed there in creative, to keep as the
 * file. Chunk by chunk on each chunk's own region thread (Folia-safe), the palette built as it goes so a map of
 * millions of blocks holds one string per kind of block, not per block.
 */
public final class SchematicCapture {

    private SchematicCapture() {
    }

    /** Every chunk the box touches, x then z, as {@code {chunkX, chunkZ}}. */
    static List<long[]> chunksOf(int x0, int z0, int width, int length) {
        List<long[]> chunks = new ArrayList<>();
        for (long cx = Math.floorDiv(x0, 16); cx <= Math.floorDiv(x0 + width - 1, 16); cx++) {
            for (long cz = Math.floorDiv(z0, 16); cz <= Math.floorDiv(z0 + length - 1, 16); cz++) {
                chunks.add(new long[]{cx, cz});
            }
        }
        return chunks;
    }

    /**
     * Reads {@code width × height × length} blocks from the corner {@code (x0, y0, z0)}.
     *
     * @param done told on an async thread with the schematic once every chunk is read
     */
    public static void capture(Plugin plugin, World world, int x0, int y0, int z0, int width, int height, int length,
                               Consumer<Schematic> done) {
        List<long[]> chunks = chunksOf(x0, z0, width, length);
        int[] blocks = new int[width * height * length];
        Map<String, Integer> palette = new ConcurrentHashMap<>();
        AtomicInteger next = new AtomicInteger();
        AtomicInteger left = new AtomicInteger(chunks.size());
        int dataVersion = Bukkit.getUnsafe().getDataVersion();
        for (long[] chunk : chunks) {
            int cx = (int) chunk[0];
            int cz = (int) chunk[1];
            Scheduling.region(plugin, new Location(world, cx * 16, y0, cz * 16), () -> {
                world.getChunkAt(cx, cz);
                int fromX = Math.max(x0, cx * 16);
                int toX = Math.min(x0 + width, cx * 16 + 16);
                int fromZ = Math.max(z0, cz * 16);
                int toZ = Math.min(z0 + length, cz * 16 + 16);
                for (int y = 0; y < height; y++) {
                    for (int z = fromZ; z < toZ; z++) {
                        for (int x = fromX; x < toX; x++) {
                            String state = world.getBlockAt(x, y0 + y, z).getBlockData().getAsString();
                            Integer index = palette.computeIfAbsent(state, added -> next.getAndIncrement());
                            blocks[(x - x0) + (z - z0) * width + y * width * length] = index;
                        }
                    }
                }
                if (left.decrementAndGet() == 0) {
                    Scheduling.async(plugin, () -> {
                        String[] names = new String[palette.size()];
                        palette.forEach((state, index) -> names[index] = state);
                        done.accept(new Schematic(width, height, length, dataVersion, List.of(names), blocks));
                    });
                }
            });
        }
    }
}
