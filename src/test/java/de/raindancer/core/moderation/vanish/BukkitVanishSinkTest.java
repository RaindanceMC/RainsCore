package de.raindancer.core.moderation.vanish;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one place {@link Vanish}'s bookkeeping actually touches a real Bukkit player.
 *
 * <h2>Why {@link #allowFlight} needs its own gamemode logic</h2>
 * {@link Vanish} only ever asks for a flag it remembered — "did they already have this before I
 * touched it" — and has no idea what gamemode somebody is standing in right now. That is exactly
 * the gap: Creative and Spectator each own flight as a fact about the gamemode itself, and a call
 * that came from vanish alone, remembering nothing about either gamemode, has no business
 * overruling that. See {@link BukkitVanishSink#allowFlight} for the two different ways this goes
 * wrong if it does.
 */
class BukkitVanishSinkTest {

    private static final UUID MOD = UUID.randomUUID();

    private static Player playerIn(GameMode mode) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(MOD);
        when(player.getGameMode()).thenReturn(mode);
        return player;
    }

    @Nested
    @DisplayName("granting or taking back flight")
    class AllowFlight {

        @Test
        @DisplayName("an ordinary gamemode gets the flag set as asked")
        void survivalIsTouchedNormally() {
            Player target = playerIn(GameMode.SURVIVAL);
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class));

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(target);
                sink.allowFlight(MOD, true);
            }

            verify(target).setAllowFlight(true);
        }

        @Test
        @DisplayName("spectator is left alone entirely — the client flies regardless of the flag")
        void spectatorIsUntouched() {
            // The bug this guards: the client hard-codes free-fly no-clip in spectator no matter
            // what the ability flag says, so setAllowFlight(false) only makes the *server* think
            // they stopped flying — the client keeps moving through walls, and the next tick
            // applies gravity to a position the server no longer believes is airborne. That
            // mismatch is what dropped a revealed moderator straight through the world.
            Player target = playerIn(GameMode.SPECTATOR);
            when(target.isFlying()).thenReturn(true);
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class));

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(target);
                sink.allowFlight(MOD, false);
            }

            verify(target, never()).setAllowFlight(anyBoolean());
            verify(target, never()).setFlying(anyBoolean());
        }

        @Test
        @DisplayName("creative is left alone too — its flight is the gamemode's, not vanish's")
        void creativeIsUntouched() {
            // The other half of the same mistake, the opposite way round: unlike spectator,
            // setAllowFlight(false) in creative *works* — which is exactly the danger. Somebody who
            // switches to creative mid-vanish to check a build gets flight as a fact about that
            // gamemode, nothing vanish granted and nothing it remembered. A later reveal that came
            // from vanish alone has no business taking it back — it was never vanish's to give.
            Player target = playerIn(GameMode.CREATIVE);
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class));

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(target);
                sink.allowFlight(MOD, false);
            }

            verify(target, never()).setAllowFlight(anyBoolean());
            verify(target, never()).setFlying(anyBoolean());
        }

        @Test
        @DisplayName("taking flight away also stops the flying flag, for an ordinary gamemode")
        void takingItAwayStopsFlyingToo() {
            Player target = playerIn(GameMode.SURVIVAL);
            when(target.isFlying()).thenReturn(true);
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class));

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(target);
                sink.allowFlight(MOD, false);
            }

            verify(target).setAllowFlight(false);
            verify(target).setFlying(false);
        }

        @Test
        @DisplayName("nobody online is not an error")
        void offlineIsHarmless() {
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class));

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(null);
                sink.allowFlight(MOD, true);
            }
            // Reaching here without throwing is the assertion.
        }
    }

    @Nested
    @DisplayName("beside flight somebody else lends")
    class Leases {

        private boolean allowFlight;
        private final java.util.Set<Object> saved = new java.util.HashSet<>();
        private final de.raindancer.core.world.movement.Flight flight =
                new de.raindancer.core.world.movement.Flight(mock(Plugin.class), () -> 1_000L);

        private Player survivalPlayer() {
            Player player = playerIn(GameMode.SURVIVAL);
            org.bukkit.World world = mock(org.bukkit.World.class);
            when(world.hasCollisionsIn(org.mockito.ArgumentMatchers.any())).thenReturn(true);
            when(player.getWorld()).thenReturn(world);
            when(player.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(0, 64, 0, 0.6, 65.8, 0.6));
            when(player.getAllowFlight()).thenAnswer(call -> allowFlight);
            org.mockito.Mockito.doAnswer(call -> allowFlight = call.getArgument(0))
                    .when(player).setAllowFlight(anyBoolean());
            org.bukkit.persistence.PersistentDataContainer data =
                    mock(org.bukkit.persistence.PersistentDataContainer.class);
            when(player.getPersistentDataContainer()).thenReturn(data);
            org.mockito.Mockito.doAnswer(call -> saved.add(call.getArgument(0))).when(data)
                    .set(org.mockito.ArgumentMatchers.any(org.bukkit.NamespacedKey.class),
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(call -> saved.remove(call.getArgument(0))).when(data)
                    .remove(org.mockito.ArgumentMatchers.any(org.bukkit.NamespacedKey.class));
            return player;
        }

        private void vanishFlight(Player target, boolean allowed) {
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class), flight);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(target);
                sink.allowFlight(MOD, allowed);
            }
        }

        @Test
        @DisplayName("revealing inside a flying claim leaves the claim's flight on")
        void revealKeepsTheClaimsFlight() {
            Player target = survivalPlayer();
            flight.grant(target, "claims");
            vanishFlight(target, true);

            vanishFlight(target, false);

            assertThat(allowFlight).isTrue();
            assertThat(flight.holds(MOD, "claims")).isTrue();
            assertThat(flight.holds(MOD, BukkitVanishSink.FLIGHT_SOURCE)).isFalse();
        }

        @Test
        @DisplayName("revealing does not end /fly")
        void revealKeepsFly() {
            Player target = survivalPlayer();
            flight.grant(target, de.raindancer.core.world.movement.Flight.FLY);
            vanishFlight(target, true);

            vanishFlight(target, false);

            assertThat(allowFlight).isTrue();
            assertThat(flight.hasOwnFlight(target)).isTrue();
        }

        @Test
        @DisplayName("flight vanish alone lent goes with the reveal")
        void ownFlightEndsWithTheReveal() {
            Player target = survivalPlayer();
            vanishFlight(target, true);
            assertThat(allowFlight).isTrue();
            assertThat(flight.hasOwnFlight(target)).isFalse();

            vanishFlight(target, false);

            assertThat(allowFlight).isFalse();
            assertThat(flight.isGranted(MOD)).isFalse();
        }
    }

    @Nested
    @DisplayName("on Folia, from somebody else's region")
    class OtherRegion {

        @Test
        @DisplayName("hiding is done on each viewer's own thread, and flight on the target's")
        @SuppressWarnings("unchecked")
        void hopsToTheOwners() {
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            Player target = playerIn(GameMode.SURVIVAL);
            Player viewer = mock(Player.class);
            when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
            java.util.List<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>>
                    queued = new java.util.ArrayList<>();
            for (Player each : java.util.List.of(target, viewer)) {
                io.papermc.paper.threadedregions.scheduler.EntityScheduler scheduler =
                        mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
                when(each.getScheduler()).thenReturn(scheduler);
                when(scheduler.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                            queued.add(call.getArgument(1));
                            return null;
                        });
            }
            BukkitVanishSink sink = new BukkitVanishSink(plugin);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(target);
                bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> java.util.List.of(target, viewer));
                sink.hide(MOD, Set.of());
                sink.allowFlight(MOD, true);
            }

            verify(viewer, never()).hidePlayer(plugin, target);
            verify(target, never()).setAllowFlight(anyBoolean());
            queued.forEach(task -> task.accept(null));
            verify(viewer).hidePlayer(plugin, target);
            verify(target).setAllowFlight(true);
        }
    }
}
