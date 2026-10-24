package de.raindancer.core.ui.help;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerLoadEvent;

/** Keeps the guide's library current as plugins come and go, and tells new players the guide exists. */
public final class HelpListener implements Listener {

    /** Long enough for the join messages and the resource pack prompt to have passed. */
    private static final long GREET_AFTER_TICKS = 100;

    private final HelpDesk desk;

    public HelpListener(HelpDesk desk) {
        this.desk = desk;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnable(PluginEnableEvent event) {
        desk.invalidate();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisable(PluginDisableEvent event) {
        HelpFacts.forgetFrom(event.getPlugin().getClass().getClassLoader());
        desk.invalidate();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStarted(ServerLoadEvent event) {
        HelpTakeover.takeOver(org.bukkit.Bukkit.getCommandMap());
        // Every plugin and module has reported to the hub by now; build once, so the first reader waits for nothing
        // and the console says what the guide knows.
        desk.reloadAsync(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!desk.settings().greetNewPlayers() || !RainsCore.isAvailable()) {
            return;
        }
        Player player = event.getPlayer();
        RainsCore.get().isNewPlayer(player).thenAccept(isNew -> {
            if (Boolean.TRUE.equals(isNew)) {
                Scheduling.entityLater(desk.plugin(), player, GREET_AFTER_TICKS, () -> {
                    if (player.isOnline()) {
                        RainsCore.get().messages().send(player, "help.new-player",
                                "guide", desk.settings().guideName());
                    }
                });
            }
        });
    }
}
