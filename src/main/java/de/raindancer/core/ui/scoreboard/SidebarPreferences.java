package de.raindancer.core.ui.scoreboard;

import de.raindancer.core.ui.profile.PlayerSwitch;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Where a player's {@link SidebarLayout} is kept: in their own data, in Core's switches namespace, so it survives
 * restarts and follows them across the network like every other switch of theirs.
 */
public final class SidebarPreferences {

    private static final NamespacedKey KEY = new NamespacedKey(PlayerSwitch.NAMESPACE, "core.sidebar");

    private SidebarPreferences() {
    }

    public static SidebarLayout of(Player player) {
        if (player == null) {
            return SidebarLayout.DEFAULT;
        }
        return SidebarLayout.fromLine(player.getPersistentDataContainer().get(KEY, PersistentDataType.STRING));
    }

    /** The layout of a player by id: the server's default for anybody not online. */
    public static SidebarLayout of(UUID player) {
        if (player == null || Bukkit.getServer() == null) {
            return SidebarLayout.DEFAULT;   // no server (a test), no player data
        }
        return of(Bukkit.getPlayer(player));
    }

    public static void save(Player player, SidebarLayout layout) {
        if (player == null) {
            return;
        }
        if (layout == null || layout.isDefault()) {
            player.getPersistentDataContainer().remove(KEY);
        } else {
            player.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, layout.asLine());
        }
    }
}
