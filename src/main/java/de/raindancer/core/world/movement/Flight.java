package de.raindancer.core.world.movement;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Flight that several things hand out at once — a claim perk, {@code /fly}, an enchant, vanish.
 *
 * <p>{@code setAllowFlight} is one boolean, and every plugin flipping it on its own is how a player loses the
 * {@code /fly} they were given by walking out of a claim that also let them fly. So each source takes a named
 * lease; flight goes only when the last lease does, and never when the player could already fly before the
 * first one — that flight was not ours to take.
 *
 * <ul>
 *   <li>Creative and spectator own their flight; leases are kept but the switch is not touched, and put back
 *       when the player returns to survival or adventure (the game drops it on the change).</li>
 *   <li>Taken away mid-air, the landing is spared, once, within {@link #SOFT_LANDING_MILLIS}.</li>
 *   <li>Leases do not survive logging out: the game saves {@code mayfly} with the player, and a grant nobody
 *       holds any more would come back with them. Sources re-grant after the join as they see fit.</li>
 * </ul>
 *
 * <p>{@link #grant} and {@link #revoke} touch the player and belong on the thread that owns them — any event about
 * the player, or their entity scheduler. The bookkeeping itself is safe from any thread.
 */
public final class Flight implements Listener {

    /** A minute of falling is plenty — from build height it takes a few seconds. */
    public static final long SOFT_LANDING_MILLIS = 60_000L;

    private final Plugin plugin;
    private final SoftLandings landings;
    private final Map<UUID, Set<String>> leases = new ConcurrentHashMap<>();
    /** Could fly before the first lease, so the last one leaves it on. */
    private final Set<UUID> foundFlying = ConcurrentHashMap.newKeySet();
    /** Logged out in the air under a lease; their next landing is spared. */
    private final Set<UUID> returningAirborne = ConcurrentHashMap.newKeySet();

    public Flight(Plugin plugin, LongSupplier clock) {
        this.plugin = plugin;
        this.landings = new SoftLandings(clock, SOFT_LANDING_MILLIS);
    }

    /**
     * Lets them fly for {@code source}. Safe to call every tick: it also puts flight back if something unaware of
     * leases switched it off.
     *
     * @return whether this source did not hold a lease before
     */
    public boolean grant(Player player, String source) {
        UUID id = player.getUniqueId();
        boolean ownedByMode = gameModeOwnsFlight(player);
        Set<String> held = leases.computeIfAbsent(id, key -> {
            if (!ownedByMode && player.getAllowFlight()) {
                foundFlying.add(id);
            }
            return ConcurrentHashMap.newKeySet();
        });
        boolean added = held.add(source);
        if (!ownedByMode && !player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
        return added;
    }

    /**
     * Ends {@code source}'s lease.
     *
     * @return whether flight was actually taken away — false while another lease holds, when the player could
     *         fly before any lease, or when their gamemode owns flight
     */
    public boolean revoke(Player player, String source) {
        UUID id = player.getUniqueId();
        Set<String> held = leases.get(id);
        if (held == null || !held.remove(source)) {
            return false;
        }
        if (!held.isEmpty()) {
            return false;
        }
        leases.remove(id, held);
        if (foundFlying.remove(id) || gameModeOwnsFlight(player) || !player.getAllowFlight()) {
            return false;
        }
        if (!Footing.grounded(player)) {
            landings.launched(id);
        }
        player.setFlying(false);
        player.setAllowFlight(false);
        return true;
    }

    public boolean holds(UUID player, String source) {
        Set<String> held = leases.get(player);
        return held != null && held.contains(source);
    }

    /** Whether any source grants them flight. */
    public boolean isGranted(UUID player) {
        Set<String> held = leases.get(player);
        return held != null && !held.isEmpty();
    }

    /** Who grants them flight, as a snapshot — for a diagnostic. */
    public Set<String> sources(UUID player) {
        Set<String> held = leases.get(player);
        return held == null ? Set.of() : Set.copyOf(held);
    }

    private static boolean gameModeOwnsFlight(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    /** Puts leased flight back after the game dropped it. */
    private void reassert(Player player) {
        Scheduling.entityLater(plugin, player, 1L, () -> {
            if (isGranted(player.getUniqueId()) && !gameModeOwnsFlight(player) && !player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
        });
    }

    // ---------------------------------------------------------------------------- the game's side

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player
                && landings.spared(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        reassert(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reassert(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        reassert(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Set<String> held = leases.remove(id);
        boolean wasFound = foundFlying.remove(id);
        landings.forget(id);
        if (held == null || held.isEmpty() || wasFound || gameModeOwnsFlight(player)) {
            return;
        }
        if (player.isFlying() || !Footing.grounded(player)) {
            returningAirborne.add(id);
        }
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (returningAirborne.remove(id)) {
            landings.launched(id);
        }
    }
}
