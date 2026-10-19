package de.raindancer.core.social.economy;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Selling items for money, for anything that is not the shop — the way {@link ItemValues} is for what they are worth.
 *
 * <p>Static and process-wide, like {@link Economies}. Several buyers may register; the newest answers and the one
 * before takes over again when it retracts. Whatever goes wrong — no buyer, a buyer that throws or fails, an answer
 * that does not line up with the stacks — the answer is "nothing was bought", so the caller hands every stack back.
 */
public final class ItemSales {

    private static final LogChannel log = Log.of("economy");

    private record Provided(Plugin owner, ItemBuyer buyer) {
    }

    private static final List<Provided> providers = new ArrayList<>();

    private ItemSales() {
    }

    /** Starts buying. Providing the same buyer twice is one registration. */
    public static void provide(Plugin owner, ItemBuyer buyer) {
        if (buyer == null) {
            return;
        }
        synchronized (providers) {
            if (providers.stream().noneMatch(each -> each.buyer() == buyer)) {
                providers.add(new Provided(owner, buyer));
            }
        }
    }

    /** Stops it. Harmless for one that was never provided. */
    public static void retract(ItemBuyer buyer) {
        synchronized (providers) {
            providers.removeIf(each -> each.buyer() == buyer);
        }
    }

    public static Optional<ItemBuyer> current() {
        synchronized (providers) {
            return providers.isEmpty() ? Optional.empty() : Optional.of(providers.getLast().buyer());
        }
    }

    /** Whether anybody buys items here at all. */
    public static boolean isOpen() {
        return current().isPresent();
    }

    public static boolean buys(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Optional<ItemBuyer> buyer = current();
        try {
            return buyer.isPresent() && buyer.get().buys(stack);
        } catch (RuntimeException broken) {
            log.error(broken, "The shop could not say whether it buys an item.");
            return false;
        }
    }

    /**
     * Sells these stacks, already taken out by the caller.
     *
     * @return per stack, in order, whether it was bought; never completes exceptionally
     */
    public static CompletableFuture<List<Boolean>> sell(Player seller, List<ItemStack> stacks) {
        List<Boolean> none = Collections.nCopies(stacks.size(), false);
        Optional<ItemBuyer> buyer = current();
        if (stacks.isEmpty() || buyer.isEmpty()) {
            return CompletableFuture.completedFuture(none);
        }
        CompletableFuture<List<Boolean>> sale;
        try {
            sale = buyer.get().sell(seller, List.copyOf(stacks));
        } catch (RuntimeException broken) {
            log.error(broken, "Items could not be sold.");
            return CompletableFuture.completedFuture(none);
        }
        if (sale == null) {
            return CompletableFuture.completedFuture(none);
        }
        return sale.handle((bought, failed) -> {
            if (failed != null) {
                log.error(failed, "Items could not be sold.");
                return none;
            }
            // Not contains(null): an immutable list throws on that question instead of answering it.
            if (bought == null || bought.size() != stacks.size()
                    || bought.stream().anyMatch(java.util.Objects::isNull)) {
                log.warn("The shop answered a sale of {} stack(s) with {} answer(s); nothing counts as sold.",
                        stacks.size(), bought == null ? 0 : bought.size());
                return none;
            }
            return List.copyOf(bought);
        });
    }

    /**
     * Drops whatever {@code loader}'s code provided — for a plugin disabled without retracting.
     *
     * @return how many were dropped
     */
    public static int forgetFrom(ClassLoader loader) {
        synchronized (providers) {
            int before = providers.size();
            providers.removeIf(each -> PluginCode.isFrom(each.buyer(), loader));
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
