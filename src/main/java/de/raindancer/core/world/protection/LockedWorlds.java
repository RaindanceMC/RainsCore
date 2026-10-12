package de.raindancer.core.world.protection;

import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Worlds nobody changes: a hub, an arena — every block where it was built, nothing taken, nothing placed, no fire, no
 * explosion damage, no wither. A plugin locks its world; Core's {@link LockedWorldListener} enforces it, the same way for
 * every plugin, so two of them cannot each get a different half of it wrong.
 *
 * <p>Somebody building is exempt: by default whoever is in creative with {@link #BUILD_PERMISSION}, or whoever the
 * locking plugin says. Static and process-wide like {@code AdminModes}; a plugin's locks go with its class loader.
 */
public final class LockedWorlds {

    public static final String BUILD_PERMISSION = "rainscore.lockedworlds.build";

    private record Lock(Plugin owner, Predicate<Player> builder) {
    }

    private static final Map<String, Lock> locks = new ConcurrentHashMap<>();

    private LockedWorlds() {
    }

    /** Locks a world; only somebody in creative with {@link #BUILD_PERMISSION} still changes anything in it. */
    public static void lock(Plugin owner, String world) {
        lock(owner, world, LockedWorlds::defaultBuilder);
    }

    /** Locks a world with the owner's own idea of who is building there. */
    public static void lock(Plugin owner, String world, Predicate<Player> builder) {
        if (world != null && !world.isBlank()) {
            locks.put(key(world), new Lock(owner, builder == null ? LockedWorlds::defaultBuilder : builder));
        }
    }

    public static void unlock(String world) {
        if (world != null) {
            locks.remove(key(world));
        }
    }

    public static boolean isLocked(String world) {
        return world != null && locks.containsKey(key(world));
    }

    /** Whether this player is building in that world — exempt from its lock. A predicate that throws is nobody. */
    public static boolean isBuilding(String world, Player player) {
        Lock lock = world == null ? null : locks.get(key(world));
        if (lock == null || player == null) {
            return false;
        }
        try {
            return lock.builder().test(player);
        } catch (RuntimeException broken) {
            return false;
        }
    }

    public static List<String> locked() {
        return locks.keySet().stream().sorted().toList();
    }

    public static int forgetFrom(ClassLoader loader) {
        int before = locks.size();
        locks.values().removeIf(lock -> lock.owner() != null && PluginCode.isFrom(lock.owner(), loader));
        return before - locks.size();
    }

    public static void clear() {
        locks.clear();
    }

    private static boolean defaultBuilder(Player player) {
        return player.getGameMode() == GameMode.CREATIVE && player.hasPermission(BUILD_PERMISSION);
    }

    private static String key(String world) {
        return world.toLowerCase(Locale.ROOT);
    }
}
