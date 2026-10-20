package de.raindancer.core.moderation.players;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A moderator's click arrives on the moderator's region; the player it acts on may be in another one.
 * Every change is made on the target's own thread.
 */
class BukkitPlayerAdminSinkTest {

    private final UUID who = UUID.randomUUID();
    private final Player target = mock(Player.class);
    private final Plugin plugin = mock(Plugin.class);
    private final List<Consumer<ScheduledTask>> queued = new ArrayList<>();
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        when(plugin.isEnabled()).thenReturn(true);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(target.getScheduler()).thenReturn(scheduler);
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            queued.add(call.getArgument(1));
            return null;
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(who)).thenReturn(target);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    @DisplayName("from another region, nothing is touched until the target's own thread runs it")
    void hopsToTheTarget() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(target)).thenReturn(false);
        BukkitPlayerAdminSink sink = new BukkitPlayerAdminSink(plugin);

        sink.food(who, 20);
        sink.allowFlight(who, true);
        sink.extinguish(who);
        sink.kick(who, "bye");

        verify(target, never()).setFoodLevel(anyInt());
        verify(target, never()).setAllowFlight(anyBoolean());
        queued.forEach(task -> task.accept(null));
        verify(target).setFoodLevel(20);
        verify(target).setAllowFlight(true);
        verify(target).setFireTicks(0);
        verify(target).kick(any());
    }

    @Test
    @DisplayName("on the target's own thread, it happens at once")
    void inPlace() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(target)).thenReturn(true);

        new BukkitPlayerAdminSink(plugin).food(who, 5);

        verify(target).setFoodLevel(5);
    }

    @Test
    @DisplayName("an offline player is nothing to do")
    void offline() {
        new BukkitPlayerAdminSink(plugin).food(UUID.randomUUID(), 5);

        verify(target, never()).setFoodLevel(anyInt());
    }

    @Test
    @DisplayName("with Core's flight, the switch is /fly's lease: on takes it, off gives it back, and a claim's flight stays")
    void flightIsALease() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(target)).thenReturn(true);
        de.raindancer.core.world.movement.Flight flight = mock(de.raindancer.core.world.movement.Flight.class);
        BukkitPlayerAdminSink sink = new BukkitPlayerAdminSink(plugin, flight);

        sink.allowFlight(who, true);
        verify(flight).grant(target, de.raindancer.core.world.movement.Flight.FLY);
        verify(target, never()).setAllowFlight(anyBoolean());

        when(flight.isGranted(who)).thenReturn(true); // a claim still lends it
        when(target.getAllowFlight()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(who);
        sink.allowFlight(who, false);
        verify(flight).revoke(target, de.raindancer.core.world.movement.Flight.FLY);
        verify(target, never()).setAllowFlight(false);
    }

    @Test
    @DisplayName("off with no lease behind the flight switches it off — an admin asked for it")
    void offWithoutLease() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(target)).thenReturn(true);
        de.raindancer.core.world.movement.Flight flight = mock(de.raindancer.core.world.movement.Flight.class);
        when(target.getUniqueId()).thenReturn(who);
        when(target.getAllowFlight()).thenReturn(true);
        when(target.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);

        new BukkitPlayerAdminSink(plugin, flight).allowFlight(who, false);

        verify(target).setAllowFlight(false);
    }

    @Test
    @DisplayName("whether they fly is /fly's own flight, not what a claim lends")
    void stateReadsOwnFlight() {
        de.raindancer.core.world.movement.Flight flight = mock(de.raindancer.core.world.movement.Flight.class);
        when(target.getAllowFlight()).thenReturn(true);
        when(target.getHealth()).thenReturn(20.0);
        when(target.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        when(flight.hasOwnFlight(target)).thenReturn(false);

        PlayerState state = new BukkitPlayerAdminSink(plugin, flight).stateOf(who).orElseThrow();

        org.assertj.core.api.Assertions.assertThat(state.flying()).isFalse();
    }
}
