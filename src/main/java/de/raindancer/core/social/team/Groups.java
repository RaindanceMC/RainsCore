package de.raindancer.core.social.team;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lasting groups of players another plugin can lean on — a guild — so land, a chest or anything else can belong to
 * one: who leads it, who manages for it, who is in it. Asked every time, never copied, so a member who leaves stops
 * counting at once. Safe from any thread; a source that throws is skipped (logged once).
 */
public final class Groups {

    private static final LogChannel log = Log.of("team");

    /**
     * @param id       stable for the group's life, whatever it is renamed to: {@code guild:<id>}
     * @param managers who may act for it — the leader always among them
     * @param members  everybody in it — leader and managers too
     */
    public record Group(String id, String name, String tag, UUID leader, Set<UUID> managers, Set<UUID> members) {

        public Group {
            managers = Set.copyOf(managers);
            members = Set.copyOf(members);
        }

        public boolean isMember(UUID player) {
            return player != null && (members.contains(player) || player.equals(leader));
        }

        public boolean isManager(UUID player) {
            return player != null && (managers.contains(player) || player.equals(leader));
        }
    }

    /** Where a plugin's groups come from. */
    public interface Source {
        Optional<Group> group(String id);

        List<Group> of(UUID player);
    }

    private record Entry(String owner, Source source) {
    }

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();
    private static final Map<String, Boolean> complainedAbout = new ConcurrentHashMap<>();

    private Groups() {
    }

    public static synchronized void register(String owner, Source source) {
        if (owner == null || source == null) {
            return;
        }
        entries.removeIf(entry -> entry.owner().equals(owner));
        entries.add(new Entry(owner, source));
        complainedAbout.remove(owner);
    }

    public static synchronized boolean unregister(String owner) {
        complainedAbout.remove(owner);
        return entries.removeIf(entry -> entry.owner().equals(owner));
    }

    public static Optional<Group> group(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        for (Entry entry : entries) {
            try {
                Optional<Group> found = entry.source().group(id);
                if (found != null && found.isPresent()) {
                    return found;
                }
            } catch (RuntimeException failed) {
                complain(entry, failed);
            }
        }
        return Optional.empty();
    }

    /** Every group this player is in. */
    public static List<Group> of(UUID player) {
        List<Group> found = new ArrayList<>();
        if (player == null) {
            return found;
        }
        for (Entry entry : entries) {
            try {
                List<Group> theirs = entry.source().of(player);
                if (theirs != null) {
                    found.addAll(theirs);
                }
            } catch (RuntimeException failed) {
                complain(entry, failed);
            }
        }
        return found;
    }

    /** The groups this player may act for. */
    public static List<Group> managedBy(UUID player) {
        return of(player).stream().filter(group -> group.isManager(player)).toList();
    }

    private static void complain(Entry entry, RuntimeException failed) {
        if (complainedAbout.putIfAbsent(entry.owner(), Boolean.TRUE) == null) {
            log.error(failed, "The groups of {} failed and are left out until they work.", entry.owner());
        }
    }

    /** Drops the groups whose code came from {@code loader}. @return how many */
    public static synchronized int forgetFrom(ClassLoader loader) {
        if (loader == null) {
            return 0;
        }
        List<Entry> gone = entries.stream().filter(entry -> entry.source().getClass().getClassLoader() == loader).toList();
        entries.removeAll(gone);
        return gone.size();
    }

    /** For tests. */
    public static synchronized void clear() {
        entries.clear();
        complainedAbout.clear();
    }
}
