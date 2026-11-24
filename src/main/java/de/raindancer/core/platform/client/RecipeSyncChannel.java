package de.raindancer.core.platform.client;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * {@link RecipeSync} against the running server. The order matters and comes from Paper: the initial configure
 * event fires before the client is asked for its known packs, which is when Fabric decides whether to ask.
 */
public final class RecipeSyncChannel implements PluginMessageListener, Listener {

    private static final LogChannel log = Log.of("recipes");

    private final ServerRecipes server;
    private final RecipeSync sync;

    private RecipeSyncChannel(ServerRecipes server, RecipeSync sync) {
        this.server = server;
        this.sync = sync;
    }

    /** Null on a server the reflection does not fit; recipe viewers then see what vanilla sends. */
    public static RecipeSyncChannel open(Plugin plugin) {
        ServerRecipes server;
        try {
            server = ServerRecipes.resolve(plugin.getServer().getClass().getClassLoader());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError misfit) {
            log.warn("Recipe sync for JEI/REI/EMI does not fit this server version ({}); not offered.", misfit.toString());
            return null;
        }
        RecipeSync sync = new RecipeSync(server::bySerializer, (id, payload) -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                player.sendPluginMessage(plugin, RecipeSync.RECIPE_CHANNEL, payload);
            }
        });
        RecipeSyncChannel channel = new RecipeSyncChannel(server, sync);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, RecipeSync.RECIPE_CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, RecipeSync.REQUEST_CHANNEL, channel);
        plugin.getServer().getPluginManager().registerEvents(channel, plugin);
        return channel;
    }

    @EventHandler
    public void onConfigure(PlayerConnectionInitialConfigureEvent event) {
        try {
            server.register(event.getConnection(), RecipeSync.announcement());
        } catch (ReflectiveOperationException | RuntimeException failed) {
            log.warn("Could not offer recipe sync to {}: {}", event.getConnection().getProfile().getName(),
                    failed.toString());
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull PlayerConnection connection,
                                        byte @NotNull [] message) {
        if (RecipeSync.REQUEST_CHANNEL.equals(channel) && connection instanceof PlayerConfigurationConnection configuring
                && configuring.getProfile().getId() != null) {
            sync.requested(configuring.getProfile().getId(), message);
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (RecipeSync.REQUEST_CHANNEL.equals(channel)) {
            sync.requested(player.getUniqueId(), message);
        }
    }

    @EventHandler
    public void onListening(PlayerRegisterChannelEvent event) {
        if (RecipeSync.RECIPE_CHANNEL.equals(event.getChannel())) {
            sync.listening(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onReload(ServerResourcesReloadedEvent event) {
        sync.reloaded();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sync.left(event.getPlayer().getUniqueId());
    }
}
