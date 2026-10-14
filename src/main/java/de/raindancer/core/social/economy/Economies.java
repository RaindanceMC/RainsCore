package de.raindancer.core.social.economy;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The economy every plugin on this server charges through.
 *
 * <p>Static and process-wide for the same reason as {@code ProfileExtensions}: modules are hosted by
 * whichever plugin loaded them, and there is one server's worth of money however many plugins are
 * involved.
 *
 * <p>Several providers may register; the newest answers and the one before it takes over again when it
 * retracts — so a module reloaded in place does not leave the server without money for the moment in
 * between, and a second economy plugin installed by mistake does not silently split the accounts in
 * two: whichever answers is logged.
 */
public final class Economies {

    private static final LogChannel log = Log.of("economy");

    private record Provided(Plugin owner, Economy economy) {
    }

    private static final List<Provided> providers = new ArrayList<>();
    private static volatile EconomyBridge bridge = EconomyBridge.NONE;

    private Economies() {
    }

    /** Starts providing money. Providing the same economy twice is one registration. */
    public static void provide(Plugin owner, Economy economy) {
        if (economy == null) {
            return;
        }
        synchronized (providers) {
            if (providers.stream().anyMatch(each -> each.economy() == economy)) {
                return;
            }
            if (!providers.isEmpty() && !(providers.getLast().economy() instanceof NetworkEconomy)) {
                log.warn("{} now provides the server's money, in place of {}. Two economies on one "
                        + "server keep two sets of balances.", economy.name(), providers.getLast().economy().name());
            }
            providers.add(new Provided(owner, economy));
        }
        try {
            bridge.exported(owner, economy);
        } catch (RuntimeException broken) {
            log.error(broken, "{} could not be offered to other economy plugins.", economy.name());
        }
    }

    /** Stops providing it. Harmless for one that was never provided. */
    public static void retract(Economy economy) {
        boolean removed;
        synchronized (providers) {
            removed = providers.removeIf(each -> each.economy() == economy);
        }
        if (removed) {
            try {
                bridge.retracted(economy);
            } catch (RuntimeException broken) {
                log.error(broken, "{} could not be withdrawn from other economy plugins.", economy.name());
            }
        }
    }

    /**
     * The economy to charge through: the newest Rain provider, else a foreign one through the bridge,
     * else none — in which case a feature that costs money should say it is unavailable, not make it free.
     */
    public static Optional<Economy> current() {
        synchronized (providers) {
            if (!providers.isEmpty()) {
                return Optional.of(providers.getLast().economy());
            }
        }
        try {
            return bridge.foreign();
        } catch (RuntimeException broken) {
            log.error(broken, "The economy bridge could not be asked for a foreign economy.");
            return Optional.empty();
        }
    }

    public static boolean isAvailable() {
        return current().isPresent();
    }

    /** Whether a Rain plugin provides the money, rather than something reached through the bridge. */
    public static boolean isProvidedHere() {
        synchronized (providers) {
            return !providers.isEmpty();
        }
    }

    /** Installs the bridge to economies outside Rain's plugins. Core does this itself. */
    public static void bridge(EconomyBridge installed) {
        bridge = installed == null ? EconomyBridge.NONE : installed;
        List<Provided> already;
        synchronized (providers) {
            already = List.copyOf(providers);
        }
        for (Provided each : already) {
            bridge.exported(each.owner(), each.economy());
        }
    }

    /**
     * Drops whatever {@code loader}'s code provided — for a plugin disabled without retracting.
     *
     * @return how many were dropped
     */
    public static int forgetFrom(ClassLoader loader) {
        List<Economy> gone = new ArrayList<>();
        synchronized (providers) {
            providers.removeIf(each -> {
                if (PluginCode.isFrom(each.economy(), loader)) {
                    gone.add(each.economy());
                    return true;
                }
                return false;
            });
        }
        gone.forEach(economy -> {
            try {
                bridge.retracted(economy);
            } catch (RuntimeException broken) {
                log.error(broken, "{} could not be withdrawn from other economy plugins.", economy.name());
            }
        });
        return gone.size();
    }

    /** Forgets everything, bridge included — for Core shutting down, and for tests. */
    public static void clear() {
        synchronized (providers) {
            providers.clear();
        }
        bridge = EconomyBridge.NONE;
    }
}
