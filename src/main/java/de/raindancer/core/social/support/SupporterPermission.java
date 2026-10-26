package de.raindancer.core.social.support;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code rains.supporter} for everybody in {@link Supporters}: what a thank-you hangs off. A plugin adds its own
 * nodes as children of {@link #permission()}, and an owner can still give any of them to anybody by hand. Core's quit
 * handler calls {@link #forget}.
 */
public final class SupporterPermission implements Listener {

    public static final String NODE = "rains.supporter";

    private final Plugin plugin;
    private final Supporters supporters;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public SupporterPermission(Plugin plugin, Supporters supporters) {
        this.plugin = plugin;
        this.supporters = supporters;
    }

    /** The node itself, registered once; children added to it reach every supporter. */
    public static Permission permission(org.bukkit.plugin.PluginManager manager) {
        Permission found = manager.getPermission(NODE);
        if (found == null) {
            found = new Permission(NODE, "Bought the owner a coffee — given and taken by RainsCore", PermissionDefault.FALSE);
            manager.addPermission(found);
        }
        return found;
    }

    /** On the player's own thread. Gives or takes the node so it matches the list. */
    public void sync(Player player) {
        UUID who = player.getUniqueId();
        boolean should = supporters.isSupporter(who);
        boolean has = attachments.containsKey(who);
        if (should && !has) {
            PermissionAttachment attachment = player.addAttachment(plugin);
            attachment.setPermission(NODE, true);
            attachments.put(who, attachment);
            player.recalculatePermissions();
        } else if (!should && has) {
            forget(who);
            player.recalculatePermissions();
        }
    }

    public void forget(UUID who) {
        PermissionAttachment attachment = attachments.remove(who);
        if (attachment != null) {
            try {
                attachment.remove();
            } catch (IllegalStateException alreadyGone) {
                // detached already, when the player has left
            }
        }
    }

    public int holding() {
        return attachments.size();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        sync(event.getPlayer());
    }

}
