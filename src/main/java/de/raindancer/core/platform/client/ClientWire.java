package de.raindancer.core.platform.client;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * How bytes reach one player's client on a plugin channel, and the one place that knows they must not be
 * sent from just anywhere.
 *
 * <p>A seam because a protocol whose failure is "the client silently draws nothing" can only be checked by
 * reading what was emitted — and because on Folia a player belongs to a region thread, so a send from a
 * timer is a cross-thread touch of an entity.
 */
@FunctionalInterface
public interface ClientWire {

    void send(UUID player, String channel, byte[] message);

    /**
     * The real thing, on the player's own thread. The server drops a message on a channel the client has not
     * registered, and says nothing about it.
     */
    static ClientWire through(Plugin plugin) {
        return (id, channel, message) -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                return;
            }
            Scheduling.entity(plugin, player, () -> {
                if (player.isOnline()) {
                    player.sendPluginMessage(plugin, channel, message);
                }
            });
        };
    }
}
