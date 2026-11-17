package de.raindancer.core.ui.travel;

import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every plugin's ways to travel, for the one {@code /travel} window, so homes, warps, teleport requests, a random
 * spot and back are found in one place. Static and process-wide, like {@code CosmeticDoors}.
 */
public final class TravelDoors {

    private static final List<TravelDoor> offered = new ArrayList<>();

    private TravelDoors() {
    }

    /** Offers a door; a second with the same id is ignored. */
    public static void offer(Plugin owner, TravelDoor door) {
        if (door == null) {
            return;
        }
        synchronized (offered) {
            if (offered.stream().noneMatch(each -> each.id().equals(door.id()))) {
                offered.add(door);
            }
        }
    }

    public static void withdraw(TravelDoor door) {
        synchronized (offered) {
            offered.removeIf(each -> each == door || each.id().equals(door.id()));
        }
    }

    /** Every door, by order and then id. */
    public static List<TravelDoor> doors() {
        synchronized (offered) {
            return offered.stream().sorted(Comparator.comparingInt(TravelDoor::order).thenComparing(TravelDoor::id))
                    .toList();
        }
    }

    public static boolean any() {
        synchronized (offered) {
            return !offered.isEmpty();
        }
    }

    public static int forgetFrom(ClassLoader loader) {
        synchronized (offered) {
            int before = offered.size();
            offered.removeIf(each -> PluginCode.isFrom(each.open(), loader));
            return before - offered.size();
        }
    }

    public static void clear() {
        synchronized (offered) {
            offered.clear();
        }
    }
}
