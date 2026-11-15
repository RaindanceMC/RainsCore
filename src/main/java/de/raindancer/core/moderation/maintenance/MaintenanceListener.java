package de.raindancer.core.moderation.maintenance;

import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/** Turns away whoever may not join during maintenance, before they are in the world at all. */
public final class MaintenanceListener implements Listener {

    private final Server server;
    private final Maintenance maintenance;

    public MaintenanceListener(Server server, Maintenance maintenance) {
        this.server = server;
        this.maintenance = maintenance;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerConnectionValidateLoginEvent event) {
        // A ban or the vanilla whitelist already said no: their message is the truer one.
        if (!maintenance.isOn() || !event.isAllowed() || !(event.getConnection() instanceof PlayerLoginConnection login)) {
            return;
        }
        var profile = login.getAuthenticatedProfile();
        if (profile == null || profile.getId() == null) {
            return;
        }
        if (!maintenance.mayJoin(profile.getId(), isOperator(server, profile.getId()))) {
            event.kickMessage(MaintenanceText.closed(maintenance.reason(), maintenance.backAt(), System.currentTimeMillis()));
        }
    }

    /** Whoever is let in is reminded that the doors are shut, so it is not left on by mistake. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (maintenance.isOn()) {
            event.getPlayer().sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gold>Maintenance mode is on.</gold> <gray>Only ops and the maintenance list can join. "
                            + "<white>/maintenance off</white> opens the server."));
        }
    }

    /** From ops.json, which needs no player object — there is none yet at login. */
    static boolean isOperator(Server server, UUID id) {
        for (OfflinePlayer operator : server.getOperators()) {
            if (id.equals(operator.getUniqueId())) {
                return true;
            }
        }
        return false;
    }
}
