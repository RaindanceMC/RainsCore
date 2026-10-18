package de.raindancer.core.platform.permission;

import de.raindancer.core.moderation.players.AdminModes;
import de.raindancer.core.platform.network.NetworkBus;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Whether somebody's staff powers apply right now, as two LuckPerms contexts.
 *
 * <ul>
 *   <li>{@code rains:powers} — {@code on} unless the server says powers need admin mode
 *       ({@link #powersNeedAdminMode(boolean)}) and they are not in it.</li>
 *   <li>{@code rains:dev} — {@code on} while they have switched dev mode on ({@code /dev}).</li>
 * </ul>
 *
 * A permission given in LuckPerms with context {@code rains:powers=on} therefore works only on duty. Without
 * LuckPerms there are no contexts; see {@link LocalGroups}.
 */
public final class Duty {

    public static final String POWERS = "rains:powers";
    public static final String DEV = "rains:dev";
    public static final String ON = "on";
    public static final String OFF = "off";

    private static volatile boolean powersNeedAdminMode;
    private static final Set<UUID> devs = ConcurrentHashMap.newKeySet();
    private static final List<Consumer<UUID>> listeners = new CopyOnWriteArrayList<>();

    private Duty() {
    }

    /** The server's switch: staff are ordinary players until they go into admin mode. */
    public static void powersNeedAdminMode(boolean need) {
        if (powersNeedAdminMode != need) {
            powersNeedAdminMode = need;
            changed(null);
        }
    }

    public static boolean powersNeedAdminMode() {
        return powersNeedAdminMode;
    }

    public static boolean powersOn(UUID who) {
        return !powersNeedAdminMode || AdminModes.isOn(who);
    }

    public static boolean devOn(UUID who) {
        return who != null && devs.contains(who);
    }

    /** @return whether dev mode is now on */
    public static boolean toggleDev(UUID who) {
        boolean now = !devs.remove(who) && devs.add(who);
        changed(who);
        return now;
    }

    /** Called when somebody's admin mode switched, so their contexts are worked out again. */
    public static void adminModeSwitched(UUID who) {
        changed(who);
    }

    /** They left: dev mode does not outlive the session. */
    public static void forget(UUID who) {
        if (who != null && devs.remove(who)) {
            changed(who);
        }
    }

    public static Map<String, String> contextsOf(UUID who) {
        return Map.of(POWERS, powersOn(who) ? ON : OFF, DEV, devOn(who) ? ON : OFF);
    }

    public static NetworkBus.Subscription onChange(Consumer<UUID> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private static void changed(UUID who) {
        for (Consumer<UUID> listener : listeners) {
            try {
                listener.accept(who);
            } catch (RuntimeException broken) {
                // One listener's mistake must not keep the others from hearing it.
            }
        }
    }

    public static void clear() {
        powersNeedAdminMode = false;
        devs.clear();
        listeners.clear();
    }
}
