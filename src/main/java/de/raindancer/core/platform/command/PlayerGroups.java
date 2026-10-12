package de.raindancer.core.platform.command;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Named groups of players any plugin offers — a guild by its tag — so {@code @DAWN} means its members in every
 * command that takes players through {@link PlayerTargets}. Not a vanilla selector: {@code @a}, {@code @p},
 * {@code @r}, {@code @s}, {@code @e}, {@code @n} stay the game's, and a group needs no selector permission.
 */
public final class PlayerGroups {

    private static final LogChannel log = Log.of("command");
    private static final Pattern VANILLA = Pattern.compile("@[aeprsn](\\[.*)?", Pattern.DOTALL);

    private record Entry(String owner, Function<String, Optional<List<UUID>>> members, Supplier<List<String>> names) {
    }

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();
    private static final Map<String, Boolean> complainedAbout = new ConcurrentHashMap<>();

    private PlayerGroups() {
    }

    /**
     * @param members a group's members by its name (asked case-insensitively); empty when this owner has no such group
     * @param names   every group name, for completion
     */
    public static synchronized void register(String owner, Function<String, Optional<List<UUID>>> members,
                                             Supplier<List<String>> names) {
        if (owner == null || members == null) {
            return;
        }
        entries.removeIf(entry -> entry.owner().equals(owner));
        entries.add(new Entry(owner, members, names == null ? List::of : names));
        complainedAbout.remove(owner);
    }

    public static synchronized boolean unregister(String owner) {
        complainedAbout.remove(owner);
        return entries.removeIf(entry -> entry.owner().equals(owner));
    }

    /** Whether {@code text} is a group rather than one of the game's own selectors. */
    public static boolean isGroup(String text) {
        String typed = text == null ? "" : text.trim();
        return typed.length() > 1 && typed.startsWith("@") && !VANILLA.matcher(typed.toLowerCase(Locale.ROOT)).matches();
    }

    /** The members of the group {@code text} names, the first owner that knows it answering; empty for none. */
    public static List<UUID> members(String text) {
        if (!isGroup(text)) {
            return List.of();
        }
        String name = text.trim().substring(1);
        for (Entry entry : entries) {
            try {
                Optional<List<UUID>> found = entry.members().apply(name);
                if (found != null && found.isPresent()) {
                    return List.copyOf(found.get());
                }
            } catch (RuntimeException failed) {
                if (complainedAbout.putIfAbsent(entry.owner(), Boolean.TRUE) == null) {
                    log.error(failed, "The player groups of {} failed and are left out until they work.", entry.owner());
                }
            }
        }
        return List.of();
    }

    /** Groups beginning with what was typed, as {@code @NAME}; nothing unless it starts with {@code @}. */
    public static List<String> suggest(String typed) {
        if (typed == null || !typed.startsWith("@")) {
            return List.of();
        }
        String start = typed.substring(1).toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (Entry entry : entries) {
            try {
                for (String name : entry.names().get()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(start) && !found.contains("@" + name)) {
                        found.add("@" + name);
                    }
                }
            } catch (RuntimeException failed) {
                // The same broken owner is reported by members(); completion just goes without it.
            }
        }
        found.sort(String.CASE_INSENSITIVE_ORDER);
        return found;
    }

    /** Drops the groups whose code came from {@code loader}. @return how many */
    public static synchronized int forgetFrom(ClassLoader loader) {
        if (loader == null) {
            return 0;
        }
        List<Entry> gone = entries.stream().filter(entry -> entry.members().getClass().getClassLoader() == loader).toList();
        entries.removeAll(gone);
        return gone.size();
    }

    /** For tests. */
    public static synchronized void clear() {
        entries.clear();
        complainedAbout.clear();
    }
}
