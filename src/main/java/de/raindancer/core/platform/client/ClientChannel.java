package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.Wire;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * {@link ClientMods} against the running server: the channel registered both ways, so the server announces it
 * to every client and a mod knows it may speak.
 *
 * <p>A message arrives on the thread that owns its player — the main thread on Paper, the player's region on
 * Folia — so nothing is rescheduled on the way in.
 */
public final class ClientChannel implements PluginMessageListener, Listener {

    private final ClientMods mods;

    private ClientChannel(ClientMods mods) {
        this.mods = mods;
    }

    public static ClientChannel open(Plugin plugin, ClientMods mods) {
        ClientChannel channel = new ClientChannel(mods);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Wire.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, Wire.CHANNEL, channel);
        plugin.getServer().getPluginManager().registerEvents(channel, plugin);
        return channel;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (Wire.CHANNEL.equals(channel)) {
            mods.received(player.getUniqueId(), message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        mods.left(event.getPlayer().getUniqueId());
    }
}
