package de.raindancer.core.moderation.whitelist;

import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.event.server.WhitelistStateUpdateEvent;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The server's own whitelist, for {@link NetworkWhitelist}: what is on it, and every change made to it. */
public final class WhitelistListener implements Listener, NetworkWhitelist.Server {

    private final Plugin plugin;
    private NetworkWhitelist whitelist;

    public WhitelistListener(Plugin plugin) {
        this.plugin = plugin;
    }

    public void feeding(NetworkWhitelist into) {
        this.whitelist = into;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChange(WhitelistStateUpdateEvent event) {
        NetworkWhitelist into = whitelist;
        if (into == null) {
            return;
        }
        UUID player = event.getPlayerProfile().getId();
        String name = event.getPlayerProfile().getName();
        boolean on = event.getStatus() == WhitelistStateUpdateEvent.WhitelistStatus.ADDED;
        // The network's document is a database read and write: not on the server thread.
        Scheduling.async(plugin, () -> into.changedHere(player, name, on));
    }

    @Override
    public Map<UUID, String> whitelisted() {
        Map<UUID, String> players = new LinkedHashMap<>();
        for (OfflinePlayer player : plugin.getServer().getWhitelistedPlayers()) {
            players.put(player.getUniqueId(), player.getName());
        }
        return players;
    }

    @Override
    public void whitelist(UUID player, String name, boolean on) {
        Scheduling.global(plugin, () -> plugin.getServer().getOfflinePlayer(player).setWhitelisted(on));
    }
}
