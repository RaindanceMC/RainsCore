package de.raindancer.core.ui.identity;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Small badges drawn in front of a player's name in chat and the tablist: a guild's tag, a clan's.
 *
 * <p>Not {@link Identities#setPrefix}: that is one slot, owned by whoever writes ranks, and a second plugin
 * writing it would overwrite the rank. A badge is <em>asked for</em> each time a name is drawn, so it is
 * never stale and never stored, and any number of plugins can each have one.
 *
 * <p>A badge function is called on the chat thread and the tablist refresh, so it must be cheap and safe
 * from any thread. One that throws is skipped (and logged once), never taking the chat line with it.
 */
public final class NameBadges {

    private static final LogChannel log = Log.of("identity");

    private record Entry(String owner, Function<UUID, Optional<Component>> badge) {
    }

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();
    private static final Map<String, Boolean> complainedAbout = new ConcurrentHashMap<>();

    private NameBadges() {
    }

    /** Adds a badge under {@code owner}; registering again under the same owner replaces it in place. */
    public static synchronized void register(String owner, Function<UUID, Optional<Component>> badge) {
        if (owner == null || badge == null) {
            return;
        }
        Entry entry = new Entry(owner, badge);
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).owner().equals(owner)) {
                entries.set(index, entry);
                complainedAbout.remove(owner);
                return;
            }
        }
        entries.add(entry);
    }

    public static synchronized boolean unregister(String owner) {
        complainedAbout.remove(owner);
        return entries.removeIf(entry -> entry.owner().equals(owner));
    }

    public static List<String> owners() {
        return entries.stream().map(Entry::owner).toList();
    }

    /** Every badge this player has, each followed by a space; empty when there are none. */
    public static Component before(UUID player) {
        if (player == null || entries.isEmpty()) {
            return Component.empty();
        }
        List<Component> found = new ArrayList<>(2);
        for (Entry entry : entries) {
            try {
                Optional<Component> badge = entry.badge().apply(player);
                if (badge != null && badge.isPresent()) {
                    found.add(badge.get());
                }
            } catch (RuntimeException failed) {
                if (complainedAbout.putIfAbsent(entry.owner(), Boolean.TRUE) == null) {
                    log.error(failed, "The name badge of {} failed and is left out of names until it works.",
                            entry.owner());
                }
            }
        }
        Component built = Component.empty();
        for (Component badge : found) {
            built = built.append(badge).append(Component.space());
        }
        return built;
    }

    /** Drops the badges whose code came from {@code loader}. @return how many */
    public static synchronized int forgetFrom(ClassLoader loader) {
        if (loader == null) {
            return 0;
        }
        List<Entry> gone = entries.stream()
                .filter(entry -> entry.badge().getClass().getClassLoader() == loader)
                .toList();
        entries.removeAll(gone);
        gone.forEach(entry -> complainedAbout.remove(entry.owner()));
        return gone.size();
    }

    /** For tests. */
    public static synchronized void clear() {
        entries.clear();
        complainedAbout.clear();
    }
}
