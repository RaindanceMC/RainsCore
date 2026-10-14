package de.raindancer.core.platform.network;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Sending a player to another server of the network — to its spawn, or to a place there. The place is written
 * down first ({@link Arrivals}); the proxy moves the player; the server they arrive on puts them there. A player
 * still here a few seconds later was not moved: they are told so, rather than left wondering.
 *
 * <p>The server id in {@code network.yml} must be the name the proxy knows the server by.
 */
public final class NetworkTravel implements Listener {

    private static final LogChannel log = Log.of("network");

    /** How long an arrival waits for its player — a slow proxy hop, not an appointment for later. */
    static final long ARRIVAL_MILLIS = 60_000;

    /** How long after asking the proxy a player still here counts as not moved. */
    static final long NOT_MOVED_TICKS = 20 * 6;

    private final Plugin plugin;
    private final Arrivals arrivals;
    private final Supplier<String> self;
    private final LongSupplier clock;
    private final Say say;

    /** Tells a player one of Core's message keys, with the server's name for {@code <server>}. */
    @FunctionalInterface
    public interface Say {
        void say(Player player, String key, String server);
    }

    public NetworkTravel(Plugin plugin, Arrivals arrivals, Supplier<String> self, LongSupplier clock, Say say) {
        this.plugin = plugin;
        this.arrivals = arrivals;
        this.self = self;
        this.clock = clock;
        this.say = say;
        ProxyTransfer.register(plugin);
    }

    /**
     * Sends {@code player} to {@code server}, landing at {@code destination} — the server's spawn when empty.
     * A destination on this server is a plain teleport. On the player's thread.
     */
    public void send(Player player, String server, Optional<Arrivals.Destination> destination) {
        travel(player, server, destination);
    }

    /**
     * As {@link #send}, saying how it went: true once they left this server (or were teleported here), false when
     * they were not moved — so whatever was charged for the trip can be given back.
     */
    public java.util.concurrent.CompletableFuture<Boolean> travel(Player player, String server,
                                                                 Optional<Arrivals.Destination> destination) {
        java.util.concurrent.CompletableFuture<Boolean> moved = new java.util.concurrent.CompletableFuture<>();
        if (server == null || server.isBlank()) {
            moved.complete(false);
            return moved;
        }
        if (server.equals(self.get())) {
            destination.flatMap(NetworkTravel::location).ifPresentOrElse(
                    place -> player.teleportAsync(place).whenComplete((done, failed) -> moved.complete(failed == null && Boolean.TRUE.equals(done))),
                    () -> moved.complete(false));
            return moved;
        }
        Runnable hop = () -> Scheduling.entity(plugin, player, () -> {
            ProxyTransfer.send(plugin, player, server);
            // Retired = they left this server before the check, which is what being moved looks like from here.
            Object check = player.getScheduler().runDelayed(plugin, ignored -> {
                if (player.isOnline()) {
                    say.say(player, "travel.failed", server);
                    moved.complete(false);
                } else {
                    moved.complete(true);
                }
            }, () -> moved.complete(true), NOT_MOVED_TICKS);
            if (check == null) {
                moved.complete(true);
            }
        }, () -> moved.complete(!player.isOnline()));
        if (destination.isEmpty()) {
            hop.run();
            return moved;
        }
        // Written before the hop, never after: the next server reads it as the player arrives.
        Scheduling.async(plugin, () -> {
            if (arrivals.expect(player.getUniqueId(), server, destination.get(), clock.getAsLong() + ARRIVAL_MILLIS)) {
                hop.run();
            } else {
                log.warn("Where {} was to land on {} could not be written; they were not sent.", player.getName(), server);
                Scheduling.entity(plugin, player, () -> say.say(player, "travel.failed", server));
                moved.complete(false);
            }
        });
        return moved;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Scheduling.async(plugin, () -> arrivals.take(player.getUniqueId(), self.get(), clock.getAsLong())
                .ifPresent(destination -> Scheduling.entity(plugin, player, () -> location(destination).ifPresentOrElse(
                        player::teleportAsync,
                        () -> say.say(player, "travel.arrival-lost", "")))));
    }

    private static Optional<Location> location(Arrivals.Destination destination) {
        World world = org.bukkit.Bukkit.getWorld(destination.world());
        return world == null ? Optional.empty() : Optional.of(new Location(world, destination.x(), destination.y(),
                destination.z(), destination.yaw(), destination.pitch()));
    }
}
