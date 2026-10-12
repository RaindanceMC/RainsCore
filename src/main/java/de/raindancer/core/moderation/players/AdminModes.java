package de.raindancer.core.moderation.players;

import de.raindancer.core.platform.util.PluginCode;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Who is in admin mode: answered by whichever plugin runs it (essentials), asked by any that treats
 * staff on duty differently — moderation shows them the bait ores as what they are. Static and
 * process-wide, like {@link de.raindancer.core.moderation.reports.AutoReports}: neither knows the other.
 */
public final class AdminModes {

    @FunctionalInterface
    public interface Source {

        /** Whether this player is in admin mode right now. May be called from any thread. */
        boolean isInAdminMode(UUID player);
    }

    @FunctionalInterface
    public interface Listener {

        /** Called on the switching player's thread, right after the switch. */
        void switched(UUID player, boolean inAdminMode);
    }

    private static final AtomicReference<Source> source = new AtomicReference<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private AdminModes() {
    }

    public static void provide(Source answering) {
        source.set(answering);
    }

    public static void withdraw(Source answering) {
        source.compareAndSet(answering, null);
    }

    public static boolean isOn(UUID player) {
        Source answering = source.get();
        if (answering == null || player == null) {
            return false;
        }
        try {
            return answering.isInAdminMode(player);
        } catch (RuntimeException broken) {
            return false;
        }
    }

    public static void listen(Listener listener) {
        listeners.add(listener);
    }

    public static void unlisten(Listener listener) {
        listeners.remove(listener);
    }

    /** For the plugin running admin mode: somebody went in or came out. */
    public static void switched(UUID player, boolean inAdminMode) {
        for (Listener listener : listeners) {
            try {
                listener.switched(player, inAdminMode);
            } catch (RuntimeException broken) {
                // One plugin's mistake must not keep the others from hearing it.
            }
        }
    }

    public static int forgetFrom(ClassLoader loader) {
        int dropped = 0;
        Source answering = source.get();
        if (answering != null && PluginCode.isFrom(answering, loader) && source.compareAndSet(answering, null)) {
            dropped++;
        }
        for (Listener listener : listeners) {
            if (PluginCode.isFrom(listener, loader) && listeners.remove(listener)) {
                dropped++;
            }
        }
        return dropped;
    }

    public static void clear() {
        source.set(null);
        listeners.clear();
    }
}
