package de.raindancer.core.world.teleport;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A trip whose end is not a teleport here — a hop to another server — still waits and cancels like any other. */
class TravelElsewhereTest {

    private final Travel travel = new Travel(mock(Plugin.class), null);
    private final Player traveller = mock(Player.class);
    private final AtomicReference<Consumer<ScheduledTask>> timer = new AtomicReference<>();
    private final ScheduledTask task = mock(ScheduledTask.class);

    @SuppressWarnings("unchecked")
    TravelElsewhereTest() {
        when(traveller.getUniqueId()).thenReturn(UUID.randomUUID());
        when(traveller.isOnline()).thenReturn(true);
        Location here = mock(Location.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(here.getWorld()).thenReturn(world);
        when(here.clone()).thenReturn(here);
        when(traveller.getLocation()).thenReturn(here);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.runAtFixedRate(any(), any(), any(), anyLong(), anyLong())).thenAnswer(call -> {
            timer.set(call.getArgument(1));
            return task;
        });
        when(traveller.getScheduler()).thenReturn(scheduler);
    }

    @Test
    @DisplayName("no warm-up: the hop happens at once, nothing is teleported here, the trip is over")
    void atOnce() {
        AtomicInteger hops = new AtomicInteger();
        TravelWatcher watcher = mock(TravelWatcher.class);
        travel.goElsewhere(traveller, Trip.to("home").quiet(), watcher, player -> hops.incrementAndGet());
        assertThat(hops).hasValue(1);
        verify(watcher).arrived(any(), any(), any());
        verify(traveller, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        assertThat(travel.isTravelling(traveller.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("with a warm-up: counted down first, then the hop, once")
    void afterTheWarmup() {
        AtomicInteger hops = new AtomicInteger();
        travel.goElsewhere(traveller, Trip.to("home").after(2).quiet(), mock(TravelWatcher.class),
                player -> hops.incrementAndGet());
        assertThat(hops).hasValue(0);
        assertThat(travel.isTravelling(traveller.getUniqueId())).isTrue();
        timer.get().accept(task);
        timer.get().accept(task);
        timer.get().accept(task);
        assertThat(hops).hasValue(1);
        assertThat(travel.isTravelling(traveller.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("moving during the warm-up cancels the hop")
    void cancelled() {
        AtomicInteger hops = new AtomicInteger();
        TravelWatcher watcher = mock(TravelWatcher.class);
        travel.goElsewhere(traveller, Trip.to("home").after(2).quiet(), watcher, player -> hops.incrementAndGet());
        travel.cancel(traveller, TravelReason.MOVED);
        timer.get().accept(task);
        timer.get().accept(task);
        assertThat(hops).hasValue(0);
        verify(watcher).cancelled(any(), any(), any());
    }
}
