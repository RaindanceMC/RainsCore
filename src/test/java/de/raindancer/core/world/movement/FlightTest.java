package de.raindancer.core.world.movement;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Flight several things hand out at once — a claim perk, {@code /fly}, an enchant, vanish — without one of
 * them taking away what another gave, or what the player had before any of them.
 */
class FlightTest {

    private final UUID id = UUID.randomUUID();
    private final AtomicLong now = new AtomicLong(1_000);
    private final Plugin plugin = mock(Plugin.class);
    private final World world = mock(World.class);
    private Player player;
    private boolean allowFlight;
    private boolean flying;
    private Flight flight;

    @BeforeEach
    void setUp() {
        when(plugin.isEnabled()).thenReturn(true);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getWorld()).thenReturn(world);
        when(player.getBoundingBox()).thenReturn(new BoundingBox(0, 64, 0, 0.6, 65.8, 0.6));
        when(player.getAllowFlight()).thenAnswer(call -> allowFlight);
        when(player.isFlying()).thenAnswer(call -> flying);
        doAnswer(call -> {
            allowFlight = call.getArgument(0);
            if (!allowFlight) {
                flying = false;
            }
            return null;
        }).when(player).setAllowFlight(anyBoolean());
        doAnswer(call -> {
            flying = call.getArgument(0);
            return null;
        }).when(player).setFlying(anyBoolean());
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        // Runs "a tick later" at once, so the test reads straight through.
        when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenAnswer(call -> {
            ((java.util.function.Consumer<Object>) call.getArgument(1)).accept(null);
            return null;
        });
        grounded(true);
        flight = new Flight(plugin, now::get);
    }

    private void grounded(boolean onGround) {
        when(world.hasCollisionsIn(any())).thenReturn(onGround);
    }

    @Test
    @DisplayName("a grant lets them fly; taking it back takes flight away")
    void grantAndRevoke() {
        assertThat(flight.grant(player, "claims")).isTrue();
        assertThat(allowFlight).isTrue();
        assertThat(flight.holds(id, "claims")).isTrue();

        assertThat(flight.revoke(player, "claims")).isTrue();
        assertThat(allowFlight).isFalse();
        assertThat(flight.isGranted(id)).isFalse();
    }

    @Test
    @DisplayName("granting again is harmless, and puts flight back if something else switched it off")
    void grantIsIdempotentAndReasserts() {
        flight.grant(player, "claims");
        assertThat(flight.grant(player, "claims")).isFalse();

        allowFlight = false; // another plugin, unaware of leases
        flight.grant(player, "claims");
        assertThat(allowFlight).isTrue();
    }

    @Test
    @DisplayName("flight stays while any source still grants it")
    void lastLeaseDecides() {
        flight.grant(player, "claims");
        flight.grant(player, "fly-command");

        assertThat(flight.revoke(player, "claims")).isFalse();
        assertThat(allowFlight).isTrue();

        assertThat(flight.revoke(player, "fly-command")).isTrue();
        assertThat(allowFlight).isFalse();
    }

    @Test
    @DisplayName("flight the player already had is left as it was found")
    void flightFoundOnIsLeftOn() {
        allowFlight = true;
        flight.grant(player, "claims");

        assertThat(flight.revoke(player, "claims")).isFalse();
        assertThat(allowFlight).isTrue();
    }

    @Test
    @DisplayName("revoking a lease never held changes nothing")
    void revokeWithoutLease() {
        allowFlight = true;
        assertThat(flight.revoke(player, "claims")).isFalse();
        assertThat(allowFlight).isTrue();
    }

    @Test
    @DisplayName("creative and spectator own their flight: neither grant nor revoke touches it")
    void gameModeOwnsFlight() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        allowFlight = true;
        flight.grant(player, "claims");
        flight.revoke(player, "claims");
        verify(player, never()).setAllowFlight(anyBoolean());
        assertThat(allowFlight).isTrue();
    }

    @Test
    @DisplayName("leaving creative while a lease is held keeps them flying")
    void backToSurvivalReasserts() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        flight.grant(player, "claims");

        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        allowFlight = false; // what the game does on the switch
        flight.onGameModeChange(new PlayerGameModeChangeEvent(player, GameMode.SURVIVAL,
                PlayerGameModeChangeEvent.Cause.COMMAND, null));
        assertThat(allowFlight).isTrue();
    }

    @Test
    @DisplayName("taken away mid-air, the landing is spared — once")
    void softLanding() {
        flight.grant(player, "claims");
        flying = true;
        grounded(false);
        flight.revoke(player, "claims");
        assertThat(flying).isFalse();

        EntityDamageEvent landing = fall();
        flight.onFall(landing);
        assertThat(landing.isCancelled()).isTrue();

        EntityDamageEvent next = fall();
        flight.onFall(next);
        assertThat(next.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("taken away on the ground, the next fall hurts as usual")
    void noSoftLandingOnTheGround() {
        flight.grant(player, "claims");
        flight.revoke(player, "claims");

        EntityDamageEvent landing = fall();
        flight.onFall(landing);
        assertThat(landing.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("a soft landing does not wait for ever")
    void softLandingExpires() {
        flight.grant(player, "claims");
        grounded(false);
        flight.revoke(player, "claims");
        now.addAndGet(Flight.SOFT_LANDING_MILLIS + 1);

        EntityDamageEvent landing = fall();
        flight.onFall(landing);
        assertThat(landing.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("logging out drops every lease and the flight they gave, so it is not saved with the player")
    void quitDropsGrantedFlight() {
        flight.grant(player, "claims");
        flight.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(allowFlight).isFalse();
        assertThat(flight.isGranted(id)).isFalse();
    }

    @Test
    @DisplayName("logging out mid-flight spares the fall on the way back in")
    void quitMidAirLandsSoftlyOnReturn() {
        flight.grant(player, "claims");
        flying = true;
        grounded(false);
        flight.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        flight.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));

        EntityDamageEvent landing = fall();
        flight.onFall(landing);
        assertThat(landing.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("flight found on and kept on survives logging out too")
    void quitLeavesFoundFlight() {
        allowFlight = true;
        flight.grant(player, "claims");
        flight.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(allowFlight).isTrue();
    }

    @Test
    @DisplayName("who grants what, for a diagnostic")
    void sources() {
        flight.grant(player, "claims");
        flight.grant(player, "vanish");
        assertThat(flight.sources(id)).containsExactlyInAnyOrder("claims", "vanish");
        assertThat(flight.sources(UUID.randomUUID())).isEmpty();
    }

    /** A fall, mocked: a real damage event needs the server's damage-type registry. */
    private EntityDamageEvent fall() {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        when(event.getEntity()).thenReturn(player);
        boolean[] cancelled = {false};
        doAnswer(call -> {
            cancelled[0] = call.getArgument(0);
            return null;
        }).when(event).setCancelled(anyBoolean());
        when(event.isCancelled()).thenAnswer(call -> cancelled[0]);
        return event;
    }
}
