package de.raindancer.core.platform.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Puts items into a chest, barrel or any other container, wherever it is: on the thread owning its region,
 * after loading its chunk if needed — never by waiting on the load, which would hang the server.
 */
public final class ContainerDelivery {

    private ContainerDelivery() {
    }

    /**
     * @return what did not go in: the overflow of a full container, or everything when the world is not on
     * this server or no container stands there any more. Completes on the container's region thread, so
     * whatever is done with the rest must hop to its own thread.
     */
    public static CompletableFuture<List<ItemStack>> deliver(Plugin plugin, Location at, List<ItemStack> items) {
        List<ItemStack> copies = new ArrayList<>(items.size());
        items.forEach(item -> copies.add(item.clone()));
        World world = at.getWorld();
        if (world == null || copies.isEmpty()) {
            return CompletableFuture.completedFuture(copies);
        }
        CompletableFuture<List<ItemStack>> done = new CompletableFuture<>();
        Runnable insert = () -> {
            try {
                done.complete(insert(at, copies));
            } catch (RuntimeException failure) {
                done.complete(copies);
            }
        };
        Scheduling.region(plugin, at, () -> {
            if (world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                insert.run();
            } else {
                world.getChunkAtAsync(at).whenComplete((chunk, failure) -> {
                    if (failure != null) {
                        done.complete(copies);
                    } else {
                        Scheduling.region(plugin, at, insert);
                    }
                });
            }
        });
        return done;
    }

    private static List<ItemStack> insert(Location at, List<ItemStack> items) {
        BlockState state = at.getBlock().getState(false);
        if (!(state instanceof Container container)) {
            return items;
        }
        return new ArrayList<>(container.getInventory().addItem(items.toArray(ItemStack[]::new)).values());
    }
}
