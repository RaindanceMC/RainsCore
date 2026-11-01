package de.raindancer.core.social.economy;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Prices of plugins' own items, for the shop: a plugin item never sells as its material (a fish is not a cod), so the
 * plugin that made it says what it is worth. Every pricer answers for its own items; the first that knows one wins.
 *
 * <p>Static and process-wide, like {@link ItemSales}. A pricer that throws, or answers nothing above zero, leaves
 * the item unsold.
 */
public final class ItemPrices {

    private static final LogChannel log = Log.of("economy");

    private record Provided(Plugin owner, ItemPricer pricer) {
    }

    private static final List<Provided> providers = new ArrayList<>();

    private ItemPrices() {
    }

    /** Starts pricing. Providing the same pricer twice is one registration. */
    public static void provide(Plugin owner, ItemPricer pricer) {
        if (pricer == null) {
            return;
        }
        synchronized (providers) {
            if (providers.stream().noneMatch(each -> each.pricer() == pricer)) {
                providers.add(new Provided(owner, pricer));
            }
        }
    }

    /** Stops it. Harmless for one that was never provided. */
    public static void retract(ItemPricer pricer) {
        synchronized (providers) {
            providers.removeIf(each -> each.pricer() == pricer);
        }
    }

    /** What the shop pays for one of this plugin item; empty when nobody prices it. */
    public static Optional<Money> sellPrice(ItemStack stack) {
        if (stack == null) {
            return Optional.empty();
        }
        List<ItemPricer> pricers;
        synchronized (providers) {
            pricers = providers.stream().map(Provided::pricer).toList();
        }
        for (ItemPricer pricer : pricers) {
            Optional<Money> price;
            try {
                price = pricer.sellPrice(stack);
            } catch (RuntimeException broken) {
                log.error(broken, "A plugin could not say what one of its items is worth.");
                continue;
            }
            if (price != null && price.isPresent()) {
                return price.filter(Money::isPositive);
            }
        }
        return Optional.empty();
    }

    /**
     * Drops whatever {@code loader}'s code provided — for a plugin disabled without retracting.
     *
     * @return how many were dropped
     */
    public static int forgetFrom(ClassLoader loader) {
        synchronized (providers) {
            int before = providers.size();
            providers.removeIf(each -> PluginCode.isFrom(each.pricer(), loader));
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
