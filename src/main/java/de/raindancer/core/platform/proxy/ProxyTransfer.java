package de.raindancer.core.platform.proxy;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Sending a player to another server of the network, through the proxy in front of this one.
 *
 * <p>The BungeeCord channel's {@code Connect} message, which Velocity understands too (with
 * {@code bungee-plugin-message-channel = true}). It rides on the player's own connection, so it needs nothing else
 * running — and it says nothing about whether the move worked: a server that is down simply leaves them here.
 */
public final class ProxyTransfer {

    public static final String CHANNEL = "BungeeCord";

    private ProxyTransfer() {
    }

    /** Once per plugin, before the first {@link #send}. */
    public static void register(Plugin plugin) {
        if (!plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, CHANNEL)) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        }
    }

    public static void send(Plugin plugin, Player player, String server) {
        player.sendPluginMessage(plugin, CHANNEL, connectPayload(server));
    }

    static byte[] connectPayload(String server) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("a server name is needed");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server.strip());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }
}
