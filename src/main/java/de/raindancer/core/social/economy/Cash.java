package de.raindancer.core.social.economy;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Money as items, for anything that is not the economy itself — the way {@link ItemSales} is for selling.
 *
 * <p>Static and process-wide, like {@link Economies}. Several desks may register; the newest answers and the one
 * before takes over again when it retracts. Whatever goes wrong — no desk, a desk that throws or fails — the answer
 * is "nothing was taken out", and the money stays where it was.
 */
public final class Cash {

    private static final LogChannel log = Log.of("economy");

    private record Provided(Plugin owner, CashDesk desk) {
    }

    private static final List<Provided> providers = new ArrayList<>();

    private Cash() {
    }

    /** Starts making cash. Providing the same desk twice is one registration. */
    public static void provide(Plugin owner, CashDesk desk) {
        if (desk == null) {
            return;
        }
        synchronized (providers) {
            if (providers.stream().noneMatch(each -> each.desk() == desk)) {
                providers.add(new Provided(owner, desk));
            }
        }
    }

    /** Stops it. Harmless for one that was never provided. */
    public static void retract(CashDesk desk) {
        synchronized (providers) {
            providers.removeIf(each -> each.desk() == desk);
        }
    }

    public static Optional<CashDesk> current() {
        synchronized (providers) {
            return providers.isEmpty() ? Optional.empty() : Optional.of(providers.getLast().desk());
        }
    }

    /** Whether money can be had as items here at all. */
    public static boolean isOpen() {
        return current().isPresent();
    }

    /**
     * {@link CashDesk#takeOut}, through whichever desk is current.
     *
     * @return the cash; empty when nothing was taken out. Never completes exceptionally
     */
    public static CompletableFuture<List<ItemStack>> takeOut(UUID player, Money amount, int mostCoins, String issuer) {
        Optional<CashDesk> desk = current();
        if (desk.isEmpty() || amount == null || !amount.isPositive()) {
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<ItemStack>> taken;
        try {
            taken = desk.get().takeOut(player, amount, mostCoins, issuer);
        } catch (RuntimeException broken) {
            log.error(broken, "Money could not be taken out as cash.");
            return CompletableFuture.completedFuture(List.of());
        }
        if (taken == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return taken.handle((items, failed) -> {
            if (failed != null) {
                log.error(failed, "Money could not be taken out as cash.");
                return List.of();
            }
            return items == null ? List.of() : List.copyOf(items);
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
            providers.removeIf(each -> PluginCode.isFrom(each.desk(), loader));
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
