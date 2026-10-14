package de.raindancer.core.platform.util;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A plugin that is not enabled runs scheduled work in place, which is what lets this run without a server. */
class ContainerDeliveryTest {

    private final Plugin plugin = mock(Plugin.class);
    private final World world = mock(World.class);
    private final Block block = mock(Block.class);
    private final Location at = new Location(world, 40, 64, -20);
    private final ItemStack cobble = mock(ItemStack.class);
    private final ItemStack rest = mock(ItemStack.class);

    private Inventory chestHolding(Map<Integer, ItemStack> leftOver) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>(leftOver));
        Chest chest = mock(Chest.class);
        when(chest.getInventory()).thenReturn(inventory);
        when(block.getState(anyBoolean())).thenReturn(chest);
        return inventory;
    }

    {
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(cobble.clone()).thenReturn(cobble);
        when(rest.clone()).thenReturn(rest);
    }

    @Test
    @DisplayName("into a loaded chest: nothing comes back")
    void loaded() {
        when(world.isChunkLoaded(2, -2)).thenReturn(true);
        Inventory inventory = chestHolding(Map.of());

        assertThat(ContainerDelivery.deliver(plugin, at, List.of(cobble)).join()).isEmpty();
        verify(inventory).addItem(cobble);
        verify(world, never()).getChunkAtAsync(any(Location.class));
    }

    @Test
    @DisplayName("an unloaded chunk is loaded first, never waited on")
    void unloaded() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        when(world.getChunkAtAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
        chestHolding(Map.of());

        assertThat(ContainerDelivery.deliver(plugin, at, List.of(cobble)).join()).isEmpty();
        verify(world).getChunkAtAsync(at);
    }

    @Test
    @DisplayName("a full chest hands back what did not fit; a chest that is gone hands back everything")
    void leftovers() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        chestHolding(Map.of(0, rest));
        assertThat(ContainerDelivery.deliver(plugin, at, List.of(cobble)).join()).containsExactly(rest);

        when(block.getState(anyBoolean())).thenReturn(mock(BlockState.class));
        assertThat(ContainerDelivery.deliver(plugin, at, List.of(cobble)).join()).containsExactly(cobble);
    }

    @Test
    @DisplayName("a world that is not here takes nothing")
    void noWorld() {
        Location nowhere = new Location(null, 0, 0, 0);
        assertThat(ContainerDelivery.deliver(plugin, nowhere, List.of(cobble)).join()).containsExactly(cobble);
    }

    @Test
    @DisplayName("a container the caller refuses — not the right kind, or not theirs to fill — takes nothing")
    void refused() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Inventory inventory = chestHolding(Map.of());

        assertThat(ContainerDelivery.deliver(plugin, at, List.of(cobble), state -> false).join()).containsExactly(cobble);
        verify(inventory, never()).addItem(any(ItemStack[].class));
        assertThat(ContainerDelivery.deliver(plugin, at, List.of(cobble), state -> state instanceof Chest).join()).isEmpty();
    }
}
