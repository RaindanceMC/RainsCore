package de.raindancer.core.ui.book;

import de.raindancer.core.RainsCore;
import de.raindancer.core.content.items.TaggedItems;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/** Hands the welcome book to somebody new to the network, a moment after they arrive. */
public final class WelcomeBookListener implements Listener {

    private static final long GIVE_AFTER_TICKS = 40;

    private final Plugin plugin;

    public WelcomeBookListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!WelcomeBook.takesOver() || !RainsCore.isAvailable()) {
            return;
        }
        Player player = event.getPlayer();
        RainsCore.get().isNewPlayer(player).thenAccept(isNew -> {
            if (Boolean.TRUE.equals(isNew)) {
                Scheduling.entityLater(plugin, player, GIVE_AFTER_TICKS, () -> {
                    if (player.isOnline()) {
                        TaggedItems.handTo(player, WelcomeBook.item(player, RainsCore.get().serverName()));
                    }
                });
            }
        });
    }
}
