package de.raindancer.core.content.items;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Who makes which plugin items for {@code /give}: one {@link ItemGiver} per namespace ({@code fishing:rod-void} is
 * the {@code fishing} giver's {@code rod-void}). {@code minecraft} is vanilla's and never anybody's here.
 *
 * <p>Static and process-wide, like {@code ItemPrices}: one {@code /give} for the server however many plugins.
 */
public final class ItemGivers {

    private static final LogChannel log = Log.of("items");

    private record Provided(Plugin owner, String namespace, ItemGiver giver) {
    }

    private static final List<Provided> providers = new ArrayList<>();

    private ItemGivers() {
    }

    /** Starts answering for {@code namespace}. The first giver of a namespace wins. */
    public static void provide(Plugin owner, String namespace, ItemGiver giver) {
        if (giver == null || namespace == null || namespace.isBlank()) {
            return;
        }
        String ns = namespace.toLowerCase(Locale.ROOT);
        if (ns.equals("minecraft")) {
            return;
        }
        synchronized (providers) {
            if (providers.stream().noneMatch(each -> each.giver() == giver || each.namespace().equals(ns))) {
                providers.add(new Provided(owner, ns, giver));
            }
        }
    }

    public static void retract(ItemGiver giver) {
        synchronized (providers) {
            providers.removeIf(each -> each.giver() == giver);
        }
    }

    private static Optional<Provided> of(String key) {
        int colon = key == null ? -1 : key.indexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        String ns = key.substring(0, colon).toLowerCase(Locale.ROOT);
        synchronized (providers) {
            return providers.stream().filter(each -> each.namespace().equals(ns)).findFirst();
        }
    }

    private static String id(String key) {
        return key.substring(key.indexOf(':') + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean knows(String key) {
        return of(key).map(provided -> {
            try {
                return provided.giver().ids().contains(id(key));
            } catch (RuntimeException broken) {
                log.error(broken, "A plugin could not list the items it gives.");
                return false;
            }
        }).orElse(false);
    }

    /** {@code amount} of what {@code key} names, from its plugin; empty when nobody makes it. */
    public static Optional<ItemStack> make(String key, int amount) {
        return of(key).flatMap(provided -> {
            try {
                return provided.giver().make(id(key), amount);
            } catch (RuntimeException broken) {
                log.error(broken, "A plugin could not make {} to give.", key);
                return Optional.empty();
            }
        });
    }

    /** Every key a giver makes, sorted. */
    public static List<String> keys() {
        List<Provided> all;
        synchronized (providers) {
            all = List.copyOf(providers);
        }
        List<String> keys = new ArrayList<>();
        for (Provided provided : all) {
            try {
                provided.giver().ids().forEach(id -> keys.add(provided.namespace() + ":" + id));
            } catch (RuntimeException broken) {
                log.error(broken, "A plugin could not list the items it gives.");
            }
        }
        keys.sort(null);
        return keys;
    }

    /** Drops whatever {@code loader}'s code provided — for a plugin disabled without retracting. */
    public static int forgetFrom(ClassLoader loader) {
        synchronized (providers) {
            int before = providers.size();
            providers.removeIf(each -> PluginCode.isFrom(each.giver(), loader));
            return before - providers.size();
        }
    }

    /** Forgets everything — for Core shutting down, and for tests. */
    public static void clear() {
        synchronized (providers) {
            providers.clear();
        }
    }
}
