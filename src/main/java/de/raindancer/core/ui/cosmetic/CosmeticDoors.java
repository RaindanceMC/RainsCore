package de.raindancer.core.ui.cosmetic;

import de.raindancer.core.platform.util.PluginCode;
import de.raindancer.core.ui.menu.Menu;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plugins' cosmetic doors, for the cosmetics page to show: fishing's rod skins beside the cosmetics plugin's
 * weapon skins. A plugin whose cosmetic has a page of its own elsewhere asks {@link #hosted()} — when a cosmetics page
 * shows the doors, that is where the cosmetic lives.
 *
 * <p>A cosmetics page that hosts as a {@link Page} can also be built on demand, so a door's own command
 * ({@code /chatstyle}, {@code /elytra}) opens with Back leading to the cosmetics page: one place for everything worn,
 * however the player got there.
 *
 * <p>Static and process-wide, like {@code ItemPrices}: one cosmetics page for the server however many plugins.
 */
public final class CosmeticDoors {

    private record Provided(Plugin owner, CosmeticDoor door) {
    }

    /** The cosmetics page, built fresh for one viewer (not opened). */
    @FunctionalInterface
    public interface Page {
        Menu of(Player viewer);
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

    /** The cosmetics page for this viewer, built but not opened; empty when none is hosted as a {@link Page}. */
    public static Optional<Menu> page(Player viewer) {
        for (Object host : hosts) {
            if (host instanceof Page page) {
                Menu built = page.of(viewer);
                if (built != null) {
                    return Optional.of(built);
                }
            }
        }
        return Optional.empty();
    }

    /** Where a door's own page goes back to: the cosmetics page when there is one, else {@code otherwise}. */
    public static Menu backTo(Player viewer, Menu otherwise) {
        return page(viewer).orElse(otherwise);
    }

    /** Whether this viewer wears anything from any door. */
    public static boolean wearingAny(Player viewer) {
        return provided().stream().anyMatch(each -> wears(each, viewer));
    }

    /**
     * Takes off whatever this viewer wears from every door, on their own thread; returns how many doors did.
     * A door that throws is logged against its plugin and skipped, so one broken plugin cannot keep the rest on.
     */
    public static int takeOffAll(Player viewer) {
        int taken = 0;
        for (Provided each : provided()) {
            if (!wears(each, viewer)) {
                continue;
            }
            try {
                each.door().takeOff(viewer);
                taken++;
            } catch (RuntimeException broken) {
                log(each, "could not take " + each.door().id() + " off", broken);
            }
        }
        return taken;
    }

    private static boolean wears(Provided each, Player viewer) {
        try {
            return each.door().wearing(viewer);
        } catch (RuntimeException broken) {
            log(each, "could not tell whether " + each.door().id() + " is worn", broken);
            return false;
        }
    }

    private static List<Provided> provided() {
        synchronized (providers) {
            return List.copyOf(providers);
        }
    }

    private static void log(Provided each, String what, RuntimeException broken) {
        if (each.owner() != null) {
            each.owner().getLogger().log(Level.WARNING, "Cosmetics: " + what, broken);
        }
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
