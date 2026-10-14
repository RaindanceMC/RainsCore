package de.raindancer.core.platform.network;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Whether a hop to another server worked, told to the caller — so a fee for it can be given back. */
class NetworkTravelTest {

    private final Plugin plugin = mock(Plugin.class);
    private final Player player = mock(Player.class);
    private final Arrivals arrivals = mock(Arrivals.class);
    private final List<String> said = new ArrayList<>();
    private final AtomicReference<Consumer<ScheduledTask>> check = new AtomicReference<>();
    private final AtomicReference<Runnable> retired = new AtomicReference<>();
    private final NetworkTravel travel;

    NetworkTravelTest() {
        Server server = mock(Server.class);
        when(server.getMessenger()).thenReturn(mock(Messenger.class));
        when(plugin.getServer()).thenReturn(server);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenAnswer(call -> {
            check.set(call.getArgument(1));
            retired.set(call.getArgument(2));
            return mock(ScheduledTask.class);
        });
        when(player.getScheduler()).thenReturn(scheduler);
        travel = new NetworkTravel(plugin, arrivals, () -> "smp", () -> 1_000L, (who, key, at) -> said.add(key));
    }

    @Test
    @DisplayName("still here after the hop: not moved, and told so")
    void notMoved() {
        when(player.isOnline()).thenReturn(true);
        CompletableFuture<Boolean> moved = travel.travel(player, "arena", Optional.empty());
        check.get().accept(null);
        assertThat(moved).isCompletedWithValue(false);
        assertThat(said).containsExactly("travel.failed");
    }

    @Test
    @DisplayName("gone from this server before the check: moved")
    void moved() {
        CompletableFuture<Boolean> moved = travel.travel(player, "arena", Optional.empty());
        retired.get().run();
        assertThat(moved).isCompletedWithValue(true);
    }

    @Test
    @DisplayName("where to land could not be written: not sent, not moved")
    void arrivalNotWritten() {
        when(arrivals.expect(any(), eq("arena"), any(), anyLong())).thenReturn(false);
        CompletableFuture<Boolean> moved = travel.travel(player, "arena",
                Optional.of(new Arrivals.Destination("world", 0, 64, 0, 0, 0)));
        assertThat(moved).isCompletedWithValue(false);
        assertThat(check.get()).as("no hop").isNull();
    }

    @Test
    @DisplayName("still here with a place to land at: the place is taken back, so a late move cannot land them there")
    void placeTakenBack() {
        when(player.isOnline()).thenReturn(true);
        when(arrivals.expect(any(), eq("arena"), any(), anyLong())).thenReturn(true);
        when(arrivals.withdraw(any(), eq("arena"))).thenReturn(true);
        CompletableFuture<Boolean> moved = travel.travel(player, "arena",
                Optional.of(new Arrivals.Destination("world", 0, 64, 0, 0, 0)));
        check.get().accept(null);
        assertThat(moved).isCompletedWithValue(false);
        assertThat(said).containsExactly("travel.failed");
    }

    @Test
    @DisplayName("still here, but the place was already taken on the other server: they arrived, nothing to give back")
    void arrivedWhileStillListedHere() {
        when(player.isOnline()).thenReturn(true);
        when(arrivals.expect(any(), eq("arena"), any(), anyLong())).thenReturn(true);
        when(arrivals.withdraw(any(), eq("arena"))).thenReturn(false);
        CompletableFuture<Boolean> moved = travel.travel(player, "arena",
                Optional.of(new Arrivals.Destination("world", 0, 64, 0, 0, 0)));
        check.get().accept(null);
        assertThat(moved).isCompletedWithValue(true);
        assertThat(said).isEmpty();
    }
}
