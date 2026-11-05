package de.raindancer.core.moderation.whitelist;

import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.event.server.WhitelistStateUpdateEvent;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** The server's own whitelist, for {@link NetworkWhitelist}: what is on it, every change made to it, and the door. */
public final class WhitelistListener implements Listener, NetworkWhitelist.Server {

    private static final long CATCH_UP_SECONDS = 5;

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
        into.heard(player);
        // The network's list is a database write: not on the server thread.
        Scheduling.async(plugin, () -> into.changedHere(player, name, on));
    }

    /** First of all, so vanilla and Discord Link's join gate both see the network's answer. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        NetworkWhitelist into = whitelist;
        if (into != null && event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED
                && plugin.getServer().hasWhitelist()) {
            into.catchUp(event.getUniqueId(), event.getName());
        }
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

    @Override
    public void whitelistNow(UUID player, String name) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Scheduling.global(plugin, () -> {
            try {
                plugin.getServer().getOfflinePlayer(player).setWhitelisted(true);
            } finally {
                done.complete(null);
            }
        });
        // On the login thread, which may wait; a server too busy to answer leaves it to vanilla's own list.
        done.completeOnTimeout(null, CATCH_UP_SECONDS, TimeUnit.SECONDS).join();
    }
}
