package de.raindancer.core.social.support;

import de.raindancer.core.platform.permission.GroupSeed;
import de.raindancer.core.platform.permission.PermissionGroups;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code rains.prime} for everybody {@link PrimeMembers} says is Prime right now, and membership of the permission
 * group {@code prime} to match — the group is what the rank tag, the Discord role (Hush's role map) and anything set
 * up in LuckPerms hang off. The node is attached directly too, so Prime works the moment it is granted and on a
 * server without LuckPerms.
 *
 * <p>A plugin makes a perk Prime's by adding its node as a child of {@link #permission}. Somebody put into the group
 * by hand, with no Prime record, is left there: only an ended Prime is taken out.
 */
public final class PrimePermission implements Listener {

    public static final String NODE = "rains.prime";
    public static final String GROUP = "prime";

    private final Plugin plugin;
    private final PrimeMembers members;
    private final Supplier<PermissionGroups> groups;
    private final Supplier<Instant> clock;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public PrimePermission(Plugin plugin, PrimeMembers members, Supplier<PermissionGroups> groups,
                           Supplier<Instant> clock) {
        this.plugin = plugin;
        this.members = members;
        this.groups = groups;
        this.clock = clock;
    }

    public static Permission permission(org.bukkit.plugin.PluginManager manager) {
        Permission found = manager.getPermission(NODE);
        if (found == null) {
            found = new Permission(NODE, "Holds the Prime subscription — given and taken by RainsCore",
                    PermissionDefault.FALSE);
            manager.addPermission(found);
        }
        if (manager.getPermission(PrimeCommand.ADMIN) == null) {
            manager.addPermission(new Permission(PrimeCommand.ADMIN,
                    "/prime grant|revoke|info — the shop's console and the owner", PermissionDefault.OP));
        }
        return found;
    }

    /** What the group starts with; afterwards it is LuckPerms' to edit. Just under the staff departments. */
    public static GroupSeed seed() {
        return GroupSeed.named(GROUP).displayName("Prime").weight(40).granting(NODE);
    }

    public boolean isPrime(UUID player) {
        return members.isPrime(player, clock.get());
    }

    /** On the player's own thread. */
    public void sync(Player player) {
        UUID who = player.getUniqueId();
        boolean should = members.isPrime(who, clock.get());
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

    /** Blocking (LuckPerms); off the server's threads. */
    public void seedGroup() {
        PermissionGroups live = groups.get();
        if (live.editable() && !live.exists(GROUP)) {
            live.seed(seed());
        }
    }

    /** Blocking (LuckPerms); off the server's threads. @return how many memberships it changed */
    public int reconcileGroup() {
        PermissionGroups live = groups.get();
        if (!live.editable() || !live.exists(GROUP)) {
            return 0;
        }
        Instant now = clock.get();
        Set<UUID> inGroup = new HashSet<>(live.membersOf(GROUP));
        int changed = 0;
        for (PrimeMembers.Member member : members.all()) {
            boolean active = member.activeAt(now);
            boolean listed = inGroup.contains(member.player());
            if (active && !listed && live.join(member.player(), GROUP)) {
                changed++;
            } else if (!active && listed && live.leave(member.player(), GROUP)) {
                changed++;
            }
        }
        return changed;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        sync(event.getPlayer());
    }
}
