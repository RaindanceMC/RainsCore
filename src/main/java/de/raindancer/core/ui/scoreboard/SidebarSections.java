package de.raindancer.core.ui.scoreboard;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A few lines another plugin adds to whichever plugin draws a player's sidebar — a guild's level and bank under the
 * economy's balance — without a second sidebar fighting the first for the one slot the client has.
 *
 * <p>Asked on every redraw, so a section is never stale and never stored. Safe from any thread; a section that
 * throws is left out (and logged once), never taking the sidebar with it.
 */
public final class SidebarSections {

    private static final LogChannel log = Log.of("scoreboard");

    private record Entry(String owner, Function<UUID, List<Component>> lines) {
    }

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();
    private static final Map<String, Boolean> complainedAbout = new ConcurrentHashMap<>();

    private SidebarSections() {
    }

    /** Adds a section under {@code owner}; registering again under the same owner replaces it in place. */
    public static synchronized void register(String owner, Function<UUID, List<Component>> lines) {
        if (owner == null || lines == null) {
            return;
        }
        Entry entry = new Entry(owner, lines);
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

    /**
     * Every section this player has, each after an empty line, in the order registered — within {@code room}
     * lines; a section that does not fit whole is left out rather than cut in half.
     */
    public static List<Component> linesFor(UUID player, int room) {
        List<Component> built = new ArrayList<>();
        if (player == null || room <= 0) {
            return built;
        }
        for (Entry entry : entries) {
            List<Component> section;
            try {
                section = entry.lines().apply(player);
            } catch (RuntimeException failed) {
                if (complainedAbout.putIfAbsent(entry.owner(), Boolean.TRUE) == null) {
                    log.error(failed, "The sidebar section of {} failed and is left out until it works.", entry.owner());
                }
                continue;
            }
            if (section == null || section.isEmpty() || built.size() + 1 + section.size() > room) {
                continue;
            }
            built.add(Component.empty());
            built.addAll(section);
        }
        return built;
    }

    /** Drops the sections whose code came from {@code loader}. @return how many */
    public static synchronized int forgetFrom(ClassLoader loader) {
        if (loader == null) {
            return 0;
        }
        List<Entry> gone = entries.stream().filter(entry -> entry.lines().getClass().getClassLoader() == loader).toList();
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
