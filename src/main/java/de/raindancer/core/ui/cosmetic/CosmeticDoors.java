package de.raindancer.core.ui.cosmetic;

import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plugins' cosmetic doors, for the cosmetics page to show: fishing's rod skins beside the cosmetics plugin's
 * weapon skins. A plugin whose cosmetic has a page of its own elsewhere asks {@link #hosted()} — when a cosmetics page
 * shows the doors, that is where the cosmetic lives.
 *
 * <p>Static and process-wide, like {@code ItemPrices}: one cosmetics page for the server however many plugins.
 */
public final class CosmeticDoors {

    private record Provided(Plugin owner, CosmeticDoor door) {
    }

    private static final List<Provided> providers = new ArrayList<>();
    private static final Set<Object> hosts = ConcurrentHashMap.newKeySet();

    private CosmeticDoors() {
    }

    /** Offers a door; a second door with the same id is ignored. */
    public static void provide(Plugin owner, CosmeticDoor door) {
        if (door == null || door.id() == null) {
            return;
        }
        synchronized (providers) {
            if (providers.stream().noneMatch(each -> each.door() == door || each.door().id().equals(door.id()))) {
                providers.add(new Provided(owner, door));
            }
        }
    }

    public static void retract(CosmeticDoor door) {
        synchronized (providers) {
            providers.removeIf(each -> each.door() == door);
        }
    }

    /** Every door, in id order. */
    public static List<CosmeticDoor> doors() {
        synchronized (providers) {
            return providers.stream().map(Provided::door).sorted(Comparator.comparing(CosmeticDoor::id)).toList();
        }
    }

    /** A cosmetics page shows the doors from now on; {@code page} is whatever stands for it, to take back. */
    public static void host(Object page) {
        if (page != null) {
            hosts.add(page);
        }
    }

    public static void unhost(Object page) {
        hosts.remove(page);
    }

    /** Whether a cosmetics page shows the doors. */
    public static boolean hosted() {
        return !hosts.isEmpty();
    }

    /** Drops whatever {@code loader}'s code offered or hosted — for a plugin disabled without taking it back. */
    public static int forgetFrom(ClassLoader loader) {
        hosts.removeIf(host -> PluginCode.isFrom(host, loader));
        synchronized (providers) {
            int before = providers.size();
            providers.removeIf(each -> PluginCode.isFrom(each.door(), loader));
            return before - providers.size();
        }
    }

    /** Forgets everything — for Core shutting down, and for tests. */
    public static void clear() {
        hosts.clear();
        synchronized (providers) {
            providers.clear();
        }
    }
}
